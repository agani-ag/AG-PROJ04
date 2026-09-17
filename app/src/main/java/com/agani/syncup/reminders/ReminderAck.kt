package com.agani.syncup.reminders

import android.content.Context
import com.agani.syncup.data.ApiClient
import com.agani.syncup.data.ReminderAckRequest
import com.agani.syncup.data.TokenStore

/** Reports reminder delivery back to the backend: "synced" (downloaded) or "fired" (shown). */
object ReminderAck {
    const val SYNCED = "synced"
    const val FIRED = "fired"

    /** Returns true on success (or nothing-to-do); false if the network call failed. */
    suspend fun report(context: Context, reminderIds: List<String>, event: String): Boolean {
        if (reminderIds.isEmpty()) return true
        val store = TokenStore(context)
        val token = store.token() ?: return true // logged out — nothing to report
        return runCatching {
            ApiClient.service.ackReminders(
                "Bearer $token",
                ReminderAckRequest(store.deviceId(), event, reminderIds),
            )
            true
        }.getOrDefault(false)
    }
}
