package com.agani.syncup.data

import com.google.gson.annotations.SerializedName

data class LoginRequest(
    val email: String,
    val password: String,
)

data class User(
    val id: String,
    val name: String,
    val email: String,
)

data class UrlItem(
    val id: String,
    val title: String,
    val url: String,
    val icon: String? = null,
    val description: String? = null,
)

data class LoginResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String? = null,
    val user: User,
    val urls: List<UrlItem> = emptyList(),
)

data class ChangePasswordRequest(
    @SerializedName("current_password") val currentPassword: String,
    @SerializedName("new_password") val newPassword: String,
)

data class DeviceRegisterRequest(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("fcm_token") val fcmToken: String,
    val platform: String = "android",
    @SerializedName("app_version") val appVersion: String? = null,
)

data class ConfigResponse(
    @SerializedName("min_supported_version") val minSupportedVersion: Int = 0,
    @SerializedName("latest_version") val latestVersion: Int = 0,
    @SerializedName("support_email") val supportEmail: String = "",
)
