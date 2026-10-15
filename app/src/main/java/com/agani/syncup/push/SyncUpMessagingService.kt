package com.agani.syncup.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.agani.syncup.MainActivity
import com.agani.syncup.R
import com.agani.syncup.reminders.ReminderContract
import com.agani.syncup.reminders.ReminderImages
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** Receives FCM messages and (re)registers the device token. */
class SyncUpMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // Token rotated — every install (signed in or not) checks in with the new one.
        DeviceRegistrar.hello(applicationContext)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Silent "sync": the to-dos changed on another phone, or trees were added from the server — fetch them, show nothing.
        if (message.data["type"] == "sync") {
            com.agani.syncup.sync.SyncPingWorker.enqueue(applicationContext, message.data["what"])
            return
        }
        val notif = message.notification
        val title = notif?.title ?: message.data["title"] ?: "SyncUp"
        val body = notif?.body ?: message.data["body"] ?: ""
        // Image from the notification payload or a `data.image` key. (When the app is backgrounded,
        // Android shows notification-payload images itself; this covers the foreground case.)
        val imageUrl = notif?.imageUrl?.toString() ?: message.data["image"]
        val isChat = message.data["type"] == "chat"
        // A partner verification prompt — tap opens the Action screen for that request.
        val actionId = if (message.data["type"] == "action") message.data["action_id"] else null
        // onMessageReceived only fires while the app is in the FOREGROUND — so open the prompt
        // instantly (bring MainActivity to front), no notification tap needed. The notification is
        // still posted as a fallback / for the tray.
        if (!actionId.isNullOrBlank()) {
            runCatching {
                startActivity(
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra(MainActivity.EXTRA_ACTION_ID, actionId)
                    },
                )
            }
        }
        val logId = message.data["log_id"]
        showNotification(
            title, body, imageUrl, message.data["link_url"], message.data["link_title"], isChat, actionId,
            openInNormal = message.data["open_in"] == "normal",
            openPartners = message.data["type"] == "partners",
            broadcast = !message.data["audience"].isNullOrBlank(),
            logId = logId,
        )
        // It's on the Notifications page now: the bell goes up (chat and codes aren't listed there).
        if (!logId.isNullOrBlank() && !isChat && actionId.isNullOrBlank()) {
            com.agani.syncup.notifications.Inbox.onPushArrived(applicationContext)
        }
    }

    private fun showNotification(
        title: String,
        body: String,
        imageUrl: String?,
        linkUrl: String?,
        linkTitle: String?,
        openChat: Boolean = false,
        actionId: String? = null,
        openInNormal: Boolean = false,
        openPartners: Boolean = false,
        broadcast: Boolean = false,
        logId: String? = null,
    ) {
        ensureChannel()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // Tapping it marks it read on the Notifications page.
            if (!logId.isNullOrBlank()) putExtra(MainActivity.EXTRA_LOG_ID, logId)
            when {
                // A verification prompt — tap opens the Action screen.
                !actionId.isNullOrBlank() -> putExtra(MainActivity.EXTRA_ACTION_ID, actionId)
                // A chat reply — tap opens the chat-with-admin screen.
                openChat -> putExtra(MainActivity.EXTRA_OPEN_CHAT, true)
                // A partner added this user — tap opens the Partners page.
                openPartners -> putExtra(MainActivity.EXTRA_OPEN_PARTNERS, true)
                // Otherwise tap opens this URL in the in-app WebView (campaign / form / any page).
                !linkUrl.isNullOrBlank() -> {
                    putExtra(ReminderContract.EXTRA_LINK_URL, linkUrl)
                    putExtra(ReminderContract.EXTRA_LINK_TITLE, linkTitle ?: "")
                    // Broadcast links open in a Normal tab; account pushes open in Work.
                    if (openInNormal) putExtra(MainActivity.EXTRA_OPEN_IN, "normal")
                }
            }
        }
        val pending = PendingIntent.getActivity(
            this, System.currentTimeMillis().toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // onMessageReceived runs off the main thread, so downloading here is safe.
        val image = if (!imageUrl.isNullOrBlank()) ReminderImages.load(imageUrl) else null
        // Verification prompts go on the high-importance Verification channel (heads-up).
        val channel = when {
            !actionId.isNullOrBlank() -> VERIFY_CHANNEL_ID
            broadcast -> UPDATES_CHANNEL_ID
            else -> CHANNEL_ID
        }
        val builder = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_sync)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Image shows in the notification shade when unlocked; hidden on the lock screen.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(pending)
        if (image != null) {
            builder.setLargeIcon(image)
                .setStyle(
                    NotificationCompat.BigPictureStyle().bigPicture(image).bigLargeIcon(null as android.graphics.Bitmap?),
                )
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }
        val nm = getSystemService(NotificationManager::class.java)
        // A push the Notifications page lists goes under its log's tag — the same one the system uses
        // when it shows the push itself — so reading it in the app takes it off the panel.
        if (!logId.isNullOrBlank() && !openChat && actionId.isNullOrBlank()) {
            nm.notify(com.agani.syncup.notifications.PushTray.tag(logId), 0, builder.build())
        } else {
            nm.notify(System.currentTimeMillis().toInt(), builder.build())
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "SyncUp Notifications", NotificationManager.IMPORTANCE_HIGH),
            )
            // Also ensure the Verification + Updates channels (normally created at app startup).
            mgr.createNotificationChannel(
                NotificationChannel(VERIFY_CHANNEL_ID, "Verification", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "One-time verification prompts (OTP / code / number)" },
            )
            mgr.createNotificationChannel(updatesChannel())
        }
    }

    companion object {
        private const val CHANNEL_ID = "syncup_push"

        /** High-importance channel for verification prompts (heads-up). Also created in SyncUpApp. */
        const val VERIFY_CHANNEL_ID = "syncup_verify"

        /** News and announcements broadcast to every install (the backend routes topic sends here). */
        const val UPDATES_CHANNEL_ID = "syncup_updates"

        @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
        fun updatesChannel() =
            NotificationChannel(UPDATES_CHANNEL_ID, "SyncUp updates", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "News and announcements from SyncUp" }
    }
}
