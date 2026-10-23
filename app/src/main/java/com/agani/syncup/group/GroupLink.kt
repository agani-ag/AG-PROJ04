package com.agani.syncup.group

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.agani.syncup.BuildConfig
import com.agani.syncup.decide.Together
import com.agani.syncup.party.Party
import com.agani.syncup.party.PartyClock
import com.agani.syncup.quickshare.QsPhase
import com.agani.syncup.quickshare.QuickShare
import com.agani.syncup.walkie.Walkie
import com.agani.syncup.watchparty.WatchParty
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.BandwidthInfo
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What a group is doing right now — the host switches it, every phone follows. */
enum class GroupMode(val key: String, val label: String) {
    MUSIC("music", "Music party"),
    WATCH("watch", "Watch together"),
    WALKIE("walkie", "Walkie talkie"),
    SHARE("share", "Share files"),
    PHOTOS("photos", "Group photos");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

enum class GroupRole { NONE, HOST, GUEST }

enum class GroupPhase {
    IDLE,
    /** Host: the group is on (phones nearby can find it). */
    HOSTING,
    /** Guest: asked to join, waiting for the host to accept. */
    JOINING,
    /** Guest: in the group. */
    IN_GROUP,
    /** Guest: the host's phone dropped — finding it again (what's playing carries on). */
    RECONNECTING,
}

/** A group a nearby phone is hosting (from its advertisement). */
class GroupRoom(
    val endpointId: String, val groupId: String, val name: String, val host: String, val people: Int, val mode: GroupMode, val title: String,
)

/** Host: someone asked to join (both phones show [code]). */
class GroupJoinRequest(val endpointId: String, val token: String, val name: String, val code: String)

/** Someone in the group, host first. [quality] = their link's [BandwidthInfo.Quality] (LOW = Bluetooth only). */
class GroupPerson(val name: String, val token: String, val host: Boolean, val quality: Int)

/**
 * One mode's side of the group link (Music party, Watch together, Walkie talkie, Share files, Group photos).
 * On the host, an endpoint id is a guest; on a guest, it is the host. Main thread, except
 * [onReceive] / [onTransfer] / [onFile], which come from Nearby (also the main thread).
 */
internal interface GroupChannel {
    /** A message on this mode's channel (the channel byte already taken off). */
    fun onReceive(endpointId: String, body: ByteArray, arrivedNs: Long)
    fun onTransfer(endpointId: String, update: PayloadTransferUpdate) {}
    fun onFile(endpointId: String, payload: Payload) {}
    /** Host: a phone came in (or came back after a drop). */
    fun onMemberIn(endpointId: String, token: String, name: String) {}
    /** Host: a phone went. */
    fun onMemberOut(endpointId: String) {}
    /** Guest: (re)connected to the host. */
    fun onLinkUp() {}
    /** Guest: the host's phone dropped (finding it again). */
    fun onLinkDown() {}
    /** Guest: a new clock measurement (see [GroupLink.clock]). */
    fun onClock() {}
    /** The group is in this mode now. [resume]: it was in it before — carry on from there. */
    fun activate(resume: Boolean)
    /** The group switched to another mode: stop, but keep what's needed to come back. */
    fun deactivate()
    /** The group is over (left / ended): forget everything. */
    fun reset()
    /** One line for the notification. */
    fun statusText(): String? = null
}

/**
 * SyncUp Group: the one connection a group of nearby phones shares, whatever they do together.
 *
 * Google Nearby Connections, star-shaped: the host's phone in the middle, up to [MAX_PHONES] in all.
 * This object owns finding groups, joining (code + Accept), who's in, each phone's link speed, the
 * host's clock (measured once — music and video both use it), reconnecting, and the notification
 * ([GroupService]). The modes — [Party] (music), [WatchParty], [GroupWalkie], [GroupShare], [GroupPhotos] — run on
 * top of it: each has its own channel (the first byte of every message), and the host's
 * [switchTo] moves every phone to another mode at once — nobody joins again.
 *
 * Main thread for everything public (Nearby calls back there too).
 */
object GroupLink {
    private const val TAG = "Group"
    private const val SERVICE_ID = "com.agani.syncup.group"
    private const val PREFS = "group"
    /** Phones in a group, the host included (Watch together's limit, for every mode). */
    const val MAX_PHONES = 8
    private const val SEP = '\u001F'

    internal const val CH_GROUP: Byte = 0
    internal const val CH_MUSIC: Byte = 1
    internal const val CH_WATCH: Byte = 2
    internal const val CH_WALKIE: Byte = 3
    internal const val CH_SHARE: Byte = 4
    /** Decide's Coin toss / Wheel for everyone ([Together]): a side channel, not a mode — it works whatever mode is on. */
    internal const val CH_DECIDE: Byte = 5
    internal const val CH_PHOTOS: Byte = 6

    private val strategy = Strategy.P2P_STAR
    private lateinit var app: Context
    private val client: ConnectionsClient by lazy { Nearby.getConnectionsClient(app) }
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private var ready = false

    // ------------------------------------------------------------------ what the screens show
    var role by mutableStateOf(GroupRole.NONE)
        private set
    var phase by mutableStateOf(GroupPhase.IDLE)
        private set
    /** Groups nearby (while looking). */
    val rooms = mutableStateListOf<GroupRoom>()
    var groupName by mutableStateOf("")
        private set
    var hostName by mutableStateOf("")
        private set
    /** Everyone in the group, host first. */
    val people = mutableStateListOf<GroupPerson>()
    var mode by mutableStateOf(GroupMode.MUSIC)
        private set
    /** Goes up with every switch (screens follow it to the new mode). */
    var modeGen by mutableIntStateOf(0)
        private set
    /** Guest, joining: the code both phones show. */
    var joinCode by mutableStateOf("")
        private set
    val joinRequests = mutableStateListOf<GroupJoinRequest>()
    /** Something to tell the user once (a toast). */
    var notice by mutableStateOf<String?>(null)
    /** Guest: the group ended (shown until dismissed). */
    var ended by mutableStateOf<String?>(null)
        private set
    /** Guest: the host just switched the group (from → to), shown for a moment. */
    var switched by mutableStateOf<Pair<GroupMode, GroupMode>?>(null)
        private set
    /** Guest: this phone's link to the host ([BandwidthInfo.Quality]). */
    var linkQuality by mutableIntStateOf(BandwidthInfo.Quality.UNKNOWN)
        private set
    /** Someone asked to see the group's mode screen (a card, the notification): screens go there. */
    var openRequest by mutableIntStateOf(0)
        private set
    /** Host: the notification's Switch was tapped — the app shows the Switch sheet (it sets this back). */
    var switchWanted by mutableStateOf(false)

    val active: Boolean get() = phase != GroupPhase.IDLE
    /** In a group (not just asking to join). */
    val inGroup: Boolean get() = phase == GroupPhase.HOSTING || phase == GroupPhase.IN_GROUP || phase == GroupPhase.RECONNECTING
    val isHost: Boolean get() = role == GroupRole.HOST
    val peopleNames: List<String> get() = people.map { it.name }
    /** Phones on a slow (Bluetooth-only) link. */
    val slowPeople: List<String> get() = people.filter { it.quality == BandwidthInfo.Quality.LOW }.map { it.name }

    /** The host's clock, as this phone sees it (guests); measured once for every mode. */
    internal val clock = PartyClock()
    internal val myName: String get() = QuickShare.myName.ifBlank { "SyncUp" }
    internal var myToken = ""
        private set

    // ------------------------------------------------------------------ inside
    private class Member(val endpointId: String, val token: String, val name: String) {
        @Volatile var quality = BandwidthInfo.Quality.UNKNOWN
    }

    private var groupId = ""
    private var title = ""
    @Volatile internal var hostEndpoint: String? = null
        private set
    /** Host: the guests, by endpoint id. */
    private val members = ConcurrentHashMap<String, Member>()
    private val pendingJoins = HashMap<String, GroupJoinRequest>()
    /** Phones let in once: back after a drop, they come straight in. */
    private val knownTokens = HashMap<String, String>()
    private val channels = HashMap<GroupMode, GroupChannel>()
    /** The modes this group has been in (switching back carries on). */
    private val modesUsed = HashSet<GroupMode>()
    private var opts = JSONObject()
    private var heartbeat: Job? = null
    private var readvertise: Runnable? = null
    private var pinger: Job? = null
    private var pingId = 0
    private val pingSent = HashMap<Int, Long>()
    private var leaving = false
    private var stateSeq = 0
    private var lastSeq = -1
    /** Guest: the group's mode is running on this phone (after the first "group" message). */
    private var modeOn = false

    fun init(context: Context) {
        if (ready) return
        ready = true
        app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        myToken = prefs.getString("token", null) ?: UUID.randomUUID().toString().take(12).also { prefs.edit().putString("token", it).apply() }
        QuickShare.init(app)
        // The modes sign in to their channels.
        Party.init(app)
        WatchParty.init(app)
        GroupWalkie.init(app)
        GroupShare.init(app)
        GroupPhotos.init(app)
    }

    internal fun register(mode: GroupMode, channel: GroupChannel) {
        channels[mode] = channel
    }

    /** The mode's options the host chose when switching (e.g. Walkie talkie's talk style). */
    internal fun option(key: String): String = opts.optString(key)

    private fun channelByte(m: GroupMode): Byte = when (m) {
        GroupMode.MUSIC -> CH_MUSIC
        GroupMode.WATCH -> CH_WATCH
        GroupMode.WALKIE -> CH_WALKIE
        GroupMode.SHARE -> CH_SHARE
        GroupMode.PHOTOS -> CH_PHOTOS
    }

    private fun modeOf(ch: Byte): GroupMode? = when (ch) {
        CH_MUSIC -> GroupMode.MUSIC
        CH_WATCH -> GroupMode.WATCH
        CH_WALKIE -> GroupMode.WALKIE
        CH_SHARE -> GroupMode.SHARE
        CH_PHOTOS -> GroupMode.PHOTOS
        else -> null
    }

    /** Another nearby feature is busy on this phone (only one can use Nearby at a time). */
    fun busyElsewhere(): String? = when {
        QuickShare.phase != QsPhase.IDLE -> "Finish SyncUp Share first"
        Walkie.active -> "Leave Walkie Talkie first"
        else -> null
    }

    /** Ask to see the group's current mode (the screens route there). */
    fun requestOpen() {
        openRequest++
    }

    /** Host: open "Switch the group to…" (the notification's Switch). */
    fun requestSwitch() {
        if (isHost && inGroup) switchWanted = true
    }

    /** This group's id (settings kept per group, e.g. volume-key talk). */
    internal val id: String get() = groupId

    // ================================================================== browsing (guests)
    fun startBrowsing() {
        if (active && phase != GroupPhase.RECONNECTING) return
        if (busyElsewhere() != null) return
        if (phase == GroupPhase.IDLE) rooms.clear()
        client.startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { e -> if (!isAlready(e)) notice = friendly(e) }
    }

    fun stopBrowsing() {
        if (phase == GroupPhase.IDLE || phase == GroupPhase.HOSTING) runCatching { client.stopDiscovery() }
        if (phase == GroupPhase.IDLE) rooms.clear()
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID) return
            val p = info.endpointName.split(SEP)
            if (p.getOrNull(0) != "G1" || p.size < 7) return
            val room = GroupRoom(endpointId, p[1], p[2], p[3], p[4].toIntOrNull() ?: 1, GroupMode.of(p[5]) ?: GroupMode.MUSIC, p[6])
            rooms.removeAll { it.endpointId == endpointId || it.groupId == room.groupId }
            rooms.add(room)
            // Back after a drop: rejoin our group by itself.
            if (phase == GroupPhase.RECONNECTING && room.groupId == groupId) rejoin(room)
        }

        override fun onEndpointLost(endpointId: String) {
            rooms.removeAll { it.endpointId == endpointId }
        }
    }

    // ================================================================== hosting
    /** Start a group in [startMode]; that mode starts on this phone at once. */
    fun startGroup(name: String, startMode: GroupMode): Boolean {
        if (active) return false
        busyElsewhere()?.let {
            notice = it
            return false
        }
        runCatching { client.stopDiscovery() }
        clear()
        role = GroupRole.HOST
        phase = GroupPhase.HOSTING
        groupId = UUID.randomUUID().toString().take(6)
        groupName = name.trim().ifBlank { "$myName's group" }.take(30)
        hostName = myName
        mode = startMode
        modeGen = 1
        opts = JSONObject()
        refreshPeople()
        advertise()
        heartbeat = io.launch {
            while (isActive) {
                delay(10_000)
                main.post { if (role == GroupRole.HOST) broadcastGroup() }
            }
        }
        GroupService.start(app)
        modesUsed.add(startMode)
        channels[startMode]?.activate(resume = false)
        return true
    }

    /**
     * Host: move the whole group to [to]. [resume]: carry on where that mode was (else it starts
     * fresh — the caller then gives it its video / songs). [options]: e.g. {"talk": "hold"}.
     */
    fun switchTo(to: GroupMode, options: JSONObject = JSONObject(), resume: Boolean = true) {
        if (role != GroupRole.HOST) return
        if (to == mode) {
            opts = options
            return
        }
        val from = mode
        channels[from]?.deactivate()
        mode = to
        modeGen++
        opts = options
        // Everyone switches first, so they're ready for the new mode's first word.
        broadcastGroup()
        val carryOn = resume && to in modesUsed
        modesUsed.add(to)
        channels[to]?.activate(carryOn)
        title = ""
        readvertiseSoon()
        GroupService.refresh(app)
    }

    /** Host: what's on now ("Evening Walk", "Leo (2023)") — phones nearby see it with the group. */
    internal fun setTitle(t: String) {
        if (role != GroupRole.HOST || t == title) return
        title = t
        readvertiseSoon()
        GroupService.refresh(app)
    }

    private fun advertisedName(): String = listOf(
        "G1", groupId, groupName.take(30), hostName.take(24), people.size.toString(), mode.key, title.take(28),
    ).joinToString(SEP.toString())

    private fun advertise() {
        client.startAdvertising(advertisedName(), SERVICE_ID, hostLifecycle, AdvertisingOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { e -> if (!isAlready(e)) notice = friendly(e) }
    }

    /** People / mode / what's on changed: phones nearby see it a moment later (debounced). */
    private fun readvertiseSoon() {
        if (role != GroupRole.HOST) return
        readvertise?.let { main.removeCallbacks(it) }
        readvertise = Runnable {
            if (role != GroupRole.HOST) return@Runnable
            runCatching { client.stopAdvertising() }
            advertise()
        }.also { main.postDelayed(it, 2_500) }
    }

    fun acceptJoin(r: GroupJoinRequest) {
        joinRequests.remove(r)
        knownTokens[r.token] = r.name
        client.acceptConnection(r.endpointId, payloads).addOnFailureListener { e -> notice = friendly(e) }
    }

    fun declineJoin(r: GroupJoinRequest) {
        joinRequests.remove(r)
        pendingJoins.remove(r.endpointId)
        runCatching { client.rejectConnection(r.endpointId) }
    }

    /** Host: take someone out of the group. */
    fun remove(name: String) {
        if (role != GroupRole.HOST) return
        val m = members.values.firstOrNull { it.name == name } ?: return
        knownTokens.remove(m.token)
        send(m.endpointId, groupPayload(msg("removed")))
        main.postDelayed({ runCatching { client.disconnectFromEndpoint(m.endpointId) } }, 300)
        dropMember(m.endpointId)
    }

    private val hostLifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (role != GroupRole.HOST) return run { client.rejectConnection(endpointId) }
            val parts = info.endpointName.split(SEP)
            val token = parts.getOrNull(0).orEmpty()
            val name = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "A guest"
            val r = GroupJoinRequest(endpointId, token, name, info.authenticationDigits)
            pendingJoins[endpointId] = r
            val back = token.isNotBlank() && knownTokens.containsKey(token)
            when {
                // Full (a phone coming back after a drop still fits: its old place is taken over).
                !back && members.size >= MAX_PHONES - 1 -> {
                    pendingJoins.remove(endpointId)
                    client.rejectConnection(endpointId)
                }
                back -> client.acceptConnection(endpointId, payloads).addOnFailureListener { pendingJoins.remove(endpointId) }
                else -> joinRequests.add(r)
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val r = pendingJoins.remove(endpointId)
            joinRequests.removeAll { it.endpointId == endpointId }
            if (result.status.statusCode != ConnectionsStatusCodes.STATUS_OK || r == null || role != GroupRole.HOST) return
            // A reconnect replaces the old link of the same phone.
            members.values.filter { it.token == r.token }.forEach { dropMember(it.endpointId, quietly = true) }
            members[endpointId] = Member(endpointId, r.token, r.name)
            refreshPeople()
            channels[mode]?.onMemberIn(endpointId, r.token, r.name)
        }

        override fun onDisconnected(endpointId: String) {
            dropMember(endpointId)
        }

        override fun onBandwidthChanged(endpointId: String, info: BandwidthInfo) {
            val m = members[endpointId] ?: return
            m.quality = info.quality
            if (BuildConfig.DEBUG) Log.d(TAG, "link to ${m.name}: quality ${info.quality}")
            refreshPeople()
        }
    }

    private fun dropMember(endpointId: String, quietly: Boolean = false) {
        members.remove(endpointId) ?: return
        if (quietly) runCatching { client.disconnectFromEndpoint(endpointId) }
        channels.values.forEach { it.onMemberOut(endpointId) }
        refreshPeople()
    }

    private fun refreshPeople() {
        people.clear()
        people.add(GroupPerson(hostName, myToken, host = true, quality = BandwidthInfo.Quality.HIGH))
        members.values.forEach { people.add(GroupPerson(it.name, it.token, host = false, quality = it.quality)) }
        broadcastGroup()
        readvertiseSoon()
        GroupService.refresh(app)
    }

    private fun groupJson(): JSONObject {
        val ppl = JSONArray()
        people.forEach { ppl.put(JSONObject().put("n", it.name).put("k", it.token).put("h", it.host).put("q", it.quality)) }
        return msg("group")
            .put("seq", ++stateSeq)
            .put("gid", groupId).put("name", groupName).put("host", hostName)
            .put("people", ppl)
            .put("mode", mode.key).put("gen", modeGen).put("opts", opts)
    }

    private fun broadcastGroup() {
        if (role != GroupRole.HOST || members.isEmpty()) return
        send(members.keys, groupPayload(groupJson()))
    }

    // ================================================================== joining (guests)
    fun join(room: GroupRoom) {
        if (active) return
        busyElsewhere()?.let {
            notice = it
            return
        }
        clear()
        role = GroupRole.GUEST
        phase = GroupPhase.JOINING
        groupId = room.groupId
        groupName = room.name
        hostName = room.host
        mode = room.mode
        hostEndpoint = room.endpointId
        leaving = false
        requestConnection(room.endpointId)
    }

    private fun requestConnection(endpointId: String) {
        client.requestConnection("$myToken$SEP$myName", endpointId, guestLifecycle)
            .addOnFailureListener { e ->
                if (phase == GroupPhase.JOINING) {
                    notice = friendly(e)
                    backToStart()
                }
            }
    }

    private fun rejoin(room: GroupRoom) {
        if (hostEndpoint == room.endpointId) return
        hostEndpoint = room.endpointId
        requestConnection(room.endpointId)
    }

    fun cancelJoin() {
        hostEndpoint?.let { runCatching { client.disconnectFromEndpoint(it) } }
        backToStart()
    }

    private val guestLifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (endpointId != hostEndpoint) return run { client.rejectConnection(endpointId) }
            joinCode = info.authenticationDigits
            client.acceptConnection(endpointId, payloads)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (endpointId != hostEndpoint) return
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    runCatching { client.stopDiscovery() }
                    val first = phase == GroupPhase.JOINING
                    phase = GroupPhase.IN_GROUP
                    startPinging()
                    if (first) GroupService.start(app) else if (modeOn) channels[mode]?.onLinkUp()
                    GroupService.refresh(app)
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    notice = "$hostName didn't let you in."
                    backToStart()
                }
                else -> if (phase == GroupPhase.JOINING) {
                    notice = "Couldn't join $groupName. Keep the phones close together and try again."
                    backToStart()
                } else {
                    hostEndpoint = null // try again when the group shows up
                }
            }
        }

        override fun onBandwidthChanged(endpointId: String, info: BandwidthInfo) {
            if (endpointId != hostEndpoint) return
            linkQuality = info.quality
            if (BuildConfig.DEBUG) Log.d(TAG, "link to host: quality ${info.quality}")
        }

        override fun onDisconnected(endpointId: String) {
            if (endpointId != hostEndpoint || leaving || role != GroupRole.GUEST) return
            // The host's phone dropped: whatever plays carries on from what's here; look for the group again.
            hostEndpoint = null
            phase = GroupPhase.RECONNECTING
            pinger?.cancel()
            if (modeOn) channels[mode]?.onLinkDown()
            client.startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(strategy).build()).addOnFailureListener { }
            GroupService.refresh(app)
        }
    }

    private fun applyGroup(o: JSONObject) {
        val seq = o.optInt("seq", -1)
        if (seq in 0..lastSeq) return
        lastSeq = seq
        groupId = o.optString("gid", groupId)
        groupName = o.optString("name", groupName)
        hostName = o.optString("host", hostName)
        people.clear()
        o.optJSONArray("people")?.let { a ->
            for (i in 0 until a.length()) {
                val p = a.getJSONObject(i)
                people.add(GroupPerson(p.optString("n"), p.optString("k"), p.optBoolean("h"), p.optInt("q")))
            }
        }
        opts = o.optJSONObject("opts") ?: JSONObject()
        val newMode = GroupMode.of(o.optString("mode")) ?: mode
        val gen = o.optInt("gen")
        if (!modeOn) {
            // Just in: start whatever the group is doing.
            modeOn = true
            mode = newMode
            modeGen = gen
            modesUsed.add(newMode)
            channels[newMode]?.activate(resume = false)
            openRequest++
        } else if (gen != modeGen) {
            val from = mode
            channels[from]?.deactivate()
            mode = newMode
            modeGen = gen
            if (from != newMode) {
                switched = from to newMode
                main.postDelayed({ if (switched?.second == newMode) switched = null }, 2_600)
            }
            val carryOn = newMode in modesUsed
            modesUsed.add(newMode)
            channels[newMode]?.activate(carryOn)
        }
        GroupService.refresh(app)
    }

    // ================================================================== the clock (guests)
    /**
     * Keep measuring the host's clock: a quick burst at the start, quickly again until a clean
     * (quick) round trip comes back, then every 2 s. Not reset on a reconnect: what plays plays on.
     */
    private fun startPinging() {
        pinger?.cancel()
        pinger = io.launch {
            var n = 0
            while (isActive) {
                main.post { ping() }
                n++
                delay(
                    when {
                        n < 8 -> 120
                        clock.count < 12 || clock.rttNs > 40_000_000L -> 400
                        else -> 2_000
                    },
                )
            }
        }
    }

    private fun ping() {
        if (hostEndpoint == null || phase != GroupPhase.IN_GROUP) return
        val id = ++pingId
        val now = System.nanoTime()
        pingSent[id] = now
        if (pingSent.size > 50) pingSent.keys.sorted().take(25).forEach { pingSent.remove(it) }
        sendToHost(groupPayload(msg("ping").put("id", id).put("a", now)))
    }

    // ================================================================== messages
    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val arrived = System.nanoTime()
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val bytes = payload.asBytes() ?: return
                    if (bytes.isEmpty()) return
                    val body = bytes.copyOfRange(1, bytes.size)
                    when (bytes[0]) {
                        CH_GROUP -> onGroupMessage(endpointId, body, arrived)
                        // Decide's side channel: before the modes, so it works while any of them runs.
                        CH_DECIDE -> Together.onReceive(endpointId, body)
                        else -> modeOf(bytes[0])?.let { channels[it] }?.onReceive(endpointId, body, arrived)
                    }
                }
                Payload.Type.FILE -> channels[GroupMode.SHARE]?.onFile(endpointId, payload)
                else -> Unit
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            channels.values.forEach { it.onTransfer(endpointId, update) }
        }
    }

    private fun onGroupMessage(endpointId: String, body: ByteArray, arrived: Long) {
        val o = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull() ?: return
        if (role == GroupRole.HOST) {
            if (!members.containsKey(endpointId)) return
            when (o.optString("t")) {
                "ping" -> send(
                    endpointId,
                    groupPayload(msg("pong").put("id", o.optInt("id")).put("a", o.optLong("a")).put("b", arrived).put("c", System.nanoTime())),
                )
                "bye" -> dropMember(endpointId, quietly = true)
            }
        } else if (role == GroupRole.GUEST) {
            when (o.optString("t")) {
                "pong" -> {
                    val sent = pingSent.remove(o.optInt("id")) ?: return
                    clock.add(sent, o.optLong("b"), o.optLong("c"), arrived)
                    if (BuildConfig.DEBUG && clock.count % 10 == 0) Log.d(TAG, "clock best rtt=%.1fms".format(clock.rttNs / 1e6))
                    if (modeOn) channels[mode]?.onClock()
                    Together.onClock()
                }
                "group" -> applyGroup(o)
                "end" -> {
                    ended = "$hostName ended the group."
                    backToStart()
                }
                "removed" -> {
                    ended = "$hostName took you out of the group."
                    backToStart()
                }
            }
        }
    }

    // ================================================================== leaving / ending
    /** Guest: leave the group (it goes on for the others). */
    fun leave() {
        if (role != GroupRole.GUEST) return
        leaving = true
        sendToHost(groupPayload(msg("bye")))
        val h = hostEndpoint
        main.postDelayed({ h?.let { runCatching { client.disconnectFromEndpoint(it) } } }, 300)
        backToStart()
    }

    /** Host: end the group for everyone (the host leaving ends it too). */
    fun end() {
        if (role != GroupRole.HOST) return
        val ids = members.keys.toList()
        if (ids.isNotEmpty()) send(ids, groupPayload(msg("end")))
        main.postDelayed({ ids.forEach { runCatching { client.disconnectFromEndpoint(it) } } }, 400)
        backToStart()
    }

    fun dismissEnded() {
        ended = null
    }

    private fun backToStart() {
        val wasHost = role == GroupRole.HOST
        runCatching { client.stopAdvertising() }
        runCatching { client.stopDiscovery() }
        if (!wasHost) hostEndpoint?.let { h -> main.postDelayed({ runCatching { client.disconnectFromEndpoint(h) } }, 400) }
        channels.values.forEach { runCatching { it.reset() } }
        clear()
        role = GroupRole.NONE
        phase = GroupPhase.IDLE
        GroupService.stop(app)
    }

    private fun clear() {
        heartbeat?.cancel()
        pinger?.cancel()
        readvertise?.let { main.removeCallbacks(it) }
        members.clear()
        pendingJoins.clear()
        knownTokens.clear()
        joinRequests.clear()
        pingSent.clear()
        clock.reset()
        Together.reset() // Decide's "for everyone" follows the group and its clock
        people.clear()
        modesUsed.clear()
        opts = JSONObject()
        groupId = ""
        title = ""
        joinCode = ""
        hostEndpoint = null
        linkQuality = BandwidthInfo.Quality.UNKNOWN
        switched = null
        switchWanted = false
        stateSeq = 0
        lastSeq = -1
        modeOn = false
        modeGen = 0
        leaving = false
    }

    // ================================================================== for the modes
    /** A message on [mode]'s channel, ready to send. BYTES payloads are 32 KB at most (one byte goes to the channel). */
    internal fun payload(mode: GroupMode, body: ByteArray): Payload = framed(channelByte(mode), body)

    private fun groupPayload(o: JSONObject): Payload = framed(CH_GROUP, o.toString().toByteArray(Charsets.UTF_8))

    /** A message on Decide's side channel ([CH_DECIDE]), ready to send. */
    internal fun decidePayload(body: ByteArray): Payload = framed(CH_DECIDE, body)

    private fun framed(ch: Byte, body: ByteArray): Payload =
        Payload.fromBytes(ByteArray(body.size + 1).also {
            it[0] = ch
            System.arraycopy(body, 0, it, 1, body.size)
        })

    internal fun send(endpoints: Collection<String>, payload: Payload): Boolean {
        if (endpoints.isEmpty()) return true
        return runCatching { client.sendPayload(endpoints.toList(), payload) }
            .onFailure { Log.w(TAG, "send failed", it) }.isSuccess
    }

    internal fun send(endpoint: String, payload: Payload): Boolean =
        runCatching { client.sendPayload(endpoint, payload) }.onFailure { Log.w(TAG, "send failed", it) }.isSuccess

    internal fun sendToHost(payload: Payload): Boolean {
        val h = hostEndpoint ?: return false
        return send(h, payload)
    }

    internal fun cancel(payloadId: Long) {
        runCatching { client.cancelPayload(payloadId) }
    }

    /** Host: the guests' endpoint ids. */
    internal fun guestEndpoints(): List<String> = members.keys.toList()

    /** Host: each guest as (endpoint id, token, name). */
    internal fun guestList(): List<Triple<String, String, String>> = members.values.map { Triple(it.endpointId, it.token, it.name) }

    internal fun nameOf(endpointId: String): String? = if (endpointId == hostEndpoint) hostName else members[endpointId]?.name

    internal fun endpointOf(name: String): String? = members.values.firstOrNull { it.name == name }?.endpointId

    private fun msg(type: String) = JSONObject().put("t", type)

    /** One line for the Tools page / notification. */
    fun statusLine(): String? = when (phase) {
        GroupPhase.IDLE -> null
        GroupPhase.JOINING -> "Joining $groupName"
        GroupPhase.RECONNECTING -> "$groupName · reconnecting…"
        else -> "$groupName · ${mode.label} · ${people.size} phone${if (people.size == 1) "" else "s"}"
    }

    /** The current mode's own line for the notification ("Playing “Evening Walk”"). */
    internal fun modeStatus(): String? = channels[mode]?.statusText()

    private fun isAlready(e: Exception) =
        (e as? ApiException)?.statusCode.let { it == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING || it == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING }

    private fun friendly(e: Exception): String {
        val code = (e as? ApiException)?.statusCode ?: return "Something went wrong. Try again."
        return when (code) {
            ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH, ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_ADMIN,
            ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_WIFI_STATE, ConnectionsStatusCodes.MISSING_PERMISSION_CHANGE_WIFI_STATE,
            ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_COARSE_LOCATION,
            -> "SyncUp needs the Nearby devices permission to find phones nearby."
            ConnectionsStatusCodes.STATUS_RADIO_ERROR -> "Turn on Bluetooth and Wi-Fi, then try again."
            ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT -> "Already connected."
            else -> "Couldn't connect (code $code). Turn on Bluetooth and Wi-Fi, then try again."
        }
    }
}
