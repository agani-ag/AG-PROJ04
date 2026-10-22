package com.agani.syncup.music

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin

/**
 * The waves in Now playing, from the song itself: the player's own sound (no microphone), turned into
 * the beat (how strong the bass is) and how loud it is, about 40 times a second.
 *
 * [WavesAudioSink] copies the PCM on its way to the speaker, each piece with the time it will be heard.
 * The screen asks [frameAt] for "now" and gets the analysis of what is coming out of the speaker at that
 * moment, so the monitor spikes with the beat. Nothing is analysed unless [active] (waves on screen, playing).
 */
internal object SoundWaves {
    /** Set by the waves on screen; off = the player's sound passes straight through. */
    @Volatile var active = false

    /** One analysed slice: when it's heard, its bass level (0–1) and loudness (dBFS). */
    class Frame(val timeUs: Long, val bass: Float, val loudnessDb: Float)

    private val lock = Any()
    private val frames = ArrayDeque<Frame>()
    private var clockUs = C.TIME_UNSET
    private var clockAtNs = 0L
    private var running = false

    /** What's heard right now, or null when there's nothing to show yet (just started, after a seek). */
    fun frameAt(nowNs: Long): Frame? = synchronized(lock) {
        if (clockUs == C.TIME_UNSET || frames.isEmpty()) return null
        // The player reports its position often while playing; between reports, carry it on.
        val at = clockUs + if (running) min((nowNs - clockAtNs) / 1000, 300_000L) else 0L
        if (at < frames.first().timeUs) return null
        val i = frames.indexOfFirst { it.timeUs > at }
        if (i < 0) return frames.last()
        val a = frames[i - 1]
        val b = frames[i]
        val t = ((at - a.timeUs).toFloat() / (b.timeUs - a.timeUs).coerceAtLeast(1)).coerceIn(0f, 1f)
        return Frame(at, a.bass + (b.bass - a.bass) * t, a.loudnessDb + (b.loudnessDb - a.loudnessDb) * t)
    }

    internal fun clock(positionUs: Long, nowNs: Long) = synchronized(lock) {
        clockUs = positionUs
        clockAtNs = nowNs
    }

    internal fun running(on: Boolean) = synchronized(lock) { running = on }

    internal fun publish(f: Frame) = synchronized(lock) {
        frames.addLast(f)
        while (frames.size > MAX_FRAMES) frames.removeFirst()
    }

    /** A seek, a stop or a new player: what was analysed no longer lines up. */
    internal fun clear() = synchronized(lock) {
        frames.clear()
        clockUs = C.TIME_UNSET
        running = false
    }

    private const val MAX_FRAMES = 256 // ~6 s, well past the speaker's buffer
}

/** The player's renderers, with [WavesAudioSink] in front of the normal audio output. */
@UnstableApi
internal class WavesRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? =
        super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)?.let { WavesAudioSink(it) }
}

/**
 * Passes everything to the real audio output, and — while the waves are on screen — hands a copy of
 * each PCM piece to the [WaveAnalyzer], with its presentation time. The output's own position (the
 * same clock) tells [SoundWaves] which piece is being heard.
 */
@UnstableApi
private class WavesAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    private val analyzer = WaveAnalyzer()
    private var encoding = C.ENCODING_INVALID
    private var channels = 0

    // The renderer offers a piece again until the output takes all of it: analyse it once.
    private var lastBuffer: ByteBuffer? = null
    private var lastTimeUs = Long.MIN_VALUE

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
        encoding = if (inputFormat.sampleMimeType == MimeTypes.AUDIO_RAW) inputFormat.pcmEncoding else C.ENCODING_INVALID
        channels = inputFormat.channelCount
        analyzer.configure(inputFormat.sampleRate)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (SoundWaves.active && (buffer !== lastBuffer || presentationTimeUs != lastTimeUs)) {
            lastBuffer = buffer
            lastTimeUs = presentationTimeUs
            analyzer.feed(buffer, encoding, channels, presentationTimeUs)
        }
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val p = super.getCurrentPositionUs(sourceEnded)
        if (p != AudioSink.CURRENT_POSITION_NOT_SET) SoundWaves.clock(p, System.nanoTime())
        return p
    }

    override fun play() {
        super.play()
        SoundWaves.running(true)
    }

    override fun pause() {
        super.pause()
        SoundWaves.running(false)
    }

    override fun flush() {
        super.flush()
        forget()
    }

    override fun reset() {
        super.reset()
        forget()
    }

    private fun forget() {
        lastBuffer = null
        lastTimeUs = Long.MIN_VALUE
        analyzer.restart()
        SoundWaves.clear()
    }
}

/**
 * Mono samples in, a [SoundWaves.Frame] out every [HOP] samples: a 2048-point FFT (Hann window) whose
 * bass notes (60–250 Hz: the kick and bass line, where a beat lives) give the bass level, plus the
 * loudness of the same stretch. Runs on the player's thread.
 */
private class WaveAnalyzer {
    private val ring = FloatArray(N)
    private var write = 0
    private var filled = 0
    private var sinceHop = 0
    private var sampleRate = 44_100

    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private var bassLo = 1
    private var bassHi = 1
    private val binTilt = FloatArray(N / 2)

    init {
        configure(sampleRate)
    }

    fun configure(rate: Int) {
        if (rate <= 0) return
        sampleRate = rate
        val binHz = rate.toFloat() / N
        bassLo = (BASS_LOW_HZ / binHz).toInt().coerceIn(1, N / 2 - 1)
        bassHi = (BASS_HIGH_HZ / binHz).toInt().coerceIn(bassLo, N / 2 - 1)
        // The lowest notes carry the most energy; level them a little so the whole bass counts.
        for (bin in bassLo..bassHi) binTilt[bin] = TILT_DB_PER_OCTAVE * (ln(bin * binHz / TILT_PIVOT_HZ) / ln(2f))
        restart()
    }

    fun restart() {
        write = 0
        filled = 0
        sinceHop = 0
    }

    fun feed(buffer: ByteBuffer, encoding: Int, channels: Int, timeUs: Long) {
        val bytes = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT -> 4
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_8BIT -> 1
            else -> return // compressed pass-through: nothing to read
        }
        if (channels <= 0) return
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN) // never moves the player's buffer
        val start = b.position()
        val frameBytes = bytes * channels
        val count = (b.limit() - start) / frameBytes
        for (f in 0 until count) {
            val base = start + f * frameBytes
            var sum = 0f
            for (c in 0 until channels) sum += sample(b, base + c * bytes, encoding)
            ring[write] = sum / channels
            write = (write + 1) % N
            if (filled < N) filled++
            if (++sinceHop >= HOP && filled == N) {
                sinceHop = 0
                // This slice ends at sample f; it's heard, centred, half a window earlier.
                analyse(timeUs + (f + 1 - N / 2) * 1_000_000L / sampleRate)
            }
        }
    }

    private fun sample(b: ByteBuffer, i: Int, encoding: Int): Float = when (encoding) {
        C.ENCODING_PCM_16BIT -> b.getShort(i) / 32768f
        C.ENCODING_PCM_FLOAT -> b.getFloat(i)
        C.ENCODING_PCM_32BIT -> b.getInt(i) / 2147483648f
        C.ENCODING_PCM_24BIT -> ((b.get(i).toInt() and 0xFF) or ((b.get(i + 1).toInt() and 0xFF) shl 8) or (b.get(i + 2).toInt() shl 16)) / 8388608f
        else -> ((b.get(i).toInt() and 0xFF) - 128) / 128f
    }

    private fun analyse(timeUs: Long) {
        var power = 0f
        for (j in 0 until N) {
            val s = ring[(write + j) % N] // oldest first
            power += s * s
            re[j] = s * WINDOW[j]
            im[j] = 0f
        }
        fft(re, im)
        var bass = 0f
        for (bin in bassLo..bassHi) {
            val p = (re[bin] * re[bin] + im[bin] * im[bin]) / FULL_SCALE
            val db = 10f * log10(p + 1e-12f) + binTilt[bin]
            bass += ((db - FLOOR_DB) / (CEIL_DB - FLOOR_DB)).coerceIn(0f, 1f)
        }
        bass /= bassHi - bassLo + 1
        val loudness = 10f * log10(power / N + 1e-10f)
        SoundWaves.publish(SoundWaves.Frame(timeUs, bass, loudness))
    }

    private companion object {
        const val N = 2048
        const val HOP = 1024
        const val BASS_LOW_HZ = 60f
        const val BASS_HIGH_HZ = 250f
        const val TILT_PIVOT_HZ = 400f
        const val TILT_DB_PER_OCTAVE = 4f
        const val FLOOR_DB = -72f
        const val CEIL_DB = -18f

        /** A full-scale sine's peak power after the Hann window: (N/4)². */
        const val FULL_SCALE = (N / 4f) * (N / 4f)

        val WINDOW = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / (N - 1))).toFloat() }
        private val COS = FloatArray(N / 2) { cos(-2 * PI * it / N).toFloat() }
        private val SIN = FloatArray(N / 2) { sin(-2 * PI * it / N).toFloat() }
        private val BITS = (ln(N.toDouble()) / ln(2.0)).toInt()
        private val REVERSED = IntArray(N) { Integer.reverse(it) ushr (32 - BITS) }

        /** In-place radix-2 FFT. */
        fun fft(re: FloatArray, im: FloatArray) {
            for (i in 0 until N) {
                val j = REVERSED[i]
                if (j > i) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var size = 2
            while (size <= N) {
                val half = size / 2
                val step = N / size
                var start = 0
                while (start < N) {
                    for (k in 0 until half) {
                        val w = k * step
                        val a = start + k
                        val b = a + half
                        val tr = re[b] * COS[w] - im[b] * SIN[w]
                        val ti = re[b] * SIN[w] + im[b] * COS[w]
                        re[b] = re[a] - tr
                        im[b] = im[a] - ti
                        re[a] += tr
                        im[a] += ti
                    }
                    start += size
                }
                size *= 2
            }
        }
    }
}
