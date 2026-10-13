package com.agani.syncup.notifications

import android.app.NotificationManager
import android.content.Context

/**
 * SyncUp's pushes in the phone's notification panel. The number (Samsung) or dot (Pixel) on the app
 * icon is the app's notifications in the panel — Android lets no app set it directly — so once a push
 * is read in the app it's taken off the panel, and the icon matches the bell.
 *
 * Pushes the Notifications page lists are shown under the tag "syncup-log-<Push Log id>": by the
 * system from the server's FCM tag when the app is closed, by SyncUpMessagingService when it's open.
 */
object PushTray {
    private const val PREFIX = "syncup-log-"

    fun tag(logId: String) = PREFIX + logId

    /** These pushes were read: off the panel. */
    fun clear(context: Context, logIds: Collection<String>) {
        val tags = logIds.map(::tag).toSet()
        if (tags.isEmpty()) return
        cancelWhere(context) { it in tags }
    }

    /** Everything read: every listed push off the panel (chat and verification codes stay). */
    fun clearAll(context: Context) = cancelWhere(context) { it.startsWith(PREFIX) }

    private fun cancelWhere(context: Context, match: (String) -> Boolean) {
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.activeNotifications.filter { it.tag?.let(match) == true }.forEach { nm.cancel(it.tag, it.id) }
        }
    }
}
