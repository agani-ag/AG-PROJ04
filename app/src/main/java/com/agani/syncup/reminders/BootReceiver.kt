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
        // Tools → Calendar's reminders: after a restart, and after SyncUp updates itself.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            com.agani.syncup.calendar.CalendarReminders.reschedule(context)
            // Tools → Forest: the gentle daily reminder, and calm time's end if one was running.
            com.agani.syncup.forest.ForestReminder.schedule(context)
            com.agani.syncup.forest.CalmSession.rescheduleAfterBoot(context)
            // Tools → Wellbeing: Water, Steps, Breaks, Sleep, Habits and Mood reminders.
            com.agani.syncup.wellbeing.Wellbeing.init(context)
        }
    }
}
