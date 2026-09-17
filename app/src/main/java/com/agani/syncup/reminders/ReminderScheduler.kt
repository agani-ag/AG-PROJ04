package com.agani.syncup.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.agani.syncup.data.ReminderDto
import com.agani.syncup.data.ReminderStore
import java.util.Calendar

/**
 * Schedules each reminder as a near-exact local alarm (`setAndAllowWhileIdle` — no
 * special permission). One-shot alarms are re-armed for the next occurrence when they
 * fire (see [ReminderReceiver]); [rescheduleAll] rebuilds the whole set after a sync or reboot.
 */
object ReminderScheduler {

    fun rescheduleAll(context: Context, reminders: List<ReminderDto>) {
        val store = ReminderStore(context)
        // Cancel everything we previously scheduled (covers deletions & edits).
        store.scheduledIds().forEach { cancelOne(context, it) }

        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val scheduled = mutableSetOf<String>()
        reminders.forEach { r ->
            val trigger = nextTrigger(r) ?: return@forEach
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent(context, r))
            scheduled.add(r.id)
        }
        store.setScheduledIds(scheduled)
    }

    /**
     * Re-arm a single (recurring) reminder for its next occurrence after it fires.
     * Returns true if another occurrence was armed, false if the reminder is finished
     * (one-time, or a counted "Repeat N times" that has fired its last time).
     * Uses `now + 1s` with no grace so it never re-arms the occurrence that just fired.
     */
    fun scheduleNext(context: Context, r: ReminderDto): Boolean {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        val trigger = nextTrigger(r, now = System.currentTimeMillis() + 1_000L, graceMs = 0L) ?: return false
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent(context, r))
        val store = ReminderStore(context)
        store.setScheduledIds(store.scheduledIds() + r.id)
        return true
    }

    private fun cancelOne(context: Context, id: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderContract.ACTION_FIRE
            data = Uri.parse("syncup://reminder/$id")
        }
        val pi = PendingIntent.getBroadcast(
            context, requestCode(id), intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        pi?.let { am.cancel(it); it.cancel() }
    }

    private fun pendingIntent(context: Context, r: ReminderDto): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderContract.ACTION_FIRE
            // Distinct data per id → distinct PendingIntent (extras don't affect matching).
            data = Uri.parse("syncup://reminder/${r.id}")
            putExtra(ReminderContract.EXTRA_ID, r.id)
            putExtra(ReminderContract.EXTRA_TITLE, r.title)
            putExtra(ReminderContract.EXTRA_BODY, r.body)
            putExtra(ReminderContract.EXTRA_LINK_URL, r.linkUrl)
            putExtra(ReminderContract.EXTRA_LINK_TITLE, r.linkTitle)
            putExtra(ReminderContract.EXTRA_IMAGE_URL, r.imageUrl)
            putExtra(ReminderContract.EXTRA_RECURRENCE, r.recurrence)
        }
        return PendingIntent.getBroadcast(
            context, requestCode(r.id), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun requestCode(id: String): Int = id.toIntOrNull() ?: id.hashCode()

    /**
     * Next fire time (epoch millis) at/after now, or null if there's nothing more to schedule.
     * `graceMs` lets a just-passed occurrence still fire (used on initial scheduling / after reboot);
     * pass 0 when re-arming so the occurrence that just fired isn't picked again.
     *
     *  - "daily"    → the reminder's time-of-day, every day (unlimited), in the device's local tz.
     *  - "interval" → occurrences at scheduledAt + k·interval for k = 0…count-1 (stateless: the
     *                 count limit is derived from k, so it survives reboots and re-syncs).
     *  - "once" / anything else → the single scheduled instant.
     */
    fun nextTrigger(r: ReminderDto, now: Long = System.currentTimeMillis(), graceMs: Long = 60_000L): Long? {
        if (r.scheduledAtMs <= 0L) return null

        if (r.recurrence == "daily") {
            val base = Calendar.getInstance().apply { timeInMillis = r.scheduledAtMs }
            val hour = base.get(Calendar.HOUR_OF_DAY)
            val minute = base.get(Calendar.MINUTE)
            return Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
        }

        val interval = r.repeatIntervalMs
        if (interval > 0L) {
            // "Repeat N times": find the earliest occurrence at/after (now - grace).
            val count = r.repeatCount // 0 = unlimited
            val lower = now - graceMs
            val k = if (r.scheduledAtMs >= lower) 0L
            else (lower - r.scheduledAtMs + interval - 1) / interval // ceil division
            if (count > 0 && k > count - 1) return null // all occurrences done
            return r.scheduledAtMs + k * interval
        }

        // "Once" — fire at the exact scheduled instant (skip if already well past).
        return if (r.scheduledAtMs >= now - graceMs) r.scheduledAtMs else null
    }
}
