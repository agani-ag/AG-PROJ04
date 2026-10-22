package com.agani.syncup.data

import android.content.Context

/** v7 offers Light and Dark (Dark is pure black). SYSTEM and BLACK stay only so older saved or
 * synced values still read; [AppPrefs.themeMode] turns them into LIGHT / DARK. */
enum class ThemeMode { SYSTEM, LIGHT, DARK, BLACK }

/** Plain (non-sensitive) app preferences: theme choice + biometric toggle. */
class AppPrefs(private val context: Context) {
    private val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    /** Light or Dark (Settings → Appearance); Light by default. An older System choice becomes what
     * the phone uses now, and Black becomes Dark — saved, so it's asked only once. */
    fun themeMode(): ThemeMode {
        val saved = runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.LIGHT.name)!!) }.getOrDefault(ThemeMode.LIGHT)
        val mode = normalize(saved)
        if (mode != saved) setThemeMode(mode)
        return mode
    }

    fun setThemeMode(mode: ThemeMode) = prefs.edit().putString(KEY_THEME, normalize(mode).name).apply()

    /** Only LIGHT or DARK. */
    fun normalize(mode: ThemeMode): ThemeMode = when (mode) {
        ThemeMode.LIGHT -> ThemeMode.LIGHT
        ThemeMode.DARK, ThemeMode.BLACK -> ThemeMode.DARK
        ThemeMode.SYSTEM -> if ((context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES) ThemeMode.DARK else ThemeMode.LIGHT
    }

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
