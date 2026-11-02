package com.agani.syncup.stationfiles

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.agani.syncup.MainActivity
import com.agani.syncup.downloads.Files

/**
 * Keeps uploads to stations going with the app closed or the screen off (a "data sync" foreground service,
 * like downloads), with one progress notification and Cancel.
 */
class UploadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_ALL) {
            StationUploads.items.filter { it.active }.forEach { StationUploads.cancel(it.id) }
        }
        val ok = runCatching {
            ServiceCompat.startForeground(
                this, PROGRESS_ID, progress(this),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        }.isSuccess
        if (!ok || StationUploads.items.none { it.active }) stopSelf()
        return START_NOT_STICKY
    }

    /** Android 15+ limits data-sync services to 6 hours a day: stop; uploads carry on when SyncUp is opened. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        NotificationManagerCompat.from(this).cancel(PROGRESS_ID)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "uploads_progress"
        private const val PROGRESS_ID = 4200
        private const val ACTION_CANCEL_ALL = "com.agani.syncup.stationfiles.CANCEL_ALL"
        private var on = false

        fun start(context: Context) {
            if (on) return
            channel(context)
            on = runCatching { ContextCompat.startForegroundService(context, Intent(context, UploadService::class.java)) }.isSuccess
        }

        fun stop(context: Context) {
            if (!on) return
            on = false
            context.stopService(Intent(context, UploadService::class.java))
        }

        fun update(context: Context) {
            if (!on || !canNotify(context)) return
            runCatching { NotificationManagerCompat.from(context).notify(PROGRESS_ID, progress(context)) }
        }

        private fun progress(context: Context): Notification {
            channel(context)
            val active = StationUploads.items.filter { it.active }
            val current = active.firstOrNull { it.state == StationUpload.RUNNING }
            val title = when (active.size) {
                0 -> "Uploads"
                1 -> "Sending ${active[0].name} to ${active[0].station}"
                else -> "Sending ${active.size} files"
            }
            val done = current?.let { StationUploads.sent[it.id] ?: it.received } ?: 0L
            val text = current?.note ?: current?.let { "${Files.size(done)} of ${Files.size(it.size)}" } ?: "Starting…"
            val open = PendingIntent.getActivity(
                context, 4201, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val cancel = PendingIntent.getService(
                context, 4202, Intent(context, UploadService::class.java).setAction(ACTION_CANCEL_ALL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val b = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(title)
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(open)
                .addAction(0, "Cancel", cancel)
            if (current != null && current.size > 0) {
                b.setProgress(1000, (done * 1000 / current.size).toInt().coerceIn(0, 1000), false)
            } else {
                b.setProgress(0, 0, true)
            }
            return b.build()
        }

        private fun channel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(CHANNEL, "Uploads", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Files you're sending to a station"
                    },
                )
            }
        }

        private fun canNotify(context: Context): Boolean =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
}
