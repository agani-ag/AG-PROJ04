package com.agani.syncup.push

import com.agani.syncup.data.ApiClient

data class BootstrapResult(val minSupportedVersion: Int)

/** Runs at launch: pulls the base URL from Remote Config, then the server config (version gate). */
object AppBootstrap {
    suspend fun run(): BootstrapResult {
        // 1) Dynamic base URL (keeps the FALLBACK if Remote Config is unavailable).
        runCatching { RemoteConfigManager.fetchBaseUrl() }.getOrNull()?.let { ApiClient.setBaseUrl(it) }
        // 2) Server-driven config for the force-update gate.
        val cfg = runCatching { ApiClient.service.config() }.getOrNull()
        return BootstrapResult(minSupportedVersion = cfg?.minSupportedVersion ?: 0)
    }
}
