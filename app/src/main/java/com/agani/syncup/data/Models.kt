package com.agani.syncup.data

import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

/** How the user signs in (v6): the backend treats a missing mode as email (what v5 sends). */
enum class LoginMode(val wire: String, val label: String) {
    EMAIL("email", "Email"),
    PHONE("phone", "Phone"),
    USERNAME("username", "Username"),
}

data class LoginRequest(
    @SerializedName("login_mode") val loginMode: String,
    val login: String,
    val password: String,
    // Binds the session to this install, so the admin can sign out one device.
    @SerializedName("device_id") val deviceId: String? = null,
    @SerializedName("app_version") val appVersion: String? = null,
)

/** Self sign-up (only while the admin has "Allow sign-up" on). Email and/or phone, no verification. */
data class SignupRequest(
    val name: String,
    val email: String?,
    val phone: String?,
    val password: String,
    @SerializedName("accept_privacy") val acceptPrivacy: Boolean,
    @SerializedName("device_id") val deviceId: String? = null,
    @SerializedName("app_version") val appVersion: String? = null,
)

// Gson skips Kotlin defaults, so every field newer than v5 is nullable (older saved sessions lack them).
data class User(
    val id: String,
    val name: String,
    val email: String,
    val phone: String? = null,
    val username: String? = null,
    val source: String? = null, // admin | partner | self
    // Put on every Normal/Work page as window.SyncUp.token (never in Incognito); a site that also has
    // the SyncUp notify key can push to this user with it. Blank until the backend sends it.
    @SerializedName("notify_token") val notifyToken: String? = null,
) {
    /** What the user signs in with, for display: email, else phone, else @username. */
    val loginLabel: String
        get() = email.ifBlank { phone?.takeIf { it.isNotBlank() }?.let(::formatPhone) ?: username?.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "" }
}

/** "+919876543210" → "+91 98765 43210" (anything else is shown as stored). */
fun formatPhone(raw: String): String =
    if (raw.length == 13 && raw.startsWith("+91")) "+91 ${raw.substring(3, 8)} ${raw.substring(8)}" else raw

/** Add / change email or phone (needs the current password) or set the username. Null = unchanged. */
data class ProfileUpdateRequest(
    val email: String? = null,
    val phone: String? = null,
    val username: String? = null,
    @SerializedName("current_password") val currentPassword: String? = null,
)

data class ProfileResponse(val success: Boolean = false, val user: User? = null)

data class UsernameCheckResponse(
    val available: Boolean = false,
    val username: String? = null,
    val message: String? = null,
)

/** A partner (e.g. GSTSync) that added this user. Only enabled partners reach the user. */
data class PartnerDto(
    val id: String = "",
    val partner: String = "",
    val status: String = "", // not_enabled | enabled | disabled | suspended
    @SerializedName("added_at") val addedAt: String? = null,
    @SerializedName("locked_until") val lockedUntil: String? = null,
)

data class PartnersResponse(val partners: List<PartnerDto>? = null)

data class PartnerEnableRequest(val password: String)

data class PartnerResult(val success: Boolean = false, val partner: PartnerDto? = null)

/** Every install checks in on launch (signed in or not). */
data class DeviceHelloRequest(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("device_secret") val deviceSecret: String?,
    @SerializedName("fcm_token") val fcmToken: String?,
    @SerializedName("app_version") val appVersion: String,
    @SerializedName("os_version") val osVersion: String,
    val locale: String,
    @SerializedName("time_zone") val timeZone: String,
    @SerializedName("device_model") val deviceModel: String,
    @SerializedName("webview_version") val webviewVersion: String,
    @SerializedName("install_source") val installSource: String,
    @SerializedName("notifications_allowed") val notificationsAllowed: Boolean,
    @SerializedName("updates_enabled") val updatesEnabled: Boolean,
)

data class DeviceHelloResponse(
    val success: Boolean = false,
    @SerializedName("device_secret") val deviceSecret: String? = null,
)

data class DeviceUnregisterRequest(@SerializedName("device_id") val deviceId: String)

// ---------------------------------------------------------------- browser sync
/** One synced item: kind is bookmark | history | shortcut | setting. */
data class SyncChange(
    val kind: String,
    val key: String,
    val data: JsonObject?,
    @SerializedName("updated_ms") val updatedMs: Long,
    val deleted: Boolean,
)

data class SyncTab(val title: String? = null, val url: String? = null)

/** This phone's Sync switches (Settings → Sync), shown to the admin per device. */
data class SyncStateDto(val enabled: Boolean, val types: List<String>)

data class TabsSnapshot(
    @SerializedName("device_name") val deviceName: String,
    val tabs: List<SyncTab>,
    val state: SyncStateDto? = null,
)

data class BrowserSyncRequest(
    @SerializedName("device_id") val deviceId: String,
    val since: String,
    val changes: List<SyncChange>,
    val tabs: TabsSnapshot?,
)

/** Another phone of the same user, with its open Normal tabs. */
data class OtherDevice(
    @SerializedName("device_id") val deviceId: String? = null,
    @SerializedName("device_name") val deviceName: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null,
    val tabs: List<SyncTab>? = null,
)

data class BrowserSyncResponse(
    val cursor: String? = null,
    val changes: List<SyncChange>? = null,
    @SerializedName("other_devices") val otherDevices: List<OtherDevice>? = null,
)

data class UrlItem(
    val id: String,
    val title: String,
    val url: String,
    val icon: String? = null,
    val description: String? = null,
    // Where the link comes from, for grouping on the Work home: "partner" or "admin" (null = admin),
    // and the partner's display name ("GSTSync"). Optional until the backend sends them.
    val source: String? = null,
    @SerializedName("source_name") val sourceName: String? = null,
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
    // Radio on/off for everyone (the admin's Radio page); shown as a tab in Music when signed in.
    @SerializedName("radio_enabled") val radioEnabled: Boolean = false,
    // "Allow sign-up" on the admin's Config page — shows "Create account" on the sign-in sheet.
    @SerializedName("signup_enabled") val signupEnabled: Boolean = false,
    val announcement: AnnouncementDto? = null,
)

/** A live radio station (GET /radio/channels). */
data class RadioChannel(
    val id: String = "",
    val name: String = "",
    @SerializedName("stream_url") val streamUrl: String = "",
    @SerializedName("now_playing") val nowPlaying: String = "",
    val listeners: Int = 0,
)

data class RadioChannelsResponse(
    val enabled: Boolean = false,
    val channels: List<RadioChannel> = emptyList(),
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
    // Partners that added this user and wait to be enabled (badge on the Partners rows).
    @SerializedName("partners_waiting") val partnersWaiting: Int = 0,
    // Partner connections of any status — the Partners page is shown only when there's one.
    @SerializedName("partner_count") val partnerCount: Int = 0,
)

/** A partner verification prompt shown on the phone (GET /action/{id}). */
data class ActionParams(
    val code: String? = null,          // type = otp
    val length: Int? = null,           // type = code
    val numbers: List<String>? = null, // type = number
    val body: String? = null,                              // type = notice — full text to read
    @SerializedName("cta_url") val ctaUrl: String? = null, // type = notice — optional button link
    @SerializedName("cta_label") val ctaLabel: String? = null,
    @SerializedName("approve_label") val approveLabel: String? = null, // type = approve
    @SerializedName("reject_label") val rejectLabel: String? = null,
)

data class ActionDto(
    val id: String = "",
    val type: String = "",             // otp | code | number | notice | approve
    val title: String = "",
    val message: String = "",
    val params: ActionParams = ActionParams(),
    val status: String = "pending",
)

data class ActionEnvelope(val action: ActionDto? = null)

/** Body for POST /action/{id}/respond — the code entered or number selected. */
data class ActionRespondRequest(val value: String)

/** Minimal success response for endpoints that just acknowledge. */
data class SimpleOk(val success: Boolean = false)

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

/** The home page's shortcut catalogue (GET shortcuts, no sign-in). [version] unchanged → 304. */
data class ShortcutCatalogResponse(
    val version: String = "",
    val categories: List<ShortcutCategoryDto> = emptyList(),
)

data class ShortcutCategoryDto(
    val id: Long = 0,
    val name: String = "",
    val shortcuts: List<HomeShortcutDto> = emptyList(),
)

data class HomeShortcutDto(
    val id: Long = 0,
    val title: String = "",
    val url: String = "",
)
