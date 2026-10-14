package com.agani.syncup.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.min

/**
 * Wave to control: a hand over the top of the phone (the proximity sensor — the one that turns the
 * screen off during calls) pauses or skips what's playing. Only while SyncUp is on screen; off until
 * switched on in Tools → Sensors → Wave to control.
 */
object WaveControl {
    enum class Gesture(val label: String) { ONCE("Wave once"), TWICE("Wave twice"), HOLD("Hold your hand there (2 s)") }

    enum class Action(val label: String) {
        PLAY_PAUSE("Pause / play"), NEXT("Next song / +10 s"), PREVIOUS("Previous song / −10 s"), NOTHING("Nothing"),
    }

    private const val PREFS = "wave_control"
    private var loaded = false
    var enabled by mutableStateOf(false)
        private set
    private var actions by mutableStateOf(mapOf(Gesture.ONCE to Action.PLAY_PAUSE, Gesture.TWICE to Action.NEXT, Gesture.HOLD to Action.PREVIOUS))
    /** The Wave tool is open: it shows what each wave would do instead of doing it. */
    var trying by mutableStateOf(false)

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = p.getBoolean("on", false)
        actions = Gesture.entries.associateWith { g ->
            p.getString(g.name, null)?.let { runCatching { Action.valueOf(it) }.getOrNull() } ?: actions.getValue(g)
        }
    }

    fun setEnabled(context: Context, on: Boolean) {
        enabled = on
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()
    }

    fun actionFor(g: Gesture): Action = actions.getValue(g)

    fun setAction(context: Context, g: Gesture, a: Action) {
        actions = actions + (g to a)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(g.name, a.name).apply()
    }
}

/**
 * Turns proximity readings into waves: near then far again quickly = one wave; two within ~0.7 s =
 * twice; a hand held there 1.5–4 s = hold. Longer (a pocket, face down on a table) is ignored.
 */
class WaveDetector(private val maxRange: Float, private val onNear: (Boolean) -> Unit = {}, private val onGesture: (WaveControl.Gesture) -> Unit) : SensorEventListener {
    private val handler = Handler(Looper.getMainLooper())
    private var near = false
    private var nearAt = 0L
    private var waves = 0
    private var primed = false
    private val flush = Runnable {
        if (waves == 1) onGesture(WaveControl.Gesture.ONCE)
        waves = 0
    }

    override fun onSensorChanged(e: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        val isNear = e.values[0] < min(maxRange * .9f, 5f)
        // The first reading only says where things are (it may already be face down).
        if (!primed) {
            primed = true
            near = isNear
            nearAt = now
            onNear(isNear)
            return
        }
        if (isNear == near) return
        near = isNear
        onNear(isNear)
        if (isNear) {
            nearAt = now
            handler.removeCallbacks(flush)
            return
        }
        val held = now - nearAt
        when {
            held < 900 -> {
                waves++
                if (waves >= 2) {
                    waves = 0
                    onGesture(WaveControl.Gesture.TWICE)
                } else {
                    handler.postDelayed(flush, 700)
                }
            }
            held in 1_500..4_000 -> onGesture(WaveControl.Gesture.HOLD)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun stop() = handler.removeCallbacks(flush)
}

/**
 * Listens for waves while this screen's activity is in front and Wave to control is on, and hands the
 * chosen action to [onAction] (the music player, the video player).
 */
@Composable
fun WaveEffect(onAction: (WaveControl.Action) -> Unit) {
    val context = LocalContext.current
    remember { WaveControl.init(context) }
    val owner = LocalLifecycleOwner.current
    val latest = remember { arrayOf(onAction) }
    latest[0] = onAction
    val on = WaveControl.enabled
    DisposableEffect(owner, on) {
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        var detector: WaveDetector? = null
        fun start() {
            if (detector != null || sensor == null || !on) return
            detector = WaveDetector(sensor.maximumRange) { g ->
                if (!WaveControl.trying) latest[0](WaveControl.actionFor(g))
            }.also { sm.registerListener(it, sensor, SensorManager.SENSOR_DELAY_NORMAL) }
        }
        fun stop() {
            detector?.let {
                sm?.unregisterListener(it)
                it.stop()
            }
            detector = null
        }
        val observer = LifecycleEventObserver { _, _ ->
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start() else stop()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            stop()
        }
    }
}
