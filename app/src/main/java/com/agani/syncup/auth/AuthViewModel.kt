package com.agani.syncup.auth

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agani.syncup.data.AuthRepository
import com.agani.syncup.data.TokenStore
import com.agani.syncup.data.UrlItem
import com.agani.syncup.data.User
import kotlinx.coroutines.launch

data class AuthState(
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val user: User? = null,
    val urls: List<UrlItem> = emptyList(),
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
    }

    fun login(email: String, password: String) {
        if (state.loading) return
        state = state.copy(loading = true, error = null)
        viewModelScope.launch {
            val result = repository.login(email, password)
            state = result.fold(
                onSuccess = { AuthState(user = it.user, urls = it.urls) },
                onFailure = { state.copy(loading = false, error = it.message ?: "Login failed") },
            )
            if (result.isSuccess) {
                // Register this device for push now that we have an auth token.
                com.agani.syncup.push.DeviceRegistrar.register(getApplication())
            }
        }
    }

    /** Re-fetches the link list so newly added links appear without logging out. */
    fun refresh() {
        if (state.refreshing || state.loading) return
        state = state.copy(refreshing = true, error = null)
        viewModelScope.launch {
            state = repository.refreshUrls().fold(
                onSuccess = { state.copy(urls = it, refreshing = false) },
                onFailure = { state.copy(refreshing = false, error = it.message ?: "Couldn't refresh") },
            )
        }
    }

    suspend fun changePassword(current: String, new: String): Result<Unit> =
        repository.changePassword(current, new)

    fun logout() {
        repository.logout()
        state = AuthState()
    }
}
