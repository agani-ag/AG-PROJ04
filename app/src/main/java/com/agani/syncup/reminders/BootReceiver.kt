package com.agani.syncup.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.agani.syncup.data.ReminderStore

/** Alarms are cleared on reboot — re-arm every cached reminder when the device boots. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ReminderScheduler.rescheduleAll(context, ReminderStore(context).load())
        }
    }
}
