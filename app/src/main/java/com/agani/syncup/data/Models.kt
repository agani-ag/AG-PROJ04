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

data class AnnouncementDto(
    val active: Boolean = false,
    val title: String = "",
    val message: String = "",
)

data class ConfigResponse(
    @SerializedName("min_supported_version") val minSupportedVersion: Int = 0,
    @SerializedName("latest_version") val latestVersion: Int = 0,
    @SerializedName("support_email") val supportEmail: String = "",
    @SerializedName("support_phone") val supportPhone: String = "",
    @SerializedName("privacy_policy_url") val privacyPolicyUrl: String = "",
    val announcement: AnnouncementDto? = null,
)

/** Delivery receipt the app sends back: event is "synced" (downloaded) or "fired" (shown). */
data class ReminderAckRequest(
    @SerializedName("device_id") val deviceId: String,
    val event: String,
    @SerializedName("reminder_ids") val reminderIds: List<String>,
)

/** A reminder authored on the server, fired locally on the device via AlarmManager. */
data class ReminderDto(
    val id: String,
    val title: String = "",
    val body: String = "",
    @SerializedName("link_url") val linkUrl: String = "",
    @SerializedName("link_title") val linkTitle: String = "",
    @SerializedName("image_url") val imageUrl: String = "",
    @SerializedName("scheduled_at_ms") val scheduledAtMs: Long = 0L,
    val recurrence: String = "once", // once | daily
)
