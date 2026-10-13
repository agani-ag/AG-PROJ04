package com.agani.syncup.walkie

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.agani.syncup.BuildConfig
import com.agani.syncup.quickshare.QuickShare
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** How people talk in a channel — chosen by whoever starts it, for everyone. */
enum class TalkMode(val key: String) {
    HOLD("hold"), OPEN("open");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: HOLD
    }
}

/** Who can come in — chosen by whoever starts the channel. */
enum class JoinRule(val key: String) {
    ASK("ask"), ANYONE("anyone");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: ASK
    }
}

enum class WalkiePhase {
    IDLE,
    /** Asked to join, waiting for the channel to let this phone in. */
    JOINING,
    IN_CHANNEL,
    /** The phone running the channel went (dropped, left, handed over): finding the channel again. */
    SWITCHING,
}

/** A channel a nearby phone runs (from its advertisement). */
class WalkieChannel(
    val endpointId: String, val channelId: String, val name: String, val host: String, val people: Int,
    val mode: TalkMode, val join: JoinRule, val epoch: Int, val hostToken: String,
)

/** Someone in the channel, in the order they came in (the first is who started it). */
class WalkieMember(val token: String, val name: String) {
    val id: Int get() = idOf(token)
}

/** The phone running the channel: someone asked to join (both phones show [code]). */
class WalkieJoinRequest(val endpointId: String, val token: String, val name: String, val code: String)

internal fun idOf(token: String) = token.hashCode()

/**
 * Walkie Talkie (Tools → Walkie Talkie): talk to friends nearby — no internet, no sign-in.
 *
 * Google Nearby Connections, star-shaped: the phone running the channel (the "hub") passes every
 * voice on to the others. Each channel is its own; several can run side by side. Two ways to talk,
 * picked by whoever starts it: **Hold to talk** (one voice at a time — the hub gives the turn — up
 * to a minute each) or **Open line** (hands-free; voice goes out only while someone speaks). Join
 * rule, also picked at the start: ask first (code + Accept) or anyone nearby.
 *
 * If the hub leaves it hands the channel to someone (the longest-in by default); if its phone
 * just disappears, the next in line takes over by itself. Every phone keeps the people list and
 * settings for that. Voice is live only — never recorded or saved.
 *
 * Debug builds: `adb shell run-as com.agani.syncup touch files/walkie_test_tone` makes the mic send
 * a test tone (emulators have no real mic); "Walkie" log lines show what goes out and comes in.
 *
 * Main thread for everything public (Nearby calls back there too).
 */
object Walkie {
    private const val TAG = Voice.TAG
    private const val PREFS = "walkie"
    const val MAX_PEOPLE = 20
    /** Hold to talk: the longest one turn can be. */
    const val TALK_LIMIT_MS = 60_000L
    /** …and a buzz this long in. */
    private const val WARN_AT_MS = 50_000L
    /** Voice frames per payload (3 × 20 ms). */
    private const val FRAMES_PER_SEND = 3
    /** The hub has vanished: the next in line takes over after this (the one after, later still). */
    private const val TAKE_OVER_MS = 10_000L
    private const val TAKE_OVER_STEP_MS = 6_000L
    /** Open line: louder than this (RMS) counts as speaking. */
    private const val VAD_LEVEL = 600.0

    private val strategy = Strategy.P2P_STAR
    private lateinit var app: Context
    private val client: ConnectionsClient by lazy { Nearby.getConnectionsClient(app) }
    private val main = Handler(Looper.getMainLooper())
    private var ready = false

    // ------------------------------------------------------------------ what the screens show
    var phase by mutableStateOf(WalkiePhase.IDLE)
        private set
    /** This phone runs the channel. */
    var isHub by mutableStateOf(false)
        private set
    /** Channels nearby (while looking). */
    val channels = mutableStateListOf<WalkieChannel>()
    var channelName by mutableStateOf("")
        private set
    var hostName by mutableStateOf("")
        private set
    var hostToken by mutableStateOf("")
        private set
    var mode by mutableStateOf(TalkMode.HOLD)
        private set
    var joinRule by mutableStateOf(JoinRule.ASK)
        private set
    /** Everyone in the channel, in the order they came in. */
    val members = mutableStateListOf<WalkieMember>()
    /** Hold to talk: whose turn it is. */
    var floorId by mutableStateOf<Int?>(null)
        private set
    /** When the current turn began (elapsedRealtime here). */
    var floorSinceMs by mutableLongStateOf(0L)
        private set
    /** This phone's voice is going out. */
    var onAir by mutableStateOf(false)
        private set
    /** Hold to talk: pressed, waiting for the turn. */
    var asking by mutableStateOf(false)
        private set
    var talkStartedMs by mutableLongStateOf(0L)
        private set
    /** Open line: this phone's mic is off. */
    var micMuted by mutableStateOf(false)
        private set
    /** Incoming voices are silenced on this phone. */
    var othersMuted by mutableStateOf(false)
        private set
    var loudspeaker by mutableStateOf(true)
        private set
    /** Mirrors for the audio threads. */
    @Volatile private var loud = true
    @Volatile private var inCall = false
    /** A connected headset (voices go there), or null. */
    var headset by mutableStateOf<String?>(null)
        private set
    /** Who's heard right now (ids), this phone included while on air. */
    var speaking by mutableStateOf<Set<Int>>(emptySet())
        private set
    val joinRequests = mutableStateListOf<WalkieJoinRequest>()
    /** Joining an "ask first" channel: the code both phones show. */
    var code by mutableStateOf("")
        private set
    /** Something to tell the user once (a toast). */
    var notice by mutableStateOf<String?>(null)
    /** The channel was ended (shown until dismissed). */
    var ended by mutableStateOf<String?>(null)
        private set
    /** "Ravi left — Priya runs the channel now." */
    var banner by mutableStateOf<String?>(null)
        private set
    /** Someone pressed Call all (shown for a few seconds). */
    var callFrom by mutableStateOf<String?>(null)
        private set
    /** Your own per-channel switch: hold a volume key to talk. */
    var volumeKeyTalk by mutableStateOf(false)
        private set
    /** A real phone call is on: Walkie Talkie steps aside on this phone. */
    var inPhoneCall by mutableStateOf(false)
        private set
    /** The channel screen is on display (volume-key talk only works then). */
    var screenVisible = false

    val active: Boolean get() = phase != WalkiePhase.IDLE
    val inChannel: Boolean get() = phase == WalkiePhase.IN_CHANNEL || phase == WalkiePhase.SWITCHING
    val myId: Int get() = idOf(myToken)
    val myName: String get() = QuickShare.myName.ifBlank { "SyncUp" }
    val micAllowed: Boolean get() = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val floorName: String? get() = floorId?.let { id -> members.firstOrNull { it.id == id }?.name }
    /** Who'd run the channel next: the longest-in after this phone. */
    val successor: WalkieMember? get() = members.firstOrNull { it.token != myToken }
    fun isMe(m: WalkieMember) = m.token == myToken

    // ------------------------------------------------------------------ inside
    private var myToken = ""
    private var channelId = ""
    /** Goes up at every hand-over, so phones can tell the current hub from an old one. */
    private var epoch = 0
    /** Phones let in (and everyone in the channel): they come straight back after a drop. */
    private val trusted = HashSet<String>()

    // hub
    private class Guest(val endpointId: String, val token: String, val name: String) {
        val id = idOf(token)
    }
    private val guests = HashMap<String, Guest>()
    private val pendingJoins = HashMap<String, WalkieJoinRequest>()
    private var floorStartMs = 0L
    private var readvertise: Runnable? = null
    private var yieldWatch: Runnable? = null

    // member
    @Volatile private var hubEndpoint: String? = null
    private var expectHost: String? = null
    private var lostHost: String? = null
    private var takeOver: Runnable? = null
    private var leaving = false
    private var welcomed = false

    // voice
    private var voiceIn: VoiceIn? = null
    private var voiceOut: VoiceOut? = null
    private var beeps: Beeps? = null
    @Volatile private var voiceTargets: List<String> = emptyList()
    @Volatile private var micMode = TalkMode.HOLD
    @Volatile private var pressed = false
    @Volatile private var granted = false
    private val held = ArrayList<ByteArray>()
    private val batch = ArrayList<ByteArray>()
    private val preroll = ArrayDeque<ByteArray>()
    private var seq = 0
    private var hangover = 0
    private var openTalking = false
    private var warned = false
    private var ticker: Runnable? = null
    private var tick = 0
    private var lastCallMs = 0L
    @Volatile private var txCount = 0
    private val rxCount = HashMap<Int, Int>()

    fun init(context: Context) {
        if (ready) return
        ready = true
        app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        myToken = prefs.getString("token", null) ?: UUID.randomUUID().toString().replace("-", "").take(10).also {
            prefs.edit().putString("token", it).apply()
        }
        loudspeaker = prefs.getBoolean("loudspeaker", true)
        loud = loudspeaker
        QuickShare.init(app)
    }

    // ================================================================== looking for channels
    fun startBrowsing() {
        if (active) return
        channels.clear()
        discover()
    }

    fun stopBrowsing() {
        if (!active) {
            runCatching { client.stopDiscovery() }
            channels.clear()
        }
    }

    private fun discover() {
        client.startDiscovery(WalkieWire.SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { e -> if (!isAlready(e) && phase == WalkiePhase.IDLE) notice = friendly(e) }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != WalkieWire.SERVICE_ID) return
            val p = info.endpointName.split(WalkieWire.SEP)
            if (p.getOrNull(0) != "1" || p.size < 9) return
            val ch = WalkieChannel(
                endpointId, p[1], p[2], p[3], p[4].toIntOrNull() ?: 1, TalkMode.of(p[5]), JoinRule.of(p[6]), p[7].toIntOrNull() ?: 1, p[8],
            )
            channels.removeAll { it.endpointId == endpointId || (it.channelId == ch.channelId && it.hostToken == ch.hostToken) }
            channels.add(ch)
            if (ch.channelId != channelId || ch.hostToken == myToken) return
            when {
                phase == WalkiePhase.SWITCHING -> considerRejoin(ch)
                isHub -> considerYield(ch)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            channels.removeAll { it.endpointId == endpointId }
        }
    }

    // ================================================================== starting a channel
    fun start(name: String, talk: TalkMode, join: JoinRule) {
        if (active) return
        runCatching { client.stopDiscovery() }
        channels.clear()
        clearChannel()
        channelId = UUID.randomUUID().toString().take(6)
        epoch = 1
        channelName = name.trim().ifBlank { "$myName's channel" }.take(24)
        mode = talk
        micMode = talk
        joinRule = join
        isHub = true
        hostToken = myToken
        hostName = myName
        members.add(WalkieMember(myToken, myName))
        trusted.add(myToken)
        phase = WalkiePhase.IN_CHANNEL
        advertise()
        enterChannel()
    }

    private fun advertisedName(): String = listOf(
        "1", channelId, channelName.take(24), hostName.take(20), members.size.toString(), mode.key, joinRule.key, epoch.toString(), myToken,
    ).joinToString(WalkieWire.SEP.toString())

    private fun advertise() {
        client.startAdvertising(advertisedName(), WalkieWire.SERVICE_ID, hubLifecycle, AdvertisingOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { e -> if (!isAlready(e)) notice = friendly(e) }
    }

    /** People / settings changed: nearby phones see it a moment later. */
    private fun readvertiseSoon() {
        if (!isHub) return
        readvertise?.let { main.removeCallbacks(it) }
        readvertise = Runnable {
            if (!isHub) return@Runnable
            runCatching { client.stopAdvertising() }
            advertise()
        }.also { main.postDelayed(it, 2_500) }
    }

    fun acceptJoin(r: WalkieJoinRequest) {
        joinRequests.remove(r)
        trusted.add(r.token)
        client.acceptConnection(r.endpointId, hubPayloads).addOnFailureListener { e -> notice = friendly(e) }
    }

    fun declineJoin(r: WalkieJoinRequest) {
        joinRequests.remove(r)
        pendingJoins.remove(r.endpointId)
        runCatching { client.rejectConnection(r.endpointId) }
    }

    private val hubLifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (!isHub) return run { client.rejectConnection(endpointId) }
            val parts = info.endpointName.split(WalkieWire.SEP)
            val token = parts.getOrNull(0).orEmpty()
            val name = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "Someone"
            val r = WalkieJoinRequest(endpointId, token, name, info.authenticationDigits)
            pendingJoins[endpointId] = r
            when {
                members.size >= MAX_PEOPLE -> client.rejectConnection(endpointId)
                // Already let in (or in the channel before a hand-over) — or anyone may join.
                token in trusted || joinRule == JoinRule.ANYONE ->
                    client.acceptConnection(endpointId, hubPayloads).addOnFailureListener { pendingJoins.remove(endpointId) }
                else -> joinRequests.add(r)
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val r = pendingJoins.remove(endpointId)
            joinRequests.removeAll { it.endpointId == endpointId }
            if (result.status.statusCode != ConnectionsStatusCodes.STATUS_OK || r == null || !isHub) return
            trusted.add(r.token)
            // A reconnect replaces the old link of the same phone.
            guests.values.filter { it.token == r.token }.forEach { dropGuest(it.endpointId, quietly = true) }
            guests[endpointId] = Guest(endpointId, r.token, r.name)
            members.removeAll { it.token == r.token }
            members.add(WalkieMember(r.token, r.name))
            Log.d(TAG, "hub: ${r.name} joined (${members.size} people)")
            afterPeopleChange()
        }

        override fun onDisconnected(endpointId: String) {
            dropGuest(endpointId, quietly = false)
        }
    }

    private fun dropGuest(endpointId: String, quietly: Boolean) {
        val g = guests.remove(endpointId) ?: return
        if (quietly) runCatching { client.disconnectFromEndpoint(endpointId) }
        members.removeAll { it.token == g.token }
        if (floorId == g.id) floorId = null
        voiceOut?.forget(g.id)
        afterPeopleChange()
    }

    private fun afterPeopleChange() {
        updateTargets()
        broadcastState()
        readvertiseSoon()
        WalkieService.refresh(app)
    }

    private val hubPayloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val g = guests[endpointId] ?: return
            when (val m = WalkieWire.parse(bytes)) {
                is WalkieWire.Voice -> {
                    // Only their own voice, and only when it's theirs to talk.
                    if (m.sender != g.id || (mode == TalkMode.HOLD && floorId != g.id)) return
                    val others = guests.keys.filter { it != endpointId }
                    if (others.isNotEmpty()) runCatching { client.sendPayload(others, Payload.fromBytes(bytes)) }
                    heard(m)
                }
                is WalkieWire.Json -> onMemberMessage(g, m.o)
                null -> Unit
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private fun onMemberMessage(g: Guest, o: JSONObject) {
        when (o.optString("t")) {
            "talk" -> if (o.optBoolean("on")) {
                if (mode == TalkMode.HOLD && (floorId == null || floorId == g.id)) giveTurn(g.id) else sendState(listOf(g.endpointId))
            } else if (floorId == g.id) {
                endTurn()
            }
            "call" -> callAllFrom(g.name, except = g.endpointId)
            "bye" -> dropGuest(g.endpointId, quietly = true)
        }
    }

    // ------------------------------------------------------------------ hub: turns (Hold to talk)
    private fun giveTurn(id: Int) {
        floorId = id
        floorStartMs = SystemClock.elapsedRealtime()
        floorSinceMs = floorStartMs
        broadcastState()
        if (id == myId) turnGranted()
    }

    private fun endTurn() {
        floorId = null
        broadcastState()
    }

    private fun stateJson(): JSONObject {
        val f = floorId
        return WalkieWire.msg("state")
            .put("ch", channelId).put("name", channelName).put("host", hostToken).put("hostName", hostName)
            .put("epoch", epoch).put("mode", mode.key).put("join", joinRule.key)
            .put("members", JSONArray(members.map { JSONObject().put("t", it.token).put("n", it.name) }))
            .put("trusted", JSONArray(trusted.toList()))
            .put("floor", f ?: 0).put("hasFloor", f != null)
            .put("floorMs", if (f != null) SystemClock.elapsedRealtime() - floorStartMs else 0)
    }

    private fun broadcastState() {
        if (!isHub) return
        if (guests.isNotEmpty()) sendState(guests.keys.toList())
        WalkieService.refresh(app)
    }

    private fun sendState(to: List<String>) = sendTo(to, stateJson())

    // ------------------------------------------------------------------ hub: settings
    fun changeMode(m: TalkMode) {
        if (!isHub || m == mode) return
        stopMyTalk(sendRelease = false)
        mode = m
        micMode = m
        floorId = null
        // Nobody's mic goes live by surprise: on a switch to Open line every mic starts off.
        if (m == TalkMode.OPEN) micMuted = true
        refreshMic()
        broadcastState()
        readvertiseSoon()
    }

    fun changeJoinRule(r: JoinRule) {
        if (!isHub || r == joinRule) return
        joinRule = r
        broadcastState()
        readvertiseSoon()
    }

    // ================================================================== joining
    fun join(ch: WalkieChannel) {
        if (active) return
        clearChannel()
        channelId = ch.channelId
        channelName = ch.name
        hostName = ch.host
        hostToken = ch.hostToken
        mode = ch.mode
        micMode = ch.mode
        joinRule = ch.join
        epoch = ch.epoch
        phase = WalkiePhase.JOINING
        hubEndpoint = ch.endpointId
        code = ""
        leaving = false
        requestConnection(ch.endpointId)
    }

    fun cancelJoin() {
        hubEndpoint?.let { runCatching { client.disconnectFromEndpoint(it) } }
        backToStart()
    }

    private fun requestConnection(endpointId: String) {
        client.requestConnection("$myToken${WalkieWire.SEP}$myName", endpointId, memberLifecycle)
            .addOnFailureListener { e ->
                when (phase) {
                    WalkiePhase.JOINING -> {
                        notice = friendly(e)
                        backToStart()
                    }
                    WalkiePhase.SWITCHING -> if (hubEndpoint == endpointId) hubEndpoint = null // try the next sighting
                    else -> Unit
                }
            }
    }

    private val memberLifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (endpointId != hubEndpoint) return run { client.rejectConnection(endpointId) }
            code = info.authenticationDigits
            client.acceptConnection(endpointId, memberPayloads)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (endpointId != hubEndpoint) return
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> connected()
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> if (phase == WalkiePhase.JOINING) {
                    notice = "$hostName didn't let you in."
                    backToStart()
                } else {
                    hubEndpoint = null
                }
                else -> if (phase == WalkiePhase.JOINING) {
                    notice = "Couldn't join $channelName. Keep the phones close together and try again."
                    backToStart()
                } else {
                    hubEndpoint = null // try again when the channel shows up
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (endpointId != hubEndpoint || leaving || isHub) return
            hubLost()
        }
    }

    private fun connected() {
        runCatching { client.stopDiscovery() }
        channels.clear()
        val first = phase == WalkiePhase.JOINING
        phase = WalkiePhase.IN_CHANNEL
        takeOver?.let { main.removeCallbacks(it) }
        takeOver = null
        lostHost = null
        expectHost = null
        updateTargets()
        sendToHub(WalkieWire.msg("hello").put("token", myToken).put("name", myName))
        if (first) {
            // Joining an Open line channel you picked: your mic is on (the channel said so).
            micMuted = false
            enterChannel()
        }
        refreshMic()
        Log.d(TAG, "member: in $channelName (epoch $epoch)")
    }

    private val memberPayloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            when (val m = WalkieWire.parse(bytes)) {
                is WalkieWire.Voice -> if (m.sender != myId) heard(m)
                is WalkieWire.Json -> onHubMessage(m.o)
                null -> Unit
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private fun onHubMessage(o: JSONObject) {
        when (o.optString("t")) {
            "state" -> applyState(o)
            "call" -> called(o.optString("from"))
            "end" -> {
                ended = "${o.optString("by").ifBlank { hostName }} ended the channel."
                backToStart()
            }
            "handover" -> handedOver(o.optString("to"), o.optInt("epoch"), o.optString("from"), o.optBoolean("staying"))
        }
    }

    private fun applyState(o: JSONObject) {
        val oldHost = hostToken
        channelName = o.optString("name", channelName)
        hostToken = o.optString("host", hostToken)
        hostName = o.optString("hostName", hostName)
        epoch = maxOf(epoch, o.optInt("epoch", epoch))
        joinRule = JoinRule.of(o.optString("join"))
        val newMode = TalkMode.of(o.optString("mode"))
        if (newMode != mode) {
            stopMyTalk(sendRelease = false)
            mode = newMode
            micMode = newMode
            if (welcomed) {
                if (newMode == TalkMode.OPEN) {
                    micMuted = true
                    notice = "$hostName switched to Open line — tap the mic to talk."
                } else {
                    notice = "$hostName switched to Hold to talk."
                }
            }
        }
        o.optJSONArray("members")?.let { a ->
            members.clear()
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { members.add(WalkieMember(it.optString("t"), it.optString("n"))) }
        }
        o.optJSONArray("trusted")?.let { a -> for (i in 0 until a.length()) trusted.add(a.optString(i)) }
        members.forEach { trusted.add(it.token) }
        val f = if (o.optBoolean("hasFloor")) o.optInt("floor") else null
        if (f != floorId) floorSinceMs = SystemClock.elapsedRealtime() - o.optLong("floorMs")
        floorId = f
        when {
            f == myId -> turnGranted()
            // Asked for the turn but someone else has it (or lost it): stop.
            pressed && f != myId && (granted || f != null) -> {
                val wasOnAir = granted
                stopMyTalk(sendRelease = false)
                if (!wasOnAir) beeps?.busy()
            }
        }
        if (welcomed && oldHost.isNotBlank() && oldHost != hostToken && banner == null) banner = "$hostName runs the channel now."
        welcomed = true
        loadVolumeKeyTalk()
        refreshMic()
        WalkieService.refresh(app)
    }

    // ================================================================== losing the hub / hand-over
    private fun hubLost() {
        Log.d(TAG, "member: lost the hub ($hostName)")
        hubEndpoint = null
        updateTargets()
        stopMyTalk(sendRelease = false)
        floorId = null
        lostHost = hostToken
        phase = WalkiePhase.SWITCHING
        discover()
        planTakeOver()
        WalkieService.refresh(app)
    }

    /** If nobody runs the channel again soon, the next in line does — the one after a bit later. */
    private fun planTakeOver() {
        takeOver?.let { main.removeCallbacks(it) }
        val gone = lostHost
        val order = members.filter { it.token != gone }
        val i = order.indexOfFirst { it.token == myToken }
        if (i < 0) return
        val oldName = hostName
        takeOver = Runnable {
            // Still not back in (a try at an old sighting may hang): take over anyway.
            if (phase == WalkiePhase.SWITCHING) {
                becomeHub(epoch + 1, "$oldName's phone dropped out — you run the channel now.", gone)
            }
        }.also { main.postDelayed(it, TAKE_OVER_MS + i * TAKE_OVER_STEP_MS) }
    }

    /** A channel with our id showed up while finding the channel again. */
    private fun considerRejoin(ch: WalkieChannel) {
        if (hubEndpoint != null) return
        val ok = when {
            ch.epoch > epoch -> true
            ch.epoch == epoch -> expectHost == null || ch.hostToken == expectHost
            else -> false
        }
        if (!ok) return
        Log.d(TAG, "member: rejoining via ${ch.host} (epoch ${ch.epoch})")
        epoch = ch.epoch
        hubEndpoint = ch.endpointId
        requestConnection(ch.endpointId)
    }

    /** Two phones run the same channel (after a split): the newer hand-over / earlier in line stays. */
    private fun considerYield(ch: WalkieChannel) {
        val rank = { t: String -> members.indexOfFirst { it.token == t }.let { if (it < 0) Int.MAX_VALUE else it } }
        val yieldToIt = ch.epoch > epoch || (ch.epoch == epoch && rank(ch.hostToken) < rank(myToken))
        if (!yieldToIt) return
        Log.d(TAG, "hub: yielding to ${ch.host} (epoch ${ch.epoch})")
        stepDown(ch.hostToken, ch.epoch)
    }

    /** Stop running the channel and find it again as a member (its new hub is [to]). */
    private fun stepDown(to: String, newEpoch: Int) {
        val ids = guests.keys.toList()
        runCatching { client.stopAdvertising() }
        main.postDelayed({ ids.forEach { runCatching { client.disconnectFromEndpoint(it) } } }, 600)
        guests.clear()
        pendingJoins.clear()
        joinRequests.clear()
        isHub = false
        epoch = newEpoch
        expectHost = to
        lostHost = myToken
        hubEndpoint = null
        floorId = null
        stopYieldWatch()
        updateTargets()
        phase = WalkiePhase.SWITCHING
        discover()
        planTakeOver()
    }

    /** This phone runs the channel from now on. */
    private fun becomeHub(newEpoch: Int, note: String?, gone: String?) {
        Log.d(TAG, "becoming the hub (epoch $newEpoch)")
        hubEndpoint?.let { runCatching { client.disconnectFromEndpoint(it) } }
        hubEndpoint = null
        runCatching { client.stopDiscovery() }
        takeOver?.let { main.removeCallbacks(it) }
        takeOver = null
        isHub = true
        epoch = newEpoch
        hostToken = myToken
        hostName = myName
        if (gone != null) members.removeAll { it.token == gone }
        if (members.none { it.token == myToken }) members.add(0, WalkieMember(myToken, myName))
        trusted.addAll(members.map { it.token })
        guests.clear()
        floorId = null
        phase = WalkiePhase.IN_CHANNEL
        expectHost = null
        lostHost = null
        // Until the others are back, the list shows only this phone; they're re-added as they reconnect.
        members.retainAll { it.token == myToken }
        advertise()
        watchForTwin()
        banner = note
        updateTargets()
        refreshMic()
        WalkieService.refresh(app)
    }

    /** For a minute after taking over, look for another phone that also took over. */
    private fun watchForTwin() {
        stopYieldWatch()
        discover()
        yieldWatch = Runnable { if (isHub) runCatching { client.stopDiscovery() } }.also { main.postDelayed(it, 60_000) }
    }

    private fun stopYieldWatch() {
        yieldWatch?.let { main.removeCallbacks(it) }
        yieldWatch = null
    }

    /** Hub: give the channel to [to]. [leave] = this phone goes; otherwise it stays as a member. */
    fun handOver(to: WalkieMember, leave: Boolean) {
        if (!isHub || to.token == myToken) return
        val e = epoch + 1
        sendTo(guests.keys.toList(), WalkieWire.msg("handover").put("to", to.token).put("epoch", e).put("from", myName).put("staying", !leave))
        Log.d(TAG, "hub: handing over to ${to.name} (leave=$leave)")
        if (leave) {
            val ids = guests.keys.toList()
            main.postDelayed({ ids.forEach { runCatching { client.disconnectFromEndpoint(it) } } }, 800)
            leaving = true
            backToStart(disconnectNow = false)
        } else {
            banner = "${to.name} runs the channel now."
            stepDown(to.token, e)
        }
    }

    private fun handedOver(to: String, newEpoch: Int, from: String, staying: Boolean) {
        val toName = members.firstOrNull { it.token == to }?.name ?: "Someone"
        val old = hostToken
        hubEndpoint?.let { h -> main.postDelayed({ runCatching { client.disconnectFromEndpoint(h) } }, 200) }
        hubEndpoint = null
        if (to == myToken) {
            becomeHub(newEpoch, if (staying) "$from handed the channel to you." else "$from left — you run the channel now.", if (staying) null else old)
            return
        }
        stopMyTalk(sendRelease = false)
        floorId = null
        epoch = newEpoch
        expectHost = to
        lostHost = old
        if (!staying) members.removeAll { it.token == old }
        banner = if (staying) "$from handed the channel to $toName." else "$from left — $toName runs the channel now."
        updateTargets()
        phase = WalkiePhase.SWITCHING
        discover()
        planTakeOver()
        WalkieService.refresh(app)
    }

    fun dismissBanner() {
        banner = null
    }

    // ================================================================== leaving / ending
    /** A member leaves. (The hub hands over or ends instead — see the Leave sheet.) */
    fun leave() {
        if (isHub) {
            val next = successor
            if (next != null) handOver(next, leave = true) else end()
            return
        }
        leaving = true
        sendToHub(WalkieWire.msg("bye"))
        backToStart(disconnectNow = false)
    }

    /** Hub: end the channel for everyone. */
    fun end() {
        if (!isHub) return
        val ids = guests.keys.toList()
        sendTo(ids, WalkieWire.msg("end").put("by", myName))
        main.postDelayed({ ids.forEach { runCatching { client.disconnectFromEndpoint(it) } } }, 500)
        backToStart(disconnectNow = false)
    }

    fun dismissEnded() {
        ended = null
    }

    private fun backToStart(disconnectNow: Boolean = true) {
        runCatching { client.stopAdvertising() }
        runCatching { client.stopDiscovery() }
        val h = hubEndpoint
        if (h != null) {
            if (disconnectNow) runCatching { client.disconnectFromEndpoint(h) }
            else main.postDelayed({ runCatching { client.disconnectFromEndpoint(h) } }, 400)
        }
        leaveChannel()
        clearChannel()
        phase = WalkiePhase.IDLE
        isHub = false
    }

    private fun clearChannel() {
        readvertise?.let { main.removeCallbacks(it) }
        takeOver?.let { main.removeCallbacks(it) }
        takeOver = null
        stopYieldWatch()
        guests.clear()
        pendingJoins.clear()
        joinRequests.clear()
        trusted.clear()
        members.clear()
        hubEndpoint = null
        expectHost = null
        lostHost = null
        floorId = null
        code = ""
        banner = null
        callFrom = null
        speaking = emptySet()
        welcomed = false
        updateTargets()
    }

    // ================================================================== this phone's sound
    private fun enterChannel() {
        voiceOut = VoiceOut(onWake = { if (!inCall) AudioRoute.backToCommunication(app, loud) }).also { it.muted = othersMuted }
        beeps = Beeps()
        headset = AudioRoute.headset(app)
        AudioRoute.enter(app, loudspeaker)
        leaving = false
        loadVolumeKeyTalk()
        WalkieService.start(app)
        startTicker()
        refreshMic()
    }

    private fun leaveChannel() {
        stopMyTalk(sendRelease = false)
        voiceIn?.stop()
        voiceIn = null
        voiceOut?.release()
        voiceOut = null
        beeps?.release()
        beeps = null
        if (ready) AudioRoute.leave(app)
        stopTicker()
        WalkieService.stop(app)
        onAir = false
        asking = false
        inPhoneCall = false
        volumeKeyTalk = false
    }

    private fun heard(m: WalkieWire.Voice) {
        voiceOut?.push(m.sender, m.seq, m.codec, m.frames)
        if (BuildConfig.DEBUG) rxCount[m.sender] = (rxCount[m.sender] ?: 0) + m.frames.size
    }

    /** Mic on or off, to match: Open line + not muted, or Hold to talk while pressed. */
    private fun refreshMic() {
        val want = inChannel && micAllowed && !inPhoneCall &&
            ((mode == TalkMode.OPEN && !micMuted) || (mode == TalkMode.HOLD && pressed))
        if (want && voiceIn == null) {
            synchronized(batch) { batch.clear() }
            preroll.clear()
            hangover = 0
            openTalking = false
            AudioRoute.backToCommunication(app, loudspeaker)
            voiceIn = VoiceIn(app) { pcm, enc -> onMicFrame(pcm, enc) }.also { it.start() }
        } else if (!want && voiceIn != null) {
            voiceIn?.stop()
            voiceIn = null
            if (mode == TalkMode.OPEN && onAir) onAir = false
        }
    }

    /** Mic thread: one 20 ms frame. */
    private fun onMicFrame(pcm: ShortArray, encoded: ByteArray) {
        val out: List<ByteArray>
        synchronized(batch) {
            if (micMode == TalkMode.HOLD) {
                if (!pressed) {
                    if (batch.isNotEmpty()) flush()
                    return
                }
                if (!granted) {
                    // Pressed, turn not given yet: keep the first words, send them once it is.
                    synchronized(held) {
                        held.add(encoded)
                        if (held.size > 50) held.removeAt(0)
                    }
                    return
                }
                synchronized(held) {
                    batch.addAll(held)
                    held.clear()
                }
                batch.add(encoded)
            } else {
                val loud = Voice.rms(pcm) > VAD_LEVEL
                hangover = if (loud) 20 else (hangover - 1).coerceAtLeast(0)
                val talking = loud || hangover > 0
                if (talking != openTalking) {
                    openTalking = talking
                    main.post { if (mode == TalkMode.OPEN) onAir = talking }
                    if (!talking && batch.isNotEmpty()) flush()
                }
                if (!talking) {
                    // A little from just before, so the first sound isn't cut.
                    preroll.addLast(encoded)
                    while (preroll.size > 3) preroll.removeFirst()
                    return
                }
                batch.addAll(preroll)
                preroll.clear()
                batch.add(encoded)
            }
            if (batch.size < FRAMES_PER_SEND) return
            out = batch.toList()
            batch.clear()
        }
        sendVoice(out)
    }

    private fun flush() {
        val out = batch.toList()
        batch.clear()
        sendVoice(out)
    }

    private fun sendVoice(frames: List<ByteArray>) {
        if (frames.isEmpty()) return
        val targets = voiceTargets
        if (targets.isEmpty()) return
        val bytes = WalkieWire.voice(myId, seq++, Voice.sendCodec, frames)
        runCatching { client.sendPayload(targets, Payload.fromBytes(bytes)) }
        txCount += frames.size
    }

    private fun updateTargets() {
        voiceTargets = if (isHub) guests.keys.toList() else listOfNotNull(hubEndpoint)
    }

    // ------------------------------------------------------------------ Hold to talk
    /** The talk button (or a volume key) went down. */
    fun pressTalk() {
        if (!inChannel || mode != TalkMode.HOLD || pressed || phase != WalkiePhase.IN_CHANNEL) return
        if (!micAllowed) {
            notice = "Allow the microphone to talk."
            return
        }
        if (inPhoneCall) return
        val f = floorId
        if (f != null && f != myId) {
            beeps?.busy()
            return
        }
        pressed = true
        granted = false
        asking = true
        synchronized(held) { held.clear() }
        refreshMic()
        if (isHub) {
            giveTurn(myId)
        } else {
            sendToHub(WalkieWire.msg("talk").put("on", true))
            main.postDelayed(askTimeout, 2_000)
        }
    }

    private val askTimeout = Runnable {
        if (pressed && !granted) {
            stopMyTalk(sendRelease = true)
            notice = "Couldn't get through — try again."
        }
    }

    private fun turnGranted() {
        if (!pressed || granted) return
        main.removeCallbacks(askTimeout)
        granted = true
        asking = false
        onAir = true
        talkStartedMs = SystemClock.elapsedRealtime()
        warned = false
        beeps?.start()
        WalkieService.refresh(app)
    }

    /** The talk button (or volume key) came up. */
    fun releaseTalk() {
        if (!pressed) return
        val wasOnAir = granted
        stopMyTalk(sendRelease = true)
        if (wasOnAir) beeps?.over()
    }

    private fun stopMyTalk(sendRelease: Boolean) {
        main.removeCallbacks(askTimeout)
        if (!pressed && !granted) return
        pressed = false
        granted = false
        asking = false
        if (mode == TalkMode.HOLD) onAir = false
        synchronized(held) { held.clear() }
        if (sendRelease) {
            if (isHub) {
                if (floorId == myId) endTurn()
            } else {
                // Just after the last words, so the hub doesn't cut them off.
                main.postDelayed({ if (!pressed) sendToHub(WalkieWire.msg("talk").put("on", false)) }, 150)
            }
        }
        // Let the last words out, then the mic goes off.
        main.postDelayed({ refreshMic() }, 120)
        WalkieService.refresh(app)
    }

    /** Lock-screen Talk: tap to start, tap to stop (Open line: mic on / off). */
    fun toggleTalk() {
        when {
            mode == TalkMode.OPEN -> toggleMic()
            pressed -> releaseTalk()
            else -> pressTalk()
        }
    }

    // ------------------------------------------------------------------ Open line
    fun toggleMic() {
        if (mode != TalkMode.OPEN) return
        if (!micAllowed) {
            notice = "Allow the microphone to talk."
            return
        }
        micMuted = !micMuted
        refreshMic()
        WalkieService.refresh(app)
    }

    // ------------------------------------------------------------------ the bottom row
    fun toggleLoudspeaker() {
        loudspeaker = !loudspeaker
        loud = loudspeaker
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("loudspeaker", loudspeaker).apply()
        AudioRoute.apply(app, loudspeaker)
    }

    fun toggleOthersMuted() {
        othersMuted = !othersMuted
        voiceOut?.muted = othersMuted || inPhoneCall
        WalkieService.refresh(app)
    }

    /** Beep everyone's phone (once every few seconds at most). */
    fun callAll() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCallMs < 5_000 || !inChannel) return
        lastCallMs = now
        if (isHub) callAllFrom(myName, except = null) else sendToHub(WalkieWire.msg("call"))
        notice = "Calling everyone…"
    }

    private fun callAllFrom(name: String, except: String?) {
        sendTo(guests.keys.filter { it != except }, WalkieWire.msg("call").put("from", name))
        if (name != myName || except != null) called(name)
    }

    private fun called(from: String) {
        callFrom = from
        beeps?.call()
        vibrate(400)
        WalkieService.refresh(app)
        main.postDelayed({ if (callFrom == from) callFrom = null; WalkieService.refresh(app) }, 6_000)
    }

    // ------------------------------------------------------------------ volume-key talk
    fun changeVolumeKeyTalk(on: Boolean) {
        volumeKeyTalk = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("vkey_$channelId", on).apply()
    }

    private fun loadVolumeKeyTalk() {
        if (channelId.isNotBlank()) volumeKeyTalk = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("vkey_$channelId", false)
    }

    private var keyTalking = false
    private val keyStart = Runnable {
        keyTalking = true
        pressTalk()
    }

    /** From MainActivity: a volume key. True = Walkie Talkie used it (hold ½ s = talk; a tap still changes the volume). */
    fun onVolumeKey(event: KeyEvent): Boolean {
        val key = event.keyCode
        if (key != KeyEvent.KEYCODE_VOLUME_UP && key != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (!(inChannel && mode == TalkMode.HOLD && volumeKeyTalk && screenVisible) && !keyTalking) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                keyTalking = false
                main.postDelayed(keyStart, 500)
            }
            KeyEvent.ACTION_UP -> {
                main.removeCallbacks(keyStart)
                if (keyTalking) {
                    keyTalking = false
                    releaseTalk()
                } else {
                    (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).adjustStreamVolume(
                        AudioManager.STREAM_VOICE_CALL,
                        if (key == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                        AudioManager.FLAG_SHOW_UI,
                    )
                }
            }
        }
        return true
    }

    // ------------------------------------------------------------------ every 200 ms in a channel
    private fun startTicker() {
        stopTicker()
        tick = 0
        ticker = object : Runnable {
            override fun run() {
                onTick()
                main.postDelayed(this, 200)
            }
        }.also { main.postDelayed(it, 200) }
    }

    private fun stopTicker() {
        ticker?.let { main.removeCallbacks(it) }
        ticker = null
    }

    private fun onTick() {
        tick++
        val now = SystemClock.elapsedRealtime()
        val heardNow = voiceOut?.speaking(now) ?: emptySet()
        val s = if (onAir) heardNow + myId else heardNow
        if (s != speaking) speaking = s
        // My turn's time limit.
        if (granted && mode == TalkMode.HOLD) {
            val t = now - talkStartedMs
            if (t >= WARN_AT_MS && !warned) {
                warned = true
                vibrate(150)
            }
            if (t >= TALK_LIMIT_MS) {
                releaseTalk()
                notice = "1 minute is the limit — hold again to carry on."
            }
        }
        // Hub: nobody keeps the turn past the limit (a stuck phone).
        if (isHub && floorId != null && floorId != myId && now - floorStartMs > TALK_LIMIT_MS + 3_000) endTurn()
        if (tick % 5 == 0) {
            // A real phone call: step aside on this phone, come back after.
            val call = AudioRoute.inPhoneCall(app)
            if (call != inPhoneCall) {
                inPhoneCall = call
                inCall = call
                if (call) {
                    if (pressed) releaseTalk()
                } else {
                    AudioRoute.backToCommunication(app, loudspeaker)
                }
                voiceOut?.muted = othersMuted || call
                refreshMic()
                WalkieService.refresh(app)
            }
            val h = AudioRoute.headset(app)
            if (h != headset) {
                headset = h
                AudioRoute.apply(app, loudspeaker)
            }
        }
        if (BuildConfig.DEBUG && tick % 10 == 0 && (txCount > 0 || rxCount.isNotEmpty())) {
            val rx = rxCount.entries.joinToString { (id, n) -> "${members.firstOrNull { it.id == id }?.name ?: id}=$n rms=${"%.0f".format(voiceOut?.level(id) ?: 0.0)}" }
            Log.d(TAG, "2s: sent $txCount frames; heard $rx; floor=${floorName ?: "-"} hub=$isHub")
            txCount = 0
            rxCount.clear()
        }
    }

    private fun vibrate(ms: Long) {
        @Suppress("DEPRECATION")
        val v = app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") v.vibrate(ms)
        }
    }

    /** One line for the notification / Tools tile. */
    fun statusLine(): String = when {
        phase == WalkiePhase.JOINING -> "Joining…"
        inPhoneCall -> "Paused for a phone call"
        phase == WalkiePhase.SWITCHING -> "Reconnecting…"
        callFrom != null -> "$callFrom is calling everyone"
        onAir -> "You're on air"
        mode == TalkMode.HOLD && floorId != null && floorId != myId -> "${floorName ?: "Someone"} is talking…"
        mode == TalkMode.OPEN && speaking.any { it != myId } ->
            members.filter { it.id in speaking && it.token != myToken }.joinToString { it.name } + " talking"
        mode == TalkMode.OPEN && micMuted -> "Your mic is off"
        else -> "Connected"
    }

    // ================================================================== sending
    private fun sendTo(endpoints: List<String>, o: JSONObject) {
        if (endpoints.isEmpty()) return
        runCatching { client.sendPayload(endpoints, Payload.fromBytes(WalkieWire.json(o))) }
            .onFailure { Log.w(TAG, "send failed", it) }
    }

    private fun sendToHub(o: JSONObject) {
        val h = hubEndpoint ?: return
        sendTo(listOf(h), o)
    }

    private fun isAlready(e: Exception) =
        (e as? ApiException)?.statusCode.let { it == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING || it == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING }

    private fun friendly(e: Exception): String {
        val code = (e as? ApiException)?.statusCode ?: return "Something went wrong. Try again."
        return when (code) {
            ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH, ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_ADMIN,
            ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_WIFI_STATE, ConnectionsStatusCodes.MISSING_PERMISSION_CHANGE_WIFI_STATE,
            ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_COARSE_LOCATION,
            -> "SyncUp needs the Nearby devices permission for Walkie Talkie."
            ConnectionsStatusCodes.STATUS_RADIO_ERROR -> "Turn on Bluetooth and Wi-Fi, then try again."
            else -> "Couldn't connect (code $code). Turn on Bluetooth and Wi-Fi, then try again."
        }
    }
}
