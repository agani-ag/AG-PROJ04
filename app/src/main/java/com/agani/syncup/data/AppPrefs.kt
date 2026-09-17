package com.agani.syncup.data

import android.content.Context

enum class ThemeMode { SYSTEM, LIGHT, DARK, BLACK }

/** Plain (non-sensitive) app preferences: theme choice + biometric toggle. */
class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    fun themeMode(): ThemeMode = runCatching {
        ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name)!!)
    }.getOrDefault(ThemeMode.SYSTEM)

    fun setThemeMode(mode: ThemeMode) = prefs.edit().putString(KEY_THEME, mode.name).apply()

    fun biometricEnabled(): Boolean = prefs.getBoolean(KEY_BIOMETRIC, false)
    fun setBiometricEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_BIOMETRIC, enabled).apply()

    /** Identity (content hash) of the last full-screen announcement the user dismissed. */
    fun acknowledgedAnnouncement(): String = prefs.getString(KEY_ANNOUNCEMENT_ACK, "") ?: ""
    fun setAcknowledgedAnnouncement(hash: String) =
        prefs.edit().putString(KEY_ANNOUNCEMENT_ACK, hash).apply()

    /** Auto-lock grace, in seconds: how long the app can be backgrounded before it re-locks. */
    fun lockGraceSeconds(): Int = prefs.getInt(KEY_LOCK_GRACE, DEFAULT_LOCK_GRACE)
    fun setLockGraceSeconds(seconds: Int) = prefs.edit().putInt(KEY_LOCK_GRACE, seconds).apply()

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_BIOMETRIC = "biometric_enabled"
        const val KEY_ANNOUNCEMENT_ACK = "announcement_ack"
        const val KEY_LOCK_GRACE = "lock_grace_seconds"
        const val DEFAULT_LOCK_GRACE = 30
    }
}
