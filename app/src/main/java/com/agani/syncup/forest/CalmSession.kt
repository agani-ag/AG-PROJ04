package com.agani.syncup.forest

import android.Manifest
import android.app.AlarmManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.agani.syncup.R
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

enum class CalmMode(val title: String, val hint: String) {
    QUIET("Quiet time", "phone down"),
    BREATHE("Breathe", "guided"),
    SOUNDS("Sounds", "rain, river…"),
}

/** Seconds of breathe in, hold, breathe out, hold. */
enum class BreathPattern(val title: String, val secs: IntArray) {
    CALM("Calm · 4 in, 6 out", intArrayOf(4, 0, 6, 0)),
    BOX("Box · 4-4-4-4", intArrayOf(4, 4, 4, 4)),
    SLEEP("Sleep · 4-7-8", intArrayOf(4, 7, 8, 0)),
    ;

    val cycleMs: Long get() = secs.sum() * 1000L
}

/** How the last calm time went, for the "It grew" screen (kept until seen). */
data class CalmResult(val treeId: String?, val minutes: Int, val before: Stage?, val after: Stage?, val completed: Boolean, val atMs: Long, val found: List<Species> = emptyList())

/**
 * Calm time: a timer by the clock, so it survives SyncUp closing and the screen sleeping. Opening
 * another app (screen on) pauses it — never ends it; the screen turning off never counts as leaving.
 * The minutes go to today and to the tree being cared for when it ends (or on End here).
 *
 * The end is noticed by whatever comes first: the open Forest screen, the sound service's timer, or
 * an alarm (which may come a little late on a sleeping phone — the minutes are right either way).
 */
object CalmSession {
    private const val PREFS = "forest_session"

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val player = NaturePlayer()

    var active by mutableStateOf(false)
        private set
    var paused by mutableStateOf(false)
        private set
    var pausedAtMs by mutableLongStateOf(0L)
        private set
    var mode by mutableStateOf(CalmMode.QUIET)
        private set
    var pattern by mutableStateOf(BreathPattern.CALM)
        private set
    var treeId by mutableStateOf<String?>(null)
        private set
    var totalMs by mutableLongStateOf(0L)
        private set
    private var keptMs by mutableLongStateOf(0L)
    private var runStartMs by mutableLongStateOf(0L)
    var sound by mutableStateOf<NatureSound?>(null)
        private set
    var result by mutableStateOf<CalmResult?>(null)
        private set

    /** SyncUp is on screen (MainActivity's onStart / onStop). */
    @Volatile var appVisible = false
        private set

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        SoundBank.init(app)
        if (!ForestStore.ready) ForestStore.init(app)
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        active = p.getBoolean("active", false)
        paused = p.getBoolean("paused", false)
        pausedAtMs = p.getLong("paused_at", 0)
        mode = runCatching { CalmMode.valueOf(p.getString("mode", "QUIET")!!) }.getOrDefault(CalmMode.QUIET)
        pattern = runCatching { BreathPattern.valueOf(p.getString("pattern", "CALM")!!) }.getOrDefault(BreathPattern.CALM)
        treeId = p.getString("tree", null)
        totalMs = p.getLong("total", 0)
        keptMs = p.getLong("kept", 0)
        runStartMs = p.getLong("run_start", 0)
        sound = p.getString("sound", null)?.let { s -> runCatching { NatureSound.valueOf(s) }.getOrNull() }
        result = p.getString("result", null)?.let { s ->
            runCatching {
                val o = JSONObject(s)
                CalmResult(
                    o.optString("tree").ifBlank { null }, o.optInt("min"),
                    o.optString("before").ifBlank { null }?.let { Stage.valueOf(it) },
                    o.optString("after").ifBlank { null }?.let { Stage.valueOf(it) },
                    o.optBoolean("completed"), o.optLong("at"),
                    o.optString("found").split(',').mapNotNull { n -> runCatching { Species.valueOf(n) }.getOrNull() },
                )
            }.getOrNull()
        }
        // SyncUp was closed while it ran: the sound stopped with it, so carry on quietly.
        if (active && !paused) {
            if (!checkEnd(app)) {
                sound = null
                save()
                showCard()
                setEndAlarm()
            }
        }
    }

    fun elapsedMs(now: Long = System.currentTimeMillis()): Long =
        (keptMs + if (active && !paused) now - runStartMs else 0L).coerceIn(0L, totalMs)

    fun leftMs(now: Long = System.currentTimeMillis()): Long = totalMs - elapsedMs(now)

    private fun endAt(): Long = runStartMs + totalMs - keptMs

    // ------------------------------------------------------------------ changes
    fun start(context: Context, tree: String?, mode: CalmMode, minutes: Int, sound: NatureSound?, pattern: BreathPattern) {
        init(context)
        val now = System.currentTimeMillis()
        active = true
        paused = false
        this.mode = mode
        this.pattern = pattern
        treeId = tree
        totalMs = minutes * 60_000L
        keptMs = 0
        runStartMs = now
        this.sound = sound
        result = null
        save()
        startSound()
        showCard()
        setEndAlarm()
    }

    /** SyncUp left with the screen on (or Back from calm time): wait, nothing lost. */
    fun pause(context: Context, at: Long = System.currentTimeMillis()) {
        init(context)
        if (!active || paused) return
        if (checkEnd(context)) return
        keptMs = (keptMs + at - runStartMs).coerceIn(0, totalMs)
        paused = true
        pausedAtMs = at
        save()
        stopSound()
        cancelEndAlarm()
        showCard()
    }

    fun resume(context: Context) {
        init(context)
        if (!active || !paused) return
        paused = false
        runStartMs = System.currentTimeMillis()
        save()
        startSound()
        showCard()
        setEndAlarm()
    }

    /** End here / Stop early: the whole minutes given so far count. */
    fun stop(context: Context) {
        init(context)
        if (active) finish(completed = false)
    }

    /** Ends calm time when its time is up. True when it ended now. */
    fun checkEnd(context: Context): Boolean {
        init(context)
        if (!active || paused || leftMs() > 0) return false
        finish(completed = true)
        return true
    }

    fun changeSound(context: Context, s: NatureSound?) {
        init(context)
        sound = s
        if (s != null) ForestStore.changeSound(s.name)
        save()
        if (active && !paused) startSound()
    }

    fun changePattern(p: BreathPattern) {
        pattern = p
        if (::app.isInitialized) save()
    }

    fun clearResult() {
        result = null
        if (::app.isInitialized) save()
    }

    fun onAppStarted(context: Context) {
        appVisible = true
        init(context)
        if (active) checkEnd(context)
    }

    /** Leaving with the screen still on is "opening another app"; the screen going off is not. */
    fun onAppStopped(context: Context) {
        appVisible = false
        init(context)
        if (!active || paused) return
        val at = System.currentTimeMillis()
        // The screen-off case can reach onStop a moment before the screen reports it.
        main.postDelayed({
            val pm = app.getSystemService(PowerManager::class.java)
            if (!appVisible && active && !paused && pm?.isInteractive == true) pause(app, at)
        }, 800)
    }

    private fun finish(completed: Boolean) {
        val now = System.currentTimeMillis()
        val ms = if (completed) totalMs else elapsedMs(now)
        val minutes = (ms / 60_000).toInt()
        val tree = treeId
        val knew = Species.entries.filter { ForestStore.found(it) }
        val (before, after) = ForestStore.addCalm(tree, minutes)
        val found = Species.entries.filter { ForestStore.found(it) } - knew.toSet()
        active = false
        paused = false
        result = if (minutes > 0) CalmResult(tree, minutes, before, after, completed, now, found) else null
        stopSound()
        cancelEndAlarm()
        NotificationManagerCompat.from(app).cancel(ForestReminder.SESSION_ID)
        save()
        ForestStore.flush()
        if (completed) {
            NaturePlayer.chime()
            Buzz.end(app)
        }
        if (!appVisible && minutes > 0) {
            val t = tree?.let { ForestStore.tree(it) }
            val title = if (before != null && after != null && after != before) "Your ${t?.label?.lowercase() ?: "tree"} grew" else "Calm time done"
            val text = buildString {
                append("+$minutes calm minute${if (minutes == 1) "" else "s"}")
                if (t != null && after != null) {
                    if (before != after) append(" · ${t.label} is a ${(if (after == Stage.YOUNG) "young tree" else after.title.lowercase())} now")
                    else append(" · ${t.stageName()}: ${t.nextText().replaceFirstChar { it.lowercase() }}")
                }
            }
            ForestReminder.notify(app, ForestReminder.DONE_ID, title, text)
        }
    }

    // ------------------------------------------------------------------ sound, card, alarm
    private fun startSound() {
        val s = sound
        if (s == null) {
            stopSound()
            return
        }
        player.play(s)
        // A media service keeps the sound going with SyncUp in the background or the screen off.
        runCatching { ContextCompat.startForegroundService(app, Intent(app, CalmSoundService::class.java)) }
    }

    private fun stopSound() {
        player.stop()
        runCatching { app.stopService(Intent(app, CalmSoundService::class.java)) }
    }

    internal fun card(ctx: Context): android.app.Notification {
        ForestReminder.ensureChannels(ctx)
        val tree = treeId?.let { ForestStore.tree(it) }
        val what = tree?.let { "${it.label} ${it.stage().title.lowercase()}" } ?: "Forest"
        val b = NotificationCompat.Builder(ctx, ForestReminder.CHANNEL_SESSION)
            .setSmallIcon(R.drawable.ic_sync)
            .setColor(0xFF2F5D3A.toInt())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(ForestReminder.openForest(ctx, ForestReminder.SESSION_ID))
        if (paused) {
            b.setContentTitle("Calm time paused · $what")
                .setContentText("${clock(leftMs())} left — tap to go on")
                .setShowWhen(false)
        } else {
            b.setContentTitle("Calm time · $what")
                .setContentText("${mode.title}${sound?.let { " · ${it.title}" } ?: ""} · ends ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(endAt()))}")
                .setWhen(endAt())
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
        }
        return b.build()
    }

    private fun showCard() {
        if (!active) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        runCatching { NotificationManagerCompat.from(app).notify(ForestReminder.SESSION_ID, card(app)) }
    }

    /**
     * Two alarms for the end, no special permission: one asked to come within a minute (Android
     * keeps that for an awake phone), one allowed while the phone sleeps deeply (it may be later).
     * Whichever comes first ends calm time; the minutes are right either way.
     */
    private fun setEndAlarm() {
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        am.setWindow(AlarmManager.RTC_WAKEUP, endAt(), 60_000, ForestReminder.endPending(app))
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt(), ForestReminder.endPending(app, idle = true))
    }

    private fun cancelEndAlarm() {
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(ForestReminder.endPending(app))
        am.cancel(ForestReminder.endPending(app, idle = true))
    }

    private fun save() {
        val r = result
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("active", active).putBoolean("paused", paused).putLong("paused_at", pausedAtMs)
            .putString("mode", mode.name).putString("pattern", pattern.name).putString("tree", treeId)
            .putLong("total", totalMs).putLong("kept", keptMs).putLong("run_start", runStartMs)
            .putString("sound", sound?.name)
            .putString(
                "result",
                r?.let {
                    JSONObject().put("tree", it.treeId ?: "").put("min", it.minutes).put("before", it.before?.name ?: "")
                        .put("after", it.after?.name ?: "").put("completed", it.completed).put("at", it.atMs)
                        .put("found", it.found.joinToString(",") { s -> s.name }).toString()
                },
            )
            .apply()
    }

    /** Re-set the end alarm after a restart. */
    fun rescheduleAfterBoot(context: Context) {
        init(context)
        if (active && !paused && !checkEnd(context)) {
            showCard()
            setEndAlarm()
        }
    }
}

/** "14:12" / "1:02:30". */
internal fun clock(ms: Long): String {
    val s = ((ms + 999) / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = s % 3600 / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Gentle buzzes: a tick for each breath, a soft double at the end. */
internal object Buzz {
    private fun vibrator(ctx: Context): Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        ctx.getSystemService(Vibrator::class.java)
    }

    fun tick(ctx: Context) = buzz(ctx, longArrayOf(0, 35), intArrayOf(0, 90))

    fun end(ctx: Context) = buzz(ctx, longArrayOf(0, 70, 160, 70), intArrayOf(0, 120, 0, 90))

    private fun buzz(ctx: Context, timings: LongArray, amps: IntArray) {
        val v = vibrator(ctx) ?: return
        if (!v.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(timings, if (v.hasAmplitudeControl()) amps else IntArray(amps.size) { if (it % 2 == 1) 255 else 0 }, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(timings, -1)
            }
        }
    }
}

/**
 * Keeps a calm sound playing with SyncUp in the background or the screen off (a media service, only
 * while a sound plays). Its card is calm time's card; it also ends calm time right on time.
 */
class CalmSoundService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!CalmSession.active || CalmSession.paused || CalmSession.checkEnd(this@CalmSoundService)) {
                stopSelf()
                return
            }
            handler.postDelayed(this, CalmSession.leftMs().coerceIn(200, 30_000))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CalmSession.init(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        val ok = runCatching { ServiceCompat.startForeground(this, ForestReminder.SESSION_ID, CalmSession.card(this), type) }.isSuccess
        if (!ok || !CalmSession.active || CalmSession.sound == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        // Keep calm time's card (without the service) while calm time goes on.
        ServiceCompat.stopForeground(this, if (CalmSession.active) ServiceCompat.STOP_FOREGROUND_DETACH else ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
