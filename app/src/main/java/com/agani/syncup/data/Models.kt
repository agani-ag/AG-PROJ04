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
    @SerializedName("can_manage_links") val canManageLinks: Boolean = false,
)

data class UrlItem(
    val id: String,
    val title: String,
    val url: String,
    val icon: String? = null,
    val description: String? = null,
    @SerializedName("can_remove") val canRemove: Boolean = false,
    // Non-empty when the link opts into partner push — injected as window.SyncUp.token.
    @SerializedName("notify_token") val notifyToken: String = "",
    // Keep the screen awake on this page so its audio keeps playing (radio/music links).
    @SerializedName("keep_screen_on") val keepScreenOn: Boolean = false,
)

/** Body for adding a self-managed link. */
data class AddLinkRequest(
    val title: String,
    val url: String,
    val description: String = "",
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
    // When true, show a full-screen announcement (like the update screen) instead of the banner.
    val fullscreen: Boolean = false,
    // Full-screen only: no dismiss button; shows every launch (maintenance/outage notice).
    val blocking: Boolean = false,
)

data class ConfigResponse(
    @SerializedName("min_supported_version") val minSupportedVersion: Int = 0,
    @SerializedName("support_email") val supportEmail: String = "",
    @SerializedName("support_phone") val supportPhone: String = "",
    @SerializedName("privacy_policy_url") val privacyPolicyUrl: String = "",
    // Global on/off for the in-app chat button (users + support agents).
    @SerializedName("chat_enabled") val chatEnabled: Boolean = true,
    val announcement: AnnouncementDto? = null,
)

/** Delivery receipt the app sends back: event is "synced" (downloaded) or "fired" (shown). */
data class ReminderAckRequest(
    @SerializedName("device_id") val deviceId: String,
    val event: String,
    @SerializedName("reminder_ids") val reminderIds: List<String>,
)

/** Response of GET /sync — one call that refreshes user details, links, chat badge and config. */
data class SyncResponse(
    val user: User,
    val urls: List<UrlItem> = emptyList(),
    @SerializedName("chat_unread") val chatUnread: Int = 0,
    val config: ConfigResponse = ConfigResponse(),
)

/** Response of GET chat/session — a one-time signed URL the app opens in its WebView. */
data class ChatSessionResponse(val url: String = "")

/** Response of GET chat/unread — count of unread admin messages (drives the chat badge). */
data class ChatUnreadResponse(val count: Int = 0)

/** A reminder authored on the server, fired locally on the device via AlarmManager. */
data class ReminderDto(
    val id: String,
    val title: String = "",
    val body: String = "",
    @SerializedName("link_url") val linkUrl: String = "",
    @SerializedName("link_title") val linkTitle: String = "",
    @SerializedName("image_url") val imageUrl: String = "",
    @SerializedName("scheduled_at_ms") val scheduledAtMs: Long = 0L,
    val recurrence: String = "once", // once | daily | interval
    // "Repeat N times" mode: gap between fires (ms) and total fire count (0 = unlimited).
    @SerializedName("repeat_interval_ms") val repeatIntervalMs: Long = 0L,
    @SerializedName("repeat_count") val repeatCount: Int = 0,
)
