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
            val resp = try {
                ApiClient.service.login(LoginRequest(email.trim(), password))
            } catch (e: java.io.IOException) {
                throw Exception("No internet connection. Check your network and try again.")
            } catch (e: retrofit2.HttpException) {
                throw Exception(if (e.code() == 401) "Invalid email or password" else "Login failed. Please try again.")
            }
            Session(resp.accessToken, resp.user, resp.urls).also(::persist)
        }
    }

    fun restore(): Session? {
        val json = tokenStore.session() ?: return null
        return runCatching { gson.fromJson(json, Session::class.java) }.getOrNull()
    }

    /** Re-fetches the link list from the backend and updates the saved session. */
    suspend fun refreshUrls(): Result<List<UrlItem>> = runCatching {
        val newUrls = if (USE_MOCK) {
            delay(500)
            demoUrls()
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

    /** Re-fetch the current user's details (name/email) so admin edits show up on refresh. */
    suspend fun refreshUser(): Result<User> = runCatching {
        if (USE_MOCK) {
            delay(300)
            restore()?.user ?: throw Exception("No session")
        } else {
            val user = try {
                ApiClient.service.me("Bearer ${tokenStore.token().orEmpty()}")
            } catch (e: java.io.IOException) {
                throw Exception("No internet connection. Check your network and try again.")
            }
            // Persist so the refreshed details survive an app restart.
            restore()?.let { persist(it.copy(user = user)) }
            user
        }
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

    /** Add a self-managed link; persists + returns the refreshed list. */
    suspend fun addLink(title: String, url: String, description: String): Result<List<UrlItem>> = runCatching {
        val newUrls = try {
            ApiClient.service.addLink(
                "Bearer ${tokenStore.token().orEmpty()}",
                AddLinkRequest(title.trim(), url.trim(), description.trim()),
            )
        } catch (e: retrofit2.HttpException) {
            throw Exception("Couldn't add the link. Check the title and an https:// URL.")
        } catch (e: java.io.IOException) {
            throw Exception("No internet connection. Try again.")
        }
        restore()?.let { persist(it.copy(urls = newUrls)) }
        newUrls
    }

    /** Remove a user-added link; persists + returns the refreshed list. */
    suspend fun removeLink(id: String): Result<List<UrlItem>> = runCatching {
        val newUrls = try {
            ApiClient.service.removeLink("Bearer ${tokenStore.token().orEmpty()}", id)
        } catch (e: retrofit2.HttpException) {
            throw Exception("Couldn't remove the link.")
        } catch (e: java.io.IOException) {
            throw Exception("No internet connection. Try again.")
        }
        restore()?.let { persist(it.copy(urls = newUrls)) }
        newUrls
    }

    suspend fun changePassword(current: String, new: String): Result<Unit> = runCatching {
        require(current.isNotBlank()) { "Enter your current password" }
        require(new.length >= 6) { "New password must be at least 6 characters" }
        if (USE_MOCK) {
            delay(600) // no backend yet — accept locally so the flow can be tested
        } else {
            ApiClient.service.changePassword(
                "Bearer ${tokenStore.token().orEmpty()}",
                ChangePasswordRequest(current, new),
            )
        }
    }

    /** Deletes (deactivates) the account server-side, then clears the local session. */
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

    private fun persist(session: Session) {
        tokenStore.saveToken(session.token)
        tokenStore.saveSession(gson.toJson(session))
    }

    /** Demo links (replaced by the API's URL list in production). Each exercises a permission. */
    private fun demoUrls(): List<UrlItem> = listOf(
        UrlItem("1", "Google", "https://www.google.com", description = "General web browsing"),
        UrlItem("2", "Camera & Microphone Test", "https://webrtc.github.io/samples/src/content/getusermedia/gum/", description = "Grants camera + microphone"),
        UrlItem("3", "Location Test", "https://browserleaks.com/geo", description = "Grants device location"),
        UrlItem("4", "File Upload Test", "https://the-internet.herokuapp.com/upload", description = "Opens the file picker"),
        UrlItem("5", "YouTube", "https://m.youtube.com", description = "Video playback test"),
        UrlItem("6", "Notification Test", "https://www.bennish.net/web-notifications.html", description = "Web notifications"),
        UrlItem("7", "Speed Test", "https://fast.com", description = "Network test"),
        UrlItem("8", "My Website", "https://microman2000.pythonanywhere.com", description = "Test website"),
        UrlItem("9", "New-Tab / Popup Test", "https://the-internet.herokuapp.com/windows", description = "Opens a new-tab link in a Custom Tab"),
        UrlItem("10", "Print Test", "data:text/html;base64,PCFkb2N0eXBlIGh0bWw+PG1ldGEgbmFtZT12aWV3cG9ydCBjb250ZW50PSJ3aWR0aD1kZXZpY2Utd2lkdGgsaW5pdGlhbC1zY2FsZT0xIj48ZGl2IHN0eWxlPSJmb250LWZhbWlseTpzeXN0ZW0tdWksc2Fucy1zZXJpZjt0ZXh0LWFsaWduOmNlbnRlcjtwYWRkaW5nOjU2cHggMjRweDtjb2xvcjojMGYxNzJhIj48aDI+UHJpbnQgVGVzdDwvaDI+PHAgc3R5bGU9ImNvbG9yOiM1YjY0NzIiPlRhcCB0aGUgYnV0dG9uIHRvIHByaW50IHRoaXMgcGFnZSAob3IgU2F2ZSBhcyBQREYpLjwvcD48YnV0dG9uIG9uY2xpY2s9IndpbmRvdy5wcmludCgpIiBzdHlsZT0iZm9udC1zaXplOjE4cHg7cGFkZGluZzoxNHB4IDI2cHg7Ym9yZGVyOjA7Ym9yZGVyLXJhZGl1czoxMnB4O2JhY2tncm91bmQ6IzI1NjNFQjtjb2xvcjojZmZmIj5QcmludCB0aGlzIHBhZ2U8L2J1dHRvbj48L2Rpdj4=", description = "Page with a Print button"),
        UrlItem("11", "UPI Test", "data:text/html;base64,PCFkb2N0eXBlIGh0bWw+PG1ldGEgbmFtZT0idmlld3BvcnQiIGNvbnRlbnQ9IndpZHRoPWRldmljZS13aWR0aCxpbml0aWFsLXNjYWxlPTEiPjxkaXYgc3R5bGU9ImZvbnQtZmFtaWx5OnN5c3RlbS11aSxzYW5zLXNlcmlmO3BhZGRpbmc6MjhweCAyMnB4O2NvbG9yOiMwZjE3MmE7bWF4LXdpZHRoOjQ2MHB4O21hcmdpbjphdXRvIj48aDIgc3R5bGU9InRleHQtYWxpZ246Y2VudGVyIj5VUEkgVGVzdDwvaDI+PGxhYmVsIHN0eWxlPSJkaXNwbGF5OmJsb2NrO21hcmdpbjoxMnB4IDAgNHB4O2ZvbnQtc2l6ZToxNHB4O2NvbG9yOiM1YjY0NzIiPlVQSSBJRCAoVlBBKTwvbGFiZWw+PGlucHV0IGlkPSJ2cGEiIHBsYWNlaG9sZGVyPSJuYW1lQGJhbmsiIHN0eWxlPSJ3aWR0aDoxMDAlO2JveC1zaXppbmc6Ym9yZGVyLWJveDtmb250LXNpemU6MTZweDtwYWRkaW5nOjEycHg7Ym9yZGVyOjFweCBzb2xpZCAjY2JkNWUxO2JvcmRlci1yYWRpdXM6MTBweCI+PGxhYmVsIHN0eWxlPSJkaXNwbGF5OmJsb2NrO21hcmdpbjoxNHB4IDAgNHB4O2ZvbnQtc2l6ZToxNHB4O2NvbG9yOiM1YjY0NzIiPkFtb3VudCAoSU5SKTwvbGFiZWw+PGlucHV0IGlkPSJhbXQiIHR5cGU9Im51bWJlciIgdmFsdWU9IjEuMDAiIHN0eWxlPSJ3aWR0aDoxMDAlO2JveC1zaXppbmc6Ym9yZGVyLWJveDtmb250LXNpemU6MTZweDtwYWRkaW5nOjEycHg7Ym9yZGVyOjFweCBzb2xpZCAjY2JkNWUxO2JvcmRlci1yYWRpdXM6MTBweCI+PGJ1dHRvbiBpZD0icGF5IiBzdHlsZT0id2lkdGg6MTAwJTttYXJnaW4tdG9wOjE4cHg7Zm9udC1zaXplOjE4cHg7cGFkZGluZzoxNHB4O2JvcmRlcjowO2JvcmRlci1yYWRpdXM6MTJweDtiYWNrZ3JvdW5kOiMyNTYzRUI7Y29sb3I6I2ZmZiI+UGF5IHZpYSBVUEk8L2J1dHRvbj48cCBpZD0ib3V0IiBzdHlsZT0ibWFyZ2luLXRvcDoxNnB4O2NvbG9yOiM1YjY0NzI7Zm9udC1zaXplOjE0cHgiPjwvcD48L2Rpdj48c2NyaXB0PmRvY3VtZW50LmdldEVsZW1lbnRCeUlkKCJwYXkiKS5vbmNsaWNrPWZ1bmN0aW9uKCl7dmFyIHZwYT1kb2N1bWVudC5nZXRFbGVtZW50QnlJZCgidnBhIikudmFsdWUudHJpbSgpO3ZhciBhbXQ9ZG9jdW1lbnQuZ2V0RWxlbWVudEJ5SWQoImFtdCIpLnZhbHVlLnRyaW0oKXx8IjEuMDAiO2lmKCF2cGEpe2RvY3VtZW50LmdldEVsZW1lbnRCeUlkKCJvdXQiKS5pbm5lclRleHQ9IlBsZWFzZSBlbnRlciBhIFVQSSBJRC4iO3JldHVybjt9dmFyIHVybD0idXBpOi8vcGF5P3BhPSIrZW5jb2RlVVJJQ29tcG9uZW50KHZwYSkrIiZwbj1TeW5jVXAlMjBUZXN0JmFtPSIrZW5jb2RlVVJJQ29tcG9uZW50KGFtdCkrIiZjdT1JTlImdHI9REVNTyIrRGF0ZS5ub3coKSsiJnRuPVN5bmNVcCUyMFVQSSUyMFRlc3QiO2RvY3VtZW50LmdldEVsZW1lbnRCeUlkKCJvdXQiKS5pbm5lclRleHQ9Ik9wZW5pbmcgeW91ciBVUEkgYXBwLiBDb21wbGV0ZSB0aGUgcGF5bWVudCB0aGVyZSwgdGhlbiB2ZXJpZnkgaXQgbWFudWFsbHkuIjt2YXIgYT1kb2N1bWVudC5jcmVhdGVFbGVtZW50KCJhIik7YS5ocmVmPXVybDtkb2N1bWVudC5ib2R5LmFwcGVuZENoaWxkKGEpO2EuY2xpY2soKTt9Ozwvc2NyaXB0Pg==", description = "Enter UPI ID + amount, opens UPI app"),

    )

    companion object {
        /** Mock mode: the app runs without a backend. Set false when the API is live. */
        const val USE_MOCK = false
    }
}
