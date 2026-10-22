package com.agani.syncup.calendar

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.agani.syncup.MainActivity
import com.agani.syncup.R
import org.json.JSONArray

/**
 * A to-do's reminders are notifications, set as alarms on the phone itself — they come with no
 * internet, SyncUp closed, after a restart. Same timing as SyncUp's reminders today
 * (setAndAllowWhileIdle, no special permission): on time normally, maybe a few minutes late on a
 * phone left asleep for long.
 *
 * Only the next 14 days are set (Android limits how many alarms an app may hold); a quiet
 * "refresh" alarm re-sets them before that runs out, and so does every change, sync and restart.
 */
object CalendarReminders {
    const val CHANNEL = "calendar"
    private const val PREFS = "calendar_alarms"
    private const val HORIZON_DAYS = 14
    private const val MAX_ALARMS = 60
    /** Days a repeating to-do is walked through to find its coming ones (a year of daily ones). */
    private const val OCCURRENCE_LIMIT = 400

    const val ACTION_FIRE = "com.agani.syncup.calendar.FIRE"
    const val ACTION_REFRESH = "com.agani.syncup.calendar.REFRESH"
    const val ACTION_DONE = "com.agani.syncup.calendar.DONE"
    const val ACTION_SNOOZE = "com.agani.syncup.calendar.SNOOZE"
    const val EXTRA_TASK = "task"
    const val EXTRA_DAY = "day"
    const val EXTRA_OFFSET = "offset"

    /** Cancel what was set and set the next reminders again. Cheap; called after every change. */
    fun reschedule(context: Context) {
        val ctx = context.applicationContext
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Cancel everything set last time.
        val old = runCatching { JSONArray(prefs.getString("set", "[]")) }.getOrDefault(JSONArray())
        for (i in 0 until old.length()) {
            val parts = old.optString(i).split('|')
            if (parts.size == 3) am.cancel(firePending(ctx, parts[0], parts[1].toInt(), parts[2].toInt(), PendingIntent.FLAG_UPDATE_CURRENT))
        }
        am.cancel(refreshPending(ctx))
        if (!CalendarStore.ready) CalendarStore.init(ctx)
        if (!CalendarStore.remindersOn) {
            prefs.edit().putString("set", "[]").apply()
            return
        }
        val now = System.currentTimeMillis()
        val today = Days.today()
        val until = Days.add(today, HORIZON_DAYS)
        val horizonEnd = Days.at(Days.add(until, 1), 0)
        data class Fire(val at: Long, val task: String, val day: Int, val offset: Int)
        val fires = ArrayList<Fire>()
        for (t in CalendarStore.tasks) {
            if (t.done || !t.hasDate || t.reminders.isEmpty()) continue
            // Any number of minutes before — custom ones too, weeks before, or (all day) below 0 = later
            // on the day. A reminder N days before goes off for the days N days further on, so each one
            // looks at its own stretch of days.
            for (off in t.reminders.distinct()) {
                val shift = off.floorDiv(24 * 60)
                // A repeating to-do reminds on every coming occurrence, even if the last one wasn't ticked.
                val from = if (t.repeat.repeats) maxOf(t.day, Days.add(today, shift - 1)) else t.day
                for (d in t.occurrences(from, Days.add(until, shift + 1), OCCURRENCE_LIMIT)) {
                    val at = Reminders.atMs(t, off, d)
                    if (at > now && at < horizonEnd) fires.add(Fire(at, t.id, d, off))
                }
            }
        }
        // Snoozed ones come back once (and ones long gone are forgotten).
        val snoozes = runCatching { org.json.JSONObject(prefs.getString("snoozes", "{}") ?: "{}") }.getOrDefault(org.json.JSONObject())
        val stale = ArrayList<String>()
        snoozes.keys().forEach { id ->
            val at = snoozes.optLong(id)
            if (at > now && CalendarStore.task(id)?.done == false) fires.add(Fire(at, id, CalendarStore.task(id)?.day ?: today, SNOOZED))
            else if (at < now - 86_400_000L) stale.add(id)
        }
        if (stale.isNotEmpty()) {
            stale.forEach { snoozes.remove(it) }
            prefs.edit().putString("snoozes", snoozes.toString()).apply()
        }
        val chosen = fires.sortedBy { it.at }.take(MAX_ALARMS)
        val set = JSONArray()
        for (f in chosen) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, f.at, firePending(ctx, f.task, f.day, f.offset, PendingIntent.FLAG_UPDATE_CURRENT))
            set.put("${f.task}|${f.day}|${f.offset}")
        }
        prefs.edit().putString("set", set.toString()).apply()
        // Before the set ones run out (or in a week), set the next ones.
        val refreshAt = minOf(now + 7L * 86_400_000, chosen.lastOrNull()?.at?.plus(60_000) ?: Long.MAX_VALUE)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, refreshAt, refreshPending(ctx))
    }

    fun snooze(context: Context, taskId: String) {
        val ctx = context.applicationContext
        if (!CalendarStore.ready) CalendarStore.init(ctx)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val snoozes = runCatching { org.json.JSONObject(prefs.getString("snoozes", "{}") ?: "{}") }.getOrDefault(org.json.JSONObject())
        snoozes.put(taskId, System.currentTimeMillis() + CalendarStore.snoozeMinutes * 60_000L)
        prefs.edit().putString("snoozes", snoozes.toString()).apply()
        NotificationManagerCompat.from(ctx).cancel(notificationId(taskId))
        reschedule(ctx)
    }

    private fun clearSnooze(context: Context, taskId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val snoozes = runCatching { org.json.JSONObject(prefs.getString("snoozes", "{}") ?: "{}") }.getOrDefault(org.json.JSONObject())
        if (snoozes.has(taskId)) {
            snoozes.remove(taskId)
            prefs.edit().putString("snoozes", snoozes.toString()).apply()
        }
    }

    /** The reminder itself: title, when, list and steps — with Done and Snooze. */
    fun show(context: Context, taskId: String, day: Int, offset: Int) {
        val ctx = context.applicationContext
        if (!CalendarStore.ready) CalendarStore.init(ctx)
        val t = CalendarStore.task(taskId) ?: return
        if (t.done) return
        ensureChannel(ctx)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val today = Days.today()
        val whenText = (if (offset == SNOOZED) "Snoozed · " else "") + t.whenText(day, today)
        val steps = if (t.checklist.isNotEmpty()) " · ${t.checklist.count { it.done }} of ${t.checklist.size} steps done" else ""
        val text = "$whenText · ${CalendarStore.list(t.listId).name}$steps"
        val open = PendingIntent.getActivity(
            ctx, notificationId(taskId),
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_CALENDAR, taskId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_sync)
            // Reminded on an earlier day (a day or a week before, the evening before…): say which day it's for.
            .setContentTitle(if (day > today) "${Days.friendly(day, today)}: ${t.title}" else t.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (t.notes.isNotBlank()) "$text\n${t.notes}" else text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "Done", actionPending(ctx, ACTION_DONE, taskId))
            .addAction(0, "Snooze ${CalendarStore.snoozeMinutes} min", actionPending(ctx, ACTION_SNOOZE, taskId))
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(notificationId(taskId), n) }
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Calendar reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Your to-dos' reminders, from Tools → Calendar"
            },
        )
    }

    fun notificationId(taskId: String) = 0x5C00000 + (taskId.hashCode() and 0xFFFFF)

    private fun firePending(ctx: Context, taskId: String, day: Int, offset: Int, flags: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, "$taskId|$day|$offset".hashCode(),
            Intent(ctx, CalendarAlarmReceiver::class.java).setAction(ACTION_FIRE)
                .setData(Uri.parse("syncup://calendar/$taskId/$day/$offset"))
                .putExtra(EXTRA_TASK, taskId).putExtra(EXTRA_DAY, day).putExtra(EXTRA_OFFSET, offset),
            flags or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun refreshPending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0x5C0FFFF, Intent(ctx, CalendarAlarmReceiver::class.java).setAction(ACTION_REFRESH),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun actionPending(ctx: Context, action: String, taskId: String): PendingIntent = PendingIntent.getBroadcast(
        ctx, "$action$taskId".hashCode(),
        Intent(ctx, CalendarAlarmReceiver::class.java).setAction(action).setData(Uri.parse("syncup://calendar/$action/$taskId")).putExtra(EXTRA_TASK, taskId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * "offset" of a snoozed reminder coming back. Not -1 (as it once was): an all-day to-do's custom
     * reminder may be -1 (9:01 on the day), so this is a number no reminder can be.
     */
    const val SNOOZED = Int.MIN_VALUE

    internal fun fired(context: Context, taskId: String, day: Int, offset: Int) {
        if (offset == SNOOZED) clearSnooze(context, taskId)
        show(context, taskId, day, offset)
        reschedule(context)
    }
}

/** Alarms (reminder due, refresh) and the notification's Done / Snooze buttons. */
class CalendarAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(CalendarReminders.EXTRA_TASK)
        when (intent.action) {
            CalendarReminders.ACTION_FIRE -> if (id != null) {
                CalendarReminders.fired(context, id, intent.getIntExtra(CalendarReminders.EXTRA_DAY, 0), intent.getIntExtra(CalendarReminders.EXTRA_OFFSET, 0))
            }
            CalendarReminders.ACTION_REFRESH -> CalendarReminders.reschedule(context)
            CalendarReminders.ACTION_DONE -> if (id != null) {
                CalendarStore.init(context)
                CalendarStore.complete(id)
                CalendarStore.flush()
                NotificationManagerCompat.from(context).cancel(CalendarReminders.notificationId(id))
                // Ticked from the notification: the other phones hear about it even if SyncUp stays closed.
                if (CalendarSync.active) com.agani.syncup.sync.SyncPingWorker.enqueue(context)
            }
            CalendarReminders.ACTION_SNOOZE -> if (id != null) CalendarReminders.snooze(context, id)
        }
    }
}
