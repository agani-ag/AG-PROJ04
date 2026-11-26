package com.agani.syncup.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Backend interface — see docs/api-contract.md. Extended as the backend grows. */
interface ApiService {
    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    /** Self sign-up — same response as login. 403 while sign-up is off, 409 when already registered. */
    @POST("auth/signup")
    suspend fun signup(@Body body: SignupRequest): LoginResponse

    /** Revoke this session's token on the server (sign-out). */
    @POST("auth/logout")
    suspend fun logout(@Header("Authorization") auth: String): SimpleOk

    /** Add / change email or phone (current password required) and set the username. */
    @POST("account/profile")
    suspend fun updateProfile(@Header("Authorization") auth: String, @Body body: ProfileUpdateRequest): ProfileResponse

    /** Live "is this username free?" while typing. */
    @GET("account/username-check")
    suspend fun usernameCheck(@Header("Authorization") auth: String, @Query("u") username: String): UsernameCheckResponse

    /** Partners that added this user, with their status. */
    @GET("account/partners")
    suspend fun partners(@Header("Authorization") auth: String): PartnersResponse

    /** Enable a partner with the password that partner gave the user (5 wrong tries → 15-min wait). */
    @POST("account/partners/{id}/enable")
    suspend fun enablePartner(
        @Header("Authorization") auth: String,
        @Path("id") id: String,
        @Body body: PartnerEnableRequest,
    ): PartnerResult

    @POST("account/partners/{id}/disable")
    suspend fun disablePartner(@Header("Authorization") auth: String, @Path("id") id: String): PartnerResult

    /** Install check-in (launch + FCM token change). Auth optional: with it the account is attached. */
    @POST("device/hello")
    suspend fun deviceHello(@Header("Authorization") auth: String?, @Body body: DeviceHelloRequest): DeviceHelloResponse

    /** Usage time heartbeat: every 30 s while SyncUp is open, and once as it closes (Share usage time on). */
    @POST("device/beat")
    suspend fun deviceBeat(@Body body: DeviceBeatRequest): SimpleOk

    /** Sign-out: detach the account from this install (it keeps public broadcasts). */
    @POST("devices/unregister")
    suspend fun deviceUnregister(@Header("Authorization") auth: String, @Body body: DeviceUnregisterRequest): SimpleOk

    /** Browser sync: upload local changes, download the account's changes since the cursor. */
    @POST("browser/sync")
    suspend fun browserSync(@Header("Authorization") auth: String, @Body body: BrowserSyncRequest): BrowserSyncResponse

    /** Settings → Delete synced data (this phone keeps its own copy). */
    @POST("browser/sync/delete")
    suspend fun browserSyncDelete(@Header("Authorization") auth: String): SimpleOk

    /** Re-fetch the current user's link list (used by pull-to-refresh / refresh button). */
    @GET("account/urls")
    suspend fun urls(@Header("Authorization") auth: String): List<UrlItem>


    /** Combined refresh: user details + links + chat badge + config in one call. */
    @GET("sync")
    suspend fun sync(@Header("Authorization") auth: String): SyncResponse

    @POST("account/change-password")
    suspend fun changePassword(
        @Header("Authorization") auth: String,
        @Body body: ChangePasswordRequest,
    )

    /** User-initiated account deletion: the server erases the account and everything kept for it, for good. */
    @POST("account/delete")
    suspend fun deleteAccount(@Header("Authorization") auth: String)

    /** Server-driven config: version gate, announcement, support email. Unauthenticated. */
    @GET("config")
    suspend fun config(): ConfigResponse

    /** The home page's shortcut catalogue, for everyone. Sending the version we have → 304 when unchanged. */
    @GET("dns")
    suspend fun dns(@Query("v") version: String?): retrofit2.Response<DnsCatalogResponse>

    @GET("shortcuts")
    suspend fun shortcuts(@Query("v") version: String?): retrofit2.Response<ShortcutCatalogResponse>

    /** The admin's general links, for everyone (signed out: the SyncUp section). Sending the version we have → 304. */
    @GET("general-links")
    suspend fun generalLinks(@Query("v") version: String?): retrofit2.Response<GeneralLinksResponse>

    /** Live radio channels for everyone, signed in or not (empty when the admin has Radio off). */
    @GET("radio/channels")
    suspend fun radioChannels(): RadioChannelsResponse

    /** Register this device's FCM token for push. */
    @POST("devices/register")
    suspend fun deviceRegister(
        @Header("Authorization") auth: String,
        @Body body: DeviceRegisterRequest,
    )

    /** Reminders for the current user (+ broadcasts). Scheduled locally on the device.
     *  device_id lets the backend record this device's last reminder-sync time. */
    @GET("reminders")
    suspend fun reminders(
        @Header("Authorization") auth: String,
        @Query("device_id") deviceId: String,
    ): List<ReminderDto>

    /** Report reminder delivery back to the backend (synced / fired). */
    @POST("reminders/ack")
    suspend fun ackReminders(
        @Header("Authorization") auth: String,
        @Body body: ReminderAckRequest,
    )

    /** Fetch a partner verification prompt to render on the phone. */
    @GET("action/{id}")
    suspend fun getAction(@Header("Authorization") auth: String, @Path("id") id: String): ActionEnvelope

    /** Submit the user's response (entered code / selected number) to a verification prompt. */
    @POST("action/{id}/respond")
    suspend fun respondAction(
        @Header("Authorization") auth: String,
        @Path("id") id: String,
        @Body body: ActionRespondRequest,
    ): SimpleOk

    /** Mint a one-time signed URL for the web chat page (opened in the WebView). */
    @GET("chat/session")
    suspend fun chatSession(@Header("Authorization") auth: String): ChatSessionResponse

    /** Unread admin-message count for the chat button badge. */
    @GET("chat/unread")
    suspend fun chatUnread(@Header("Authorization") auth: String): ChatUnreadResponse

    // Notifications page. Signed in: the Bearer token. Signed out: this phone's id + secret (from
    // device/hello) instead — a null header is left out.
    @GET("notifications")
    suspend fun notifications(
        @Header("Authorization") auth: String?,
        @Header("X-Device-Id") deviceId: String?,
        @Header("X-Device-Secret") deviceSecret: String?,
        @Query("tab") tab: String,
        @Query("before") before: String?,
        @Query("limit") limit: Int = 30,
    ): NotificationsResponse

    @POST("notifications/read")
    suspend fun readNotifications(
        @Header("Authorization") auth: String?,
        @Header("X-Device-Id") deviceId: String?,
        @Header("X-Device-Secret") deviceSecret: String?,
        @Body body: NotificationsReadRequest,
    ): NotificationsCountResponse

    @GET("notifications/count")
    suspend fun notificationsCount(
        @Header("Authorization") auth: String?,
        @Header("X-Device-Id") deviceId: String?,
        @Header("X-Device-Secret") deviceSecret: String?,
    ): NotificationsCountResponse

    // ---- Tools → Cloudinary (the server only signs; the phone talks to Cloudinary itself) ----
    @GET("cloud/access")
    suspend fun cloudAccess(@Header("Authorization") auth: String): com.agani.syncup.cloud.CloudAccess

    /** Everything accounts only: the API key for Cloudinary's full API on this phone (kept in memory). */
    @POST("cloud/credentials")
    suspend fun cloudCredentials(@Header("Authorization") auth: String): com.agani.syncup.cloud.CloudCreds

    @GET("cloud/files")
    suspend fun cloudFiles(
        @Header("Authorization") auth: String,
        @Query("folder") folder: String? = null,
        @Query("path") path: String? = null,
    ): com.agani.syncup.cloud.CloudListingDto

    @POST("cloud/sign")
    suspend fun cloudSign(
        @Header("Authorization") auth: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>,
    ): com.agani.syncup.cloud.SignedRequest

    @POST("cloud/uploaded")
    suspend fun cloudUploaded(@Header("Authorization") auth: String, @Body body: com.google.gson.JsonObject): com.google.gson.JsonObject

    @POST("cloud/deleted")
    suspend fun cloudDeleted(@Header("Authorization") auth: String, @Body body: Map<String, @JvmSuppressWildcards Any?>): com.google.gson.JsonObject

    @POST("cloud/renamed")
    suspend fun cloudRenamed(@Header("Authorization") auth: String, @Body body: Map<String, @JvmSuppressWildcards Any?>): com.google.gson.JsonObject

    @POST("cloud/folders")
    suspend fun cloudFolderCreate(@Header("Authorization") auth: String, @Body body: Map<String, @JvmSuppressWildcards Any?>): com.google.gson.JsonObject

    @POST("cloud/folders/delete")
    suspend fun cloudFolderDelete(@Header("Authorization") auth: String, @Body body: Map<String, @JvmSuppressWildcards Any?>): com.google.gson.JsonObject

    @GET("cloud/accounts")
    suspend fun cloudAccounts(@Header("Authorization") auth: String): com.agani.syncup.cloud.CloudPeople

    @GET("cloud/pending")
    suspend fun cloudPending(@Header("Authorization") auth: String): com.agani.syncup.cloud.CloudPending

    /** The Storage details: the person's own folder by kind and file type, and the shared plan. */
    @GET("cloud/stats")
    suspend fun cloudStats(@Header("Authorization") auth: String): com.agani.syncup.cloud.CloudStats

    /** Everything accounts pass on Cloudinary's usage report, so everyone sees the plan's use. */
    @POST("cloud/usage")
    suspend fun cloudUsage(@Header("Authorization") auth: String, @Body body: com.google.gson.JsonObject): com.google.gson.JsonObject
}
