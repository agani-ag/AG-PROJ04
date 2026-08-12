package com.agani.syncup.data

import com.agani.syncup.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    // Fallback base URL — ONLY used if Firebase Remote Config `api_base_url` is empty/unreachable.
    // The live value comes from Remote Config (set it in /mobile/remote-config), which wins over this.
    private const val FALLBACK_BASE_URL =
        "https://microman2000.pythonanywhere.com/app/v1/"

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
        // A 401 on an *authenticated* request means our token is invalid/expired → force re-login.
        // (Login itself has no Authorization header, so bad-credentials 401s never trigger this.)
        builder.addInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            if (response.code == 401 && request.header("Authorization") != null) {
                SessionManager.notifyUnauthorized()
            }
            response
        }
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
