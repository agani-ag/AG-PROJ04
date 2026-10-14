package com.agani.syncup.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Readings kept on this phone for the two tools that need a history: Weather trend (air pressure over
 * the last hours) and Steps (the step counter's total, to split it into days). A small job reads both
 * every 30 minutes — a second of sensor time — and only for the tools that are switched on / allowed.
 */
object SensorLog {
    private const val PREFS = "sensor_log"
    private const val WORK = "sensor_log"
    private const val KEEP_PRESSURE_MS = 48L * 3600_000
    private const val KEEP_STEPS_DAYS = 9L

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ pressure
    /** Weather trend's "read every 30 min in the background" (on unless switched off). */
    fun weatherInBackground(context: Context): Boolean = prefs(context).getBoolean("weather_bg", true)

    fun setWeatherInBackground(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("weather_bg", on).apply()
        schedule(context)
    }

    /** (time, hPa) pairs, oldest first. */
    fun pressure(context: Context): List<Pair<Long, Float>> = read(context, "pressure").map { it.first to it.second.toFloat() }

    fun addPressure(context: Context, hPa: Float, at: Long = System.currentTimeMillis()) {
        val list = pressure(context).filter { at - it.first < KEEP_PRESSURE_MS }.toMutableList()
        // At most one reading every 10 minutes (the screen adds one each time it opens).
        if (list.lastOrNull()?.let { at - it.first < 10 * 60_000 } == true) list.removeAt(list.lastIndex)
        list.add(at to hPa)
        write(context, "pressure", list.map { it.first to it.second.toDouble() })
    }

    // ------------------------------------------------------------------ steps
    /** The Steps tool was opened and allowed, so the job keeps the step counter's readings. */
    fun stepsOn(context: Context): Boolean = prefs(context).getBoolean("steps_on", false)

    fun setStepsOn(context: Context) {
        if (stepsOn(context)) return
        prefs(context).edit().putBoolean("steps_on", true).apply()
        schedule(context)
    }

    fun addSteps(context: Context, counter: Float, at: Long = System.currentTimeMillis()) {
        val cutoff = at - KEEP_STEPS_DAYS * 24 * 3600_000
        val list = read(context, "steps").filter { it.first >= cutoff }.toMutableList()
        if (list.lastOrNull()?.let { at - it.first < 5 * 60_000 } == true) list.removeAt(list.lastIndex)
        list.add(at to counter.toDouble())
        write(context, "steps", list)
    }

    /** When the oldest kept step reading was taken (null: none yet). */
    fun firstStepsAt(context: Context): Long? = read(context, "steps").firstOrNull()?.first

    fun todayStart(): Long = midnight(System.currentTimeMillis())

    /**
     * Steps per day for the last [days] days (oldest first, each day as its midnight in ms), from the
     * counter's readings. The counter starts again from 0 when the phone restarts; steps between two
     * readings that span midnight are split by time. [now] adds the live counter value as the newest reading.
     */
    fun stepsByDay(context: Context, days: Int, now: Float?): List<Pair<Long, Int>> {
        val readings = read(context, "steps").toMutableList()
        if (now != null) readings.add(System.currentTimeMillis() to now.toDouble())
        val totals = HashMap<Long, Double>()
        for (i in 1 until readings.size) {
            val (t0, c0) = readings[i - 1]
            val (t1, c1) = readings[i]
            val inc = if (c1 >= c0) c1 - c0 else c1 // restarted
            if (inc <= 0 || t1 <= t0) continue
            var from = t0
            while (from < t1) {
                val day = midnight(from)
                val to = minOf(nextMidnight(day), t1)
                totals[day] = (totals[day] ?: 0.0) + inc * (to - from).toDouble() / (t1 - t0)
                from = to
            }
        }
        val today = midnight(System.currentTimeMillis())
        return (days - 1 downTo 0).map { back ->
            val day = Calendar.getInstance().apply { timeInMillis = today; add(Calendar.DAY_OF_YEAR, -back) }.timeInMillis
            day to (totals[day] ?: 0.0).toInt()
        }
    }

    private fun midnight(at: Long): Long = Calendar.getInstance().apply {
        timeInMillis = at
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun nextMidnight(day: Long): Long = Calendar.getInstance().apply { timeInMillis = day; add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis

    // ------------------------------------------------------------------ the job
    /** Start (or keep) the 30-minute job if anything needs it; cancel it if nothing does. */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context.applicationContext)
        if (needed(context)) {
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<SensorLogWorker>(30, TimeUnit.MINUTES).build())
        } else {
            wm.cancelUniqueWork(WORK)
        }
    }

    internal fun needed(context: Context): Boolean =
        (weatherInBackground(context) && Part.PRESSURE.present(context) && prefs(context).getBoolean("weather_used", false)) ||
            (stepsOn(context) && Part.STEP_COUNTER.present(context) && stepsAllowed(context))

    /** Weather trend was opened once: from then on the job keeps its readings (while switched on). */
    fun weatherUsed(context: Context) {
        if (prefs(context).getBoolean("weather_used", false)) return
        prefs(context).edit().putBoolean("weather_used", true).apply()
        schedule(context)
    }

    fun stepsAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    /** One reading of [type], or null within 5 s (a sensor that never answers). */
    internal suspend fun readOnce(context: Context, type: Int): Float? {
        val sm = context.getSystemService(SensorManager::class.java) ?: return null
        val sensor = sm.getDefaultSensor(type) ?: return null
        return withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) {
                        sm.unregisterListener(this)
                        if (cont.isActive) cont.resume(e.values[0])
                    }

                    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
                }
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                cont.invokeOnCancellation { sm.unregisterListener(listener) }
            }
        }
    }

    // ------------------------------------------------------------------ storage
    private fun read(context: Context, key: String): List<Pair<Long, Double>> = runCatching {
        val a = JSONArray(prefs(context).getString(key, "[]"))
        (0 until a.length()).map { i -> a.getJSONArray(i).let { it.getLong(0) to it.getDouble(1) } }
    }.getOrDefault(emptyList())

    private fun write(context: Context, key: String, list: List<Pair<Long, Double>>) {
        val a = JSONArray()
        list.forEach { a.put(JSONArray().put(it.first).put(it.second)) }
        prefs(context).edit().putString(key, a.toString()).apply()
    }
}

/** Every 30 minutes: one air-pressure reading and / or one step-counter reading, then done. */
class SensorLogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!SensorLog.needed(context)) {
            SensorLog.schedule(context)
            return Result.success()
        }
        if (SensorLog.weatherInBackground(context)) {
            SensorLog.readOnce(context, Sensor.TYPE_PRESSURE)?.let { SensorLog.addPressure(context, it) }
        }
        if (SensorLog.stepsOn(context) && SensorLog.stepsAllowed(context)) {
            SensorLog.readOnce(context, Sensor.TYPE_STEP_COUNTER)?.let { SensorLog.addSteps(context, it) }
        }
        return Result.success()
    }
}
