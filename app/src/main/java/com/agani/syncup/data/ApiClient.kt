package com.agani.syncup.data

import com.agani.syncup.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    // Fallback base URL used until Firebase Remote Config provides `api_base_url` at launch.
    // (Temporary Cloudflare tunnel → local Django; changes when the tunnel restarts.)
    private const val FALLBACK_BASE_URL =
        "https://numbers-rankings-jason-drug.trycloudflare.com/app/v1/"

    @Volatile
    private var baseUrl: String = FALLBACK_BASE_URL

    @Volatile
    private var cached: ApiService? = null

    /** Set the base URL (e.g. from Remote Config). Rebuilds the client on next use if changed. */
    fun setBaseUrl(url: String) {
        val normalized = if (url.endsWith("/")) url else "$url/"
        if (normalized != baseUrl) {
            baseUrl = normalized
            cached = null
        }
    }

    val service: ApiService
        get() = cached ?: build().also { cached = it }

    private fun build(): ApiService {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
        if (BuildConfig.DEBUG) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC },
            )
        }
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(builder.build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
