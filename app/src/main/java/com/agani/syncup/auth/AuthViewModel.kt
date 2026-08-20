package com.agani.syncup.auth

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agani.syncup.data.AppPrefs
import com.agani.syncup.data.AuthRepository
import com.agani.syncup.data.ReminderStore
import com.agani.syncup.data.SecurityStore
import com.agani.syncup.data.SessionManager
import com.agani.syncup.data.TokenStore
import com.agani.syncup.data.UrlItem
import com.agani.syncup.data.User
import com.agani.syncup.reminders.ReminderScheduler
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class AuthState(
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val user: User? = null,
    val urls: List<UrlItem> = emptyList(),
    // False until the links have been fetched fresh from the server this session (login or a
    // refresh). On a cold start we only have cached links until the first auto-refresh completes.
    val urlsLoaded: Boolean = false,
    // Unread admin chat messages — drives the chat button badge.
    val chatUnread: Int = 0,
) {
    val isLoggedIn: Boolean get() = user != null
}

class AuthViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = AuthRepository(TokenStore(app))

    var state by mutableStateOf(AuthState())
        private set

    init {
        repository.restore()?.let { session ->
            state = state.copy(user = session.user, urls = session.urls)
        }
        // A 401 on any authenticated call → token expired/revoked → sign out to Login.
        // The app-lock PIN is preserved, so the user can log back in quickly.
        viewModelScope.launch {
            SessionManager.unauthorized.collect { expired ->
                if (expired) {
                    if (state.isLoggedIn) logout()
                    SessionManager.reset()
                }
            }
        }
    }

    fun login(email: String, password: String) {
        if (state.loading) return
        state = state.copy(loading = true, error = null)
        viewModelScope.launch {
            val result = repository.login(email, password)
            state = result.fold(
                onSuccess = { AuthState(user = it.user, urls = it.urls, urlsLoaded = true) },
                onFailure = { state.copy(loading = false, error = it.message ?: "Login failed") },
            )
            if (result.isSuccess) {
                // Register this device for push now that we have an auth token.
                com.agani.syncup.push.DeviceRegistrar.register(getApplication())
            }
        }
    }

    /**
     * Re-fetches the link list so newly added links appear without logging out.
     * [silent] = triggered automatically on app open: no spinner, and failures are swallowed
     * (we keep the cached links) so opening the app offline doesn't flash an error.
     */
    fun refresh(silent: Boolean = false) {
        if (state.refreshing || state.loading) return
        if (!silent) state = state.copy(refreshing = true, error = null)
        viewModelScope.launch {
            state = repository.refreshUrls().fold(
                onSuccess = { state.copy(urls = it, refreshing = false, urlsLoaded = true) },
                onFailure = {
                    // Mark loaded either way so the UI (and kiosk decision) can proceed on cached data.
                    if (silent) state.copy(refreshing = false, urlsLoaded = true)
                    else state.copy(refreshing = false, urlsLoaded = true, message = it.message ?: "Couldn't refresh")
                },
            )
        }
    }

    /** Refresh the unread chat badge (silent — called on app open / foreground). */
    fun refreshChatUnread() {
        viewModelScope.launch {
            repository.chatUnread().onSuccess { count ->
                if (count != state.chatUnread) state = state.copy(chatUnread = count)
            }
        }
    }

    /** Refresh the signed-in user's details (name/email) — silent; picks up admin edits. */
    fun refreshUser() {
        viewModelScope.launch {
            repository.refreshUser().onSuccess { user ->
                if (user != state.user) state = state.copy(user = user)
            }
        }
    }

    /**
     * One combined refresh: user details + links + chat badge (updated in state), and the server
     * config handed to [onConfig]. Used by pull-to-refresh and foreground return.
     */
    fun syncAll(silent: Boolean, onConfig: (com.agani.syncup.data.ConfigResponse) -> Unit) {
        if (state.refreshing || state.loading) return
        if (!silent) state = state.copy(refreshing = true, error = null)
        viewModelScope.launch {
            repository.sync().fold(
                onSuccess = { s ->
                    state = state.copy(
                        user = s.user, urls = s.urls, chatUnread = s.chatUnread,
                        refreshing = false, urlsLoaded = true,
                    )
                    onConfig(s.config)
                },
                onFailure = {
                    // Keep cached data; just clear the spinner (mark loaded so the UI can proceed).
                    state = if (silent) state.copy(refreshing = false, urlsLoaded = true)
                    else state.copy(refreshing = false, urlsLoaded = true, message = it.message ?: "Couldn't refresh")
                },
            )
        }
    }

    /** Fetch the one-time chat URL to open in the WebView. */
    suspend fun chatSessionUrl(): Result<String> = repository.chatSessionUrl()

    /** Self-manage: add a link, then update the list. */
    suspend fun addLink(title: String, url: String, description: String): Result<Unit> {
        val result = repository.addLink(title, url, description)
        result.onSuccess { state = state.copy(urls = it, urlsLoaded = true) }
        return result.map { }
    }

    /** Self-manage: remove a user-added link, then update the list. */
    suspend fun removeLink(id: String): Result<Unit> {
        val result = repository.removeLink(id)
        result.onSuccess { state = state.copy(urls = it) }
        return result.map { }
    }

    fun clearMessage() {
        if (state.message != null) state = state.copy(message = null)
    }

    suspend fun changePassword(current: String, new: String): Result<Unit> =
        repository.changePassword(current, new)

    fun logout() {
        repository.logout()
        state = AuthState()
    }

    /** Deletes the account server-side, then wipes local session, PIN, and reminders. */
    suspend fun deleteAccount(): Result<Unit> {
        val result = repository.deleteAccount()
        if (result.isSuccess) {
            val ctx = getApplication<Application>()
            SecurityStore(ctx).clearPin()
            AppPrefs(ctx).setBiometricEnabled(false)
            ReminderStore(ctx).clear()
            ReminderScheduler.rescheduleAll(ctx, emptyList())
            state = AuthState()
        }
        return result
    }
}
