package com.agani.syncup.data

import com.google.gson.Gson
import kotlinx.coroutines.delay

/**
 * Handles login and session. In [USE_MOCK] mode the app runs fully without a
 * backend (returns a demo user + demo URLs), so the UI can be built and tested
 * now. Flip [USE_MOCK] to false once the real login API is live.
 */
class AuthRepository(private val tokenStore: TokenStore) {

    data class Session(
        val token: String,
        val user: User,
        val urls: List<UrlItem>,
    )

    private val gson = Gson()

    suspend fun login(email: String, password: String): Result<Session> = runCatching {
        if (USE_MOCK) {
            delay(700)
            require(email.isNotBlank() && password.isNotBlank()) { "Enter your email and password" }
            Session(
                token = "mock-token-123",
                user = User(id = "demo-1", name = "Demo User", email = email.trim()),
                urls = demoUrls(),
            ).also(::persist)
        } else {
            val resp = ApiClient.service.login(LoginRequest(email.trim(), password))
            Session(resp.accessToken, resp.user, resp.urls).also(::persist)
        }
    }

    fun restore(): Session? {
        val json = tokenStore.session() ?: return null
        return runCatching { gson.fromJson(json, Session::class.java) }.getOrNull()
    }

    fun logout() = tokenStore.clear()

    private fun persist(session: Session) {
        tokenStore.saveToken(session.token)
        tokenStore.saveSession(gson.toJson(session))
    }

    /** Demo links (replaced by the API's URL list in production). Each exercises a permission. */
    private fun demoUrls(): List<UrlItem> = listOf(
        UrlItem("1", "Google", "https://www.google.com", description = "General web browsing"),
        UrlItem(
            "2", "Camera & Microphone Test",
            "https://webrtc.github.io/samples/src/content/getusermedia/gum/",
            description = "Grants camera + microphone",
        ),
        UrlItem(
            "3", "Location Test", "https://browserleaks.com/geo",
            description = "Grants device location",
        ),
        UrlItem(
            "4", "File Upload Test",
            "https://www.w3schools.com/tags/tryit.asp?filename=tryhtml_input_type_file",
            description = "Opens the file picker",
        ),
    )

    companion object {
        /** Mock mode: the app runs without a backend. Set false when the API is live. */
        const val USE_MOCK = true
    }
}
