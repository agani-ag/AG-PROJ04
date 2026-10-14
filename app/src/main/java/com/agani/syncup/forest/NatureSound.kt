package com.agani.syncup.forest

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.Random
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Calm sounds, all made by the phone as they play — nothing to download. */
enum class NatureSound(val title: String) { RAIN("Rain"), RIVER("River"), WIND("Wind"), BIRDS("Birds") }

/**
 * Plays one [NatureSound] on and on. Changing [sound] while it plays fades across (no click).
 * Each sound is noise shaped by small filters, plus little events (drops, bubbles, bird calls).
 */
class NaturePlayer {
    @Volatile var sound: NatureSound? = null
    @Volatile private var playing = false
    private var worker: Thread? = null

    val isPlaying: Boolean get() = playing

    fun play(s: NatureSound) {
        sound = s
        if (playing) return
        playing = true
        worker = thread(name = "forest-sound", isDaemon = true) { run() }
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
        val l = FloatArray(frames)
        val r = FloatArray(frames)
        var current: NatureSound? = null
        var gen: Gen? = null
        var gain = 0f
        try {
            track.play()
            while (true) {
                val want = if (playing) sound else null
                // Fade out, swap, fade in.
                val target = if (want == current && want != null) 1f else 0f
                if (gain <= 0f && want != current) {
                    current = want
                    gen = when (want) {
                        NatureSound.RAIN -> Rain()
                        NatureSound.RIVER -> River()
                        NatureSound.WIND -> Wind()
                        NatureSound.BIRDS -> Birds()
                        null -> null
                    }
                }
                if (!playing && gain <= 0f) break
                gen?.fill(l, r, frames) ?: run { l.fill(0f); r.fill(0f) }
                val step = 1f / (RATE * .6f) // 0.6 s fades
                for (i in 0 until frames) {
                    gain = if (gain < target) minOf(target, gain + step) else maxOf(target, gain - step)
                    val g = gain * gain * VOLUME
                    buf[2 * i] = (l[i] * g).coerceIn(-1f, 1f).times(32_000).toInt().toShort()
                    buf[2 * i + 1] = (r[i] * g).coerceIn(-1f, 1f).times(32_000).toInt().toShort()
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
        private const val VOLUME = .9f

        /**
         * A singing bowl struck once: a few partials that don't line up (that's what makes it a bowl),
         * each a close pair that beats slowly, ringing out over ~6 s.
         */
        fun chime() {
            thread(name = "forest-chime", isDaemon = true) {
                val seconds = 6.5f
                val n = (RATE * seconds).toInt()
                val pcm = ShortArray(n * 2)
                val f0 = 392.0
                val ratios = doubleArrayOf(1.0, 2.76, 5.40, 8.93)
                val amps = doubleArrayOf(1.0, .45, .22, .10)
                val decays = doubleArrayOf(2.4, 1.3, .7, .4)
                for (i in 0 until n) {
                    val t = i.toDouble() / RATE
                    val attack = minOf(1.0, t / .006)
                    var s = 0.0
                    for (k in ratios.indices) {
                        val f = f0 * ratios[k]
                        val e = exp(-t / decays[k]) * amps[k]
                        s += e * (sin(2 * PI * f * t) + .7 * sin(2 * PI * f * 1.0035 * t + k))
                    }
                    val v = s * attack * .2
                    val pan = sin(2 * PI * .15 * t) * .15
                    pcm[2 * i] = (v * (1 - pan) * 32_000).coerceIn(-32_000.0, 32_000.0).toInt().toShort()
                    pcm[2 * i + 1] = (v * (1 + pan) * 32_000).coerceIn(-32_000.0, 32_000.0).toInt().toShort()
                }
                val track = runCatching {
                    AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                        .setBufferSizeInBytes(pcm.size * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build()
                }.getOrNull() ?: return@thread
                runCatching {
                    track.write(pcm, 0, pcm.size)
                    track.play()
                    Thread.sleep((seconds * 1000).toLong() + 200)
                }
                runCatching { track.stop() }
                track.release()
            }
        }
    }
}

// ------------------------------------------------------------------ the sounds
private abstract class Gen {
    val rnd = Random()
    fun white(): Float = rnd.nextFloat() * 2 - 1
    abstract fun fill(l: FloatArray, r: FloatArray, n: Int)
}

/** Pink noise (Paul Kellet's filter): softer than white, like steady rain. */
private class Pink(private val rnd: Random) {
    private var b0 = 0f; private var b1 = 0f; private var b2 = 0f; private var b3 = 0f; private var b4 = 0f; private var b5 = 0f; private var b6 = 0f
    fun next(): Float {
        val w = rnd.nextFloat() * 2 - 1
        b0 = .99886f * b0 + w * .0555179f
        b1 = .99332f * b1 + w * .0750759f
        b2 = .96900f * b2 + w * .1538520f
        b3 = .86650f * b3 + w * .3104856f
        b4 = .55000f * b4 + w * .5329522f
        b5 = -.7616f * b5 - w * .0168980f
        val out = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * .5362f
        b6 = w * .115926f
        return out * .11f
    }
}

/** A band-pass filter whose centre can move (state-variable filter). */
private class Band {
    private var low = 0f
    private var band = 0f
    fun next(x: Float, hz: Float, q: Float): Float {
        val f = (2 * sin(PI * hz / NaturePlayer.RATE)).toFloat().coerceAtMost(1.2f)
        low += f * band
        val high = x - low - band / q
        band += f * high
        return band
    }
}

/** One-pole low-pass. */
private class Low(hz: Float) {
    private val a = exp(-2 * PI * hz / NaturePlayer.RATE).toFloat()
    private var y = 0f
    fun next(x: Float): Float {
        y = x * (1 - a) + y * a
        return y
    }
}

/** A slow, smooth wander between 0 and 1 (for gusts and swells). */
private class Drift(private val rnd: Random, private val seconds: Float) {
    private var from = rnd.nextFloat()
    private var to = rnd.nextFloat()
    private var i = 0
    private var len = (NaturePlayer.RATE * seconds).toInt()
    fun next(): Float {
        if (++i >= len) {
            i = 0
            from = to
            to = rnd.nextFloat()
            len = (NaturePlayer.RATE * seconds * (.6f + rnd.nextFloat() * .8f)).toInt()
        }
        val t = i.toFloat() / len
        val s = t * t * (3 - 2 * t)
        return from + (to - from) * s
    }
}

private class Rain : Gen() {
    private val pl = Pink(rnd)
    private val pr = Pink(rnd)
    private val ll = Low(5_000f)
    private val lr = Low(5_000f)
    private val swell = Drift(rnd, 7f)

    private class Drop(var hz: Float, var phase: Float, var amp: Float, var decay: Float, var pan: Float)
    private val drops = ArrayList<Drop>()

    override fun fill(l: FloatArray, r: FloatArray, n: Int) {
        for (i in 0 until n) {
            val s = .75f + .25f * swell.next()
            var a = ll.next(pl.next()) * s * 1.6f
            var b = lr.next(pr.next()) * s * 1.6f
            if (rnd.nextFloat() < 40f / NaturePlayer.RATE && drops.size < 20) {
                drops.add(Drop(1_800f + rnd.nextFloat() * 3_200f, 0f, .03f + rnd.nextFloat() * .07f, exp(-1f / (NaturePlayer.RATE * (.004f + rnd.nextFloat() * .012f))), rnd.nextFloat()))
            }
            val it = drops.iterator()
            while (it.hasNext()) {
                val d = it.next()
                d.phase += (2 * PI * d.hz / NaturePlayer.RATE).toFloat()
                d.hz *= .9998f
                val v = sin(d.phase) * d.amp
                a += v * (1 - d.pan)
                b += v * d.pan
                d.amp *= d.decay
                if (d.amp < .0005f) it.remove()
            }
            l[i] = a
            r[i] = b
        }
    }
}

private class River : Gen() {
    private var bl = 0f
    private var br = 0f
    private val rush = Band()
    private val rush2 = Band()
    private val swell = Drift(rnd, 4f)
    private val swell2 = Drift(rnd, 3f)

    private class Bubble(var hz: Float, var phase: Float, var amp: Float, var t: Int, val len: Int, val pan: Float)
    private val bubbles = ArrayList<Bubble>()

    override fun fill(l: FloatArray, r: FloatArray, n: Int) {
        for (i in 0 until n) {
            bl = (bl + .02f * white()) / 1.02f
            br = (br + .02f * white()) / 1.02f
            val s = .6f + .4f * swell.next()
            val s2 = .5f + .5f * swell2.next()
            val w = white()
            var a = bl * 3.2f + rush.next(w, 700f + 500f * s2, 1.2f) * .22f * s
            var b = br * 3.2f + rush2.next(white(), 900f + 400f * s, 1.2f) * .22f * s2
            if (rnd.nextFloat() < 14f / NaturePlayer.RATE && bubbles.size < 10) {
                val len = (NaturePlayer.RATE * (.02f + rnd.nextFloat() * .05f)).toInt()
                bubbles.add(Bubble(350f + rnd.nextFloat() * 450f, 0f, .03f + rnd.nextFloat() * .05f, 0, len, rnd.nextFloat()))
            }
            val it = bubbles.iterator()
            while (it.hasNext()) {
                val bb = it.next()
                val t = bb.t.toFloat() / bb.len
                bb.phase += (2 * PI * bb.hz * (1 + t * 1.2f) / NaturePlayer.RATE).toFloat()
                val v = sin(bb.phase) * bb.amp * sin(PI.toFloat() * t)
                a += v * (1 - bb.pan)
                b += v * bb.pan
                if (++bb.t >= bb.len) it.remove()
            }
            l[i] = a
            r[i] = b
        }
    }
}

private class Wind : Gen() {
    private val f1 = Band()
    private val f2 = Band()
    private val gust = Drift(rnd, 5f)
    private val tone = Drift(rnd, 3.5f)
    private val tone2 = Drift(rnd, 4.5f)
    private var rumble = 0f

    override fun fill(l: FloatArray, r: FloatArray, n: Int) {
        for (i in 0 until n) {
            val g = .25f + .75f * gust.next()
            val c1 = 220f + 520f * tone.next() * g
            val c2 = 260f + 480f * tone2.next() * g
            rumble = (rumble + .02f * white()) / 1.02f
            val a = f1.next(white(), c1, 3f) * .35f * g + rumble * 1.4f
            val b = f2.next(white(), c2, 3f) * .35f * g + rumble * 1.4f
            l[i] = a
            r[i] = b
        }
    }
}

/**
 * A morning garden: soft leaves in the breeze, and three birds that call now and then — a koel's
 * rising "ku-oo", a bulbul's quick tune and a sunbird's high trill — with a little room around them.
 */
private class Birds : Gen() {
    private val pink = Pink(rnd)
    private val leaves = Low(1_200f)
    private val breeze = Drift(rnd, 6f)

    /** One sound in a call: a glide from [f0] to [f1]. */
    private class Note(val f0: Float, val f1: Float, val len: Int, val amp: Float, val gap: Int)

    private inner class Singer(val kind: Int, val pan: Float, val base: Float, val loud: Float) {
        val notes = ArrayDeque<Note>()
        var wait = (NaturePlayer.RATE * (1 + rnd.nextFloat() * 4)).toInt()
        var t = 0
        var phase = 0f
        var gap = 0

        fun phrase() {
            val sr = NaturePlayer.RATE
            when (kind) {
                0 -> { // koel: ku-oo, rising each time
                    val reps = 3 + rnd.nextInt(3)
                    for (k in 0 until reps) {
                        val f = base * (1 + k * .08f)
                        notes.add(Note(f * .9f, f, (sr * .12f).toInt(), loud, (sr * .03f).toInt()))
                        notes.add(Note(f, f * 1.18f, (sr * .30f).toInt(), loud, (sr * .55f).toInt()))
                    }
                }
                1 -> { // bulbul: a quick bubbly tune
                    val n = 4 + rnd.nextInt(4)
                    for (k in 0 until n) {
                        val f = base * (.8f + rnd.nextFloat() * .6f)
                        notes.add(Note(f, f * (.85f + rnd.nextFloat() * .4f), (sr * (.06f + rnd.nextFloat() * .09f)).toInt(), loud, (sr * (.02f + rnd.nextFloat() * .05f)).toInt()))
                    }
                }
                else -> { // sunbird: a fast high trill, falling
                    val n = 8 + rnd.nextInt(8)
                    for (k in 0 until n) {
                        val f = base * (1.1f - k * .025f)
                        notes.add(Note(f * 1.05f, f * .9f, (sr * .028f).toInt(), loud * (.7f + .3f * (k % 2)), (sr * .018f).toInt()))
                    }
                }
            }
        }

        fun next(): Float {
            if (gap > 0) {
                gap--
                return 0f
            }
            val note = notes.firstOrNull()
            if (note == null) {
                if (--wait <= 0) {
                    phrase()
                    wait = (NaturePlayer.RATE * (3 + rnd.nextFloat() * (if (kind == 0) 14 else 7))).toInt()
                }
                return 0f
            }
            val x = t.toFloat() / note.len
            val f = note.f0 + (note.f1 - note.f0) * x
            phase += (2 * PI * f / NaturePlayer.RATE).toFloat()
            if (phase > 2 * PI) phase -= (2 * PI).toFloat()
            val env = sin(PI.toFloat() * x).let { it * it }
            val v = (sin(phase) + .18f * sin(2 * phase)) * env * note.amp
            if (++t >= note.len) {
                t = 0
                notes.removeFirst()
                gap = note.gap
            }
            return v
        }
    }

    private val singers = listOf(
        Singer(0, .3f, 760f, .07f),
        Singer(1, .7f, 2_100f, .09f),
        Singer(2, .55f, 4_600f, .05f),
    )

    // A little room: two short echoes, softened.
    private val dl = FloatArray((NaturePlayer.RATE * .083f).toInt())
    private val dr = FloatArray((NaturePlayer.RATE * .121f).toInt())
    private var il = 0
    private var ir = 0
    private val el = Low(3_000f)
    private val er = Low(3_000f)

    override fun fill(l: FloatArray, r: FloatArray, n: Int) {
        for (i in 0 until n) {
            val bed = leaves.next(pink.next()) * (.25f + .35f * breeze.next())
            var a = bed
            var b = bed * .9f
            for (s in singers) {
                val v = s.next()
                a += v * (1 - s.pan)
                b += v * s.pan
            }
            val ea = el.next(dl[il])
            val eb = er.next(dr[ir])
            dl[il] = a + eb * .35f
            dr[ir] = b + ea * .35f
            il = (il + 1) % dl.size
            ir = (ir + 1) % dr.size
            l[i] = a + ea * .45f
            r[i] = b + eb * .45f
        }
    }
}

