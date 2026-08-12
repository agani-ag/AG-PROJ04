package com.agani.syncup.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/** Backend interface — see docs/api-contract.md. Extended as the backend grows. */
interface ApiService {
    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    /** Re-fetch the current user's link list (used by pull-to-refresh / refresh button). */
    @GET("account/urls")
    suspend fun urls(@Header("Authorization") auth: String): List<UrlItem>

    /** Self-manage: add a link (only when can_manage_links). Returns the refreshed list. */
    @POST("account/links")
    suspend fun addLink(
        @Header("Authorization") auth: String,
        @Body body: AddLinkRequest,
    ): List<UrlItem>

    /** Self-manage: remove a user-added link. Returns the refreshed list. */
    @POST("account/links/{id}/delete")
    suspend fun removeLink(
        @Header("Authorization") auth: String,
        @Path("id") id: String,
    ): List<UrlItem>

    @POST("account/change-password")
    suspend fun changePassword(
        @Header("Authorization") auth: String,
        @Body body: ChangePasswordRequest,
    )

    /** User-initiated account deletion (backend deactivates the account + revokes tokens). */
    @POST("account/delete")
    suspend fun deleteAccount(@Header("Authorization") auth: String)

    /** Server-driven config: version gate, announcement, support email. Unauthenticated. */
    @GET("config")
    suspend fun config(): ConfigResponse

    /** Register this device's FCM token for push. */
    @POST("devices/register")
    suspend fun deviceRegister(
        @Header("Authorization") auth: String,
        @Body body: DeviceRegisterRequest,
    )

    /** Reminders for the current user (+ broadcasts). Scheduled locally on the device. */
    @GET("reminders")
    suspend fun reminders(@Header("Authorization") auth: String): List<ReminderDto>

    /** Report reminder delivery back to the backend (synced / fired). */
    @POST("reminders/ack")
    suspend fun ackReminders(
        @Header("Authorization") auth: String,
        @Body body: ReminderAckRequest,
    )

    /** Mint a one-time signed URL for the web chat page (opened in the WebView). */
    @GET("chat/session")
    suspend fun chatSession(@Header("Authorization") auth: String): ChatSessionResponse

    /** Unread admin-message count for the chat button badge. */
    @GET("chat/unread")
    suspend fun chatUnread(@Header("Authorization") auth: String): ChatUnreadResponse
}
