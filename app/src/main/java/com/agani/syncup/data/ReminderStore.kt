package com.agani.syncup.data

import android.content.Context
import com.google.gson.Gson

/**
 * Local cache of the user's reminders (plain JSON in SharedPreferences).
 *
 * Reminders are few and non-sensitive, so a small JSON blob is simpler than Room and is
 * readable from broadcast receivers (alarm fire, boot) without async setup. It also tracks
 * which reminder ids currently have an alarm scheduled, so they can be cancelled on re-sync.
 */
class ReminderStore(context: Context) {
    private val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun save(list: List<ReminderDto>) =
        prefs.edit().putString(KEY_LIST, gson.toJson(list)).apply()

    fun load(): List<ReminderDto> = runCatching {
        val json = prefs.getString(KEY_LIST, null) ?: return emptyList()
        gson.fromJson(json, Array<ReminderDto>::class.java)?.toList() ?: emptyList()
    }.getOrDefault(emptyList())

    fun clear() = prefs.edit().remove(KEY_LIST).remove(KEY_SCHEDULED_IDS).apply()

    /** Ids of reminders that currently have an alarm set (used to cancel on re-sync). */
    fun scheduledIds(): Set<String> = prefs.getStringSet(KEY_SCHEDULED_IDS, emptySet()) ?: emptySet()

    fun setScheduledIds(ids: Set<String>) =
        prefs.edit().putStringSet(KEY_SCHEDULED_IDS, ids).apply()

    private companion object {
        const val KEY_LIST = "list"
        const val KEY_SCHEDULED_IDS = "scheduled_ids"
    }
}
