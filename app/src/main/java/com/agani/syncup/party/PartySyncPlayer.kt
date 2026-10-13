package com.agani.syncup.party

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Process
import android.util.Log
import com.agani.syncup.BuildConfig
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Plays one party song so every phone is at the same sample at the same moment.
 *
 * The host says "position 0 of this song is at host time [t0HostNs]". This phone decodes the song
 * itself (MediaCodec) and feeds an AudioTrack; Android's AudioTimestamp says which frame is coming
 * out of the speaker at what time, and [hostOffsetNs] turns that into host time. The difference
 * between where the song IS and where it SHOULD be is corrected by skipping frames (behind) or
 * holding the last frame (ahead): big gaps at once (start, a stall), small drift gently — at most
 * 1 frame in 200, which isn't heard. Before the start time it plays silence, so "start at T" lands
 * on T; joining late it skips straight to the right spot.
 *
 * Runs on its own thread; [stop] silences it at once. Used by the host for its own playback too
 * (offset 0), so host and guests follow exactly the same timeline.
 */
internal class PartySyncPlayer(
    private val context: Context,
    private val local: Uri?,
    private val data: TrackData?,
    private val t0HostNs: Long,
    private val hostOffsetNs: () -> Long,
    private val adjustNs: () -> Long,
    private val listener: Listener,
) {
    interface Listener {
        /** The song played to its end (the host moves on). */
        fun onEnded()
        fun onError(message: String)
        /** How far off this phone is, in ms (+ = ahead), after each measurement. */
        fun onSync(errorMs: Double)
    }

    @Volatile private var stopped = false
    @Volatile private var track: AudioTrack? = null
    private var thread: Thread? = null

    fun start() {
        thread = Thread({ run() }, "party-audio").also { it.start() }
    }

    fun stop() {
        stopped = true
        track?.let { runCatching { it.pause(); it.flush() } }
    }

    // ------------------------------------------------------------------ playback thread
    private var sampleRate = 44_100
    private var frameBytes = 4
    /** AudioTrack frames written so far (the next frame's index). */
    private var written = 0L
    /** Song frame = AudioTrack frame + delta; changes when frames are skipped or held. */
    private var delta = 0L
    private var started = false
    /** (AudioTrack frame, delta from there on) — to read the timeline at a past frame. */
    private val deltas = ArrayDeque<LongArray>()
    /** Frames still to hold (ahead) — or, if negative, to skip (behind) — spread gently. */
    private var fine = 0L
    private var lastFrame = ByteArray(0)
    private var silence = ByteArray(0)
    private var nextCheckNs = 0L
    /** Don't judge a correction before the audio written after it is actually playing. */
    private var settleAt = 0L
    private val ts = AudioTimestamp()
    private var lastLogNs = 0L

    private fun hostToLocal(hostNs: Long) = hostNs - hostOffsetNs()

    /** The song frame that should be playing at local time [localNs]. */
    private fun frameAt(localNs: Long): Long =
        ((localNs + hostOffsetNs() + adjustNs() - t0HostNs).toDouble() * sampleRate / 1e9).roundToLong()

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (local != null) extractor.setDataSource(context, local, null) else extractor.setDataSource(data!!.dataSource { stopped })
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalStateException("This song can't be played")
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            // Joining late: jump close to where everyone is (just before, then trim exactly).
            val startUs = ((System.nanoTime() + PREP_NS + hostOffsetNs() - t0HostNs) / 1000).coerceAtLeast(0)
            if (startUs > 1_000_000) extractor.seekTo(startUs - 500_000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            decode(extractor, codec)
        } catch (e: Exception) {
            if (!stopped) {
                Log.w(TAG, "playback failed", e)
                listener.onError(e.message ?: "Couldn't play this song")
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            track?.let { t -> runCatching { t.pause(); t.flush(); t.release() } }
            track = null
        }
    }

    private fun decode(extractor: MediaExtractor, codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        while (!stopped) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)!!
                    val n = extractor.readSampleData(buffer, 0)
                    if (n < 0 || stopped) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> openTrack(codec.outputFormat)
                outIndex >= 0 -> {
                    val buffer = codec.getOutputBuffer(outIndex)
                    if (buffer != null && info.size > 0) {
                        if (track == null) openTrack(codec.outputFormat)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val firstFrame = (info.presentationTimeUs.toDouble() * sampleRate / 1e6).roundToLong()
                        writeSong(buffer, firstFrame, info.size / frameBytes)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        drainToEnd()
                        return
                    }
                }
            }
        }
    }

    private fun openTrack(format: MediaFormat) {
        if (track != null) return
        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
        val mask = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
            else -> throw IllegalStateException("This song's sound format isn't supported")
        }
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            AudioFormat.ENCODING_PCM_8BIT -> 1
            else -> 2
        }
        frameBytes = bytesPerSample * channels
        lastFrame = ByteArray(frameBytes)
        silence = ByteArray(frameBytes * 2048)
        val min = AudioTrack.getMinBufferSize(sampleRate, mask, encoding).coerceAtLeast(frameBytes * 1024)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
            )
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(encoding).setChannelMask(mask).build())
            .setBufferSizeInBytes(min * 3)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    }

    /** Song frames [first, first + frames) from [pcm], put on the timeline. */
    private fun writeSong(pcm: ByteBuffer, first: Long, frames: Int) {
        if (!started) {
            started = true
            // The first frame out of the speaker should be the one due when it gets there.
            setDelta(frameAt(System.nanoTime() + LATENCY_GUESS_NS))
            nextCheckNs = System.nanoTime() + 300_000_000L
        }
        var at = first
        val end = first + frames
        while (at < end && !stopped) {
            checkSync()
            val due = written + delta
            when {
                // Behind (or joining late): skip what's already past.
                due > at -> {
                    val skip = minOf(due - at, end - at)
                    pcm.position(pcm.position() + (skip * frameBytes).toInt())
                    at += skip
                }
                // The song hasn't reached this frame yet (start in the future / a stall): silence.
                due < at -> writeSilence(minOf(at - due, 2048L).toInt())
                else -> {
                    // On time: write a slice, nudging by a frame now and then if slightly off.
                    val slice = minOf(end - at, 1024L).toInt()
                    val bytes = slice * frameBytes
                    val view = pcm.duplicate()
                    view.limit(view.position() + bytes)
                    writeBuffer(view, bytes)
                    pcm.position(pcm.position() + bytes - frameBytes)
                    pcm.get(lastFrame)
                    at += slice
                    nudge(slice)
                }
            }
        }
    }

    /** Spread a small correction: at most 1 frame in 200 (not heard). */
    private fun nudge(slice: Int) {
        if (fine == 0L) return
        val step = minOf(abs(fine), (slice / 200L).coerceAtLeast(1L))
        if (fine > 0) {
            // Ahead: hold the last frame a moment.
            repeat(step.toInt()) { writeBytes(lastFrame, frameBytes) }
            setDelta(delta - step)
            fine -= step
        } else {
            // Behind: skip a frame or two of what comes next.
            setDelta(delta + step)
            fine += step
        }
    }

    private fun checkSync() {
        val now = System.nanoTime()
        if (now < nextCheckNs) return
        nextCheckNs = now + 250_000_000L
        val t = track ?: return
        if (!t.getTimestamp(ts) || ts.framePosition < settleAt) return
        val actual = ts.framePosition + deltaAt(ts.framePosition)
        val expected = frameAt(ts.nanoTime)
        val error = actual - expected // + = ahead
        listener.onSync(error * 1000.0 / sampleRate)
        if (BuildConfig.DEBUG && now - lastLogNs > 2_000_000_000L) {
            // For checking phones against each other: which song moment plays at which wall-clock time.
            lastLogNs = now
            val wallMs = System.currentTimeMillis() - (now - ts.nanoTime) / 1_000_000.0
            Log.d(TAG, "sync songMs=%.1f wallMs=%.1f errMs=%.2f".format(actual * 1000.0 / sampleRate, wallMs, error * 1000.0 / sampleRate))
        }
        val big = sampleRate * 30L / 1000 // 30 ms
        val small = sampleRate * 2L / 1000 // 2 ms
        when {
            abs(error) > big -> {
                fine = 0
                if (error < 0) setDelta(delta - error) else writeSilence(error.toInt().coerceAtMost(sampleRate * 2))
                if (error > 0) setDelta(delta - minOf(error, sampleRate * 2L))
                settleAt = written
            }
            abs(error) > small -> if (fine == 0L) {
                fine = error
                settleAt = written + abs(error) * 200
            }
        }
    }

    private fun setDelta(value: Long) {
        delta = value
        deltas.addLast(longArrayOf(written, value))
        while (deltas.size > 64) deltas.removeFirst()
    }

    private fun deltaAt(frame: Long): Long {
        var d = deltas.firstOrNull()?.get(1) ?: delta
        for (e in deltas) if (e[0] <= frame) d = e[1] else break
        return d
    }

    private fun writeSilence(frames: Int) {
        var left = frames
        while (left > 0 && !stopped) {
            val n = minOf(left, silence.size / frameBytes)
            writeBytes(silence, n * frameBytes)
            left -= n
        }
    }

    private fun writeBytes(bytes: ByteArray, len: Int) = writeBuffer(ByteBuffer.wrap(bytes, 0, len), len)

    private fun writeBuffer(buffer: ByteBuffer, len: Int) {
        val t = track ?: return
        var left = len
        while (left > 0 && !stopped) {
            val n = t.write(buffer, left, AudioTrack.WRITE_BLOCKING)
            if (n <= 0) break
            left -= n
        }
        written += (len - left) / frameBytes
    }

    /** Let the last of the song come out of the speaker, then report the end. */
    private fun drainToEnd() {
        val t = track
        val until = System.nanoTime() + 5_000_000_000L
        while (!stopped && t != null && System.nanoTime() < until) {
            if (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL >= written - 64) break
            Thread.sleep(20)
        }
        if (!stopped) listener.onEnded()
    }

    companion object {
        private const val TAG = "PartySync"
        /** Time to open the song and fill the decoder before the first frame is wanted. */
        private const val PREP_NS = 400_000_000L
        /** First guess of the speaker delay; the first measurement corrects it. */
        private const val LATENCY_GUESS_NS = 120_000_000L
    }
}
