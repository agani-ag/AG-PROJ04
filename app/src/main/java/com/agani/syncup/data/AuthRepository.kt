package com.agani.syncup.data

import com.agani.syncup.BuildConfig
import com.google.gson.Gson
import kotlinx.coroutines.delay

/**
 * Handles login and session. In [USE_MOCK] mode the app runs fully without a
 * backend (returns a demo user with no links), so the UI can be built and tested
 * now. Flip [USE_MOCK] to false once the real login API is live.
 */
class AuthRepository(private val tokenStore: TokenStore) {

    data class Session(
        val token: String,
        val user: User,
        val urls: List<UrlItem>,
    )

    private val gson = Gson()
    private val auth get() = "Bearer ${tokenStore.token().orEmpty()}"

    suspend fun login(mode: LoginMode, login: String, password: String): Result<Session> = runCatching {
        if (USE_MOCK) {
            delay(700)
            require(login.isNotBlank() && password.isNotBlank()) { "Enter your details and password" }
            Session(
                token = "mock-token-123",
                user = User(id = "demo-1", name = "Demo User", email = login.trim()),
                urls = emptyList(),
            ).also(::persist)
        } else {
            val resp = call("Sign-in failed. Please try again.") {
                ApiClient.service.login(
                    LoginRequest(mode.wire, login.trim(), password, tokenStore.deviceId(), BuildConfig.VERSION_NAME),
                )
            }
            Session(resp.accessToken, resp.user, resp.urls).also(::persist)
        }
    }

    /** Create a self sign-up account; signs it in on success (same response as login). */
    suspend fun signup(name: String, email: String, phone: String, password: String): Result<Session> = runCatching {
        val resp = call("Couldn't create the account. Please try again.") {
            ApiClient.service.signup(
                SignupRequest(
                    name = name.trim(),
                    email = email.trim().ifBlank { null },
                    phone = phone.trim().ifBlank { null },
                    password = password,
                    acceptPrivacy = true,
                    deviceId = tokenStore.deviceId(),
                    appVersion = BuildConfig.VERSION_NAME,
                ),
            )
        }
        Session(resp.accessToken, resp.user, resp.urls).also(::persist)
    }

    /** Add/change email or phone (with the current password) or set the username. */
    suspend fun updateProfile(req: ProfileUpdateRequest): Result<User> = runCatching {
        val user = call("Couldn't save. Please try again.") { ApiClient.service.updateProfile(auth, req) }.user
            ?: throw Exception("Couldn't save. Please try again.")
        restore()?.let { persist(it.copy(user = user)) }
        user
    }

    suspend fun checkUsername(name: String): Result<UsernameCheckResponse> = runCatching {
        call("Couldn't check the username") { ApiClient.service.usernameCheck(auth, name.trim()) }
    }

    suspend fun partners(): Result<List<PartnerDto>> = runCatching {
        call("Couldn't load partners") { ApiClient.service.partners(auth) }.partners.orEmpty()
    }

    suspend fun enablePartner(id: String, password: String): Result<PartnerDto?> = runCatching {
        call("Couldn't enable. Please try again.") { ApiClient.service.enablePartner(auth, id, PartnerEnableRequest(password)) }.partner
    }

    suspend fun disablePartner(id: String): Result<PartnerDto?> = runCatching {
        call("Couldn't disable. Please try again.") { ApiClient.service.disablePartner(auth, id) }.partner
    }

    /**
     * Tell the server this install signed out: detach the account from the device (it keeps public
     * broadcasts) and revoke the token. Best-effort — signing out works offline too.
     */
    suspend fun serverLogout(token: String) {
        val bearer = "Bearer $token"
        runCatching { ApiClient.service.deviceUnregister(bearer, DeviceUnregisterRequest(tokenStore.deviceId())) }
        runCatching { ApiClient.service.logout(bearer) }
    }

    fun token(): String? = tokenStore.token()

    fun restore(): Session? {
        val json = tokenStore.session() ?: return null
        return runCatching { gson.fromJson(json, Session::class.java) }.getOrNull()
    }

    /** Re-fetches the link list from the backend and updates the saved session. */
    suspend fun refreshUrls(): Result<List<UrlItem>> = runCatching {
        val newUrls = if (USE_MOCK) {
            delay(500)
            emptyList()
        } else {
            try {
                ApiClient.service.urls("Bearer ${tokenStore.token().orEmpty()}")
            } catch (e: java.io.IOException) {
                throw Exception("No internet connection. Check your network and try again.")
            }
        }
        // Persist so the refreshed list survives an app restart.
        restore()?.let { persist(it.copy(urls = newUrls)) }
        newUrls
    }

    /** Combined refresh (user + links + chat badge + config) in one request. */
    suspend fun sync(): Result<SyncResponse> = runCatching {
        val resp = try {
            ApiClient.service.sync("Bearer ${tokenStore.token().orEmpty()}")
        } catch (e: java.io.IOException) {
            throw Exception("No internet connection. Check your network and try again.")
        }
        // Persist the fresh user + links so they survive an app restart.
        restore()?.let { persist(it.copy(user = resp.user, urls = resp.urls)) }
        resp
    }

    /** Fetch the live radio channel list (empty when the admin has Radio off). */
    suspend fun radioChannels(): Result<RadioChannelsResponse> = runCatching {
        ApiClient.service.radioChannels()
    }

    /** Fetch a partner verification prompt (OTP / code / number) by id.
     *  No runCatching — it would also swallow the coroutine CancellationException and surface it
     *  as a bogus "coroutine scope left the composition" error. Catch only real failures. */
    suspend fun fetchAction(id: String): Result<ActionDto> = try {
        val resp = ApiClient.service.getAction("Bearer ${tokenStore.token().orEmpty()}", id)
        Result.success(resp.action ?: throw Exception("This verification is no longer available."))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Submit the user's response to a verification prompt. */
    suspend fun respondAction(id: String, value: String): Result<Unit> = try {
        ApiClient.service.respondAction(
            "Bearer ${tokenStore.token().orEmpty()}", id, ActionRespondRequest(value),
        )
        Result.success(Unit)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** One-time signed URL for the web chat page (opened in the in-app WebView). */
    suspend fun chatSessionUrl(): Result<String> =
        // No runCatching here: it would also swallow coroutine CancellationException and surface it
        // as a bogus "coroutine scope left the composition" error. Catch only real network/HTTP errors.
        try {
            Result.success(ApiClient.service.chatSession("Bearer ${tokenStore.token().orEmpty()}").url)
        } catch (e: retrofit2.HttpException) {
            // 404 → the server this app is pointed at has no chat endpoint (e.g. still production).
            Result.failure(Exception("Chat unavailable (HTTP ${e.code()}). The app may be pointed at a server without chat."))
        } catch (e: java.io.IOException) {
            Result.failure(Exception("Can't reach the server. Check the connection / base URL."))
        }

    /** Unread admin-message count for the chat badge. */
    suspend fun chatUnread(): Result<Int> = runCatching {
        ApiClient.service.chatUnread("Bearer ${tokenStore.token().orEmpty()}").count
    }

    suspend fun changePassword(current: String, new: String): Result<Unit> = runCatching {
        require(current.isNotBlank()) { "Enter your current password" }
        require(new.length >= 6) { "New password must be at least 6 characters" }
        if (USE_MOCK) {
            delay(600) // no backend yet — accept locally so the flow can be tested
        } else {
            call("Couldn't change the password") {
                ApiClient.service.changePassword(auth, ChangePasswordRequest(current, new))
            }
        }
    }

    /** Deletes the account server-side (for good, with all its data), then clears the local session. */
    suspend fun deleteAccount(): Result<Unit> = runCatching {
        if (USE_MOCK) {
            delay(400)
        } else {
            try {
                ApiClient.service.deleteAccount("Bearer ${tokenStore.token().orEmpty()}")
            } catch (e: java.io.IOException) {
                throw Exception("No internet connection. Check your network and try again.")
            }
        }
        tokenStore.clear()
    }

    fun logout() = tokenStore.clear()

    /**
     * Run an API call and turn failures into a readable message: no network → a network hint;
     * an HTTP error → the backend's own `message` (e.g. "This email is already registered").
     */
    private suspend fun <T> call(fallback: String, block: suspend () -> T): T = try {
        block()
    } catch (e: java.io.IOException) {
        throw Exception("No internet connection. Check your network and try again.")
    } catch (e: retrofit2.HttpException) {
        throw Exception(serverMessage(e) ?: fallback)
    }

    private fun serverMessage(e: retrofit2.HttpException): String? = runCatching {
        val body = e.response()?.errorBody()?.string().orEmpty()
        gson.fromJson(body, com.google.gson.JsonObject::class.java)?.get("message")?.asString
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun persist(session: Session) {
        tokenStore.saveToken(session.token)
        tokenStore.saveSession(gson.toJson(session))
    }

    companion object {
        /** Mock mode: the app runs without a backend. Set false when the API is live. */
        const val USE_MOCK = false
    }
}
