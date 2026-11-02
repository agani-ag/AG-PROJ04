package com.agani.syncup.dns

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.agani.syncup.data.ApiClient
import com.agani.syncup.data.DnsCatalogResponse
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.util.UUID

/** One DNS service on the list: from the admin, or the person's [own]. */
data class DnsChoice(val id: String, val name: String, val address: String, val description: String, val own: Boolean) {
    internal val unencrypted get() = DnsKind.of(address) == DnsKind.PLAIN
}

/**
 * DNS for web pages in SyncUp, and only those. The admin's list comes from the backend (the app has none of
 * its own) and everything starts turned off. Which ones a person turns on, their order and any DNS of their
 * own stay on this phone; the server is never told. With none on, nothing here runs at all.
 *
 * WebView has no DNS setting, so while one is on its pages go through [BrowsingProxy]. That setting covers
 * the whole app, so Incognito tabs can't be left out, which is why the DNS page says so.
 */
object DnsServices {
    private const val PREFS = "dns_services"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_OWN = "own"
    private const val KEY_NOTICE = "notice"
    private const val LIST_FILE = "dns_services.json"

    private var prefs: SharedPreferences? = null
    private var file: File? = null
    private var app: Context? = null
    private var version = ""
    private var proxy: BrowsingProxy? = null
    private var appliedLines = ""
    private val main = Handler(Looper.getMainLooper())

    /** The admin's, in the admin's order. */
    var offered by mutableStateOf<List<DnsChoice>>(emptyList())
        private set
    var own by mutableStateOf<List<DnsChoice>>(emptyList())
        private set
    /** The ones turned on, in the person's order: the first working one answers. */
    var enabled by mutableStateOf<List<String>>(emptyList())
        private set
    /** Something to tell the person once, such as a DNS they used having gone. */
    var notice by mutableStateOf<String?>(null)
        private set
    /** How long each last took to answer. */
    val latency = mutableStateMapOf<String, Long>()
    /** The ones not answering just now. */
    val failing = mutableStateMapOf<String, Boolean>()
    /** A DNS is on but the proxy wouldn't start, so pages are on the phone's DNS after all. */
    var couldntStart by mutableStateOf(false)
        private set

    /** Whether this phone's WebView can be pointed at the proxy at all. */
    val supported: Boolean by lazy { runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE) }.getOrDefault(false) }

    private val resolver = DnsResolver(
        phone = { InetAddress.getAllByName(it).toList() },
        now = SystemClock::elapsedRealtime,
        onResult = { id, ms ->
            main.post {
                if (ms == null) {
                    failing[id] = true
                } else {
                    failing.remove(id)
                    latency[id] = ms
                }
            }
        },
    )

    val all: List<DnsChoice> get() = offered + own
    val turnedOn: List<DnsChoice> get() = enabled.mapNotNull { id -> all.firstOrNull { it.id == id } }
    val turnedOff: List<DnsChoice> get() = all.filter { it.id !in enabled }
    /** The one answering now; null when none is on or none is working. */
    val inUse: DnsChoice? get() = if (couldntStart) null else turnedOn.firstOrNull { failing[it.id] != true }
    val backups: Int get() = (turnedOn.count { failing[it.id] != true } - 1).coerceAtLeast(0)

    /** For the row in Settings. */
    val summary: String
        get() {
            if (!supported) return "Not available on this phone"
            if (turnedOn.isEmpty()) return "Off · using your phone's DNS"
            val working = inUse ?: return "Using your phone's DNS for now"
            return working.name + if (backups > 0) " + $backups backup${if (backups == 1) "" else "s"}" else ""
        }

    /** Reads the saved list and choices, and starts up again if a DNS was on. Main thread. */
    fun init(context: Context) {
        if (prefs != null) return
        val c = context.applicationContext
        app = c
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        file = File(c.filesDir, LIST_FILE)
        runCatching {
            val saved = file!!.takeIf { it.exists() }?.readText() ?: return@runCatching
            val r = Gson().fromJson(saved, DnsCatalogResponse::class.java)
            version = r.version
            offered = choices(r)
        }
        own = runCatching {
            val a = JSONArray(p.getString(KEY_OWN, "[]"))
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                DnsChoice(o.getString("id"), o.getString("name"), o.getString("address"), "", own = true)
            }
        }.getOrDefault(emptyList())
        enabled = runCatching {
            val a = JSONArray(p.getString(KEY_ENABLED, "[]"))
            List(a.length()) { a.getString(it) }
        }.getOrDefault(emptyList())
        notice = p.getString(KEY_NOTICE, null)
        apply()
    }

    private fun choices(r: DnsCatalogResponse): List<DnsChoice> = r.services
        .filter { DnsKind.of(it.address) != null }
        .map { DnsChoice("s${it.id}", it.name, it.address.trim(), it.description, own = false) }

    /** The admin's latest list: when the app or the DNS page opens. A failure keeps the saved one. */
    suspend fun refresh() {
        val resp = withContext(Dispatchers.IO) { runCatching { ApiClient.service.dns(version.ifBlank { null }) }.getOrNull() } ?: return
        if (resp.code() == 304) return
        val body = resp.body() ?: return
        version = body.version
        val before = turnedOn
        offered = choices(body)
        withContext(Dispatchers.IO) { runCatching { file?.writeText(Gson().toJson(body)) } }
        val gone = before.filter { old -> all.none { it.id == old.id } }
        if (gone.isNotEmpty()) {
            enabled = enabled.filter { id -> gone.none { it.id == id } }
            saveEnabled()
            val next = inUse?.let { "${it.name} takes over." } ?: "Now using your phone's DNS."
            val text = "${gone.joinToString { it.name }} ${if (gone.size == 1) "is" else "are"} no longer offered in SyncUp. $next"
            showNotice(text)
            app?.let { Toast.makeText(it, text, Toast.LENGTH_LONG).show() }
        }
        apply()
    }

    fun setOn(id: String, on: Boolean) {
        enabled = if (on) (if (id in enabled) enabled else enabled + id) else enabled - id
        if (!on) {
            failing.remove(id)
            latency.remove(id)
        }
        showNotice(null)
        saveEnabled()
        apply()
    }

    /** One place up ([by] -1) or down (1) in the person's order. */
    fun move(id: String, by: Int) {
        val i = enabled.indexOf(id)
        val j = i + by
        if (i < 0 || j !in enabled.indices) return
        enabled = enabled.toMutableList().apply { this[i] = this[j]; this[j] = id }
        saveEnabled()
        apply()
    }

    /** Tests [address] with a real lookup, then adds it turned off. Returns what's wrong, or null. */
    suspend fun addOwn(name: String, address: String): String? {
        val cleanName = name.trim()
        val cleanAddress = address.trim()
        if (cleanName.isEmpty()) return "Give it a name."
        val ask = DnsTransports.forAddress(cleanAddress) ?: return "That doesn't look like a DNS address."
        if (all.any { it.address.equals(cleanAddress, ignoreCase = true) }) return "That address is already on your list."
        val id = "o" + UUID.randomUUID().toString().replace("-", "")
        val answered = withContext(Dispatchers.IO) { resolver.check(DnsLine(id, ask)) }
        if (answered == null) {
            main.post { failing.remove(id) }
            return "It didn't answer a test lookup, so it wasn't added. Check the address."
        }
        own = own + DnsChoice(id, cleanName, cleanAddress, "", own = true)
        saveOwn()
        return null
    }

    fun removeOwn(id: String) {
        own = own.filterNot { it.id == id }
        saveOwn()
        if (id in enabled) setOn(id, false)
    }

    fun clearNotice() = showNotice(null)

    /** Asks each one that's on about a known name, so the DNS page shows how they're doing. */
    suspend fun checkTurnedOn() {
        val lines = lines()
        withContext(Dispatchers.IO) { lines.forEach { resolver.check(it) } }
    }

    private fun showNotice(text: String?) {
        notice = text
        prefs?.edit()?.putString(KEY_NOTICE, text)?.apply()
    }

    private fun saveEnabled() {
        prefs?.edit()?.putString(KEY_ENABLED, JSONArray(enabled).toString())?.apply()
    }

    private fun saveOwn() {
        val a = JSONArray()
        own.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("address", it.address)) }
        prefs?.edit()?.putString(KEY_OWN, a.toString())?.apply()
    }

    private fun lines(): List<DnsLine> = turnedOn.mapNotNull { c -> DnsTransports.forAddress(c.address)?.let { DnsLine(c.id, it) } }

    /** Makes what's running match what's chosen. Main thread. */
    private fun apply() {
        val signature = turnedOn.joinToString("\n") { "${it.id} ${it.address}" }
        if (signature != appliedLines) {
            appliedLines = signature
            resolver.lines = lines()
            failing.keys.filter { id -> id !in enabled }.forEach { failing.remove(it) }
        }
        if (turnedOn.isNotEmpty() && supported) startProxy() else stopProxy()
    }

    private fun startProxy() {
        if (proxy != null) return
        val started = BrowsingProxy(resolver::resolve)
        try {
            val port = started.start()
            val config = ProxyConfig.Builder()
                .addProxyRule("127.0.0.1:$port")
                // Local addresses, such as a router or an AudioSync station on the same Wi-Fi, connect directly.
                .bypassSimpleHostnames()
                .addBypassRule("localhost")
                .addBypassRule("*.local")
                .addBypassRule("127.0.0.0/8")
                .addBypassRule("10.0.0.0/8")
                .addBypassRule("172.16.0.0/12")
                .addBypassRule("192.168.0.0/16")
                .addBypassRule("169.254.0.0/16")
                .addBypassRule("[::1]")
                .addBypassRule("fc00::/7")
                .addBypassRule("fe80::/10")
                .build()
            ProxyController.getInstance().setProxyOverride(config, { it.run() }, {})
            proxy = started
            couldntStart = false
        } catch (e: Exception) {
            // Pages simply stay on the phone's DNS, and the DNS page says so rather than claiming otherwise.
            android.util.Log.w("DnsServices", "Couldn't start the browsing proxy", e)
            started.stop()
            couldntStart = true
        }
    }

    private fun stopProxy() {
        couldntStart = false
        val running = proxy ?: return
        proxy = null
        runCatching { ProxyController.getInstance().clearProxyOverride({ it.run() }, { running.stop() }) }
            .onFailure { running.stop() }
    }
}
