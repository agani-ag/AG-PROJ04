package com.agani.syncup.forest

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.agani.syncup.MainActivity
import com.agani.syncup.R
import java.util.Calendar

/**
 * Forest's notifications: the gentle daily reminder (off until switched on in Your calm week), "calm
 * time done" when a session ends with SyncUp in the background, and the quiet card while one runs.
 * Same alarms as the Calendar's (setAndAllowWhileIdle, no special permission).
 */
object ForestReminder {
    /** The reminder and "calm time done": a soft, normal notification. */
    const val CHANNEL = "forest"
    /** The card while calm time runs: silent. */
    const val CHANNEL_SESSION = "forest_session"
    const val SESSION_ID = 0x5F0001
    private const val REMIND_ID = 0x5F0002
    const val DONE_ID = 0x5F0003

    const val ACTION_REMIND = "com.agani.syncup.forest.REMIND"
    const val ACTION_END = "com.agani.syncup.forest.END"

    /** Set (or cancel) the next daily reminder. */
    fun schedule(context: Context) {
        val ctx = context.applicationContext
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(remindPending(ctx))
        if (!ForestStore.ready) ForestStore.init(ctx)
        if (!ForestStore.reminderOn) return
        val at = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, ForestStore.reminderMinutes / 60)
            set(Calendar.MINUTE, ForestStore.reminderMinutes % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() + 30_000) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, remindPending(ctx))
    }

    /** "5 calm minutes?" — not on a day the goal is already met, nor during calm time. */
    internal fun remind(context: Context) {
        val ctx = context.applicationContext
        if (!ForestStore.ready) ForestStore.init(ctx)
        CalmSession.init(ctx)
        schedule(ctx)
        if (CalmSession.active) return
        val today = ForestStore.minutesOn(FDays.today())
        if (today >= ForestStore.goalMinutes) return
        val tree = ForestStore.growing().maxByOrNull { it.lastCareMs }
        val text = when {
            tree != null -> "Your ${tree.label.lowercase()} ${tree.stage().title.lowercase()} would like a few calm minutes"
            ForestStore.trees.isEmpty() -> "Plant a seed with 5 calm minutes"
            else -> "Your forest is waiting — just 5 minutes"
        }
        notify(ctx, REMIND_ID, "A few calm minutes?", text)
    }

    internal fun notify(ctx: Context, id: Int, title: String, text: String) {
        ensureChannels(ctx)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setColor(0xFF2F5D3A.toInt())
            .setAutoCancel(true)
            .setContentIntent(openForest(ctx, id))
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }

    fun openForest(ctx: Context, request: Int): PendingIntent = PendingIntent.getActivity(
        ctx, request,
        Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_FOREST, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Forest", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "The gentle daily reminder and \"calm time done\", from Tools → Forest"
                },
            )
        }
        if (nm.getNotificationChannel(CHANNEL_SESSION) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_SESSION, "Forest calm time", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while calm time runs"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun remindPending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0x5F0F01, Intent(ctx, ForestAlarmReceiver::class.java).setAction(ACTION_REMIND),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Calm time's end: [idle] = the one that may also come while the phone sleeps deeply (it can be later). */
    internal fun endPending(ctx: Context, idle: Boolean = false): PendingIntent = PendingIntent.getBroadcast(
        ctx, if (idle) 0x5F0F03 else 0x5F0F02, Intent(ctx, ForestAlarmReceiver::class.java).setAction(ACTION_END),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** The daily reminder, and calm time's end when nothing else noticed it (SyncUp closed, screen off). */
class ForestAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ForestReminder.ACTION_REMIND -> ForestReminder.remind(context)
            ForestReminder.ACTION_END -> {
                val done = goAsync()
                Thread {
                    try {
                        if (!ForestStore.ready) ForestStore.init(context)
                        CalmSession.init(context)
                        CalmSession.checkEnd(context)
                        Thread.sleep(4_000) // let the bowl ring out
                    } finally {
                        done.finish()
                    }
                }.start()
            }
        }
    }
}
