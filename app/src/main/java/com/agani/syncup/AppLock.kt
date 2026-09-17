package com.agani.syncup

import android.os.SystemClock

/**
 * Decides when the app-lock re-locks: only when the whole app was in the background for longer
 * than a short grace period. The grace absorbs the moments the user briefly leaves for something
 * we launched from inside the app — a file picker, camera, external link / custom tab, a
 * permission dialog — so returning from those doesn't throw up the lock screen. A genuine switch
 * away (Home, another app) for longer than the grace still re-locks.
 *
 * Rotation does NOT come through here (it's a config change, not a background), and MainActivity
 * now handles rotation without recreating — so neither rotation nor a quick WebView round-trip
 * trips the lock.
 */
object AppLock {
    // Brief absences under this don't re-lock (covers pickers, camera, external links, transitions).
    // Set from the user's Auto-lock preference (AppPrefs.lockGraceSeconds) at app start / on change.
    // 0 = re-lock on any background ("Immediately").
    @Volatile var graceMs = 30_000L

    @Volatile private var backgroundedAt = 0L

    /** True once the app has come back to the foreground after being away longer than the grace. */
    @Volatile var pendingLock = false

    /** Whole app went to the background. */
    fun onBackground() {
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    /** Whole app returned to the foreground — arm the lock only if the absence exceeded the grace. */
    fun onForeground() {
        if (backgroundedAt != 0L) {
            if (SystemClock.elapsedRealtime() - backgroundedAt >= graceMs) pendingLock = true
            backgroundedAt = 0L
        }
    }
}
