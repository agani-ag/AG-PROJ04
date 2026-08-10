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

    /** Re-arm a single (recurring) reminder for its next occurrence after it fires. */
    fun scheduleNext(context: Context, r: ReminderDto) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val trigger = nextTrigger(r) ?: return
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent(context, r))
        val store = ReminderStore(context)
        store.setScheduledIds(store.scheduledIds() + r.id)
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
     * Next fire time (epoch millis) at/after now, or null if there's nothing to schedule
     * (a one-time reminder already in the past). Recurrence is computed in the device's
     * local timezone using the reminder's original time-of-day / weekday.
     */
    fun nextTrigger(r: ReminderDto, now: Long = System.currentTimeMillis()): Long? {
        if (r.scheduledAtMs <= 0L) return null
        // A one-time reminder that just passed (within a small grace) still fires.
        val grace = 60_000L

        val base = Calendar.getInstance().apply { timeInMillis = r.scheduledAtMs }
        val hour = base.get(Calendar.HOUR_OF_DAY)
        val minute = base.get(Calendar.MINUTE)

        fun todayAt(): Calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        return when (r.recurrence) {
            // "Everyday" — fire at the reminder's time-of-day, every day.
            "daily" -> todayAt().apply { if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
            // "Once" — fire at the exact scheduled instant (skip if already well past).
            else -> if (r.scheduledAtMs >= now - grace) r.scheduledAtMs else null
        }
    }
}
