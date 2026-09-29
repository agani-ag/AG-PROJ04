package com.agani.syncup.auth

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agani.syncup.data.AppPrefs
import com.agani.syncup.data.AuthRepository
import com.agani.syncup.data.LoginMode
import com.agani.syncup.data.PartnerDto
import com.agani.syncup.data.ProfileUpdateRequest
import com.agani.syncup.data.ReminderStore
import com.agani.syncup.data.SecurityStore
import com.agani.syncup.data.SessionManager
import com.agani.syncup.data.TokenStore
import com.agani.syncup.data.UrlItem
import com.agani.syncup.data.User
import com.agani.syncup.reminders.ReminderScheduler
import com.agani.syncup.sync.BrowserSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    // Partners that added this user and wait to be enabled — badge on the Partners rows.
    val partnersWaiting: Int = 0,
) {
    val isLoggedIn: Boolean get() = user != null
}

class AuthViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = AuthRepository(TokenStore(app))

    // Outlives the screen: the server half of sign-out must finish even if the UI goes away.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
                    // The token is already dead server-side — just clear this device.
                    if (state.isLoggedIn) logout(tellServer = false)
                    SessionManager.reset()
                }
            }
        }
    }

    fun login(mode: LoginMode, login: String, password: String) {
        if (state.loading) return
        state = state.copy(loading = true, error = null)
        viewModelScope.launch { finishSignIn(repository.login(mode, login, password), "Sign-in failed") }
    }

    /** Create an account (only offered while the admin allows sign-up); signs it in on success. */
    fun signup(name: String, email: String, phone: String, password: String) {
        if (state.loading) return
        state = state.copy(loading = true, error = null)
        viewModelScope.launch { finishSignIn(repository.signup(name, email, phone, password), "Couldn't create the account") }
    }

    private fun finishSignIn(result: Result<AuthRepository.Session>, fallback: String) {
        state = result.fold(
            onSuccess = { AuthState(user = it.user, urls = it.urls, urlsLoaded = true) },
            onFailure = { state.copy(loading = false, error = it.message ?: fallback) },
        )
        // Attach this install to the account (push, per-device sign-out) and switch broadcast topics.
        if (result.isSuccess) com.agani.syncup.push.DeviceRegistrar.hello(getApplication())
    }

    /** Clear the sign-in/sign-up error (e.g. when switching between the two). */
    fun clearError() {
        if (state.error != null) state = state.copy(error = null)
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
                        partnersWaiting = s.partnersWaiting,
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

    /** Partner verification prompt: fetch the prompt / submit the user's response. */
    suspend fun fetchAction(id: String) = repository.fetchAction(id)

    suspend fun radioChannels() = repository.radioChannels()
    suspend fun respondAction(id: String, value: String) = repository.respondAction(id, value)

    fun clearMessage() {
        if (state.message != null) state = state.copy(message = null)
    }

    suspend fun changePassword(current: String, new: String): Result<Unit> =
        repository.changePassword(current, new)

    // ---- profile: email / phone (current password required) and username
    suspend fun updateProfile(req: ProfileUpdateRequest): Result<User> =
        repository.updateProfile(req).onSuccess { state = state.copy(user = it) }

    suspend fun checkUsername(name: String) = repository.checkUsername(name)

    // ---- partners page
    suspend fun partners(): Result<List<PartnerDto>> =
        repository.partners().onSuccess { list -> setPartnersWaiting(list.count { it.status == "not_enabled" }) }

    suspend fun enablePartner(id: String, password: String) = repository.enablePartner(id, password)
    suspend fun disablePartner(id: String) = repository.disablePartner(id)

    fun setPartnersWaiting(n: Int) {
        if (n != state.partnersWaiting) state = state.copy(partnersWaiting = n)
    }

    /**
     * Sign out. This phone keeps its Normal bookmarks/history and stops syncing. When [tellServer],
     * pending changes are uploaded first, then the install is detached from the account (it keeps
     * public broadcasts) and the token revoked — in the background, so sign-out is instant offline.
     */
    fun logout(tellServer: Boolean = true) {
        val token = repository.token()
        BrowserSync.onSignedOut()
        repository.logout()
        state = AuthState()
        val app = getApplication<Application>()
        com.agani.syncup.push.DeviceRegistrar.applyTopics(app, signedIn = false)
        if (tellServer && token != null) {
            ioScope.launch {
                BrowserSync.finalFlush(token)
                repository.serverLogout(token)
            }
        }
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
            BrowserSync.onSignedOut()
            com.agani.syncup.push.DeviceRegistrar.applyTopics(ctx, signedIn = false)
            state = AuthState()
        }
        return result
    }
}
