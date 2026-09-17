package com.agani.syncup

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.agani.syncup.push.SyncUpMessagingService

class SyncUpApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        createVerificationChannel()

        // Load the user's auto-lock grace preference so the lock decision uses it from the start.
        AppLock.graceMs = com.agani.syncup.data.AppPrefs(this).lockGraceSeconds() * 1000L

        // Fires only when the WHOLE app goes to the background/foreground (not on internal
        // navigation). The grace period in AppLock decides whether a return actually re-locks.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                AppLock.onBackground()
            }

            override fun onStart(owner: LifecycleOwner) {
                AppLock.onForeground()
            }
        })
    }

    /** Create the high-importance Verification channel at startup so backgrounded/killed
     *  verification pushes render as a heads-up banner (a channel created only lazily in the
     *  foreground path wouldn't exist when the system renders the notification). */
    private fun createVerificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SyncUpMessagingService.VERIFY_CHANNEL_ID,
                "Verification",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "One-time verification prompts (OTP / code / number)" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
