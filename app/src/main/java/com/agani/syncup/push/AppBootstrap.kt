package com.agani.syncup.push

import com.agani.syncup.data.ApiClient
import com.agani.syncup.data.ConfigResponse

object AppBootstrap {
    /** At start, without waiting: the test server, or the address Remote Config gave last time. */
    fun applyKnownBaseUrl() {
        if (com.agani.syncup.BuildConfig.DEBUG_API_BASE_URL.isNotBlank()) {
            ApiClient.setBaseUrl(com.agani.syncup.BuildConfig.DEBUG_API_BASE_URL)
            return
        }
        RemoteConfigManager.lastBaseUrl()?.let { ApiClient.setBaseUrl(it) }
    }

    /** Apply the dynamic base URL from Remote Config (the caller bounds how long it waits). */
    suspend fun applyBaseUrl() {
        // A debug build made with -PsyncupApi=… talks to that server instead (local testing).
        if (com.agani.syncup.BuildConfig.DEBUG_API_BASE_URL.isNotBlank()) {
            ApiClient.setBaseUrl(com.agani.syncup.BuildConfig.DEBUG_API_BASE_URL)
            return
        }
        runCatching { RemoteConfigManager.fetchBaseUrl() }.getOrNull()?.let { ApiClient.setBaseUrl(it) }
    }

    /** Fetch server-driven config (version gate + announcement). Not time-bounded to the splash. */
    suspend fun fetchConfig(): ConfigResponse? =
        runCatching { ApiClient.service.config() }.getOrNull()
}
