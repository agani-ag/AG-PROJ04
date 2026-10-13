package com.agani.syncup.notifications

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.agani.syncup.data.ApiClient
import com.agani.syncup.data.NotificationsReadRequest
import com.agani.syncup.data.NotificationsResponse
import com.agani.syncup.data.TokenStore
import com.agani.syncup.push.DeviceRegistrar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The Notifications page's data: every push this user (signed in) or this phone (signed out) got,
 * from the server's Push Log — so it's there even if notifications were blocked or the phone was
 * off. [newCount] is the red number on the bell; the server keeps read state, so reading on one
 * phone clears it on the user's others.
 */
object Inbox {
    /** Pushes not opened yet — the bell's red number. */
    var newCount by mutableIntStateOf(0)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var app: Context? = null

    /** Once, at app start: lets a count of 0 clear SyncUp's pushes off the notification panel. */
    fun attach(context: Context) {
        app = context.applicationContext
    }

    fun setCount(count: Int) {
        val was = newCount
        newCount = count.coerceAtLeast(0)
        // All read (here or on the user's other phone): none of them belong in the panel any more,
        // so the app icon's badge goes too.
        if (newCount == 0 && was > 0) app?.let(PushTray::clearAll)
    }

    /** Signed in: the Bearer token. Signed out: this phone's id + secret (null until device/hello answered). */
    private class Auth(val bearer: String?, val deviceId: String?, val secret: String?)

    private fun auth(context: Context): Auth? {
        val store = TokenStore(context.applicationContext)
        store.token()?.let { return Auth("Bearer $it", null, null) }
        val secret = DeviceRegistrar.deviceSecret(context) ?: return null
        return Auth(null, store.deviceId(), secret)
    }

    /** One page of a tab ("new" / "read"), newest first; [before] = the last id already shown. */
    suspend fun page(context: Context, tab: String, before: String?): NotificationsResponse {
        val a = auth(context) ?: throw IllegalStateException("SyncUp hasn't connected yet. Check your internet and try again.")
        val response = try {
            ApiClient.service.notifications(a.bearer, a.deviceId, a.secret, tab, before)
        } catch (e: java.io.IOException) {
            throw Exception("No internet connection. Check your network and try again.")
        }
        setCount(response.newCount)
        return response
    }

    /** Mark these as read (or all of them). Updates [newCount] from the server's answer. */
    suspend fun markRead(context: Context, ids: List<String>? = null, all: Boolean = false) {
        val a = auth(context) ?: return
        val response = ApiClient.service.readNotifications(
            a.bearer, a.deviceId, a.secret, NotificationsReadRequest(ids = ids, all = if (all) true else null),
        )
        setCount(response.newCount)
    }

    /** Refresh the bell (signed out — signed in, GET /sync brings the count). Best-effort. */
    fun refreshCount(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                val a = auth(app) ?: return@launch
                setCount(ApiClient.service.notificationsCount(a.bearer, a.deviceId, a.secret).newCount)
            }
        }
    }

    /** A push was tapped: it's read now. Best-effort, in the background. */
    fun markReadFromPush(context: Context, logId: String) {
        val app = context.applicationContext
        scope.launch { runCatching { markRead(app, ids = listOf(logId)) } }
    }

    /** A push just arrived (app open): one more unread, then the real number from the server. */
    fun onPushArrived(context: Context) {
        setCount(newCount + 1)
        val app = context.applicationContext
        scope.launch {
            runCatching {
                val a = auth(app) ?: return@launch
                setCount(ApiClient.service.notificationsCount(a.bearer, a.deviceId, a.secret).newCount)
            }
        }
    }
}
