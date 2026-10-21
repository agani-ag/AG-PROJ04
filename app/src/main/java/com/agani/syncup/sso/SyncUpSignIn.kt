package com.agani.syncup.sso

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.agani.syncup.data.ApiClient
import com.agani.syncup.data.SsoCodeRequest
import com.agani.syncup.data.SsoCodeResponse
import com.agani.syncup.data.SsoInfo
import com.agani.syncup.data.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Sign in with SyncUp — for SyncHub's tools (Hibiscus, Pocket Money, J6).
 *
 * On the websites the server lists (general links with "Sign in with SyncUp" on) WebPlatform puts
 *   window.SyncUp = { v: 1, account: { signedIn, id }, signIn({ silent }) }
 * on the main page — never the person's name or email, never in Incognito. signIn() reaches [request]
 * over a message channel that says which site really sent it. The first time, the app's own sheet
 * asks (once for all of SyncHub); after that it's quiet. The page gets a one-time code (one minute,
 * that site only), and its server swaps the code with SyncUp's server for who the person is.
 *
 * The id is random and never changes; the page compares it to tell "signed in to SyncUp", "someone
 * else now" and "signed out" — the 'syncupaccount' event says when it changes while a page is open.
 */
object SyncUpSignIn {
    /** The page's message channel to the app. */
    const val BRIDGE = "SyncUpSignIn"
    private const val PREFS = "syncup_sign_in"

    var hosts by mutableStateOf<List<String>>(emptyList())
        private set
    var id by mutableStateOf<String?>(null)
        private set
    var connected by mutableStateOf(false)
        private set

    /** The sheet waiting for a yes or no (shown by [SignInConsentHost]). */
    var prompt by mutableStateOf<Prompt?>(null)
        private set

    class Prompt(val client: String, val host: String, val answer: (Boolean) -> Unit)

    /** Called with true when the person changed (signed in, out, or someone else). */
    private val listeners = CopyOnWriteArraySet<(Boolean) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(context)
        hosts = runCatching { JSONArray(p.getString("hosts", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } }
            .getOrDefault(emptyList()).filter(::validHost)
        id = p.getString("id", null)
        connected = p.getBoolean("connected", false)
    }

    /** The server's latest (null = the response didn't say; keep what we have). */
    fun update(context: Context, info: SsoInfo?) {
        if (info == null) return
        set(context, info.hosts.orEmpty().filter(::validHost).distinct().sorted(), info.id?.takeIf { it.isNotBlank() }, info.connected)
    }

    /** Signed out: no id any more (the websites stay; the phone's check-in refreshes them). */
    fun signedOut(context: Context) = set(context, hosts, null, false)

    private fun set(context: Context, newHosts: List<String>, newId: String?, newConnected: Boolean) {
        val personChanged = newId != id
        if (newHosts == hosts && !personChanged && newConnected == connected) return
        hosts = newHosts
        id = newId
        connected = newConnected
        prefs(context).edit()
            .putString("hosts", JSONArray(newHosts).toString())
            .putString("id", newId)
            .putBoolean("connected", newConnected)
            .apply()
        main.post { listeners.forEach { it(personChanged) } }
    }

    fun addListener(listener: (Boolean) -> Unit) = listeners.add(listener)
    fun removeListener(listener: (Boolean) -> Unit) = listeners.remove(listener)

    // A test build for a local server (-PsyncupApi=http://…) also allows plain http, so a SyncHub
    // running on the same computer can be tried on the emulator. Normal builds: https only.
    private val localTest = com.agani.syncup.BuildConfig.DEBUG_API_BASE_URL.startsWith("http://")

    /** The pages that get window.SyncUp.signIn: each website's own https address, with and without "www.". */
    fun origins(): Set<String> = hosts.flatMap {
        listOf("https://$it", "https://www.$it") + if (localTest) listOf("http://$it") else emptyList()
    }.toSet()

    /** The sign-in website a page (or a message's origin) belongs to — https only — or null. */
    fun hostOf(url: String?): String? {
        val uri = runCatching { Uri.parse(url ?: return null) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() != "https" && !(localTest && uri.scheme?.lowercase() == "http")) return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        return host.takeIf { it in hosts }
    }

    private fun account(): String = JSONObject().put("signedIn", id != null).put("id", id ?: JSONObject.NULL).toString()

    /** Puts the current account on a page — and with [event], tells it the person changed. */
    fun accountScript(event: Boolean): String {
        val a = account()
        return "window.SyncUp = Object.assign(window.SyncUp || {}, { v: 1, account: $a });" +
            if (event) "window.dispatchEvent(new CustomEvent('syncupaccount', { detail: $a }));" else ""
    }

    /** The whole of window.SyncUp for a sign-in website, run at the start of each of its pages. */
    fun script(): String = accountScript(false) + """
        (function () {
          var S = window.SyncUp;
          if (S.signIn || typeof $BRIDGE === 'undefined') return;
          var n = 0, waiting = {};
          $BRIDGE.onmessage = function (e) {
            var m; try { m = JSON.parse(e.data); } catch (x) { return; }
            var done = waiting[m.n]; if (!done) return;
            delete waiting[m.n];
            done(m.code ? { code: m.code } : null);
          };
          S.signIn = function (o) {
            return new Promise(function (resolve) {
              var k = ++n; waiting[k] = resolve;
              $BRIDGE.postMessage(JSON.stringify({ n: k, silent: !!(o && o.silent) }));
            });
          };
        })();
    """.trimIndent()

    /**
     * A page on [host] asked to sign in. Answers [reply] with a one-time code, or null (cancelled,
     * signed out, quiet and not said yes yet, or the server said no). One sheet at a time.
     */
    fun request(context: Context, host: String, silent: Boolean, reply: (String?) -> Unit) {
        val token = TokenStore(context.applicationContext).token()
        if (token == null || id == null || (silent && !connected) || prompt != null) {
            reply(null)
            return
        }
        scope.launch {
            val first = ask(token, host, consent = null)
            when {
                first?.code != null -> {
                    if (!connected) set(context, hosts, id, true)
                    reply(first.code)
                }
                first?.needsConsent == true && !silent -> prompt = Prompt(first.client ?: "SyncHub", host) { yes ->
                    prompt = null
                    if (!yes) {
                        reply(null)
                    } else {
                        scope.launch {
                            val second = ask(token, host, consent = true)
                            if (second?.code != null) set(context, hosts, id, true)
                            reply(second?.code)
                        }
                    }
                }
                else -> {
                    if (first?.needsConsent == true && connected) set(context, hosts, id, false) // disconnected elsewhere
                    reply(null)
                }
            }
        }
    }

    /** Disconnected in You → Signed-in sites: the next sign-in asks again. */
    fun disconnected(context: Context) = set(context, hosts, id, false)

    private suspend fun ask(token: String, host: String, consent: Boolean?): SsoCodeResponse? = withContext(Dispatchers.IO) {
        runCatching { ApiClient.service.ssoCode("Bearer $token", SsoCodeRequest(host, consent)) }.getOrNull()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun validHost(host: String?) = !host.isNullOrBlank() && host.all { it.isLetterOrDigit() || it == '.' || it == '-' }
}
