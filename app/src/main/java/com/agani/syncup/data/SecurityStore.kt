package com.agani.syncup.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest

/**
 * Encrypted store for the app-lock PIN. Separate from [TokenStore] so logout
 * (which clears the auth store) does NOT remove the app lock.
 */
class SecurityStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun hasPin(): Boolean = !prefs.getString(KEY_PIN, null).isNullOrBlank()

    fun setPin(pin: String) = prefs.edit().putString(KEY_PIN, hash(pin)).apply()

    fun checkPin(pin: String): Boolean {
        val stored = prefs.getString(KEY_PIN, null) ?: return false
        return stored == hash(pin)
    }

    fun clearPin() = prefs.edit().remove(KEY_PIN).apply()

    private fun hash(pin: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val KEY_PIN = "app_pin"
    }
}
