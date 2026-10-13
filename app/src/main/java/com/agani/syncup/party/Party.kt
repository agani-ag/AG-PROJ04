package com.agani.syncup.party

import android.content.ComponentName
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.agani.syncup.BuildConfig
import com.agani.syncup.music.Song
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.BitSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class PartyRole { NONE, HOST, GUEST }

enum class PartyPhase {
    IDLE,
    /** Host: the party is on (guests can find it). */
    HOSTING,
    /** Guest: asked to join, waiting for the host to accept. */
    JOINING,
    /** Guest: in the party. */
    IN_ROOM,
    /** Guest: the host's phone dropped — the music carries on from what's here while it reconnects. */
    RECONNECTING,
}

/** Whether guests may put songs in the queue. */
enum class GuestAdds(val key: String) {
    OFF("off"), ASK("ask"), ON("on");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: ASK
    }
}

/** A party a nearby phone is hosting (from its advertisement). */
class PartyRoom(val endpointId: String, val roomId: String, val name: String, val host: String, val people: Int, val playing: String)

/** Host: someone asked to join (both phones show [code]). */
class JoinRequest(val endpointId: String, val token: String, val name: String, val code: String)

/** Host: a guest wants to add one of their songs. */
class AddRequest(
    val endpointId: String, val guest: String, val req: Int, val title: String, val artist: String,
    val durationMs: Long, val size: Long, val mime: String,
)

/**
 * SyncUp Party (Tools → Radio → Party): a host plays songs from their phone and every phone in the
 * party plays them at exactly the same moment — a room of phones sounds like one speaker.
 *
 * Google Nearby Connections, star-shaped (one host, many guests; Bluetooth to find, direct Wi-Fi or
 * the same Wi-Fi to carry — no internet). Each host's party is its own room, so several can run side
 * by side without mixing. The host sends each song to every guest in pieces (kept only in a cache,
 * deleted after); guests match their clock to the host's ([PartyClock]) and [PartySyncPlayer] plays
 * every sample on time. The host is the DJ (queue, play / pause / next / seek); every phone keeps its
 * own volume. Guests can add songs when the host allows it (off / ask / on).
 *
 * Main thread for everything public (Nearby calls back there too).
 */
object Party {
    private const val TAG = "Party"
    private const val PREFS = "party"
    /** Ahead of "now" that a play / seek starts, so every phone has the order in time. */
    private const val LEAD_NS = 1_200_000_000L
    /** Song pieces on their way to one guest at a time (keeps clock pings from waiting long). */
    private const val WINDOW = 6
    /** Songs sent ahead of time: the playing one and the next ones. */
    private const val PREFETCH = 2
    const val MAX_GUESTS = 20

    private val strategy = Strategy.P2P_STAR
    private lateinit var app: Context
    private val client: ConnectionsClient by lazy { Nearby.getConnectionsClient(app) }
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private var ready = false

    // ------------------------------------------------------------------ what the screens show
    var role by mutableStateOf(PartyRole.NONE)
        private set
    var phase by mutableStateOf(PartyPhase.IDLE)
        private set
    /** Parties nearby (while browsing). */
    val rooms = mutableStateListOf<PartyRoom>()
    var roomName by mutableStateOf("")
        private set
    var hostName by mutableStateOf("")
        private set
    /** Everyone in the party, host first. */
    val people = mutableStateListOf<String>()
    var guestAdds by mutableStateOf(GuestAdds.ASK)
        private set
    /** Every song in the party, in order (played ones stay, before the current one). */
    val queue = mutableStateListOf<PartyTrack>()
    var currentId by mutableIntStateOf(-1)
        private set
    var paused by mutableStateOf(true)
        private set
    /** Guest, joining: the code both phones show. */
    var code by mutableStateOf("")
        private set
    val joinRequests = mutableStateListOf<JoinRequest>()
    val addRequests = mutableStateListOf<AddRequest>()
    /** Something to tell the user once (a toast / note). */
    var notice by mutableStateOf<String?>(null)
    /** Guest: the host ended the party (shown until dismissed). */
    var ended by mutableStateOf<String?>(null)
        private set
    /** How far this phone is off (ms), from the last measurement; null when not playing. */
    var syncErrorMs by mutableStateOf<Double?>(null)
        private set
    /** This phone stopped its own sound (a call / another app) — tap to rejoin. */
    var localPaused by mutableStateOf(false)
        private set
    /** "Sync adjust": play this phone earlier (+) or later (−), for Bluetooth speakers. */
    var syncAdjustMs by mutableIntStateOf(0)
        private set
    /** Guest: the host lets guests add songs right now. */
    val canAdd: Boolean get() = role == PartyRole.GUEST && guestAdds != GuestAdds.OFF

    val active: Boolean get() = phase != PartyPhase.IDLE
    val current: PartyTrack? get() = queue.firstOrNull { it.id == currentId }
    val upNext: List<PartyTrack>
        get() = queue.indexOfFirst { it.id == currentId }.let { i -> if (i < 0) queue.toList() else queue.drop(i + 1) }

    // ------------------------------------------------------------------ inside
    private var myToken = ""
    private var roomId = ""
    /** Host time (ns) at which the current song's position 0 plays. */
    private var t0HostNs = 0L
    private var pausedAtMs = 0L
    private val tracks = ConcurrentHashMap<Int, TrackData>()
    /** Songs that are files on this phone (host's own; a guest's own added ones). */
    private val localUris = ConcurrentHashMap<Int, Uri>()
    private var player: PartySyncPlayer? = null
    private var stateSeq = 0

    // host
    private class Guest(val endpointId: String, val token: String, val name: String) {
        /** Song pieces this guest has, or that are on their way to it (lock the BitSet to use it). */
        val sent = ConcurrentHashMap<Int, BitSet>()
        /** Payload id → (song, piece) of the pieces on their way. */
        val inFlight = ConcurrentHashMap<Long, Pair<Int, Int>>()
        val wake = Channel<Unit>(Channel.CONFLATED)
        var pump: Job? = null

        fun sentOf(trackId: Int): BitSet = sent.getOrPut(trackId) { BitSet() }
    }
    private val guests = ConcurrentHashMap<String, Guest>()
    private val pendingJoins = HashMap<String, JoinRequest>()
    /** Guests let in once: their reconnects come straight back in. */
    private val knownTokens = HashMap<String, String>()
    private var nextTrackId = 1
    private var heartbeat: Job? = null
    private var readvertise: Runnable? = null

    // guest
    private var hostEndpoint: String? = null
    private val clock = PartyClock()
    private var pinger: Job? = null
    private var pingId = 0
    private val pingSent = HashMap<Int, Long>()
    private var leaving = false
    private var nextReq = 1
    private val myAdds = HashMap<Int, Pair<Uri, PartyTrack>>()
    private val uploadInFlight = ConcurrentHashMap.newKeySet<Long>()
    private val uploadWake = Channel<Unit>(Channel.CONFLATED)

    // media session (lock screen) + audio focus
    private var controller: MediaController? = null
    private var playerListener: (() -> Unit)? = null
    private var focus: AudioFocusRequest? = null

    fun init(context: Context) {
        if (ready) return
        ready = true
        app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        myToken = prefs.getString("token", null) ?: UUID.randomUUID().toString().take(12).also {
            prefs.edit().putString("token", it).apply()
        }
        syncAdjustMs = prefs.getInt("sync_adjust_ms", 0)
        TrackData.clearCache(app)
        QuickShare.init(app)
    }

    private val myName: String get() = QuickShare.myName.ifBlank { "SyncUp" }

    fun setSyncAdjust(ms: Int) {
        syncAdjustMs = ms.coerceIn(-400, 400)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("sync_adjust_ms", syncAdjustMs).apply()
    }

    /** Where the song is now (ms), for the screen and the lock screen. */
    fun positionMs(): Long {
        val c = current ?: return 0
        if (paused) return pausedAtMs.coerceIn(0, c.durationMs)
        return ((hostNow() - t0HostNs) / 1_000_000).coerceIn(0, c.durationMs)
    }

    private fun hostNow(): Long = System.nanoTime() + if (role == PartyRole.GUEST) clock.offsetNs else 0

    // ================================================================== browsing (guests)
    /** Look for parties nearby (while the Party page is open). */
    fun startBrowsing() {
        if (active) return
        rooms.clear()
        client.startDiscovery(PartyWire.SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { e -> if (!isAlready(e)) notice = friendly(e) }
    }

    fun stopBrowsing() {
        if (phase == PartyPhase.IDLE || phase == PartyPhase.HOSTING) runCatching { client.stopDiscovery() }
        if (phase == PartyPhase.IDLE) rooms.clear()
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != PartyWire.SERVICE_ID) return
            val p = info.endpointName.split(PartyWire.SEP)
            if (p.getOrNull(0) != "1" || p.size < 6) return
            val room = PartyRoom(endpointId, p[1], p[2], p[3], p[4].toIntOrNull() ?: 1, p[5])
            rooms.removeAll { it.endpointId == endpointId || it.roomId == room.roomId }
            rooms.add(room)
            // Back after a drop: rejoin our party by itself.
            if (phase == PartyPhase.RECONNECTING && room.roomId == roomId) rejoin(room)
        }

        override fun onEndpointLost(endpointId: String) {
            rooms.removeAll { it.endpointId == endpointId }
        }
    }

    // ================================================================== hosting
    fun startHosting(name: String) {
        if (active) return
        runCatching { client.stopDiscovery() }
        rooms.clear()
        clearParty()
        role = PartyRole.HOST
        phase = PartyPhase.HOSTING
        roomId = UUID.randomUUID().toString().take(6)
        roomName = name.trim().ifBlank { "$myName's Party" }.take(30)
        hostName = myName
        people.clear()
        people.add(myName)
        guestAdds = GuestAdds.ASK
        advertise()
        heartbeat = io.launch {
            while (isActive) {
                delay(5_000)
                main.post { if (role == PartyRole.HOST) broadcastState() }
            }
        }
        connectSession()
    }

    private fun advertisedName(): String = listOf(
        "1", roomId, roomName.take(30), hostName.take(24), people.size.toString(), (current?.title ?: "").take(30),
    ).joinToString(PartyWire.SEP.toString())

    private fun advertise() {
        client.startAdvertising(advertisedName(), PartyWire.SERVICE_ID, hostLifecycle, AdvertisingOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { e -> if (!isAlready(e)) notice = friendly(e) }
    }

    /** People / song changed: nearby phones see it a moment later (debounced). */
    private fun readvertiseSoon() {
        if (role != PartyRole.HOST) return
        readvertise?.let { main.removeCallbacks(it) }
        readvertise = Runnable {
            if (role != PartyRole.HOST) return@Runnable
            runCatching { client.stopAdvertising() }
            advertise()
        }.also { main.postDelayed(it, 2_500) }
    }

    fun acceptJoin(request: JoinRequest) {
        joinRequests.remove(request)
        knownTokens[request.token] = request.name
        client.acceptConnection(request.endpointId, hostPayloads).addOnFailureListener { e -> notice = friendly(e) }
    }

    fun declineJoin(request: JoinRequest) {
        joinRequests.remove(request)
        pendingJoins.remove(request.endpointId)
        runCatching { client.rejectConnection(request.endpointId) }
    }

    private val hostLifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (role != PartyRole.HOST) return run { client.rejectConnection(endpointId) }
            val parts = info.endpointName.split(PartyWire.SEP)
            val token = parts.getOrNull(0).orEmpty()
            val name = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "A guest"
            val request = JoinRequest(endpointId, token, name, info.authenticationDigits)
            pendingJoins[endpointId] = request
            when {
                guests.size >= MAX_GUESTS -> client.rejectConnection(endpointId)
                // Someone already let in, back after a drop: straight in.
                token.isNotBlank() && knownTokens.containsKey(token) ->
                    client.acceptConnection(endpointId, hostPayloads).addOnFailureListener { pendingJoins.remove(endpointId) }
                else -> joinRequests.add(request)
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val request = pendingJoins.remove(endpointId)
            joinRequests.removeAll { it.endpointId == endpointId }
            if (result.status.statusCode != ConnectionsStatusCodes.STATUS_OK || request == null || role != PartyRole.HOST) return
            // A reconnect replaces the old link of the same phone.
            guests.values.filter { it.token == request.token }.forEach { dropGuest(it.endpointId, quietly = true) }
            val guest = Guest(endpointId, request.token, request.name)
            guests[endpointId] = guest
            refreshPeople()
            sendTo(listOf(endpointId), stateJson())
            // The songs follow once the guest says what it already has ("hello").
        }

        override fun onDisconnected(endpointId: String) {
            dropGuest(endpointId, quietly = false)
        }
    }

    private fun dropGuest(endpointId: String, quietly: Boolean) {
        val g = guests.remove(endpointId) ?: return
        g.pump?.cancel()
        if (quietly) runCatching { client.disconnectFromEndpoint(endpointId) }
        addRequests.removeAll { it.endpointId == endpointId }
        refreshPeople()
    }

    private fun refreshPeople() {
        people.clear()
        people.add(hostName)
        people.addAll(guests.values.map { it.name })
        broadcastState()
        readvertiseSoon()
    }

    private val hostPayloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val arrived = System.nanoTime()
            val bytes = payload.asBytes() ?: return
            when (val m = PartyWire.parse(bytes)) {
                is PartyWire.Json -> onGuestMessage(endpointId, m.o, arrived)
                is PartyWire.Chunk -> tracks[m.trackId]?.write(m.offset, m.data, m.start, m.len)
                null -> Unit
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.IN_PROGRESS) return
            val g = guests[endpointId] ?: return
            val (trackId, piece) = g.inFlight.remove(update.payloadId) ?: return
            // Didn't make it: send that piece again.
            if (update.status != PayloadTransferUpdate.Status.SUCCESS) g.sent[trackId]?.let { synchronized(it) { it.clear(piece) } }
            g.wake.trySend(Unit)
        }
    }

    private fun onGuestMessage(endpointId: String, o: JSONObject, arrived: Long) {
        val g = guests[endpointId] ?: return
        when (o.optString("t")) {
            "ping" -> sendTo(
                listOf(endpointId),
                PartyWire.msg("pong").put("id", o.optInt("id")).put("a", o.optLong("a")).put("b", arrived).put("c", System.nanoTime()),
            )
            "hello" -> {
                // Back after a drop: what it already has isn't sent again.
                o.optJSONObject("have")?.let { have ->
                    have.keys().forEach { k ->
                        val id = k.toIntOrNull() ?: return@forEach
                        val bits = runCatching { BitSet.valueOf(Base64.decode(have.optString(k), Base64.NO_WRAP)) }.getOrNull() ?: return@forEach
                        g.sent[id] = bits
                    }
                }
                if (g.pump == null) g.pump = io.launch { pump(g) }
                g.wake.trySend(Unit)
            }
            "add" -> {
                val r = AddRequest(
                    endpointId, g.name, o.optInt("req"), o.optString("title"), o.optString("artist"),
                    o.optLong("ms"), o.optLong("size"), o.optString("mime"),
                )
                when (guestAdds) {
                    GuestAdds.OFF -> sendTo(listOf(endpointId), PartyWire.msg("addno").put("req", r.req))
                    GuestAdds.ON -> acceptAdd(r)
                    GuestAdds.ASK -> addRequests.add(r)
                }
            }
            "bye" -> dropGuest(endpointId, quietly = true)
        }
    }

    /** Host: let a guest's song in. It arrives from their phone and plays like any other. */
    fun acceptAdd(r: AddRequest) {
        addRequests.remove(r)
        if (guests[r.endpointId] == null || r.size <= 0) return
        val id = nextTrackId++
        val track = PartyTrack(id, r.title, r.artist, r.durationMs, r.size, r.mime, r.guest)
        tracks[id] = TrackData.incoming(app, id, r.size)
        guests[r.endpointId]?.sentOf(id)?.let { synchronized(it) { it.set(0, pieceCount(r.size)) } } // theirs: not sent back
        queue.add(track)
        sendTo(listOf(r.endpointId), PartyWire.msg("addok").put("req", r.req).put("track", id))
        afterQueueChange()
    }

    fun declineAdd(r: AddRequest) {
        addRequests.remove(r)
        sendTo(listOf(r.endpointId), PartyWire.msg("addno").put("req", r.req))
    }

    fun changeGuestAdds(mode: GuestAdds) {
        guestAdds = mode
        broadcastState()
    }

    /** Host: songs from this phone, to the end of the queue (the first starts if nothing plays). */
    fun addSongs(songs: List<Song>) {
        if (role != PartyRole.HOST) return
        for (s in songs) {
            val size = runCatching { app.contentResolver.openFileDescriptor(s.uri, "r")?.use { it.statSize } }.getOrNull() ?: continue
            if (size <= 0) continue
            val id = nextTrackId++
            val mime = app.contentResolver.getType(s.uri) ?: "audio/mpeg"
            tracks[id] = TrackData.local(app, s.uri, size)
            localUris[id] = s.uri
            queue.add(PartyTrack(id, s.title, artistOf(s), s.durationMs, size, mime, myName))
        }
        afterQueueChange()
    }

    private fun afterQueueChange() {
        val c = current
        when {
            // Nothing played yet: the first song starts.
            c == null -> queue.firstOrNull()?.let { play(it.id, 0) } ?: broadcastState()
            // The queue had run out (stopped at the end of the last song): carry on with the new one.
            paused && pausedAtMs >= c.durationMs - 1_000 && upNext.isNotEmpty() -> play(upNext.first().id, 0)
            else -> broadcastState()
        }
        guests.values.forEach { it.wake.trySend(Unit) }
        readvertiseSoon()
    }

    fun removeFromQueue(trackId: Int) {
        if (role != PartyRole.HOST) return
        if (trackId == currentId) next()
        queue.removeAll { it.id == trackId }
        tracks.remove(trackId)?.close()
        localUris.remove(trackId)
        broadcastState()
    }

    // ------------------------------------------------------------------ host controls
    fun play(trackId: Int, fromMs: Long) {
        if (role != PartyRole.HOST) return
        currentId = trackId
        paused = false
        t0HostNs = System.nanoTime() + LEAD_NS - fromMs * 1_000_000
        broadcastState()
        startAudio()
        guests.values.forEach { it.wake.trySend(Unit) }
        readvertiseSoon()
    }

    fun togglePause() {
        if (role != PartyRole.HOST || current == null) return
        if (paused) {
            // At the very end (the queue ran out): from the top again.
            play(currentId, if (pausedAtMs >= (current?.durationMs ?: 0) - 1_000) 0 else pausedAtMs)
        } else {
            pausedAtMs = positionMs()
            paused = true
            stopAudio()
            broadcastState()
        }
    }

    fun next() {
        if (role != PartyRole.HOST) return
        val i = queue.indexOfFirst { it.id == currentId }
        val nextTrack = queue.getOrNull(i + 1)
        if (nextTrack != null) {
            play(nextTrack.id, 0)
        } else {
            // End of the queue: stop at the end of the last song.
            pausedAtMs = current?.durationMs ?: 0
            paused = true
            stopAudio()
            broadcastState()
        }
    }

    fun previous() {
        if (role != PartyRole.HOST) return
        val i = queue.indexOfFirst { it.id == currentId }
        if (positionMs() > 3_000 || i <= 0) play(currentId, 0) else play(queue[i - 1].id, 0)
    }

    fun seekTo(ms: Long) {
        if (role != PartyRole.HOST || current == null) return
        if (paused) {
            pausedAtMs = ms
            broadcastState()
        } else {
            play(currentId, ms)
        }
    }

    private fun stateJson(): JSONObject {
        val q = JSONArray()
        queue.takeLast(150).forEach { q.put(it.toJson()) }
        return PartyWire.msg("state")
            .put("seq", ++stateSeq)
            .put("room", roomName).put("host", hostName).put("roomId", roomId)
            .put("adds", guestAdds.key)
            .put("people", JSONArray(people.toList()))
            .put("queue", q)
            .put("current", currentId)
            .put("t0", t0HostNs)
            .put("paused", paused)
            .put("pos", if (paused) pausedAtMs else positionMs())
    }

    private fun broadcastState() {
        if (role != PartyRole.HOST) return
        if (guests.isNotEmpty()) sendTo(guests.keys.toList(), stateJson())
        scheduleEnd()
        playerListener?.invoke()
    }

    private var endTimer: Runnable? = null

    /**
     * Host: move everyone on when the song's time is up — by the clock, so the party carries on
     * even while this phone's own sound is off (a call). This phone's player ending does it too.
     */
    private fun scheduleEnd() {
        endTimer?.let { main.removeCallbacks(it) }
        endTimer = null
        val c = current ?: return
        if (role != PartyRole.HOST || paused || c.durationMs <= 0) return
        val forT0 = t0HostNs
        val atNs = t0HostNs + c.durationMs * 1_000_000 + 200_000_000
        endTimer = Runnable { if (role == PartyRole.HOST && t0HostNs == forT0 && !paused) next() }
            .also { main.postDelayed(it, ((atNs - System.nanoTime()) / 1_000_000).coerceAtLeast(0)) }
    }

    /**
     * Host → one guest: the songs it needs, piece by piece — the playing one first, then the next
     * ones — at most [WINDOW] pieces on the way at a time. Within a song: its first and last pieces
     * (players read both before playing), then from where the song is now (someone joining late
     * hears it at once), then the part already played (for a seek back).
     */
    private suspend fun pump(g: Guest) {
        val buf = ByteArray(PartyWire.CHUNK)
        while (guests[g.endpointId] === g) {
            if (g.inFlight.size >= WINDOW) {
                withTimeoutOrNull(2_000) { g.wake.receive() }
                continue
            }
            val (track, piece) = nextFor(g) ?: run {
                withTimeoutOrNull(5_000) { g.wake.receive() }
                null
            } ?: continue
            val data = tracks[track.id] ?: continue
            val offset = piece.toLong() * PartyWire.CHUNK
            val n = data.readPiece(offset, buf) { guests[g.endpointId] !== g }
            if (n <= 0) {
                delay(200)
                continue
            }
            val sent = g.sentOf(track.id)
            synchronized(sent) { sent.set(piece) }
            val payload = Payload.fromBytes(PartyWire.chunk(track.id, offset, buf, n))
            g.inFlight[payload.id] = track.id to piece
            runCatching { client.sendPayload(g.endpointId, payload) }.onFailure {
                g.inFlight.remove(payload.id)
                synchronized(sent) { sent.clear(piece) }
            }
        }
    }

    /** The next piece [g] should get, or null when it has everything coming up. */
    private fun nextFor(g: Guest): Pair<PartyTrack, Int>? {
        val list = queue.toList()
        val start = list.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        for (t in list.drop(start).take(1 + PREFETCH)) {
            if (!tracks.containsKey(t.id)) continue
            val count = pieceCount(t.size)
            val sent = g.sentOf(t.id)
            val piece = synchronized(sent) {
                fun free(i: Int) = i in 0 until count && !sent.get(i)
                when {
                    free(0) -> 0
                    free(count - 1) -> count - 1
                    else -> {
                        // From a little before where the song is now (a guess from the size; fine for
                        // constant-bitrate files, close enough for the rest).
                        val nowMs = if (t.id == currentId) positionMs() - 3_000 else 0
                        val from = if (nowMs <= 0 || t.durationMs <= 0) 0 else (t.size * nowMs / t.durationMs / PartyWire.CHUNK).toInt()
                        sent.nextClearBit(from.coerceIn(0, count)).takeIf { it < count } ?: sent.nextClearBit(0).takeIf { it < count }
                    }
                }
            }
            if (piece != null) return t to piece
        }
        return null
    }

    private fun pieceCount(size: Long) = ((size + PartyWire.CHUNK - 1) / PartyWire.CHUNK).toInt()

    // ================================================================== joining (guests)
    fun join(room: PartyRoom) {
        if (active) return
        clearParty()
        role = PartyRole.GUEST
        phase = PartyPhase.JOINING
        roomId = room.roomId
        roomName = room.name
        hostName = room.host
        hostEndpoint = room.endpointId
        code = ""
        leaving = false
        requestConnection(room.endpointId)
    }

    private fun requestConnection(endpointId: String) {
        client.requestConnection("$myToken${PartyWire.SEP}$myName", endpointId, guestLifecycle)
            .addOnFailureListener { e ->
                if (phase == PartyPhase.JOINING) {
                    notice = friendly(e)
                    backToStart()
                }
            }
    }

    private fun rejoin(room: PartyRoom) {
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
            code = info.authenticationDigits
            client.acceptConnection(endpointId, guestPayloads)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (endpointId != hostEndpoint) return
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    runCatching { client.stopDiscovery() }
                    phase = PartyPhase.IN_ROOM
                    // Clock first, while the link is quiet; "hello" then starts the songs coming.
                    helloSent = false
                    startPinging()
                    main.postDelayed({ sayHello() }, 2_000)
                    connectSession()
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    notice = "$hostName didn't let you in."
                    backToStart()
                }
                else -> if (phase == PartyPhase.JOINING) {
                    notice = "Couldn't join $roomName. Keep the phones close together and try again."
                    backToStart()
                } else {
                    hostEndpoint = null // try again when the room shows up
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (endpointId != hostEndpoint || leaving || role != PartyRole.GUEST) return
            // The host's phone dropped: keep playing what's here and look for the party again.
            hostEndpoint = null
            phase = PartyPhase.RECONNECTING
            pinger?.cancel()
            client.startDiscovery(PartyWire.SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(strategy).build())
                .addOnFailureListener { }
        }
    }

    private val guestPayloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val arrived = System.nanoTime()
            val bytes = payload.asBytes() ?: return
            when (val m = PartyWire.parse(bytes)) {
                is PartyWire.Json -> onHostMessage(m.o, arrived)
                is PartyWire.Chunk -> tracks[m.trackId]?.write(m.offset, m.data, m.start, m.len)
                null -> Unit
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.IN_PROGRESS) return
            if (uploadInFlight.remove(update.payloadId)) uploadWake.trySend(Unit)
        }
    }

    private fun onHostMessage(o: JSONObject, arrived: Long) {
        when (o.optString("t")) {
            "pong" -> {
                val sent = pingSent.remove(o.optInt("id")) ?: return
                clock.add(sent, o.optLong("b"), o.optLong("c"), arrived)
                if (BuildConfig.DEBUG) Log.d(TAG, "clock rtt=%.1fms best=%.1fms offset=%.2fms".format((arrived - sent - (o.optLong("c") - o.optLong("b"))) / 1e6, clock.rttNs / 1e6, clock.offsetNs / 1e6))
                if (!helloSent && clock.count >= 6) sayHello()
                if (clock.ready && player == null && !paused && !localPaused) startAudio()
            }
            "state" -> applyState(o)
            "addok" -> {
                val (uri, track) = myAdds.remove(o.optInt("req")) ?: return
                val id = o.optInt("track")
                localUris[id] = uri
                tracks.put(id, TrackData.local(app, uri, track.size))?.close()
                notice = "“${track.title}” is in the queue."
                io.launch { upload(id, uri, track.size) }
            }
            "addno" -> {
                val (_, track) = myAdds.remove(o.optInt("req")) ?: return
                notice = "$hostName didn't add “${track.title}”."
            }
            "end" -> {
                ended = "$hostName ended the party."
                backToStart()
            }
        }
    }

    private fun applyState(o: JSONObject) {
        roomName = o.optString("room", roomName)
        hostName = o.optString("host", hostName)
        guestAdds = GuestAdds.of(o.optString("adds"))
        people.clear()
        o.optJSONArray("people")?.let { a -> for (i in 0 until a.length()) people.add(a.optString(i)) }
        val list = mutableListOf<PartyTrack>()
        o.optJSONArray("queue")?.let { a -> for (i in 0 until a.length()) list.add(PartyTrack.fromJson(a.getJSONObject(i))) }
        // New songs get a cache to fill; songs gone from the queue lose theirs.
        list.forEach { t -> if (!tracks.containsKey(t.id)) tracks[t.id] = TrackData.incoming(app, t.id, t.size) }
        val keep = list.map { it.id }.toSet()
        tracks.keys.filter { it !in keep }.forEach { id -> tracks.remove(id)?.close(); localUris.remove(id) }
        queue.clear()
        queue.addAll(list)

        val newCurrent = o.optInt("current", -1)
        val newT0 = o.optLong("t0")
        val newPaused = o.optBoolean("paused", true)
        pausedAtMs = o.optLong("pos")
        val changed = newCurrent != currentId || newT0 != t0HostNs || newPaused != paused
        currentId = newCurrent
        t0HostNs = newT0
        paused = newPaused
        if (changed) {
            if (paused || current == null) stopAudio() else if (!localPaused) startAudio()
        }
        playerListener?.invoke()
    }

    private var helloSent = false

    /** Guest → host: in, and these songs (pieces) are already here — send the rest. */
    private fun sayHello() {
        if (helloSent || phase != PartyPhase.IN_ROOM) return
        helloSent = true
        val have = JSONObject()
        tracks.forEach { (id, d) -> have.put(id.toString(), Base64.encodeToString(d.pieces().toByteArray(), Base64.NO_WRAP)) }
        sendToHost(PartyWire.msg("hello").put("token", myToken).put("name", myName).put("have", have))
    }

    /**
     * Keep measuring the host's clock: a quick burst at the start, quickly again until a clean
     * (quick) round trip comes back, then every 2 s. Not reset on a reconnect: the song plays on.
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
        if (hostEndpoint == null || phase != PartyPhase.IN_ROOM) return
        val id = ++pingId
        pingSent[id] = System.nanoTime()
        if (pingSent.size > 50) pingSent.keys.sorted().take(25).forEach { pingSent.remove(it) }
        sendToHost(PartyWire.msg("ping").put("id", id).put("a", pingSent[id]))
    }

    /** Guest: ask the host to add one of this phone's songs. */
    fun requestAdd(song: Song) {
        if (role != PartyRole.GUEST || hostEndpoint == null) return
        val size = runCatching { app.contentResolver.openFileDescriptor(song.uri, "r")?.use { it.statSize } }.getOrNull() ?: return
        val mime = app.contentResolver.getType(song.uri) ?: "audio/mpeg"
        val req = nextReq++
        myAdds[req] = song.uri to PartyTrack(-1, song.title, artistOf(song), song.durationMs, size, mime, myName)
        sendToHost(
            PartyWire.msg("add").put("req", req).put("title", song.title).put("artist", artistOf(song))
                .put("ms", song.durationMs).put("size", size).put("mime", mime),
        )
        notice = if (guestAdds == GuestAdds.ASK) "Asked $hostName to add “${song.title}”." else "Adding “${song.title}”…"
    }

    private fun artistOf(song: Song) = song.artist.takeUnless { it == "Unknown artist" || it == "<unknown>" }.orEmpty()

    /** Guest → host: one of this phone's songs, piece by piece. */
    private suspend fun upload(trackId: Int, uri: Uri, size: Long) {
        val data = TrackData.local(app, uri, size)
        val buf = ByteArray(PartyWire.CHUNK)
        var offset = 0L
        // The last piece first (the host's player reads the end of the file before playing).
        val tail = (size - 1) / PartyWire.CHUNK * PartyWire.CHUNK
        var tailDone = tail == 0L
        try {
            while (offset < size && role == PartyRole.GUEST) {
                val host = hostEndpoint
                if (host == null || uploadInFlight.size >= WINDOW) {
                    withTimeoutOrNull(2_000) { uploadWake.receive() }
                    continue
                }
                val at = if (tailDone) offset else tail
                val n = data.readPiece(at, buf) { role != PartyRole.GUEST }
                if (n <= 0) break
                val payload = Payload.fromBytes(PartyWire.chunk(trackId, at, buf, n))
                uploadInFlight.add(payload.id)
                runCatching { client.sendPayload(host, payload) }.onFailure { uploadInFlight.remove(payload.id) }
                if (tailDone) offset += n else tailDone = true
            }
        } finally {
            data.close()
        }
    }

    fun leave() {
        if (role != PartyRole.GUEST) return
        leaving = true
        sendToHost(PartyWire.msg("bye"))
        val host = hostEndpoint
        main.postDelayed({ host?.let { runCatching { client.disconnectFromEndpoint(it) } } }, 300)
        backToStart()
    }

    fun dismissEnded() {
        ended = null
    }

    // ================================================================== ending
    /** Host: end the party for everyone. */
    fun end() {
        if (role != PartyRole.HOST) return
        if (guests.isNotEmpty()) sendTo(guests.keys.toList(), PartyWire.msg("end"))
        // Let "end" reach them before the links close.
        val ids = guests.keys.toList()
        main.postDelayed({ ids.forEach { runCatching { client.disconnectFromEndpoint(it) } } }, 400)
        backToStart()
    }

    private fun backToStart() {
        val wasHost = role == PartyRole.HOST
        runCatching { client.stopAdvertising() }
        runCatching { client.stopDiscovery() }
        if (!wasHost) hostEndpoint?.let { h -> main.postDelayed({ runCatching { client.disconnectFromEndpoint(h) } }, 400) }
        clearParty()
        role = PartyRole.NONE
        phase = PartyPhase.IDLE
        disconnectSession()
    }

    private fun clearParty() {
        stopAudio()
        endTimer?.let { main.removeCallbacks(it) }
        endTimer = null
        heartbeat?.cancel()
        pinger?.cancel()
        readvertise?.let { main.removeCallbacks(it) }
        guests.values.forEach { it.pump?.cancel() }
        guests.clear()
        pendingJoins.clear()
        knownTokens.clear()
        joinRequests.clear()
        addRequests.clear()
        tracks.values.forEach { it.close() }
        tracks.clear()
        localUris.clear()
        myAdds.clear()
        uploadInFlight.clear()
        pingSent.clear()
        clock.reset()
        queue.clear()
        people.clear()
        currentId = -1
        paused = true
        pausedAtMs = 0
        t0HostNs = 0
        code = ""
        hostEndpoint = null
        localPaused = false
        syncErrorMs = null
        nextTrackId = 1
    }

    // ================================================================== this phone's sound
    private fun startAudio() {
        stopAudio()
        val track = current ?: return
        if (paused || localPaused) return
        if (role == PartyRole.GUEST && !clock.ready) return // starts on the next pong
        val data = tracks[track.id]
        val uri = localUris[track.id]
        if (data == null && uri == null) return
        requestFocus()
        val forT0 = t0HostNs
        player = PartySyncPlayer(
            app, uri, if (uri == null) data else null, t0HostNs,
            hostOffsetNs = { if (role == PartyRole.GUEST) clock.offsetNs else 0L },
            adjustNs = { syncAdjustMs * 1_000_000L },
            listener = object : PartySyncPlayer.Listener {
                override fun onEnded() = main.post {
                    // The host moves everyone on; a guest waits for the host's next order.
                    if (role == PartyRole.HOST && t0HostNs == forT0 && !paused) next()
                }.let { }

                override fun onError(message: String) = main.post {
                    notice = message
                    if (role == PartyRole.HOST && t0HostNs == forT0) next()
                }.let { }

                override fun onSync(errorMs: Double) = main.post { syncErrorMs = errorMs }.let { }
            },
        ).also { it.start() }
        playerListener?.invoke()
    }

    private fun stopAudio() {
        player?.stop()
        player = null
        syncErrorMs = null
        playerListener?.invoke()
    }

    /** This phone only: stop / start its own sound (the party goes on for everyone else). */
    fun resumeHere() {
        localPaused = false
        startAudio()
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        main.post {
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    // A call / another app: this phone goes quiet; the party carries on elsewhere.
                    if (player != null) {
                        stopAudio()
                        localPaused = change == AudioManager.AUDIOFOCUS_LOSS
                        if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pausedForFocus = true
                    }
                }
                AudioManager.AUDIOFOCUS_GAIN -> if (pausedForFocus) {
                    pausedForFocus = false
                    startAudio() // back in, at exactly the right moment
                }
            }
        }
    }
    private var pausedForFocus = false

    private fun requestFocus() {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val req = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener(focusListener, main)
            .build().also { focus = it }
        am.requestAudioFocus(req)
    }

    // ================================================================== lock screen
    /** The party's media session lives in [PartyService]; a controller keeps it running. */
    private fun connectSession() {
        if (controller != null) return
        runCatching {
            val token = SessionToken(app, ComponentName(app, PartyService::class.java))
            val future = MediaController.Builder(app, token).buildAsync()
            future.addListener({ controller = runCatching { future.get() }.getOrNull() }, { main.post(it) })
        }
    }

    private fun disconnectSession() {
        focus?.let { (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it) }
        pausedForFocus = false
        controller?.release()
        controller = null
        playerListener?.invoke()
    }

    internal fun attachPlayer(onChange: () -> Unit) {
        playerListener = onChange
    }

    internal fun detachPlayer() {
        playerListener = null
    }

    // ================================================================== sending
    private fun sendTo(endpoints: List<String>, o: JSONObject) {
        if (endpoints.isEmpty()) return
        runCatching { client.sendPayload(endpoints, Payload.fromBytes(PartyWire.json(o))) }
            .onFailure { Log.w(TAG, "send failed", it) }
    }

    private fun sendToHost(o: JSONObject) {
        val h = hostEndpoint ?: return
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
            -> "SyncUp needs the Nearby devices permission for Party."
            ConnectionsStatusCodes.STATUS_RADIO_ERROR ->
                "Turn on Bluetooth and Wi-Fi, then try again."
            else -> "Couldn't start the party (code $code). Turn on Bluetooth and Wi-Fi, then try again."
        }
    }
}
