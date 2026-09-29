package com.agani.syncup.push

import android.content.Context
import android.os.Build
import android.webkit.WebView
import androidx.core.app.NotificationManagerCompat
import com.agani.syncup.BuildConfig
import com.agani.syncup.data.ApiClient
import com.agani.syncup.data.DeviceHelloRequest
import com.agani.syncup.data.TokenStore
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.TimeZone

/**
 * The install registry. Every install checks in ("device hello") on launch and whenever its FCM
 * token changes, signed in or not, so SyncUp can reach everyone. With a session the account is
 * attached; sign-out detaches it (see AuthRepository.serverLogout).
 *
 * Broadcasts use FCM topics: "all" while SyncUp updates are on, plus "users" (signed in) or
 * "public" (signed out). Account pushes and reminders go to the device token and are unaffected by
 * the updates switch.
 */
object DeviceRegistrar {
    private const val TOPIC_ALL = "syncup_all"
    private const val TOPIC_PUBLIC = "syncup_public"
    private const val TOPIC_USERS = "syncup_users"
    private const val PREFS = "device_prefs" // shared with TokenStore's device id
    private const val KEY_SECRET = "device_secret"
    private const val KEY_UPDATES = "updates_enabled"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** "SyncUp updates" (Settings → Notifications): news and announcements from SyncUp. */
    fun updatesEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_UPDATES, true)

    fun setUpdatesEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_UPDATES, on).apply()
        hello(context)
    }

    /** Check in with the backend and refresh the topic subscriptions. Best-effort, never throws. */
    fun hello(context: Context) {
        val app = context.applicationContext
        runCatching {
            val store = TokenStore(app)
            applyTopics(app, signedIn = store.token() != null)
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { fcm -> send(app, fcm) }
                .addOnFailureListener { send(app, null) } // no Play services: still counts the install
        }.onFailure { send(app, null) }
    }

    /** Kept for existing callers: signing in / a token change is just another hello. */
    fun register(context: Context) = hello(context)

    private fun send(app: Context, fcm: String?) {
        scope.launch {
            runCatching {
                val store = TokenStore(app)
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val access = store.token()
                val resp = ApiClient.service.deviceHello(
                    access?.let { "Bearer $it" },
                    DeviceHelloRequest(
                        deviceId = store.deviceId(),
                        deviceSecret = prefs.getString(KEY_SECRET, null),
                        fcmToken = fcm?.takeIf { it.isNotBlank() },
                        appVersion = BuildConfig.VERSION_NAME,
                        osVersion = "Android ${Build.VERSION.RELEASE}",
                        locale = Locale.getDefault().toLanguageTag(),
                        timeZone = TimeZone.getDefault().id,
                        deviceModel = deviceModel(),
                        webviewVersion = webViewVersion(),
                        installSource = installSource(app),
                        notificationsAllowed = NotificationManagerCompat.from(app).areNotificationsEnabled(),
                        updatesEnabled = updatesEnabled(app),
                    ),
                )
                resp.deviceSecret?.takeIf { it.isNotBlank() }?.let { prefs.edit().putString(KEY_SECRET, it).apply() }
            }
        }
    }

    /** Subscribe to the broadcast topics that match the updates switch and the sign-in state. */
    fun applyTopics(context: Context, signedIn: Boolean) {
        runCatching {
            val fm = FirebaseMessaging.getInstance()
            if (!updatesEnabled(context)) {
                listOf(TOPIC_ALL, TOPIC_PUBLIC, TOPIC_USERS).forEach { fm.unsubscribeFromTopic(it) }
                return
            }
            fm.subscribeToTopic(TOPIC_ALL)
            fm.subscribeToTopic(if (signedIn) TOPIC_USERS else TOPIC_PUBLIC)
            fm.unsubscribeFromTopic(if (signedIn) TOPIC_PUBLIC else TOPIC_USERS)
        }
    }

    /** A readable name for this phone ("Google Pixel 8"), also used for "Tabs from other devices". */
    fun deviceModel(): String {
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        return if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim()
    }

    private fun webViewVersion(): String = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WebView.getCurrentWebViewPackage()?.versionName.orEmpty() else ""
    }.getOrDefault("")

    @Suppress("DEPRECATION")
    private fun installSource(context: Context): String = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(context.packageName).installingPackageName.orEmpty()
        } else {
            pm.getInstallerPackageName(context.packageName).orEmpty()
        }
    }.getOrDefault("")
}
