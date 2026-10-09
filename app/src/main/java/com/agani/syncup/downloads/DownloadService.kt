package com.agani.syncup.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.agani.syncup.MainActivity

/**
 * Keeps downloads going with the app closed or the screen off (a "data sync" foreground service),
 * and owns the Downloads notifications: one ongoing progress notification with Pause all /
 * Cancel all, and one per finished file with Open / Share.
 */
class DownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_ALL -> Downloads.pauseAll()
            ACTION_CANCEL_ALL -> Downloads.cancelAll()
        }
        val ok = runCatching {
            ServiceCompat.startForeground(
                this, PROGRESS_ID, progress(this),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        }.isSuccess
        if (!ok || !Downloads.hasActive()) stopSelf()
        return START_NOT_STICKY
    }

    /** Android 15+ limits data-sync services to 6 hours a day: pause, carry on when SyncUp is opened. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Downloads.onServiceStopped()
        stopSelf()
    }

    override fun onDestroy() {
        NotificationManagerCompat.from(this).cancel(PROGRESS_ID)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_PROGRESS = "downloads_progress"
        private const val CHANNEL_DONE = "downloads_done"
        private const val PROGRESS_ID = 4100
        private const val ACTION_PAUSE_ALL = "com.agani.syncup.downloads.PAUSE_ALL"
        private const val ACTION_CANCEL_ALL = "com.agani.syncup.downloads.CANCEL_ALL"

        /** Start the foreground service; false when Android doesn't allow it right now. */
        fun start(context: Context): Boolean {
            channels(context)
            return runCatching { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }.isSuccess
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadService::class.java))
        }

        /** Refresh the progress notification. */
        fun update(context: Context) {
            if (!canNotify(context)) return
            runCatching { NotificationManagerCompat.from(context).notify(PROGRESS_ID, progress(context)) }
        }

        fun notifyDone(context: Context, t: DownloadTask) {
            if (!canNotify(context) || t.incognito) return
            channels(context)
            val uri = Downloads.openUri(t)
            val n = NotificationCompat.Builder(context, CHANNEL_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("${t.fileName} downloaded")
                .setContentText("${Files.size(t.total)} · ${Downloads.folderLabel(t.kind)}")
                .setAutoCancel(true)
                .setContentIntent(openDownloads(context, t.id.toInt()))
            if (uri != null) {
                val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, t.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                val share = Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType(t.mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    "Share",
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                n.addAction(0, "Open", PendingIntent.getActivity(context, (t.id * 2).toInt(), view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                n.addAction(0, "Share", PendingIntent.getActivity(context, (t.id * 2 + 1).toInt(), share, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            runCatching { NotificationManagerCompat.from(context).notify(5000 + (t.id % 100_000).toInt(), n.build()) }
        }

        private fun progress(context: Context): Notification {
            channels(context)
            val running = Downloads.tasks.filter { it.state == DlState.RUNNING || it.state == DlState.QUEUED }
            val total = running.sumOf { it.total.coerceAtLeast(0) }
            val done = running.sumOf { it.downloaded }
            val speed = running.sumOf { it.speed }
            val title = when (running.size) {
                0 -> "Downloads"
                1 -> "Downloading ${running[0].fileName}"
                else -> "Downloading ${running.size} files"
            }
            val left = if (speed > 0 && total > 0) " · about ${Files.duration((total - done).coerceAtLeast(0) / speed)} left" else ""
            val text = if (speed > 0) "${Files.size(speed)}/s$left" else "Starting…"
            val unknown = running.any { it.total <= 0 }
            return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setSilent(true)
                .setProgress(100, if (total > 0) (done * 100 / total).toInt() else 0, unknown || total <= 0)
                .setContentIntent(openDownloads(context, 0))
                .addAction(0, "Pause all", service(context, ACTION_PAUSE_ALL))
                .addAction(0, "Cancel all", service(context, ACTION_CANCEL_ALL))
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }

        private fun service(context: Context, action: String): PendingIntent =
            PendingIntent.getService(
                context, action.hashCode(), Intent(context, DownloadService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun openDownloads(context: Context, code: Int): PendingIntent =
            PendingIntent.getActivity(
                context, 7000 + code,
                Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun canNotify(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

        private fun channels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "Downloads in progress", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Progress of files SyncUp is downloading" },
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "A file finished downloading" },
            )
        }
    }
}
