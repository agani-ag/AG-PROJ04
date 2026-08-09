package com.agani.syncup.push

import android.content.Context
import com.agani.syncup.BuildConfig
import com.agani.syncup.data.ApiClient
import com.agani.syncup.data.DeviceRegisterRequest
import com.agani.syncup.data.TokenStore
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Registers this device's current FCM token with the backend (only when logged in). */
object DeviceRegistrar {
    fun register(context: Context) {
        val store = TokenStore(context)
        val access = store.token() ?: return // not logged in — nothing to associate the device with
        val deviceId = store.deviceId()
        FirebaseMessaging.getInstance().token.addOnSuccessListener { fcm ->
            if (fcm.isNullOrBlank()) return@addOnSuccessListener
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    ApiClient.service.deviceRegister(
                        "Bearer $access",
                        DeviceRegisterRequest(deviceId, fcm, "android", BuildConfig.VERSION_NAME),
                    )
                }
            }
        }
    }
}
