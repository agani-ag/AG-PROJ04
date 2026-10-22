package com.agani.syncup.wellbeing

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.agani.syncup.MainActivity
import com.agani.syncup.R
import java.util.Calendar

/**
 * Tools → Wellbeing's shared parts: starting every tool, their notifications (each tool its own
 * channel, so any one can be muted in the phone's settings), their alarms (setAndAllowWhileIdle —
 * no special permission; a sleeping phone may get them a few minutes late), and the quiet rule: no
 * nudges at night (after bedtime, set in Sleep).
 */
object Wellbeing {
    const val CH_WATER = "wellbeing_water"
    const val CH_STEPS = "wellbeing_steps"
    const val CH_BREAKS = "wellbeing_breaks"
    const val CH_SLEEP = "wellbeing_sleep"
    const val CH_SOUNDS = "wellbeing_sounds"
    const val CH_HABITS = "wellbeing_habits"
    const val CH_MOOD = "wellbeing_mood"

    // Alarms (request codes) and notifications (ids) — one each per tool.
    internal const val REQ_WATER = 0x6E0001
    internal const val REQ_STEPS = 0x6E0002
    internal const val REQ_BREAK = 0x6E0003
    internal const val REQ_HABIT = 0x6E0004
    internal const val REQ_MOOD = 0x6E0005
    internal const val REQ_BEDTIME = 0x6E0006
    internal const val REQ_MORNING = 0x6E0007
    internal const val ID_WATER = 0x6E1001
    internal const val ID_STEPS = 0x6E1002
    internal const val ID_BREAK = 0x6E1003
    internal const val ID_HABIT = 0x6E1004
    internal const val ID_MOOD = 0x6E1005
    internal const val ID_SLEEP = 0x6E1006
    internal const val ID_SOUNDS = 0x6E1007

    private var started = false

    /** Every tool's data; their alarms are re-set. Safe to call again. */
    fun init(context: Context) {
        val ctx = context.applicationContext
        Water.init(ctx)
        Steps.init(ctx)
        Habits.init(ctx)
        Sleep.init(ctx)
        Mood.init(ctx)
        Breaks.init(ctx)
        if (started) return
        started = true
        rescheduleAll(ctx)
    }

    fun rescheduleAll(context: Context) {
        val ctx = context.applicationContext
        Water.schedule(ctx)
        Steps.schedule(ctx)
        Habits.schedule(ctx)
        Sleep.schedule(ctx)
        Mood.schedule(ctx)
        Breaks.schedule(ctx)
    }

    /** It's night: bedtime set in Sleep, or a night being logged. */
    @Suppress("UNUSED_PARAMETER")
    fun quietNow(context: Context, now: Long = System.currentTimeMillis()): Boolean = Sleep.isNight(now)

    fun screenOn(context: Context): Boolean = context.getSystemService(PowerManager::class.java)?.isInteractive == true

    // ------------------------------------------------------------------ notifications
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        fun ch(id: String, name: String, about: String, importance: Int = NotificationManager.IMPORTANCE_DEFAULT) {
            if (nm.getNotificationChannel(id) == null) {
                nm.createNotificationChannel(NotificationChannel(id, name, importance).apply { description = about })
            }
        }
        ch(CH_WATER, "Water", "Reminders to drink, from Tools → Water")
        ch(CH_STEPS, "Steps", "Your step goal and the evening nudge")
        ch(CH_BREAKS, "Breaks", "Eye and move breaks while you use the phone")
        ch(CH_SLEEP, "Sleep", "Bedtime and the morning question")
        ch(CH_SOUNDS, "Sleep sounds", "Shown while sleep sounds play", NotificationManager.IMPORTANCE_LOW)
        ch(CH_HABITS, "Habits", "Your habit reminders")
        ch(CH_MOOD, "Mood", "The mood check-in")
    }

    internal fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    internal fun notify(
        ctx: Context,
        channel: String,
        id: Int,
        title: String,
        text: String?,
        open: PendingIntent,
        color: Long,
        actions: List<Pair<String, PendingIntent>> = emptyList(),
        silent: Boolean = false,
    ) {
        ensureChannels(ctx)
        if (!canNotify(ctx)) return
        val b = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_sync)
            .setContentTitle(title)
            .setColor(color.toInt())
            .setAutoCancel(true)
            .setSilent(silent)
            .setContentIntent(open)
        if (!text.isNullOrBlank()) b.setContentText(text)
        actions.forEach { (label, pi) -> b.addAction(0, label, pi) }
        runCatching { NotificationManagerCompat.from(ctx).notify(id, b.build()) }
    }

    internal fun cancelNotification(ctx: Context, id: Int) = runCatching { NotificationManagerCompat.from(ctx).cancel(id) }

    /** Opens SyncUp at a tool ([tool] = a ToolsPage name), with an optional [arg] for it. */
    internal fun open(ctx: Context, tool: String, request: Int, arg: String? = null): PendingIntent = PendingIntent.getActivity(
        ctx, request,
        Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_TOOL, tool).putExtra(MainActivity.EXTRA_TOOL_ARG, arg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** A notification button / alarm handled by [WellbeingReceiver]. */
    internal fun action(ctx: Context, action: String, request: Int, arg: String? = null, number: Int = 0): PendingIntent = PendingIntent.getBroadcast(
        ctx, request,
        Intent(ctx, WellbeingReceiver::class.java).setAction(action).putExtra(EXTRA_ARG, arg).putExtra(EXTRA_NUMBER, number),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // ------------------------------------------------------------------ alarms
    internal fun alarm(ctx: Context, action: String, request: Int, at: Long, arg: String? = null) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, action(ctx, action, request, arg))
    }

    internal fun cancelAlarm(ctx: Context, action: String, request: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(action(ctx, action, request))
    }

    /** The next time today/tomorrow at [minuteOfDay] that's later than [after]. */
    internal fun nextAt(minuteOfDay: Int, after: Long = System.currentTimeMillis()): Long = Calendar.getInstance().apply {
        timeInMillis = after
        set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        set(Calendar.MINUTE, minuteOfDay % 60)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= after + 30_000) add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis

    /** The day of [ms] at [minuteOfDay]. */
    internal fun atMinute(ms: Long, minuteOfDay: Int): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        set(Calendar.MINUTE, minuteOfDay % 60)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Minutes since midnight now. */
    internal fun minuteNow(at: Long = System.currentTimeMillis()): Int = Calendar.getInstance().apply { timeInMillis = at }.let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    internal const val EXTRA_ARG = "arg"
    internal const val EXTRA_NUMBER = "number"

    // Actions
    internal const val A_WATER_REMIND = "com.agani.syncup.wellbeing.WATER_REMIND"
    internal const val A_WATER_ADD = "com.agani.syncup.wellbeing.WATER_ADD"
    internal const val A_WATER_LATER = "com.agani.syncup.wellbeing.WATER_LATER"
    internal const val A_STEPS_NUDGE = "com.agani.syncup.wellbeing.STEPS_NUDGE"
    internal const val A_BREAK = "com.agani.syncup.wellbeing.BREAK"
    internal const val A_BREAK_SKIP = "com.agani.syncup.wellbeing.BREAK_SKIP"
    internal const val A_BREAK_PAUSE = "com.agani.syncup.wellbeing.BREAK_PAUSE"
    internal const val A_HABIT_REMIND = "com.agani.syncup.wellbeing.HABIT_REMIND"
    internal const val A_HABIT_DONE = "com.agani.syncup.wellbeing.HABIT_DONE"
    internal const val A_HABIT_LATER = "com.agani.syncup.wellbeing.HABIT_LATER"
    internal const val A_MOOD_REMIND = "com.agani.syncup.wellbeing.MOOD_REMIND"
    internal const val A_BEDTIME = "com.agani.syncup.wellbeing.BEDTIME"
    internal const val A_MORNING = "com.agani.syncup.wellbeing.MORNING"
    internal const val A_SOUNDS = "com.agani.syncup.wellbeing.SOUNDS"
    internal const val A_SOUNDS_STOP = "com.agani.syncup.wellbeing.SOUNDS_STOP"
}

/** Every Wellbeing alarm and notification button. */
class WellbeingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        Wellbeing.init(ctx)
        val arg = intent.getStringExtra(Wellbeing.EXTRA_ARG)
        val number = intent.getIntExtra(Wellbeing.EXTRA_NUMBER, 0)
        when (intent.action) {
            Wellbeing.A_WATER_REMIND -> Water.remind(ctx)
            Wellbeing.A_WATER_ADD -> Water.addFromNotification(ctx, number, arg)
            Wellbeing.A_WATER_LATER -> Water.later(ctx)
            Wellbeing.A_STEPS_NUDGE -> {
                val done = goAsync()
                Thread {
                    try {
                        Steps.eveningNudge(ctx)
                    } finally {
                        done.finish()
                    }
                }.start()
            }
            Wellbeing.A_BREAK -> Breaks.due(ctx)
            Wellbeing.A_BREAK_SKIP -> Breaks.skip(ctx)
            Wellbeing.A_BREAK_PAUSE -> Breaks.pause(ctx)
            Wellbeing.A_HABIT_REMIND -> Habits.remind(ctx)
            Wellbeing.A_HABIT_DONE -> arg?.let { Habits.doneFromNotification(ctx, it) }
            Wellbeing.A_HABIT_LATER -> arg?.let { Habits.later(ctx, it) }
            Wellbeing.A_MOOD_REMIND -> Mood.remind(ctx)
            Wellbeing.A_BEDTIME -> Sleep.bedtime(ctx)
            Wellbeing.A_MORNING -> Sleep.morning(ctx)
            Wellbeing.A_SOUNDS -> Sleep.playFromNotification(ctx)
            Wellbeing.A_SOUNDS_STOP -> Sleep.stopSounds(ctx)
        }
    }
}
