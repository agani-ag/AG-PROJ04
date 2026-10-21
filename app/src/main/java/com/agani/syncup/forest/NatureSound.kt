package com.agani.syncup.forest

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import com.agani.syncup.R
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Calm sounds — real recordings (CC0 / public domain, from Freesound), each made into a seamless
 * loop and bundled in the app, so they work offline. Forest's Sounds and Sleep share them.
 */
enum class NatureSound(val title: String, val res: Int) {
    RAIN("Rain", R.raw.snd_rain),
    STORM("Thunderstorm", R.raw.snd_storm),
    RIVER("River", R.raw.snd_river),
    OCEAN("Ocean", R.raw.snd_ocean),
    BIRDS("Birds", R.raw.snd_birds),
    NIGHT("Night", R.raw.snd_night),
    WIND("Wind", R.raw.snd_wind),
    FIRE("Campfire", R.raw.snd_fire),
    FAN("Fan", R.raw.snd_fan),
    NOISE("Brown noise", R.raw.snd_noise),
}

/**
 * The sounds as raw 44.1 kHz stereo samples, decoded once from the app's Ogg files (a loop is about
 * a minute — ~10 MB as samples), keeping the last few in memory.
 */
object SoundBank {
    private const val KEEP = 3
    private lateinit var app: Context
    private val cache = LinkedHashMap<NatureSound, ShortArray>(KEEP, .75f, true)
    private val decoding = HashSet<NatureSound>()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "sound-decode").apply { isDaemon = true } }

    fun init(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
    }

    internal val ready: Boolean get() = ::app.isInitialized
    internal val context: Context get() = app

    /** The samples if decoded, else null — and starts decoding them. */
    @Synchronized
    fun samples(s: NatureSound): ShortArray? {
        cache[s]?.let { return it }
        if (ready && decoding.add(s)) {
            worker.execute {
                val pcm = runCatching { decode(app, s.res) }.getOrNull()
                synchronized(this) {
                    decoding.remove(s)
                    if (pcm != null && pcm.isNotEmpty()) {
                        cache[s] = pcm
                        while (cache.size > KEEP) cache.remove(cache.keys.first())
                    }
                }
            }
        }
        return null
    }

    /** Ogg → interleaved 16-bit stereo samples. */
    private fun decode(ctx: Context, res: Int): ShortArray {
        val afd = ctx.resources.openRawResourceFd(res)
        val ex = MediaExtractor()
        try {
            ex.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        } finally {
            afd.close()
        }
        val format = ex.getTrackFormat(0)
        ex.selectTrack(0)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var out = ShortArray(44_100 * 2 * 75)
        var n = 0
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val size = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val shorts = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val count = shorts.remaining()
                    val needed = n + if (channels == 1) count * 2 else count
                    if (needed > out.size) out = out.copyOf(maxOf(needed, out.size * 3 / 2))
                    if (channels == 1) {
                        repeat(count) {
                            val v = shorts.get()
                            out[n++] = v
                            out[n++] = v
                        }
                    } else {
                        shorts.get(out, n, count)
                        n += count
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            ex.release()
        }
        return out.copyOf(n - n % 2)
    }
}

/**
 * Plays up to two [NatureSound]s together, each with its own volume, on and on (the files are
 * seamless loops). Changing a sound fades across; [master] fades everything (Sleep's timer).
 * Forest plays one with [play]; Sleep mixes two with [mix].
 */
class NaturePlayer {
    private class Layer {
        @Volatile var want: NatureSound? = null
        @Volatile var volume = 1f
        var current: NatureSound? = null
        var pcm: ShortArray? = null
        var pos = 0
        var gain = 0f
    }

    private val layers = arrayOf(Layer(), Layer())
    @Volatile var master = 1f
    @Volatile private var playing = false
    private var worker: Thread? = null

    val isPlaying: Boolean get() = playing
    val sound: NatureSound? get() = layers[0].want

    fun play(s: NatureSound) = mix(s, 1f, null, 0f)

    fun mix(a: NatureSound?, aVolume: Float, b: NatureSound?, bVolume: Float) {
        layers[0].want = a
        layers[0].volume = aVolume.coerceIn(0f, 1f)
        layers[1].want = b
        layers[1].volume = bVolume.coerceIn(0f, 1f)
        a?.let { SoundBank.samples(it) }
        b?.let { SoundBank.samples(it) }
        if (a == null && b == null) {
            stop()
            return
        }
        if (playing) return
        playing = true
        worker = thread(name = "calm-sound", isDaemon = true) { run() }
    }

    fun stop() {
        playing = false
        worker = null
    }

    private fun run() {
        val frames = 2_048
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(maxOf(min, frames * 4 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull()
        if (track == null) {
            playing = false
            return
        }
        val buf = ShortArray(frames * 2)
        val mixL = FloatArray(frames)
        val mixR = FloatArray(frames)
        val step = 1f / (RATE * .6f) // 0.6 s fades
        var out = 0f
        try {
            track.play()
            while (true) {
                mixL.fill(0f)
                mixR.fill(0f)
                var audible = false
                for (layer in layers) {
                    val want = if (playing) layer.want else null
                    // Fade out, swap (once the new one is decoded), fade in.
                    if (layer.gain <= 0f && want != layer.current) {
                        val pcm = want?.let { SoundBank.samples(it) }
                        if (want == null || pcm != null) {
                            layer.current = want
                            layer.pcm = pcm
                            layer.pos = 0
                        }
                    }
                    val target = if (want != null && want == layer.current) layer.volume else 0f
                    val pcm = layer.pcm
                    if (pcm == null || (layer.gain <= 0f && target <= 0f)) {
                        layer.gain = 0f
                        continue
                    }
                    audible = true
                    var pos = layer.pos
                    var g = layer.gain
                    for (i in 0 until frames) {
                        g = if (g < target) minOf(target, g + step) else maxOf(target, g - step)
                        val gg = g * g
                        mixL[i] += pcm[pos] * gg
                        mixR[i] += pcm[pos + 1] * gg
                        pos += 2
                        if (pos >= pcm.size) pos = 0
                    }
                    layer.pos = pos
                    layer.gain = g
                }
                if (!playing && !audible) break
                val m = master.coerceIn(0f, 1f)
                for (i in 0 until frames) {
                    out = if (out < m) minOf(m, out + step) else maxOf(m, out - step)
                    val g = out * out * VOLUME
                    buf[2 * i] = (mixL[i] * g).coerceIn(-32_000f, 32_000f).toInt().toShort()
                    buf[2 * i + 1] = (mixR[i] * g).coerceIn(-32_000f, 32_000f).toInt().toShort()
                }
                track.write(buf, 0, buf.size)
            }
        } finally {
            runCatching { track.pause() }
            runCatching { track.flush() }
            track.release()
        }
    }

    companion object {
        const val RATE = 44_100
        private const val VOLUME = .95f

        /** A singing bowl struck once (a real one), ringing out over ~14 s. */
        fun chime() {
            if (!SoundBank.ready) return
            thread(name = "forest-chime", isDaemon = true) {
                runCatching {
                    val mp = MediaPlayer.create(SoundBank.context, R.raw.snd_bell) ?: return@runCatching
                    mp.setOnCompletionListener { it.release() }
                    mp.start()
                }
            }
        }
    }
}
