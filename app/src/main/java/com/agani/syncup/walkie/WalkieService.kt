package com.agani.syncup.walkie

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
import com.agani.syncup.R

/**
 * Keeps a Walkie Talkie channel going with the screen off or SyncUp in the background — a
 * "connected device" foreground service (it's talking to the phones nearby), plus "microphone"
 * once the mic is allowed, so Talk works from the lock screen. The notification is the lock-screen
 * card: who's talking, and Talk / Mute / Leave.
 */
class WalkieService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TALK -> Walkie.toggleTalk()
            ACTION_MUTE -> Walkie.toggleOthersMuted()
            ACTION_LEAVE -> Walkie.leave()
        }
        if (!Walkie.inChannel && Walkie.phase != WalkiePhase.JOINING) {
            stopSelf()
            return START_NOT_STICKY
        }
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or (if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        val ok = runCatching { ServiceCompat.startForeground(this, ID, card(this), types) }.isSuccess
        if (!ok) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        NotificationManagerCompat.from(this).cancel(ID)
        super.onDestroy()
    }

    companion object {
        /** Normal importance (shown open, with its buttons) but silent: it never rings or buzzes. */
        private const val CHANNEL = "walkie_channel"
        private const val ID = 4401
        private const val ACTION_TALK = "com.agani.syncup.walkie.TALK"
        private const val ACTION_MUTE = "com.agani.syncup.walkie.MUTE"
        private const val ACTION_LEAVE = "com.agani.syncup.walkie.LEAVE"
        private var shown = ""

        fun start(context: Context) {
            channel(context)
            shown = ""
            // Not allowed right now (e.g. Android blocked it): the channel still works while SyncUp is open.
            runCatching { ContextCompat.startForegroundService(context, Intent(context, WalkieService::class.java)) }
        }

        fun stop(context: Context) {
            shown = ""
            context.stopService(Intent(context, WalkieService::class.java))
        }

        /** Redraw the card when what it says changed. */
        fun refresh(context: Context) {
            if (!Walkie.inChannel) return
            val key = "${Walkie.channelName}|${Walkie.members.size}|${Walkie.statusLine()}|${Walkie.othersMuted}|${Walkie.onAir}|${Walkie.mode}|${Walkie.micMuted}"
            if (key == shown) return
            shown = key
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            runCatching { NotificationManagerCompat.from(context).notify(ID, card(context)) }
        }

        private fun card(context: Context): Notification {
            channel(context)
            val people = Walkie.members.size
            val open = Walkie.mode == TalkMode.OPEN
            val talkLabel = when {
                open -> if (Walkie.micMuted) "Mic on" else "Mic off"
                Walkie.onAir || Walkie.asking -> "Stop"
                else -> "Talk"
            }
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_walkie)
                .setContentTitle("${Walkie.channelName} · $people ${if (people == 1) "person" else "people"}")
                .setContentText(Walkie.statusLine())
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context, 4400,
                        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_WALKIE, true)
                            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
                .addAction(0, talkLabel, action(context, ACTION_TALK, 1))
                .addAction(0, if (Walkie.othersMuted) "Unmute" else "Mute", action(context, ACTION_MUTE, 2))
                .addAction(0, "Leave", action(context, ACTION_LEAVE, 3))
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }

        private fun action(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getService(
            context, code, Intent(context, WalkieService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun channel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Walkie Talkie", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        setSound(null, null)
                        enableVibration(false)
                        description = "While you're in a Walkie Talkie channel"
                        setShowBadge(false)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    },
                )
            }
        }
    }
}
