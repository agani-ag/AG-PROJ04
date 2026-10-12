package com.agani.syncup.quickshare

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
import com.agani.syncup.downloads.Files

/**
 * Keeps a SyncUp Share transfer going while SyncUp is in the background or the screen is off (a
 * "connected device" foreground service — it's talking to the other phone), with a progress
 * notification and Cancel. Runs only while two phones are connected; stops as soon as that ends.
 */
class QuickShareService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            QuickShare.cancel()
            stopSelf()
            return START_NOT_STICKY
        }
        val ok = runCatching {
            ServiceCompat.startForeground(
                this, PROGRESS_ID, progress(this),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        }.isSuccess
        if (!ok || !QuickShare.busy) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        NotificationManagerCompat.from(this).cancel(PROGRESS_ID)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_PROGRESS = "quick_share_progress"
        private const val CHANNEL_DONE = "quick_share_done"
        private const val PROGRESS_ID = 4200
        private const val DONE_ID = 4201
        private const val ACTION_CANCEL = "com.agani.syncup.quickshare.CANCEL"

        fun start(context: Context) {
            channels(context)
            // Not allowed right now (e.g. Android blocked it): the transfer still runs while SyncUp is open.
            runCatching { ContextCompat.startForegroundService(context, Intent(context, QuickShareService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, QuickShareService::class.java))
        }

        fun update(context: Context) {
            if (!QuickShare.busy || !canNotify(context)) return
            runCatching { NotificationManagerCompat.from(context).notify(PROGRESS_ID, progress(context)) }
        }

        /** Files arrived while the SyncUp Share page wasn't on screen. */
        fun notifyReceived(context: Context, from: String, count: Int) {
            if (!canNotify(context)) return
            channels(context)
            val n = NotificationCompat.Builder(context, CHANNEL_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(if (count == 1) "1 file received from $from" else "$count files received from $from")
                .setContentText("Saved in Download / SyncUp")
                .setAutoCancel(true)
                .setContentIntent(open(context))
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(DONE_ID, n) }
        }

        private fun progress(context: Context): Notification {
            channels(context)
            val sending = QuickShare.role == QsRole.SEND
            val peer = QuickShare.peerName.ifBlank { "nearby phone" }
            val title = if (sending) "Sending to $peer" else "Receiving from $peer"
            val total = QuickShare.totalBytes
            val done = QuickShare.doneBytes
            val connecting = QuickShare.phase == QsPhase.CONNECTING
            val text = when {
                connecting -> "Connecting…"
                total > 0 -> "${Files.size(done)} of ${Files.size(total)}" +
                    (if (QuickShare.speed > 0) " · ${Files.size(QuickShare.speed)}/s" else "")
                else -> "${QuickShare.files.size} files"
            }
            return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
                .setSmallIcon(if (sending) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setSilent(true)
                .setProgress(100, if (total > 0) (done * 100 / total).toInt() else 0, connecting || total <= 0)
                .setContentIntent(open(context))
                .addAction(
                    0, "Cancel",
                    PendingIntent.getService(
                        context, 1, Intent(context, QuickShareService::class.java).setAction(ACTION_CANCEL),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }

        private fun open(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context, 4200,
                Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_QUICK_SHARE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun canNotify(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

        private fun channels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "SyncUp Share in progress", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Files being sent to or received from a nearby phone" },
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, "SyncUp Share received", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Files arrived from a nearby phone" },
            )
        }
    }
}
