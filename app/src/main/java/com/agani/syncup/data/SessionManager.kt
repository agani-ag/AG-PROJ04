package com.agani.syncup.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Signals that the backend rejected our token (HTTP 401 on an authenticated call) — i.e. the
 * session expired or was revoked. Observed by AuthViewModel, which clears the session and sends
 * the user back to Login. The app-lock PIN is left intact, so re-login is quick.
 */
object SessionManager {
    private val _unauthorized = MutableStateFlow(false)
    val unauthorized: StateFlow<Boolean> = _unauthorized

    fun notifyUnauthorized() { _unauthorized.value = true }
    fun reset() { _unauthorized.value = false }
}
