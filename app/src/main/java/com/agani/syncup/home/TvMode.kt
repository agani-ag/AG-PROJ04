package com.agani.syncup.home

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import com.agani.syncup.BuildConfig

/**
 * SyncUp on a TV (Android TV / Google TV, user 16–18 Nov 2026): the same app, opening on its own TV main page
 * ([TvHome]) that the remote moves around. A debug build can be told to look like a TV on a phone, for testing:
 * `adb shell am start -n com.agani.syncup/.MainActivity --ez tv_preview true` (false turns it off again).
 */
object TvMode {
    private const val PREFS = "tv_mode"
    private const val KEY_PREVIEW = "preview"

    fun isTv(context: Context): Boolean {
        if (BuildConfig.DEBUG && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PREVIEW, false)) return true
        val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    /** Debug builds only: show the TV main page on a phone (see the class note). */
    fun setPreview(context: Context, on: Boolean) {
        if (!BuildConfig.DEBUG) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PREVIEW, on).apply()
    }
}
