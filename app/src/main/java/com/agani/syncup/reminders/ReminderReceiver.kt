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

        val title = intent.getStringExtra(ReminderContract.EXTRA_TITLE) ?: "Reminder"
        val body = intent.getStringExtra(ReminderContract.EXTRA_BODY) ?: ""
        val linkUrl = intent.getStringExtra(ReminderContract.EXTRA_LINK_URL) ?: ""
        val linkTitle = intent.getStringExtra(ReminderContract.EXTRA_LINK_TITLE) ?: ""
        val imageUrl = intent.getStringExtra(ReminderContract.EXTRA_IMAGE_URL) ?: ""
        // Re-arm the next occurrence (daily / "Repeat N times"); if there isn't one — a one-time
        // reminder, or a counted reminder that just fired its last time — drop it from the cache.
        val reminder = ReminderStore(context).load().find { it.id == id }
        val rescheduled = reminder != null && ReminderScheduler.scheduleNext(context, reminder)
        if (!rescheduled) ReminderStore(context).remove(id)

        // Report "shown" back to the backend (reliable, survives the app being closed).
        ReminderAckWorker.reportFired(context, id)

        if (imageUrl.isBlank()) {
            ReminderNotifications.show(context, id, title, body, linkUrl, linkTitle, null)
        } else {
            // Download the image off the main thread; goAsync keeps the receiver alive for it.
            val pending = goAsync()
            Thread {
                try {
                    val bmp = ReminderImages.load(imageUrl)
                    ReminderNotifications.show(context, id, title, body, linkUrl, linkTitle, bmp)
                } finally {
                    pending.finish()
                }
            }.start()
        }
    }
}
