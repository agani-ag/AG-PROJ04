package com.agani.syncup.push

import com.agani.syncup.data.ApiClient
import com.agani.syncup.data.ConfigResponse

object AppBootstrap {
    /** Apply the dynamic base URL from Remote Config (caller bounds this for a snappy splash). */
    suspend fun applyBaseUrl() {
        runCatching { RemoteConfigManager.fetchBaseUrl() }.getOrNull()?.let { ApiClient.setBaseUrl(it) }
    }

    /** Fetch server-driven config (version gate + announcement). Not time-bounded to the splash. */
    suspend fun fetchConfig(): ConfigResponse? =
        runCatching { ApiClient.service.config() }.getOrNull()
}
