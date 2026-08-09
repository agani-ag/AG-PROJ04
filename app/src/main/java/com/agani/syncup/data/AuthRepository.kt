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

    /** Re-fetches the link list from the backend and updates the saved session. */
    suspend fun refreshUrls(): Result<List<UrlItem>> = runCatching {
        val newUrls = if (USE_MOCK) {
            delay(500)
            demoUrls()
        } else {
            ApiClient.service.urls("Bearer ${tokenStore.token().orEmpty()}")
        }
        // Persist so the refreshed list survives an app restart.
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
