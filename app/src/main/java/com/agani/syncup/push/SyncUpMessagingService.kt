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
        // Token rotated — push the new one to the backend if the user is logged in.
        DeviceRegistrar.register(applicationContext)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val notif = message.notification
        val title = notif?.title ?: message.data["title"] ?: "SyncUp"
        val body = notif?.body ?: message.data["body"] ?: ""
        // Image from the notification payload or a `data.image` key. (When the app is backgrounded,
        // Android shows notification-payload images itself; this covers the foreground case.)
        val imageUrl = notif?.imageUrl?.toString() ?: message.data["image"]
        val isChat = message.data["type"] == "chat"
        showNotification(title, body, imageUrl, message.data["link_url"], message.data["link_title"], isChat)
    }

    private fun showNotification(
        title: String,
        body: String,
        imageUrl: String?,
        linkUrl: String?,
        linkTitle: String?,
        openChat: Boolean = false,
    ) {
        ensureChannel()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            when {
                // A chat reply — tap opens the chat-with-admin screen.
                openChat -> putExtra(MainActivity.EXTRA_OPEN_CHAT, true)
                // Otherwise tap opens this URL in the in-app WebView (campaign / form / any page).
                !linkUrl.isNullOrBlank() -> {
                    putExtra(ReminderContract.EXTRA_LINK_URL, linkUrl)
                    putExtra(ReminderContract.EXTRA_LINK_TITLE, linkTitle ?: "")
                }
            }
        }
        val pending = PendingIntent.getActivity(
            this, System.currentTimeMillis().toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // onMessageReceived runs off the main thread, so downloading here is safe.
        val image = if (!imageUrl.isNullOrBlank()) ReminderImages.load(imageUrl) else null
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
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
        getSystemService(NotificationManager::class.java)
            .notify(System.currentTimeMillis().toInt(), builder.build())
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "SyncUp Notifications", NotificationManager.IMPORTANCE_HIGH,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "syncup_push"
    }
}
