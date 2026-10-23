package com.agani.syncup.group

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
import com.agani.syncup.watchparty.WatchParty
import com.agani.syncup.watchparty.WpStage

/**
 * Keeps a group going while SyncUp is in the background or the screen is off — the host keeps
 * sending, a guest stays connected. One "connected device" foreground service and one card for the
 * whole group, whatever mode it's in: the group, the mode and what's on, and Leave / End group
 * (plus Pause / Play for the host of Watch together). In Walkie talkie the microphone joins the
 * service while SyncUp is open (Android allows it only then), so talking carries on with the
 * screen off.
 */
class GroupService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> if (GroupLink.mode == GroupMode.WATCH) WatchParty.togglePause()
            ACTION_END -> if (GroupLink.isHost) GroupLink.end() else GroupLink.leave()
        }
        if (!GroupLink.active) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!foreground(this, withMic && micAllowed(this))) {
            // Not allowed with the microphone right now (in the background): without it.
            if (!foreground(this, false)) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        NotificationManagerCompat.from(this).cancel(ID)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "group"
        private const val ID = 4601
        private const val ACTION_PAUSE = "com.agani.syncup.group.PAUSE"
        private const val ACTION_END = "com.agani.syncup.group.END"
        private var shown = ""
        /** Walkie talkie wants the microphone to keep working with the screen off. */
        private var withMic = false

        fun start(context: Context) {
            channel(context)
            shown = ""
            runCatching { ContextCompat.startForegroundService(context, Intent(context, GroupService::class.java)) }
        }

        fun stop(context: Context) {
            shown = ""
            withMic = false
            context.stopService(Intent(context, GroupService::class.java))
        }

        /** Walkie talkie on / off on this phone: the service asks for the microphone too (call while SyncUp is open). */
        internal fun useMic(context: Context, on: Boolean) {
            if (withMic == on) return
            withMic = on
            if (GroupLink.active) runCatching { ContextCompat.startForegroundService(context, Intent(context, GroupService::class.java)) }
        }

        private fun micAllowed(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        private fun foreground(service: Service, mic: Boolean): Boolean {
            val types = when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> 0
                mic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            return runCatching { ServiceCompat.startForeground(service, ID, card(service), types) }.isSuccess
        }

        fun refresh(context: Context) {
            if (!GroupLink.active) return
            val key = "${GroupLink.statusLine()}|${GroupLink.modeStatus()}|${GroupLink.role}|${WatchParty.stage}"
            if (key == shown) return
            shown = key
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            runCatching { NotificationManagerCompat.from(context).notify(ID, card(context)) }
        }

        private fun card(context: Context): Notification {
            channel(context)
            val host = GroupLink.isHost
            val n = GroupLink.people.size
            val title = when (GroupLink.phase) {
                GroupPhase.JOINING -> "Joining ${GroupLink.groupName}"
                else -> "${GroupLink.groupName} · ${GroupLink.mode.label}"
            }
            val text = when (GroupLink.phase) {
                GroupPhase.RECONNECTING -> "Reconnecting to ${GroupLink.hostName}…"
                GroupPhase.JOINING -> "Waiting for ${GroupLink.hostName} to let you in"
                else -> listOfNotNull(GroupLink.modeStatus(), "$n phone${if (n == 1) "" else "s"}").joinToString(" · ")
            }
            val b = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_group)
                .setContentTitle(title)
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context, 4600,
                        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_GROUP, true)
                            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            if (host && GroupLink.mode == GroupMode.WATCH && WatchParty.current != null) {
                b.addAction(0, if (WatchParty.stage == WpStage.PLAYING) "Pause" else "Play", action(context, ACTION_PAUSE, 1))
            }
            // Host: Switch opens the app on "Switch the group to…".
            if (host && GroupLink.inGroup) {
                b.addAction(
                    0, "Switch",
                    PendingIntent.getActivity(
                        context, 4602,
                        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_GROUP_SWITCH, true)
                            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            }
            b.addAction(0, if (host) "End group" else "Leave", action(context, ACTION_END, 2))
            return b.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE).build()
        }

        private fun action(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getService(
            context, code, Intent(context, GroupService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun channel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Group", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "While you're in a group (Music party, Watch together, Walkie talkie, Share files, Group photos)"
                        setSound(null, null)
                        enableVibration(false)
                        setShowBadge(false)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    },
                )
            }
        }
    }
}
