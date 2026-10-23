package com.agani.syncup.decide

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.music.ToolTint
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// Shared parts of the Decide tools (docs/mockup/syncup-everyday-tools.html): their colours, the
// small sounds and buzzes, shake to toss, confetti and the big pill buttons.

/** Each Decide tool's colour. */
internal object DecideTint {
    val DECIDE = ToolTint.AMBER
    val COIN = ToolTint.AMBER
    val WHEEL = ToolTint.VIOLET
    val NUMBER = ToolTint.SKY
    val YES_NO = ToolTint.GREEN
}

/** Where the Decide tools keep their small settings and counts (this phone only). */
internal const val DECIDE_PREFS = "decide"

/**
 * Short sounds made in code (no audio files) and gentle buzzes, behind one "Sound & buzz" switch
 * (on by default).
 */
internal object DecideFx {
    private const val PREF_SOUND = "sound_buzz"
    private const val RATE = 44_100

    private var pool: SoundPool? = null
    private val ids = HashMap<String, Int>()

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(DECIDE_PREFS, Context.MODE_PRIVATE).getBoolean(PREF_SOUND, true)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(DECIDE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_SOUND, on).apply()
    }

    /** One wheel tick (a slice passing the pointer). */
    fun tick(context: Context) = play(context, "tick", .55f)

    /** The coin flipping up. */
    fun flip(context: Context) = play(context, "flip", .7f)

    /** A result: the coin lands, the wheel stops, a number or answer appears. */
    fun land(context: Context) = play(context, "land", .8f)

    /** A soft pop (a knock-out in Last choice). */
    fun pop(context: Context) = play(context, "pop", .7f)

    /** A light buzz: [ms] long, [strength] 1–255. */
    fun buzz(context: Context, ms: Long = 18, strength: Int = 120) {
        if (!enabled(context)) return
        val v = vibrator(context) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, if (v.hasAmplitudeControl()) strength else VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") v.vibrate(ms)
        }
    }

    /** Builds the sounds ahead of time (call when a tool opens), so the first one isn't late. */
    fun warmUp(context: Context) {
        ensurePool(context.applicationContext)
    }

    private fun play(context: Context, name: String, volume: Float) {
        if (!enabled(context)) return
        val p = ensurePool(context.applicationContext) ?: return
        val id = ids[name] ?: return
        p.play(id, volume, volume, 1, 0, 1f)
    }

    @Synchronized
    private fun ensurePool(app: Context): SoundPool? {
        pool?.let { return it }
        val p = runCatching {
            SoundPool.Builder().setMaxStreams(4)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .build()
        }.getOrNull() ?: return null
        val dir = File(app.cacheDir, "decide_sounds").apply { mkdirs() }
        for ((name, samples) in listOf("tick" to tick(), "flip" to flip(), "land" to land(), "pop" to pop())) {
            val f = File(dir, "$name.wav")
            if (!f.exists()) runCatching { writeWav(f, samples) }
            runCatching { ids[name] = p.load(f.path, 1) }
        }
        pool = p
        return p
    }

    // ---- the sounds, as 16-bit mono samples
    private fun tick(): ShortArray = synth(0.03) { t -> (sin(2 * PI * 2400 * t) * .6 + sin(2 * PI * 5200 * t) * .25) * exp(-t * 160) }

    private fun flip(): ShortArray = synth(0.28) { t ->
        val f = 1800 + 2600 * t // a rising shimmer as the coin goes up
        (sin(2 * PI * f * t) * .25 + sin(2 * PI * f * 1.51 * t) * .12) * exp(-t * 9) * (1 - exp(-t * 200))
    }

    private fun land(): ShortArray = synth(0.6) { t ->
        (sin(2 * PI * 1318 * t) * .45 + sin(2 * PI * 1976 * t) * .25 + sin(2 * PI * 2637 * t) * .12) * exp(-t * 6.5) * (1 - exp(-t * 400))
    }

    private fun pop(): ShortArray = synth(0.12) { t -> sin(2 * PI * (520 + 900 * exp(-t * 40)) * t) * .55 * exp(-t * 30) }

    private fun synth(seconds: Double, wave: (Double) -> Double): ShortArray {
        val n = (RATE * seconds).toInt()
        return ShortArray(n) { i -> (wave(i.toDouble() / RATE).coerceIn(-1.0, 1.0) * 30_000).toInt().toShort() }
    }

    private fun writeWav(f: File, s: ShortArray) {
        val data = s.size * 2
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(data)
        for (x in s) b.putShort(x)
        FileOutputStream(f).use { it.write(b.array()) }
    }

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        else @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
}

/** Shake the phone to [onShake] (toss the coin, spin the wheel) while [enabled] and on screen. */
@Composable
internal fun ShakeToDo(enabled: Boolean, onShake: () -> Unit) {
    val context = LocalContext.current
    val action by rememberUpdatedState(onShake)
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var last = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / SensorManager.GRAVITY_EARTH
                val now = SystemClock.elapsedRealtime()
                if (g > 2.4f && now - last > 1_200) {
                    last = now
                    action()
                }
            }

            override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm?.unregisterListener(listener) }
    }
}

private class Bit(val x: Float, val vx: Float, val vy: Float, val spin: Float, val color: Color, val w: Float, val h: Float)

private val CONFETTI = listOf(Color(0xFFF59E0B), Color(0xFFEF4444), Color(0xFF8B5CF6), Color(0xFF10B981), Color(0xFF3B82F6), Color(0xFFEC4899))

/** A burst of confetti over the whole area each time [key] changes to something new (null = none). */
@Composable
internal fun Confetti(key: Any?, modifier: Modifier = Modifier) {
    if (key == null) return
    val t = remember(key) { Animatable(0f) }
    val bits = remember(key) {
        List(70) {
            Bit(
                x = Random.nextFloat(), vx = (Random.nextFloat() - .5f) * .5f, vy = -(.6f + Random.nextFloat() * .7f),
                spin = (Random.nextFloat() - .5f) * 900f, color = CONFETTI[it % CONFETTI.size],
                w = 6f + Random.nextFloat() * 4f, h = 9f + Random.nextFloat() * 6f,
            )
        }
    }
    LaunchedEffect(key) { t.animateTo(1f, tween(1_800, easing = LinearEasing)) }
    Canvas(modifier.fillMaxSize()) {
        val p = t.value
        if (p >= 1f) return@Canvas
        val alpha = if (p > .75f) (1f - p) / .25f else 1f
        for (b in bits) {
            // Thrown up from the middle, then falling.
            val x = size.width * (b.x + b.vx * p)
            val y = size.height * (.45f + b.vy * p + 1.6f * p * p)
            rotate(b.spin * p, Offset(x, y)) {
                drawRect(b.color.copy(alpha = alpha), Offset(x - b.w, y - b.h / 2), Size(b.w * 2, b.h), alpha = alpha)
            }
        }
    }
}

/** The big pill button of a Decide tool: filled in its colour, or [soft] (its soft tile colour). */
@Composable
internal fun DecideButton(
    text: String,
    tint: ToolTint,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    soft: Boolean = false,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = if (soft) ButtonDefaults.buttonColors(containerColor = tint.tile, contentColor = tint.icon)
    else ButtonDefaults.buttonColors(containerColor = tint.icon, contentColor = tint.onSolid)
    Button(
        onClick = onClick, enabled = enabled, colors = colors, shape = RoundedCornerShape(50),
        modifier = modifier.fillMaxWidth().height(50.dp),
    ) {
        Row {
            if (icon != null) {
                Icon(icon, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, fontSize = 15.5.sp, fontWeight = FontWeight.Medium)
        }
    }
}
