package com.agani.syncup.push

import com.agani.syncup.BuildConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings

/** Fetches `api_base_url` from Firebase Remote Config so the API host can change without a rebuild. */
object RemoteConfigManager {
    private const val KEY_BASE_URL = "api_base_url"

    suspend fun fetchBaseUrl(): String? {
        val rc = FirebaseRemoteConfig.getInstance()
        val settings = FirebaseRemoteConfigSettings.Builder()
            .setMinimumFetchIntervalInSeconds(if (BuildConfig.DEBUG) 0 else 3600)
            .build()
        runCatching { rc.setConfigSettingsAsync(settings).awaitResult() }
        runCatching { rc.setDefaultsAsync(mapOf(KEY_BASE_URL to "")).awaitResult() }
        runCatching { rc.fetchAndActivate().awaitResult() }
        return rc.getString(KEY_BASE_URL).ifBlank { null }
    }
}
