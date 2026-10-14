package com.agani.syncup.sensors

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * A steady tone from the speaker: any pitch, left / right / both. The pitch can change while it plays
 * (no click — the wave carries on from where it was). Used by Speaker clean and Phone check.
 */
class ToneMaker {
    @Volatile var hz = 440.0
    @Volatile var left = 1f
    @Volatile var right = 1f
    @Volatile private var playing = false
    private var worker: Thread? = null

    val isPlaying: Boolean get() = playing

    fun start() {
        if (playing) return
        playing = true
        worker = thread(name = "tone", isDaemon = true) {
            val rate = 44_100
            val frames = 1_024
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val track = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(maxOf(min, frames * 4 * 2))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }.getOrNull()
            if (track == null) {
                playing = false
                return@thread
            }
            val buf = ShortArray(frames * 2)
            var phase = 0.0
            try {
                track.play()
                while (playing) {
                    val step = 2 * PI * hz / rate
                    val l = left
                    val r = right
                    for (i in 0 until frames) {
                        val s = sin(phase) * 32_000
                        buf[2 * i] = (s * l).toInt().toShort()
                        buf[2 * i + 1] = (s * r).toInt().toShort()
                        phase += step
                        if (phase > 2 * PI) phase -= 2 * PI
                    }
                    track.write(buf, 0, buf.size)
                }
            } finally {
                runCatching { track.pause() }
                runCatching { track.flush() }
                track.release()
            }
        }
    }

    fun stop() {
        playing = false
        worker = null
    }
}
