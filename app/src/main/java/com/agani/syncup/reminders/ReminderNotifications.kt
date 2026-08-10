package com.agani.syncup.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.agani.syncup.MainActivity
import com.agani.syncup.R

/** Builds the dedicated "Reminders" channel + posts a reminder notification that deep-links to its link. */
object ReminderNotifications {

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ReminderContract.CHANNEL_ID,
                "Reminders",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Scheduled reminders from SyncUp" }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun show(
        context: Context,
        id: String,
        title: String,
        body: String,
        linkUrl: String,
        linkTitle: String,
        image: Bitmap? = null,
    ) {
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val tap = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (linkUrl.isNotBlank()) {
                putExtra(ReminderContract.EXTRA_LINK_URL, linkUrl)
                putExtra(ReminderContract.EXTRA_LINK_TITLE, linkTitle)
            }
        }
        val pending = PendingIntent.getActivity(
            context, notifId(id), tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, ReminderContract.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sync)
            .setContentTitle(title.ifBlank { "Reminder" })
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
        if (image != null) {
            // Big-picture: thumbnail when collapsed, full image when expanded.
            builder.setLargeIcon(image)
                .setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(image)
                        .bigLargeIcon(null as Bitmap?),
                )
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }
        context.getSystemService(NotificationManager::class.java).notify(notifId(id), builder.build())
    }

    private fun notifId(id: String): Int = id.toIntOrNull() ?: id.hashCode()
}
