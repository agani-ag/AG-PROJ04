package com.agani.syncup.stationfiles

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.agani.syncup.BuildConfig
import com.agani.syncup.data.ApiClient
import com.agani.syncup.data.RadioChannel
import com.agani.syncup.data.RadioChannelsResponse
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** How a station file is kept: the station's fixed key and the file's path on it, never its address. */
internal const val STATION_SCHEME = "syncup-station"

internal fun stationUri(key: String, path: String): String = "$STATION_SCHEME://$key" + if (path.startsWith("/")) path else "/$path"

/** The key and path of a [stationUri], or null for any other address. */
internal fun parseStationUri(uri: String): Pair<String, String>? {
    val prefix = "$STATION_SCHEME://"
    if (!uri.startsWith(prefix, ignoreCase = true)) return null
    val rest = uri.substring(prefix.length)
    val key = rest.substringBefore('/')
    if (key.isBlank()) return null
    return key to "/" + rest.substringAfter('/', "")
}

/**
 * A station's files address now: scheme and host (and port) from its files_url. Only https, and only on the
 * same host as its audio: anyone can add a station, so a listing can't send the app anywhere else. A debug
 * build made for a test server also takes http on this phone's own 127.0.0.1.
 */
internal fun filesBase(
    filesUrl: String,
    streamUrl: String,
    testBuild: Boolean = BuildConfig.DEBUG_API_BASE_URL.isNotBlank(),
): String? {
    val u = runCatching { URI(filesUrl.trim()) }.getOrNull() ?: return null
    val host = u.host ?: return null
    val scheme = u.scheme?.lowercase() ?: return null
    if (scheme != "https" && !(testBuild && scheme == "http" && host == "127.0.0.1")) return null
    val streamHost = runCatching { URI(streamUrl.trim()).host }.getOrNull()
    if (!host.equals(streamHost, ignoreCase = true)) return null
    return if (u.port > 0) "$scheme://$host:${u.port}" else "$scheme://$host"
}

/** An IPv4 address as a number, or null for anything else. */
private fun ipv4(text: String): Int? {
    val parts = text.split('.')
    if (parts.size != 4) return null
    var out = 0
    for (p in parts) {
        if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
        val n = p.toInt()
        if (n > 255) return null
        out = (out shl 8) or n
    }
    return out
}

/** Is [ip] on the same network as [own], with its [prefix] bits? */
internal fun sameSubnet(ip: String, own: String, prefix: Int): Boolean {
    val a = ipv4(ip) ?: return false
    val b = ipv4(own) ?: return false
    if (prefix !in 8..32) return false
    val mask = -1 shl (32 - prefix)
    return a and mask == b and mask
}

/**
 * A station's address on its own Wi-Fi: plain http to a private IPv4 address and port, nothing else. The station
 * list is open, so a listing can't send the app to a server on the internet this way.
 */
internal fun lanBase(lanUrl: String): String? {
    val u = runCatching { URI(lanUrl.trim()) }.getOrNull() ?: return null
    val host = u.host ?: return null
    if (!u.scheme.equals("http", ignoreCase = true) || u.rawUserInfo != null || u.port <= 0) return null
    if (!sameSubnet(host, "10.0.0.0", 8) && !sameSubnet(host, "172.16.0.0", 12) && !sameSubnet(host, "192.168.0.0", 16)) return null
    return "http://$host:${u.port}"
}

/** A folder listing's address with search and paging added. */
internal fun listQuery(listUrl: String, q: String, offset: Int, limit: Int): String = buildString {
    append(listUrl)
    append(if (listUrl.contains('?')) '&' else '?')
    append("offset=").append(offset).append("&limit=").append(limit)
    if (q.isNotBlank()) append("&q=").append(URLEncoder.encode(q.trim(), "UTF-8"))
}

/**
 * Stations' shared files (AudioSync on someone's PC), reached by each station's fixed key. A station's
 * address changes whenever its tunnel restarts, so it's looked up in SyncUp's station list each time a
 * connection opens: downloads, uploads and the players keep a [stationUri] and carry on after a new address.
 * On the station's own Wi-Fi the same look-up goes straight to its PC instead of through the tunnel ([direct]).
 * Sign-ins (tokens) live in memory; a PIN the person chose to remember is kept encrypted on this phone.
 */
object Stations {
    private const val REFRESH_MIN_MS = 4_000L
    private const val DIRECT_OK_MS = 30_000L
    private const val DIRECT_NO_MS = 120_000L

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        // Another network: a station's Wi-Fi address may be someone else's now.
        runCatching {
            app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = forgetDirect()
                override fun onLost(network: Network) = forgetDirect()
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = forgetDirect()
            })
        }
    }

    // ------------------------------------------------------------------ the station list
    /** Every station on SyncUp's list now (radio, files or both). Compose state, for the Files tab. */
    var all by mutableStateOf<List<RadioChannel>>(emptyList()); private set
    var loaded by mutableStateOf(false); private set
    var listError by mutableStateOf<String?>(null); private set

    @Volatile private var byKey: Map<String, RadioChannel> = emptyMap()
    @Volatile private var lastRefresh = 0L
    private val refreshing = Mutex()

    /** Stations sharing folders that the app can reach. */
    val sharing: List<RadioChannel> get() = all.filter { it.filesEnabled && it.key.isNotBlank() && base(it) != null }

    fun station(key: String): RadioChannel? = byKey[key]

    fun name(key: String): String = byKey[key]?.name ?: "The station"

    internal fun base(s: RadioChannel): String? = filesBase(s.filesUrl, s.streamUrl)

    /** A fresh station list, from here or the Radio tab (its station may play on the Wi-Fi route too). */
    internal fun accept(resp: RadioChannelsResponse) {
        val list = if (resp.enabled) resp.stations else emptyList()
        byKey = list.filter { it.key.isNotBlank() }.associateBy { it.key }
        lastRefresh = SystemClock.elapsedRealtime()
        // A station with a new Wi-Fi address (or gone) isn't "on this Wi-Fi" until it's been checked there.
        wifiChecks.keys.filter { key -> wifiChecks[key]?.base != byKey[key]?.let { lanBase(it.lanUrl) } }.forEach { wifiChecks.remove(it) }
        showDirect()
        main.post {
            all = list
            loaded = true
            listError = null
        }
    }

    /** Ask SyncUp for the list again, at most every few seconds unless [force]. False when it couldn't. */
    suspend fun refresh(force: Boolean = false): Boolean = refreshing.withLock {
        if (!force && lastRefresh > 0 && SystemClock.elapsedRealtime() - lastRefresh < REFRESH_MIN_MS) return true
        val result = runCatching { ApiClient.service.radioChannels() }
        result.onSuccess { accept(it) }.onFailure { e ->
            main.post {
                listError = e.message ?: "Couldn't load stations."
                loaded = true
            }
        }
        result.isSuccess
    }

    /** For IO threads (downloads, uploads, the players' data sources). */
    internal fun refreshBlocking(): Boolean = runBlocking { refresh() }

    /** A connection to a station failed: its address may have changed, so the next look-up asks SyncUp again. */
    internal fun markStale() {
        lastRefresh = 0
        forgetDirect()
    }

    // ------------------------------------------------------------------ the same Wi-Fi
    private class WifiCheck(val base: String, val ok: Boolean, val at: Long)

    private val wifiChecks = ConcurrentHashMap<String, WifiCheck>()
    @Volatile private var wifiGeneration = 0

    /** The stations this phone reaches on their own Wi-Fi right now. Compose state, for the folder screen. */
    var onSameWifi by mutableStateOf<Set<String>>(emptySet()); private set

    private fun showDirect() {
        val now = wifiChecks.filterValues { it.ok }.keys.toSet()
        main.post { onSameWifi = now }
    }

    /** Check the Wi-Fi route again before the next connection: one just failed, or the network changed. */
    internal fun forgetDirect() {
        wifiGeneration++
        wifiChecks.clear()
        showDirect()
    }

    /**
     * [s]'s address on this Wi-Fi, or null to go the usual way. Only when this phone is on Wi-Fi (or Ethernet) on
     * the same network as that address, and the PC there answers with the station's ID. That check sends nothing
     * of ours. The ID stops a mix-up (the same 192.168.x.x elsewhere is another device); being public, it isn't
     * proof against a device pretending. Remembered a short while. Blocking: for IO threads.
     */
    private fun direct(s: RadioChannel): String? {
        val lan = lanBase(s.lanUrl)
        if (lan == null || s.stationId.isBlank()) {
            if (wifiChecks.remove(s.key) != null) showDirect()
            return null
        }
        synchronized(wifiChecks) {
            val known = wifiChecks[s.key]
            if (known != null && known.base == lan && SystemClock.elapsedRealtime() - known.at < if (known.ok) DIRECT_OK_MS else DIRECT_NO_MS) {
                return lan.takeIf { known.ok }
            }
            val generation = wifiGeneration
            val ok = onNetworkOf(lan) && answersAs(lan, s.stationId)
            if (generation != wifiGeneration) return null // the network changed while it looked: ask again next time
            wifiChecks[s.key] = WifiCheck(lan, ok, SystemClock.elapsedRealtime())
            showDirect()
            return lan.takeIf { ok }
        }
    }

    private fun onNetworkOf(lan: String): Boolean {
        val host = runCatching { URI(lan).host }.getOrNull() ?: return false
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return false
        return cm.getLinkProperties(network)?.linkAddresses.orEmpty().any { sameSubnet(host, it.address.hostAddress.orEmpty(), it.prefixLength) }
    }

    private fun answersAs(lan: String, stationId: String): Boolean = try {
        wifiCheckHttp.newCall(Request.Builder().url("$lan/api/info").header("Connection", "close").build()).execute().use { r ->
            r.code == 200 && runCatching { gson.fromJson(r.peekBody(64 * 1024L).charStream(), StationInfo::class.java) }.getOrNull()?.stationId == stationId
        }
    } catch (e: IOException) {
        false
    }

    /** The radio playing straight from its PC now, by its list address (stream_url). Compose state, for the players. */
    var directRadio by mutableStateOf<String?>(null); private set

    /** A radio's stream on this Wi-Fi, for the station list's [streamUrl]; null = as it is. Blocking. */
    internal fun directStream(streamUrl: String): String? {
        if (!streamUrl.startsWith("http", ignoreCase = true)) return null
        val base = byKey.values.firstOrNull { it.streamUrl == streamUrl }?.let { direct(it) }
        val u = runCatching { URI(streamUrl) }.getOrNull()
        val out = if (base != null && u != null) base + u.rawPath.orEmpty() + (u.rawQuery?.let { "?$it" } ?: "") else null
        main.post { directRadio = streamUrl.takeIf { out != null } }
        return out
    }

    // ------------------------------------------------------------------ signing in
    private val tokens = ConcurrentHashMap<String, String>()
    private val pins: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            app, "station_pins", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** The sign-in to send to [key]'s station, if any. */
    internal fun token(key: String): String? = tokens[key]

    /** A station said our sign-in no longer works (it restarted, or 6 hours passed). */
    internal fun tokenRejected(key: String) {
        tokens.remove(key)
    }

    fun hasRememberedPin(key: String): Boolean = runCatching { pins.contains(key) }.getOrDefault(false)

    fun forgetPin(key: String) {
        runCatching { pins.edit().remove(key).apply() }
        tokens.remove(key)
    }

    sealed interface Login {
        data object Ok : Login
        data class Wrong(val attemptsLeft: Int?) : Login
        data class Wait(val seconds: Int) : Login
        data class Failed(val message: String) : Login
    }

    /**
     * Sign in to a station with its PIN, always over its https address: a PIN never crosses the Wi-Fi as plain
     * http, even when the files then come that way. Blocking: call it off the main thread.
     */
    fun login(key: String, pin: String, remember: Boolean): Login {
        val s = byKey[key] ?: return Login.Failed("${name(key)} isn't online right now.")
        val base = base(s) ?: return Login.Failed("${s.name} isn't online right now.")
        val body = gson.toJson(mapOf("pin" to pin)).toRequestBody(JSON)
        return try {
            http.newCall(Request.Builder().url("$base/api/login").post(body).build()).execute().use { r ->
                val reply = runCatching { gson.fromJson(r.body?.charStream(), StationLoginReply::class.java) }.getOrNull() ?: StationLoginReply()
                when {
                    r.isSuccessful -> {
                        reply.token?.let { tokens[key] = it } ?: tokens.remove(key)
                        runCatching { pins.edit().apply { if (remember) putString(key, pin) else remove(key) }.apply() }
                        Login.Ok
                    }
                    r.code == 401 -> Login.Wrong(reply.attemptsLeft)
                    r.code == 429 -> Login.Wait(reply.retryAfter ?: r.header("Retry-After")?.toIntOrNull() ?: 60)
                    else -> Login.Failed("${s.name} answered ${r.code}.")
                }
            }
        } catch (e: IOException) {
            markStale()
            Login.Failed("Couldn't reach ${s.name}.")
        }
    }

    /** After a 401: sign in again with the remembered PIN. False when there's none, or it's no longer right. */
    internal fun signInAgain(key: String): Boolean {
        tokens.remove(key)
        val pin = runCatching { pins.getString(key, null) }.getOrNull() ?: return false
        return when (login(key, pin, remember = true)) {
            Login.Ok -> true
            is Login.Wrong -> {
                forgetPin(key) // the owner changed it: ask the person
                false
            }
            else -> false
        }
    }

    // ------------------------------------------------------------------ talking to a station
    internal val gson = Gson()
    internal val JSON = "application/json".toMediaType()

    internal val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /** On the Wi-Fi a PC that's there answers at once; one that isn't shouldn't hold a call up. */
    private val wifiHttp: OkHttpClient by lazy { http.newBuilder().connectTimeout(3, TimeUnit.SECONDS).build() }

    private val wifiCheckHttp: OkHttpClient by lazy {
        http.newBuilder()
            .connectTimeout(1200, TimeUnit.MILLISECONDS)
            .readTimeout(1500, TimeUnit.MILLISECONDS)
            .callTimeout(2500, TimeUnit.MILLISECONDS)
            .build()
    }

    /** The station can't be reached now (off the list, or its address doesn't answer). */
    class Gone(val station: String) : IOException("$station isn't reachable right now")

    /** The station wants its PIN, and there's no remembered one that works. */
    class PinNeeded(val key: String) : IOException("PIN needed")

    /** A station answered with an error. [error] is AudioSync's code. */
    class Refused(val code: Int, val error: String?, val retryAfter: Int?, val received: Long?) : IOException("HTTP $code ${error.orEmpty()}")

    /** Where a station file is now, and the sign-in to send with it. [direct]: on the station's own Wi-Fi. */
    data class Resolved(val url: String, val token: String?, val direct: Boolean = false) {
        val headers: Map<String, String>
            get() = buildMap {
                token?.let { put("Authorization", "Bearer $it") }
                // Straight from AudioSync, a reply ends its connection without saying so (HTTP/1.0). Kept for
                // reuse, the next request would fail on it and go again: every request would be sent twice.
                if (direct) put("Connection", "close")
            }
    }

    /**
     * The address of a station file now: on its own Wi-Fi if this phone is there, else its public one. Looks the
     * station up again when a connection failed or the list is old; signs in again with a remembered PIN when
     * the station is protected and there's no sign-in. Blocking: for IO threads.
     */
    internal fun resolve(uri: String): Resolved {
        val (key, path) = parseStationUri(uri) ?: throw IOException("Not a station file")
        if (lastRefresh == 0L || SystemClock.elapsedRealtime() - lastRefresh > 60_000 || byKey[key] == null) refreshBlocking()
        val s = byKey[key] ?: throw Gone(name(key))
        val base = base(s) ?: throw Gone(s.name)
        if (s.filesProtected && tokens[key] == null) signInAgain(key)
        val lan = direct(s)
        return Resolved((lan ?: base) + path, tokens[key], lan != null)
    }

    /**
     * One call to a station: finds its address, sends the sign-in, and on a 401 signs in again once with the
     * remembered PIN. A break on the Wi-Fi route tries again the public way. Throws [Gone] when it can't be
     * reached, [PinNeeded] when it wants a PIN. Blocking.
     */
    internal fun <T> call(key: String, path: String, build: (Request.Builder) -> Request.Builder = { it }, read: (Response) -> T): T {
        var signedIn = false
        repeat(3) {
            val r = resolve(stationUri(key, path))
            val req = build(Request.Builder().url(r.url)).apply { r.headers.forEach { (name, value) -> header(name, value) } }.build()
            val resp = try {
                (if (r.direct) wifiHttp else http).newCall(req).execute()
            } catch (e: IOException) {
                markStale()
                if (r.direct) return@repeat
                throw Gone(name(key))
            }
            resp.use {
                if (it.code == 401) {
                    tokenRejected(key)
                    if (!signedIn && signInAgain(key)) {
                        signedIn = true
                        return@repeat
                    }
                    throw PinNeeded(key)
                }
                // Cloudflare answers for a tunnel that's gone (530, 1033 pages): the station moved or left.
                if (it.code == 502 || it.code == 530 || it.code == 521 || it.code == 522) {
                    markStale()
                    throw Gone(name(key))
                }
                return read(it)
            }
        }
        throw Gone(name(key))
    }

    /** A JSON error reply as [Refused]. */
    internal fun refused(r: Response): Refused {
        val e = runCatching { gson.fromJson(r.body?.charStream(), StationError::class.java) }.getOrNull()
        return Refused(r.code, e?.error, e?.retryAfter ?: r.header("Retry-After")?.toIntOrNull(), e?.received)
    }

    /** One page of a folder (or the shared roots for "/api/files/"). Blocking. */
    fun list(key: String, listUrl: String, q: String = "", offset: Int = 0, limit: Int = 200): StationListing =
        call(key, listQuery(listUrl, q, offset, limit)) { r ->
            if (!r.isSuccessful) throw refused(r)
            gson.fromJson(r.body?.charStream(), StationListing::class.java) ?: StationListing()
        }

    // ------------------------------------------------------------------ the first-open note
    private val prefs: SharedPreferences by lazy { app.getSharedPreferences("station_files", Context.MODE_PRIVATE) }

    fun noticeSeen(key: String): Boolean = prefs.getBoolean("notice_$key", false)

    fun markNoticeSeen(key: String) = prefs.edit().putBoolean("notice_$key", true).apply()
}
