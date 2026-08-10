package com.agani.syncup.reminders

import android.content.Context
import com.agani.syncup.data.ApiClient
import com.agani.syncup.data.ReminderStore
import com.agani.syncup.data.TokenStore

/** Downloads the user's reminders and (re)schedules local alarms. Safe to call from anywhere. */
object ReminderSync {

    /** Returns true if a fresh list was fetched from the server. */
    suspend fun sync(context: Context): Boolean {
        val token = TokenStore(context).token()
        if (token.isNullOrBlank()) {
            // Logged out — drop any reminders/alarms left over from a previous session.
            ReminderStore(context).clear()
            ReminderScheduler.rescheduleAll(context, emptyList())
            return false
        }
        return runCatching {
            val list = ApiClient.service.reminders("Bearer $token")
            ReminderStore(context).save(list)
            ReminderScheduler.rescheduleAll(context, list)
            // Tell the backend which reminders this device now has ("picked up").
            ReminderAck.report(context, list.map { it.id }, ReminderAck.SYNCED)
            true
        }.getOrElse {
            // Network/parse failure — keep alarms alive from the last cached list.
            ReminderScheduler.rescheduleAll(context, ReminderStore(context).load())
            false
        }
    }
}
