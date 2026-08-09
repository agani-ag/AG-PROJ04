package com.agani.syncup

/**
 * Tracks whether the whole app went to the background, so the app-lock re-locks only
 * when the user actually leaves the app — not when navigating to our own WebView.
 */
object AppLock {
    @Volatile
    var lockPending = false
}
