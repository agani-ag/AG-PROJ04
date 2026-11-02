package com.agani.syncup.sensors

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.telephony.CellInfo
import android.telephony.CellSignalStrengthCdma
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.log10
import kotlin.math.min
import kotlin.random.Random

// ============================================================================ the connection right now
internal enum class LinkKind { WIFI, SIM, WIRED, OTHER, NONE }

/** The connection in use: what to call it, its signal, and whether a test must watch the data it uses. */
internal class NetLink(
    val kind: LinkKind,
    val name: String,
    val online: Boolean = false,
    /** 0 to 4, null with no reading (wired, or a SIM on Android below 9). */
    val bars: Int? = null,
    val dbm: Int? = null,
    val extra: String? = null,
    /** A SIM or a Wi-Fi that counts its data (a phone's hotspot, say). */
    val limited: Boolean = false,
    val network: Network? = null,
) {
    val signal: String?
        get() = bars?.let { b -> NetMath.signalWord(b) + (dbm?.let { ", $it dBm" } ?: "") }

    companion object {
        fun read(context: Context): NetLink {
            val app = context.applicationContext
            val cm = app.getSystemService(ConnectivityManager::class.java)
            val network = cm?.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            if (network == null || caps == null) return NetLink(LinkKind.NONE, "Not connected")
            val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val metered = cm.isActiveNetworkMetered
            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> wifi(app, network, online, metered)
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> sim(app, network, online)
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetLink(LinkKind.WIRED, "Wired", online, limited = metered, network = network)
                else -> NetLink(LinkKind.OTHER, "Connected", online, limited = metered, network = network)
            }
        }

        // The signal and link speed need no location permission (only the network's name would).
        @Suppress("DEPRECATION")
        private fun wifi(context: Context, network: Network, online: Boolean, metered: Boolean): NetLink {
            val info = context.getSystemService(WifiManager::class.java)?.connectionInfo
            val known = info != null && info.rssi > -127
            return NetLink(
                LinkKind.WIFI,
                if (info != null && info.frequency > 0) "Wi-Fi, ${bandOf(info.frequency)}" else "Wi-Fi",
                online,
                bars = if (known) NetMath.wifiBars(info!!.rssi) else null,
                dbm = if (known) info!!.rssi else null,
                extra = info?.linkSpeed?.takeIf { it > 0 }?.let { "Link speed $it Mbps" },
                limited = metered,
                network = network,
            )
        }

        // The SIM that carries data, on a two-SIM phone. None of this needs a permission.
        private fun sim(context: Context, network: Network, online: Boolean): NetLink {
            val all = context.getSystemService(TelephonyManager::class.java)
            val id = SubscriptionManager.getDefaultDataSubscriptionId()
            val tm = if (id != SubscriptionManager.INVALID_SUBSCRIPTION_ID) all?.createForSubscriptionId(id) ?: all else all
            var bars: Int? = null
            var dbm: Int? = null
            var generation: String? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching {
                    val strength = tm?.signalStrength
                    bars = strength?.level
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val cells = strength?.cellSignalStrengths.orEmpty().filter { it.dbm != CellInfo.UNAVAILABLE }
                        // 5G alongside 4G: name the faster one.
                        (cells.firstOrNull { it is CellSignalStrengthNr } ?: cells.firstOrNull())?.let { cell ->
                            bars = cell.level
                            dbm = cell.dbm
                            generation = when (cell) {
                                is CellSignalStrengthNr -> "5G"
                                is CellSignalStrengthLte -> "4G"
                                is CellSignalStrengthGsm, is CellSignalStrengthCdma -> "2G"
                                else -> "3G"
                            }
                        }
                    }
                }
            }
            val carrier = tm?.networkOperatorName?.takeIf { it.isNotBlank() }
            return NetLink(
                LinkKind.SIM,
                listOfNotNull(carrier, generation).joinToString(", ").ifBlank { "Mobile data" },
                online, bars, dbm, "SIM data, mobile network", limited = true, network = network,
            )
        }
    }
}

// ============================================================================ the test
private const val SERVER = "speed.cloudflare.com"
private const val PROBES = 20
private const val CONNECTIONS = 4
private const val DOWN_SECONDS = 10
private const val UP_SECONDS = 8
// On a SIM: about 50 MB in all, which is what the screen promises.
private const val LIMITED_DOWN = 35_000_000L
private const val LIMITED_UP = 13_000_000L
private const val NO_REPLY = "Couldn't reach the test server. Check your connection and try again."

internal enum class NetStep { LATENCY, DOWNLOAD, UPLOAD }

/** Why a test couldn't finish, in words for the screen. */
internal class NetFailure(message: String) : Exception(message)

internal class NetProgress {
    var step by mutableStateOf(NetStep.LATENCY)
    var live by mutableStateOf(0.0)
    var latency by mutableStateOf<Long?>(null)
    var down by mutableStateOf<Double?>(null)
}

internal class NetResult(
    val at: Long,
    val down: Double,
    val up: Double?,
    val latency: Long,
    val jitter: Long,
    val replies: Int,
    val sent: Int,
    val dns: Long,
    val signal: String?,
    val used: Long,
)

/** One direction of the test: its connections, the bytes they've moved, and stopping them all at once. */
private class Flow(val cap: Long) {
    val bytes = AtomicLong()
    val http = AtomicInteger()
    private val calls = ConcurrentHashMap.newKeySet<Call>()

    @Volatile var stopped = false
        private set
    @Volatile var stoppedAt = 0L
        private set

    /** Counted here, not by the clock, so a fast connection can't run far past the limit before the next look. */
    fun count(n: Int) {
        if (bytes.addAndGet(n.toLong()) >= cap) stop()
    }

    fun stop() {
        synchronized(this) {
            if (!stopped) {
                stoppedAt = SystemClock.elapsedRealtime()
                stopped = true
            }
        }
        calls.forEach { it.cancel() }
    }

    /** One request; false when this connection should give up (stopped, refused, or the network went). */
    fun send(call: Call, read: (Response) -> Unit): Boolean {
        calls += call
        return try {
            if (stopped) call.cancel()
            call.execute().use { r ->
                if (r.isSuccessful) {
                    read(r)
                    true
                } else {
                    http.set(r.code)
                    false
                }
            }
        } catch (_: IOException) {
            false
        } finally {
            calls -= call
        }
    }
}

private class Transfer(val mbps: Double, val bytes: Long, val gaveUp: Boolean, val http: Int)

private fun request(path: String) = Request.Builder().url("https://$SERVER$path")
    // Measures the network, so nothing may shrink the data on the way.
    .header("Accept-Encoding", "identity")
    .header("User-Agent", "SyncUp")

/**
 * Keeps each connection's send buffer small. Upload is counted as data is handed over, and a big buffer takes
 * megabytes at once: on a slow uplink the count then stood still after that, and the result read 0.0 Mbps.
 */
private class SmallSendBuffers(private val base: SocketFactory) : SocketFactory() {
    private fun Socket.small() = apply { sendBufferSize = 128 * 1024 }
    override fun createSocket(): Socket = base.createSocket().small()
    override fun createSocket(host: String?, port: Int): Socket = base.createSocket(host, port).small()
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        base.createSocket(host, port, localHost, localPort).small()
    override fun createSocket(host: InetAddress?, port: Int): Socket = base.createSocket(host, port).small()
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        base.createSocket(address, port, localAddress, localPort).small()
}

/**
 * Stays on the network the test began on. If Wi-Fi drops, the test fails rather than carrying on over the
 * SIM with no data limit. One TCP connection each (HTTP/1.1): over HTTP/2 they'd all share one.
 */
private fun testClient(network: Network) = OkHttpClient.Builder()
    .socketFactory(SmallSendBuffers(network.socketFactory))
    .dns(object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = network.getAllByName(hostname).toList()
    })
    .protocols(listOf(Protocol.HTTP_1_1))
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(8, TimeUnit.SECONDS)
    .writeTimeout(8, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false)
    .build()

private suspend fun Call.await(): Response = suspendCancellableCoroutine { c ->
    c.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = c.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (c.isActive) c.resumeWithException(e)
        }
    })
}

/** Small requests, one after another. The first only opens the connection, so it isn't counted. */
private suspend fun probe(client: OkHttpClient): Pair<List<Long>, Int> {
    val quick = client.newBuilder().callTimeout(3, TimeUnit.SECONDS).build()
    suspend fun one(): Long? = try {
        val started = SystemClock.elapsedRealtimeNanos()
        quick.newCall(request("/__down?bytes=0").build()).await().use { r ->
            val ms = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000
            if (r.isSuccessful) (ms - NetMath.serverMs(r.headers("Server-Timing"))).coerceAtLeast(1) else null
        }
    } catch (_: IOException) {
        null
    }
    if (one() == null) throw NetFailure(NO_REPLY)
    val times = ArrayList<Long>()
    var sent = 0
    val until = SystemClock.elapsedRealtime() + 8_000
    while (sent < PROBES && SystemClock.elapsedRealtime() < until) {
        sent++
        one()?.let { times += it }
    }
    if (times.isEmpty()) throw NetFailure(NO_REPLY)
    return times to sent
}

private fun Flow.download(client: OkHttpClient, chunk: Long) {
    val buffer = ByteArray(64 * 1024)
    while (!stopped) {
        val more = send(client.newCall(request("/__down?bytes=$chunk").build())) { r ->
            val input = r.body!!.byteStream()
            while (!stopped) {
                val n = input.read(buffer)
                if (n < 0) break
                count(n)
            }
        }
        if (!more) return
    }
}

private fun Flow.upload(client: OkHttpClient, chunk: Long) {
    // Random, so nothing on the way can shrink it.
    val block = Random.nextBytes(64 * 1024)
    while (!stopped) {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = chunk
            override fun writeTo(sink: BufferedSink) {
                var left = chunk
                while (left > 0) {
                    if (stopped) throw IOException("stopped")
                    val n = min(left, block.size.toLong()).toInt()
                    sink.write(block, 0, n)
                    sink.flush()
                    count(n)
                    left -= n
                }
            }
        }
        if (!send(client.newCall(request("/__up").post(body).build())) { }) return
    }
}

/** Runs [worker] on every connection until the time or the limit is up; the gauge follows along. */
private suspend fun transfer(seconds: Int, cap: Long, onLive: (Double) -> Unit, worker: Flow.() -> Unit): Transfer = coroutineScope {
    val flow = Flow(cap)
    val meter = SpeedMeter()
    val start = SystemClock.elapsedRealtime()
    val workers = List(CONNECTIONS) { launch(Dispatchers.IO) { flow.worker() } }
    var gaveUp = false
    try {
        while (true) {
            val t = SystemClock.elapsedRealtime() - start
            meter.add(t, flow.bytes.get())
            onLive(meter.live())
            if (flow.stopped || t >= seconds * 1_000L) break
            if (workers.none { it.isActive }) {
                gaveUp = true
                break
            }
            delay(100)
        }
    } finally {
        // Stop pressed lands here too: closing the sockets is what frees the blocked connections.
        flow.stop()
    }
    meter.add(flow.stoppedAt - start, flow.bytes.get())
    Transfer(meter.result(), flow.bytes.get(), gaveUp, flow.http.get())
}

internal suspend fun runNetworkTest(link: NetLink, progress: NetProgress): NetResult = withContext(Dispatchers.IO) {
    val network = link.network ?: throw NetFailure(NO_REPLY)
    val client = testClient(network)
    try {
        val dnsStart = SystemClock.elapsedRealtime()
        try {
            network.getAllByName(SERVER)
        } catch (_: IOException) {
            throw NetFailure(NO_REPLY)
        }
        val dns = SystemClock.elapsedRealtime() - dnsStart

        val (times, sent) = probe(client)
        val latency = NetMath.median(times)
        progress.latency = latency

        progress.step = NetStep.DOWNLOAD
        val down = transfer(
            DOWN_SECONDS, if (link.limited) LIMITED_DOWN else Long.MAX_VALUE, { progress.live = it },
        ) { download(client, if (link.limited) 8_000_000L else 50_000_000L) }
        when {
            down.bytes == 0L && down.http != 0 -> throw NetFailure("The test server is busy right now. Try again in a minute.")
            down.bytes == 0L -> throw NetFailure(NO_REPLY)
            down.gaveUp -> throw NetFailure("The connection dropped during the test. Try again.")
        }
        progress.down = down.mbps
        progress.live = 0.0

        progress.step = NetStep.UPLOAD
        val up = transfer(
            UP_SECONDS, if (link.limited) LIMITED_UP else Long.MAX_VALUE, { progress.live = it },
        ) { upload(client, if (link.limited) 4_000_000L else 25_000_000L) }

        NetResult(
            System.currentTimeMillis(), down.mbps, up.mbps.takeIf { up.bytes > 0 && !up.gaveUp },
            latency, NetMath.jitter(times), times.size, sent, dns, link.signal, down.bytes + up.bytes,
        )
    } finally {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}

// ============================================================================ past results
private class PastTest(val at: Long, val down: Double, val up: Double?)

/** The last few results, on this phone only. */
private object NetHistory {
    private const val KEY = "net_tests"
    private const val KEEP = 5

    fun all(context: Context): List<PastTest> = runCatching {
        val a = JSONArray(SensorPrefs.prefs(context).getString(KEY, "[]"))
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            PastTest(o.getLong("at"), o.getDouble("d"), if (o.has("u")) o.getDouble("u") else null)
        }
    }.getOrDefault(emptyList())

    fun add(context: Context, r: NetResult) {
        val a = JSONArray()
        (listOf(PastTest(r.at, r.down, r.up)) + all(context)).take(KEEP).forEach { p ->
            a.put(JSONObject().put("at", p.at).put("d", p.down).apply { p.up?.let { put("u", it) } })
        }
        SensorPrefs.prefs(context).edit().putString(KEY, a.toString()).apply()
    }
}

private fun whenText(context: Context, at: Long): String {
    val time = DateFormat.getTimeFormat(context).format(Date(at))
    return when {
        DateUtils.isToday(at) -> "Today, $time"
        DateUtils.isToday(at + DateUtils.DAY_IN_MILLIS) -> "Yesterday, $time"
        else -> DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
    }
}

// ============================================================================ the screen
private val GREEN = Color(0xFF16A34A)
private val AMBER = Color(0xFFD97706)
private val RED = Color(0xFFDC2626)

@Composable
fun NetworkTool(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val onScreen = rememberOnScreen()
    var link by remember { mutableStateOf(NetLink.read(context)) }
    var past by remember { mutableStateOf(NetHistory.all(context)) }
    var progress by remember { mutableStateOf<NetProgress?>(null) }
    var result by remember { mutableStateOf<NetResult?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(onScreen) {
        // A test in the background would use data for a number nobody sees, and read low anyway.
        if (!onScreen && job != null) {
            job?.cancel()
            problem = "The test stopped because SyncUp left the screen."
        }
        while (onScreen) {
            link = NetLink.read(context)
            delay(2_000)
        }
    }

    fun start() {
        val now = NetLink.read(context)
        link = now
        if (now.network == null) return
        val running = NetProgress()
        result = null
        problem = null
        progress = running
        job = scope.launch {
            try {
                val done = runNetworkTest(now, running)
                NetHistory.add(context, done)
                past = NetHistory.all(context)
                result = done
            } catch (e: NetFailure) {
                problem = e.message
            } finally {
                progress = null
                job = null
            }
        }
    }

    ToolPage("Network test", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            val running = progress
            val done = result
            when {
                running != null -> {
                    KeepScreenOn()
                    Testing(running, onStop = { job?.cancel() })
                }
                done != null -> Results(done, link, onAgain = { start() })
                else -> Ready(link, past, problem, onStart = { start() })
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(
        text, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 2.dp),
    )
}

/** A label on the left, its value on the right. */
@Composable
private fun Line(label: String, value: String, valueColor: Color = Color.Unspecified) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(label, fontSize = 14.sp, color = cs.onSurface, modifier = Modifier.weight(1f))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (valueColor == Color.Unspecified) cs.onSurface else valueColor)
    }
}

/** Four rising bars, lit up to [level]. */
@Composable
private fun SignalBars(level: Int?) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (bar in 1..4) {
            Box(
                Modifier.width(4.dp).height((bar * 4 + 2).dp).clip(RoundedCornerShape(1.dp))
                    .background(if (level != null && bar <= level) cs.primary else cs.outlineVariant),
            )
        }
    }
}

/** What using a limited connection costs, said before it's spent. */
@Composable
private fun DataWarning(link: NetLink) {
    if (!link.limited) return
    Text(
        if (link.kind == LinkKind.SIM) "On a SIM, a test uses your data plan: up to about 50 MB. Wi-Fi tests run longer for a closer reading."
        else "This connection counts its data, so a test uses up to about 50 MB of it.",
        fontSize = 12.5.sp, lineHeight = 17.sp, color = AMBER,
        modifier = Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(AMBER.copy(alpha = .12f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
private fun Ready(link: NetLink, past: List<PastTest>, problem: String?, onStart: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Spacer(Modifier.height(4.dp))
    Card {
        Title("Connection")
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Icon(
                when (link.kind) {
                    LinkKind.WIFI -> Icons.Rounded.Wifi
                    LinkKind.SIM -> Icons.Rounded.SignalCellularAlt
                    LinkKind.WIRED -> Icons.Rounded.SettingsEthernet
                    LinkKind.OTHER -> Icons.Rounded.Language
                    LinkKind.NONE -> Icons.Rounded.WifiOff
                },
                null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(link.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.weight(1f))
            if (link.kind != LinkKind.NONE) {
                Text(if (link.online) "Online" else "No internet", fontSize = 13.sp, color = if (link.online) GREEN else AMBER)
            }
        }
    }
    if (link.kind == LinkKind.WIFI || link.kind == LinkKind.SIM) {
        Spacer(Modifier.height(10.dp))
        Card {
            Title("Signal strength")
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                SignalBars(link.bars)
                Spacer(Modifier.width(10.dp))
                Text(link.bars?.let { NetMath.signalWord(it) } ?: "No reading", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.weight(1f))
                link.dbm?.let { Text("$it dBm", fontSize = 13.sp, color = cs.onSurfaceVariant) }
            }
            link.extra?.let {
                Text(it, fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp))
            }
        }
    }
    if (past.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Card {
            Title("Last tests")
            past.take(3).forEach { p ->
                Line(whenText(context, p.at), "${NetMath.mbpsText(p.down)} / ${p.up?.let { NetMath.mbpsText(it) } ?: "—"} Mbps")
            }
        }
    }
    problem?.let {
        Text(
            it, fontSize = 13.sp, lineHeight = 18.sp, color = RED,
            modifier = Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(RED.copy(alpha = .1f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
    DataWarning(link)
    Button(onClick = onStart, enabled = link.network != null, modifier = Modifier.padding(top = 14.dp).fillMaxWidth().height(48.dp)) { Text("Start test") }
    Note(
        "No data is used until you press Start. The test talks to Cloudflare's speed test server, only while it runs. Results stay on this phone.",
        Modifier.padding(top = 14.dp),
    )
}

@Composable
private fun ColumnScope.Testing(p: NetProgress, onStop: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    // A log scale, so 5 Mbps moves the needle as visibly as 500; a full arc is 1 Gbps.
    val sweep by animateFloatAsState((log10(1 + p.live) / 3).toFloat().coerceIn(0f, 1f), tween(300), label = "speed")
    val measuring = p.step != NetStep.LATENCY
    Box(Modifier.padding(top = 8.dp).fillMaxWidth(.8f).aspectRatio(1.6f).align(Alignment.CenterHorizontally), contentAlignment = Alignment.BottomCenter) {
        val track = cs.surfaceContainerHigh
        val fill = cs.primary
        Canvas(Modifier.fillMaxSize()) {
            val s = 18.dp.toPx()
            val d = min(size.width, size.height * 2f) - s
            val corner = Offset((size.width - d) / 2, s / 2)
            drawArc(track, 180f, 180f, false, corner, Size(d, d), style = Stroke(s, cap = StrokeCap.Round))
            if (measuring && sweep > 0f) drawArc(fill, 180f, 180f * sweep, false, corner, Size(d, d), style = Stroke(s, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (measuring) NetMath.mbpsText(p.live) else "—",
                fontSize = 44.sp, fontWeight = FontWeight.Medium, style = TextStyle(fontFeatureSettings = "tnum"), color = cs.onSurface,
            )
            Text(
                when (p.step) {
                    NetStep.LATENCY -> "Checking latency"
                    NetStep.DOWNLOAD -> "Mbps, download"
                    NetStep.UPLOAD -> "Mbps, upload"
                },
                fontSize = 13.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    Card {
        Line("Latency", p.latency?.let { "$it ms" } ?: "Testing…")
        Line(
            "Download",
            p.down?.let { "${NetMath.mbpsText(it)} Mbps" } ?: if (p.step == NetStep.DOWNLOAD) "Testing…" else "Next",
        )
        Line("Upload", if (p.step == NetStep.UPLOAD) "Testing…" else "Next")
    }
    OutlinedButton(onClick = onStop, modifier = Modifier.padding(top = 14.dp).fillMaxWidth().height(48.dp)) { Text("Stop") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Results(r: NetResult, link: NetLink, onAgain: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val grade = NetMath.grade(r.down, r.up, r.latency, r.jitter, r.replies, r.sent)
    val gradeColor = when (grade) {
        NetGrade.GREAT, NetGrade.GOOD -> GREEN
        NetGrade.FAIR -> AMBER
        NetGrade.POOR -> RED
    }
    Spacer(Modifier.height(4.dp))
    Stats(
        "${NetMath.mbpsText(r.down)} Mbps" to "Download",
        "${r.up?.let { NetMath.mbpsText(it) } ?: "—"} Mbps" to "Upload",
    )
    Spacer(Modifier.height(10.dp))
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text("Network health", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.weight(1f))
            Text(
                grade.word, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = gradeColor,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(gradeColor.copy(alpha = .14f)).padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 10.dp),
        ) {
            NetMath.uses(r.down, r.up, r.latency, r.jitter).forEach { (use, fine) ->
                val tint = if (fine) GREEN else AMBER
                Text(
                    if (fine) use else "$use: may lag", fontSize = 12.sp, color = tint,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(tint.copy(alpha = .12f)).padding(horizontal = 9.dp, vertical = 3.dp),
                )
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Card {
        Line("Latency", "${r.latency} ms")
        Line("Jitter", "${r.jitter} ms")
        Line("Replies", "${r.replies} of ${r.sent}")
        // Under 2 ms, the phone already knew the address and asked nobody.
        Line("Name lookup (DNS)", if (r.dns < 2) "Instant" else "${r.dns} ms")
        Line("Signal", r.signal ?: "—")
        Line("Data used", NetMath.sizeText(r.used))
    }
    if (r.up == null) Note("The upload couldn't be measured this time.", Modifier.padding(top = 12.dp))
    DataWarning(link)
    Button(onClick = onAgain, enabled = link.network != null, modifier = Modifier.padding(top = 14.dp).fillMaxWidth().height(48.dp)) { Text("Test again") }
}
