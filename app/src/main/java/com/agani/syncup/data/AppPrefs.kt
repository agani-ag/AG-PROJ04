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

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_BIOMETRIC = "biometric_enabled"
    }
}
