package com.agani.syncup.walkie

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import com.agani.syncup.BuildConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Walkie Talkie's sound: 16 kHz mono speech in 20 ms frames, squeezed with AMR-WB (the "HD voice"
 * codec every Android phone has — about 3 KB a second, so even Bluetooth carries a full channel),
 * or plain PCM on a phone without the encoder.
 */
internal object Voice {
    const val RATE = 16_000
    const val FRAME = 320 // samples = 20 ms
    const val CODEC_PCM = 0
    const val CODEC_AMR_WB = 1
    private const val MIME = MediaFormat.MIMETYPE_AUDIO_AMR_WB
    const val TAG = "Walkie"

    /** What this phone sends: AMR-WB when it can encode it, else PCM. */
    val sendCodec: Int by lazy {
        val f = MediaFormat.createAudioFormat(MIME, RATE, 1).apply { setInteger(MediaFormat.KEY_BIT_RATE, 23_850) }
        if (runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(f) }.getOrNull() != null) CODEC_AMR_WB else CODEC_PCM
    }

    // ------------------------------------------------------------------ codecs
    interface Encoder {
        fun encode(pcm: ShortArray, out: (ByteArray) -> Unit)
        fun release()
    }

    interface Decoder {
        fun decode(frame: ByteArray, out: (ShortArray) -> Unit)
        fun release()
    }

    fun encoder(): Encoder = if (sendCodec == CODEC_AMR_WB) AmrEncoder() else PcmCodec
    fun decoder(codec: Int): Decoder = if (codec == CODEC_AMR_WB) AmrDecoder() else PcmCodec

    private object PcmCodec : Encoder, Decoder {
        override fun encode(pcm: ShortArray, out: (ByteArray) -> Unit) {
            val b = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            b.asShortBuffer().put(pcm)
            out(b.array())
        }

        override fun decode(frame: ByteArray, out: (ShortArray) -> Unit) {
            val s = ShortArray(frame.size / 2)
            ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s)
            out(s)
        }

        override fun release() = Unit
    }

    private class AmrEncoder : Encoder {
        private val codec = MediaCodec.createEncoderByType(MIME).apply {
            val f = MediaFormat.createAudioFormat(MIME, RATE, 1).apply { setInteger(MediaFormat.KEY_BIT_RATE, 23_850) }
            configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        private val info = MediaCodec.BufferInfo()
        private var pts = 0L

        override fun encode(pcm: ShortArray, out: (ByteArray) -> Unit) {
            val i = codec.dequeueInputBuffer(10_000)
            if (i >= 0) {
                val buf = codec.getInputBuffer(i)!!
                buf.clear()
                buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm)
                codec.queueInputBuffer(i, 0, pcm.size * 2, pts, 0)
                pts += 20_000
            }
            drain(out)
        }

        private fun drain(out: (ByteArray) -> Unit) {
            while (true) {
                val o = codec.dequeueOutputBuffer(info, 2_000)
                if (o < 0) {
                    if (o == MediaCodec.INFO_TRY_AGAIN_LATER) return else continue
                }
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val buf = codec.getOutputBuffer(o)!!
                    buf.position(info.offset)
                    out(ByteArray(info.size).also { buf.get(it) })
                }
                codec.releaseOutputBuffer(o, false)
            }
        }

        override fun release() {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    private class AmrDecoder : Decoder {
        private val codec = MediaCodec.createDecoderByType(MIME).apply {
            configure(MediaFormat.createAudioFormat(MIME, RATE, 1), null, null, 0)
            start()
        }
        private val info = MediaCodec.BufferInfo()
        private var pts = 0L

        override fun decode(frame: ByteArray, out: (ShortArray) -> Unit) {
            val i = codec.dequeueInputBuffer(10_000)
            if (i >= 0) {
                val buf = codec.getInputBuffer(i)!!
                buf.clear()
                buf.put(frame)
                codec.queueInputBuffer(i, 0, frame.size, pts, 0)
                pts += 20_000
            }
            while (true) {
                val o = codec.dequeueOutputBuffer(info, 2_000)
                if (o < 0) {
                    if (o == MediaCodec.INFO_TRY_AGAIN_LATER) return else continue
                }
                if (info.size > 0) {
                    val buf = codec.getOutputBuffer(o)!!
                    buf.position(info.offset)
                    val s = ShortArray(info.size / 2)
                    buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s)
                    out(s)
                }
                codec.releaseOutputBuffer(o, false)
            }
        }

        override fun release() {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    fun rms(pcm: ShortArray): Double {
        var sum = 0.0
        for (s in pcm) sum += s.toDouble() * s
        return sqrt(sum / pcm.size.coerceAtLeast(1))
    }
}

/**
 * The microphone: 20 ms frames, encoded, handed to [onFrame] on its own thread (with the raw
 * samples too, for the open line's "is someone speaking" check). The phone's own call clean-up
 * (echo cancelling, noise removal) is switched on where the phone has it.
 */
internal class VoiceIn(
    private val context: Context,
    private val onFrame: (pcm: ShortArray, encoded: ByteArray) -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ run() }, "walkie-mic").also { it.start() }
    }

    fun stop() {
        running = false
    }

    @SuppressLint("MissingPermission") // checked by Walkie before starting
    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val min = AudioRecord.getMinBufferSize(Voice.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, Voice.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, Voice.FRAME * 2 * 4))
        }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(Voice.TAG, "mic not available")
            rec?.release()
            running = false
            return
        }
        val effects = listOfNotNull(
            if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull() else null,
            if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull() else null,
            if (AutomaticGainControl.isAvailable()) runCatching { AutomaticGainControl.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull() else null,
        )
        // Debug builds: a test tone instead of the mic (emulators have no real mic) — see Walkie's docs.
        val tone = BuildConfig.DEBUG && File(context.filesDir, "walkie_test_tone").exists()
        var phase = 0.0
        val enc = runCatching { Voice.encoder() }.getOrNull()
        val pcm = ShortArray(Voice.FRAME)
        try {
            rec.startRecording()
            while (running) {
                var got = 0
                while (got < pcm.size && running) {
                    val n = rec.read(pcm, got, pcm.size - got)
                    if (n <= 0) break
                    got += n
                }
                if (!running || got < pcm.size) break
                if (tone) for (i in pcm.indices) {
                    pcm[i] = (sin(phase) * 8_000).toInt().toShort()
                    phase += 2 * PI * 600 / Voice.RATE
                }
                val frame = pcm.copyOf()
                enc?.encode(frame) { onFrame(frame, it) }
            }
        } catch (e: Exception) {
            Log.w(Voice.TAG, "mic failed", e)
        } finally {
            runCatching { rec.stop() }
            rec.release()
            effects.forEach { runCatching { it.release() } }
            enc?.release()
            running = false
        }
    }
}

/**
 * The speaker: each person's voice is decoded into its own short queue (a little is kept in hand
 * so a shaky signal doesn't stutter), and the voices are mixed into one 16 kHz stream.
 */
internal class VoiceOut(
    /** The speaker is about to start after a quiet spell (speaker thread). */
    private val onWake: () -> Unit,
) {
    private class Stream(val codec: Int) {
        val decoder = runCatching { Voice.decoder(codec) }.getOrNull()
        val buf = ShortArray(Voice.RATE) // 1 s ring
        var read = 0
        var size = 0
        var playing = false
        var empty = 0
        @Volatile var lastHeardMs = 0L
        var lastSeq = -1
        var level = 0.0

        fun put(s: ShortArray) {
            for (v in s) {
                if (size == buf.size) {
                    read = (read + 1) % buf.size
                    size--
                }
                buf[(read + size) % buf.size] = v
                size++
            }
        }

        fun take(into: IntArray): Boolean {
            if (size < Voice.FRAME) return false
            for (i in 0 until Voice.FRAME) {
                into[i] += buf[read].toInt()
                read = (read + 1) % buf.size
            }
            size -= Voice.FRAME
            return true
        }

        fun trim(keep: Int) {
            if (size > keep) {
                read = (read + size - keep) % buf.size
                size = keep
            }
        }
    }

    private val streams = HashMap<Int, Stream>()
    private val lock = Object()
    private val decodeThread = HandlerThread("walkie-decode").apply { start() }
    private val decodeHandler = Handler(decodeThread.looper)
    @Volatile private var running = true
    @Volatile var muted = false
    private val thread = Thread({ run() }, "walkie-speaker").also { it.start() }

    /** Frames from [sender] arrived (any thread). */
    fun push(sender: Int, seq: Int, codec: Int, frames: List<ByteArray>) {
        decodeHandler.post {
            val s = synchronized(lock) { streams.getOrPut(sender) { Stream(codec) } }
            s.lastHeardMs = android.os.SystemClock.elapsedRealtime()
            // A gap in the numbers: fill what's missing with silence so the timing holds.
            if (s.lastSeq >= 0 && seq > s.lastSeq + 1 && seq - s.lastSeq < 10) {
                val missing = ShortArray(Voice.FRAME * frames.size * (seq - s.lastSeq - 1))
                synchronized(lock) { s.put(missing) }
            }
            s.lastSeq = seq
            for (f in frames) s.decoder?.decode(f) { pcm ->
                s.level = Voice.rms(pcm)
                synchronized(lock) { s.put(pcm) }
            }
        }
    }

    /** People heard in the last [withinMs] (for the "talking" rings). */
    fun speaking(now: Long, withinMs: Long = 350): Set<Int> = synchronized(lock) {
        streams.filter { now - it.value.lastHeardMs < withinMs }.keys.toSet()
    }

    /** For checking in debug logs that sound really came through. */
    fun level(sender: Int): Double = synchronized(lock) { streams[sender]?.level ?: 0.0 }

    fun forget(sender: Int) {
        decodeHandler.post {
            synchronized(lock) { streams.remove(sender) }?.decoder?.release()
        }
    }

    fun release() {
        running = false
        decodeHandler.post {
            synchronized(lock) { streams.values.toList().also { streams.clear() } }.forEach { it.decoder?.release() }
            decodeThread.quitSafely()
        }
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val min = AudioTrack.getMinBufferSize(Voice.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                .setAudioFormat(AudioFormat.Builder().setSampleRate(Voice.RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(min, Voice.FRAME * 2 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return
        val mix = IntArray(Voice.FRAME)
        val out = ShortArray(Voice.FRAME)
        var idle = 0
        var on = false
        try {
            while (running) {
                mix.fill(0)
                var any = false
                synchronized(lock) {
                    for (s in streams.values) {
                        if (!s.playing) {
                            // Keep ~80 ms in hand before starting someone's voice.
                            if (s.size >= Voice.FRAME * 4) {
                                s.playing = true
                                s.empty = 0
                            } else {
                                continue
                            }
                        }
                        // More than 0.3 s behind (a burst after a stall): catch up.
                        s.trim(Voice.FRAME * 15)
                        if (s.take(mix)) {
                            any = true
                            s.empty = 0
                        } else if (++s.empty > 10) {
                            s.playing = false // they stopped talking
                        }
                    }
                }
                if (!any) {
                    // Nothing to play: rest the speaker after a moment.
                    if (on && ++idle > 50) {
                        track.pause()
                        track.flush()
                        on = false
                    }
                    if (!on) {
                        Thread.sleep(10)
                        continue
                    }
                } else {
                    idle = 0
                    if (!on) {
                        onWake()
                        track.play()
                        on = true
                    }
                }
                for (i in 0 until Voice.FRAME) out[i] = if (muted) 0 else mix[i].coerceIn(-32768, 32767).toShort()
                track.write(out, 0, out.size)
            }
        } catch (e: Exception) {
            Log.w(Voice.TAG, "speaker failed", e)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }
}

/** Where voices come out: loudspeaker, earpiece, or a headset when one is connected. */
internal object AudioRoute {
    private fun am(context: Context) = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val headsetTypes = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET,
    )

    /** A connected headset's name, or null. */
    fun headset(context: Context): String? {
        val am = am(context)
        val list = if (Build.VERSION.SDK_INT >= 31) am.availableCommunicationDevices else am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        return list.firstOrNull { it.type in headsetTypes }?.let { d ->
            d.productName?.toString()?.takeIf { it.isNotBlank() && it != android.os.Build.MODEL } ?: "Headset"
        }
    }

    fun enter(context: Context, loud: Boolean) {
        val am = am(context)
        runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
        apply(context, loud)
    }

    @Suppress("DEPRECATION")
    fun apply(context: Context, loud: Boolean) {
        val am = am(context)
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val devices = am.availableCommunicationDevices
                val target = devices.firstOrNull { it.type in headsetTypes }
                    ?: devices.firstOrNull { it.type == if (loud) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                if (target != null) am.setCommunicationDevice(target) else am.clearCommunicationDevice()
            } else {
                am.isSpeakerphoneOn = loud && !am.isWiredHeadsetOn
            }
        }
    }

    @Suppress("DEPRECATION")
    fun leave(context: Context) {
        val am = am(context)
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else am.isSpeakerphoneOn = false
            am.mode = AudioManager.MODE_NORMAL
        }
    }

    /** A real phone call is ringing or going on (Walkie Talkie steps aside on this phone). */
    fun inPhoneCall(context: Context): Boolean = am(context).mode.let { it == AudioManager.MODE_IN_CALL || it == AudioManager.MODE_RINGTONE }

    /**
     * Back to call audio (echo cancelling, earpiece / loudspeaker choice). Android drops an app's
     * call mode a few seconds after its sound stops, so this runs each time voices or the mic start.
     */
    fun backToCommunication(context: Context, loud: Boolean) {
        if (am(context).mode != AudioManager.MODE_IN_COMMUNICATION && !inPhoneCall(context)) enter(context, loud)
    }
}

/** Short beeps: on air, over, and "call all". */
internal class Beeps {
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70) }.getOrNull()
    fun start() = play(ToneGenerator.TONE_PROP_BEEP, 120)
    fun over() = play(ToneGenerator.TONE_PROP_ACK, 160)
    fun busy() = play(ToneGenerator.TONE_PROP_NACK, 200)
    fun call() = play(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600)
    private fun play(type: Int, ms: Int) {
        runCatching { tone?.startTone(type, ms) }
    }

    fun release() {
        runCatching { tone?.release() }
    }
}
