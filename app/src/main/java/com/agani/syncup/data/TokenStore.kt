package com.agani.syncup.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/** Encrypted storage for the auth token and session (backed by the Android Keystore). */
class TokenStore(context: Context) {

    // Plain prefs for a stable per-install device id (must survive logout/clear()).
    private val devicePrefs: SharedPreferences =
        context.getSharedPreferences("device_prefs", Context.MODE_PRIVATE)

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "auth_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun saveToken(token: String) = prefs.edit().putString(KEY_TOKEN, token).apply()
    fun token(): String? = prefs.getString(KEY_TOKEN, null)

    fun saveSession(json: String) = prefs.edit().putString(KEY_SESSION, json).apply()
    fun session(): String? = prefs.getString(KEY_SESSION, null)

    fun clear() = prefs.edit().clear().apply()

    /** Stable per-install id, generated once and preserved across logout. */
    fun deviceId(): String {
        var id = devicePrefs.getString(KEY_DEVICE_ID, null)
        if (id.isNullOrBlank()) {
            id = UUID.randomUUID().toString()
            devicePrefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    private companion object {
        const val KEY_TOKEN = "token"
        const val KEY_SESSION = "session"
        const val KEY_DEVICE_ID = "device_id"
    }
}
