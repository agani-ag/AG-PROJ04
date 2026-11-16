package com.agani.syncup.usage

import android.content.Context
import android.os.SystemClock
import com.agani.syncup.data.ApiClient
import com.agani.syncup.data.DeviceBeatRequest
import com.agani.syncup.data.TokenStore
import com.agani.syncup.push.DeviceRegistrar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Usage time (user, 16 Nov 2026, "very simple", every install): how long SyncUp is open on this phone, counted
 * here as one number of seconds — from the app coming on screen to it leaving — signed in or not.
 *
 * While the app is open it sends a heartbeat every 30 s (device/beat) with that total, and one more as it
 * closes, so the admin sees each phone's usage go up and who has SyncUp open right now. The check-in
 * (DeviceRegistrar.hello) carries the total too. Nothing about what's done in the app. Settings → Privacy &
 * security → "Share usage time" (on to start) turns all of it off; the live timer there shows the count.
 */
object UsageTime {
    private const val PREFS = "usage_time"
    private const val KEY_ON = "on"
    private const val KEY_SECONDS = "seconds"
    private const val BEAT_MS = 30_000L

    /** When the app came on screen (elapsed realtime), or 0 while it's not on screen. */
    @Volatile private var openedAt = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var beating: Job? = null

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context) = prefs(context).getBoolean(KEY_ON, true)

    fun setEnabled(context: Context, on: Boolean) {
        val app = context.applicationContext
        if (!on) add(app) // what was counted so far stays
        prefs(app).edit().putBoolean(KEY_ON, on).apply()
        if (openedAt != 0L) openedAt = SystemClock.elapsedRealtime() // count from now
        if (on && openedAt != 0L) {
            startBeats(app)
        } else if (!on) {
            stopBeats()
            beat(app, open = false, withUsage = false) // off: the server no longer shows this phone as open
        }
    }

    /** The app came on screen. */
    fun onForeground(context: Context) {
        openedAt = SystemClock.elapsedRealtime()
        startBeats(context.applicationContext)
    }

    /** The app left the screen: add the time it was open, and tell the server it's closed. */
    fun onBackground(context: Context) {
        val app = context.applicationContext
        add(app)
        openedAt = 0L
        stopBeats()
        if (enabled(app)) beat(app, open = false)
    }

    /** For the check-in: the seconds so far (with the time open right now), or null when it's switched off. */
    fun forCheckIn(context: Context): Long? = if (enabled(context)) total(context) else null

    /** The seconds counted so far, with the time open right now — the live timer in Privacy & security. */
    fun total(context: Context): Long = prefs(context).getLong(KEY_SECONDS, 0L) + if (enabled(context)) running() else 0L

    private fun running(): Long = openedAt.takeIf { it != 0L }?.let { (SystemClock.elapsedRealtime() - it) / 1000 } ?: 0L

    /** Moves the time open so far into the saved count (and carries on counting from now). */
    private fun add(context: Context) {
        val seconds = running()
        // A day is the most one stretch can count (a phone left on with the app open, say).
        if (seconds > 0 && seconds <= 86_400 && enabled(context)) {
            val p = prefs(context)
            p.edit().putLong(KEY_SECONDS, p.getLong(KEY_SECONDS, 0L) + seconds).apply()
        }
        if (openedAt != 0L) openedAt = SystemClock.elapsedRealtime()
    }

    private fun startBeats(app: Context) {
        stopBeats()
        if (!enabled(app)) return
        beating = scope.launch {
            while (isActive) {
                // Each beat on its own, so a slow answer doesn't stretch the 30 s rhythm.
                launch { send(app, open = true, withUsage = true) }
                delay(BEAT_MS)
            }
        }
    }

    private fun stopBeats() {
        beating?.cancel()
        beating = null
    }

    private fun beat(app: Context, open: Boolean, withUsage: Boolean = true) {
        scope.launch { send(app, open, withUsage) }
    }

    /** One heartbeat. Skipped until the first check-in has given this phone its secret. */
    private suspend fun send(app: Context, open: Boolean, withUsage: Boolean) {
        val secret = DeviceRegistrar.deviceSecret(app) ?: return
        val usage = if (withUsage) total(app) else null
        runCatching { ApiClient.service.deviceBeat(DeviceBeatRequest(TokenStore(app).deviceId(), secret, usage, open)) }
    }
}
