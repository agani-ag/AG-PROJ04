package com.agani.syncup

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class SyncUpApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)

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
}
