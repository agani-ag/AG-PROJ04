package com.agani.syncup

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class SyncUpApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)

        // Fires only when the WHOLE app goes to the background (not on internal navigation).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                AppLock.lockPending = true
            }
        })
    }
}
