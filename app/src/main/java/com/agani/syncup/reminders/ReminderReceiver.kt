package com.agani.syncup.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.agani.syncup.data.ReminderStore

/** Fired by AlarmManager at a reminder's time: posts the notification and re-arms recurring ones. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderContract.ACTION_FIRE) return
        val id = intent.getStringExtra(ReminderContract.EXTRA_ID) ?: return

        ReminderNotifications.show(
            context,
            id = id,
            title = intent.getStringExtra(ReminderContract.EXTRA_TITLE) ?: "Reminder",
            body = intent.getStringExtra(ReminderContract.EXTRA_BODY) ?: "",
            linkUrl = intent.getStringExtra(ReminderContract.EXTRA_LINK_URL) ?: "",
            linkTitle = intent.getStringExtra(ReminderContract.EXTRA_LINK_TITLE) ?: "",
        )

        // Recurring reminders: schedule the next occurrence (one-shot alarms don't repeat).
        val recurrence = intent.getStringExtra(ReminderContract.EXTRA_RECURRENCE) ?: "once"
        if (recurrence != "once") {
            val reminder = ReminderStore(context).load().find { it.id == id }
            if (reminder != null) ReminderScheduler.scheduleNext(context, reminder)
        }
    }
}
