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
import com.agani.syncup.group.GroupChannel
import com.agani.syncup.group.GroupLink
import com.agani.syncup.group.GroupMode
import com.agani.syncup.group.GroupPhase
import com.agani.syncup.group.GroupRole
import com.agani.syncup.music.Song
import com.google.android.gms.nearby.connection.BandwidthInfo
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
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
import java.util.concurrent.ConcurrentHashMap

enum class PartyRole { NONE, HOST, GUEST }

enum class PartyPhase {
    IDLE,
    /** Host: the party is on. */
    HOSTING,
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

/** Host: a guest wants to add one of their songs. */
class AddRequest(
    val endpointId: String, val guest: String, val req: Int, val title: String, val artist: String,
    val durationMs: Long, val size: Long, val mime: String,
)

/**
 * SyncUp Party — a group's Music party mode (Tools → Radio → Party): the host plays songs from
 * their phone and every phone in the group plays them at exactly the same moment — a room of phones
 * sounds like one speaker.
 *
 * Runs on the group's link ([GroupLink]: finding, joining, who's in, the clock, reconnecting). The
 * host sends each song to every guest in pieces (kept only in a cache, deleted after); guests play
 * on the host's clock and [PartySyncPlayer] plays every sample on time. The host is the DJ (queue,
 * play / pause / next / seek); every phone keeps its own volume. Guests can add songs when the host
 * allows it (off / ask / on). When the group switches to another mode the party pauses and keeps its
 * queue — switching back carries on.
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
    /** A piece on its way that hasn't moved for this long is taken as lost: its place frees up and it goes again. */
    private const val STUCK_MS = 20_000L
    /** The queue in a "state" stays well under Nearby's 32 KB message limit (bytes). */
    private const val STATE_QUEUE_BYTES = 18_000
    /** Every so many heartbeats the state carries the queue again (in between: just the timeline). */
    private const val FULL_STATE_EVERY = 6

    private lateinit var app: Context
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private var ready = false

    // ------------------------------------------------------------------ what the screens show
    var role by mutableStateOf(PartyRole.NONE)
        private set
    var phase by mutableStateOf(PartyPhase.IDLE)
        private set
    val roomName: String get() = GroupLink.groupName
    val hostName: String get() = GroupLink.hostName
    /** Everyone in the group, host first. */
    val people: List<String> get() = GroupLink.peopleNames
    var guestAdds by mutableStateOf(GuestAdds.ASK)
        private set
    /** Every song in the party, in order (played ones stay, before the current one). */
    val queue = mutableStateListOf<PartyTrack>()
    var currentId by mutableIntStateOf(-1)
        private set
    var paused by mutableStateOf(true)
        private set
    val addRequests = mutableStateListOf<AddRequest>()
    /** Something to tell the user once (a toast / note). */
    var notice by mutableStateOf<String?>(null)
    /** How far this phone is off (ms), from the last measurement; null when not playing. */
    var syncErrorMs by mutableStateOf<Double?>(null)
        private set
    /** Guest: how fast the link to the host is ([BandwidthInfo.Quality]: LOW = Bluetooth, HIGH = Wi-Fi). */
    val linkQuality: Int get() = GroupLink.linkQuality
    /** This phone stopped its own sound (a call / another app) — tap to rejoin. */
    var localPaused by mutableStateOf(false)
        private set
    /** "Sync adjust": play this phone earlier (+) or later (−), for Bluetooth speakers. */
    var syncAdjustMs by mutableIntStateOf(0)
        private set
    /** Guest: the host lets guests add songs right now. */
    val canAdd: Boolean get() = role == PartyRole.GUEST && guestAdds != GuestAdds.OFF

    /** The group is in Music party mode on this phone. */
    val active: Boolean get() = phase != PartyPhase.IDLE
    val current: PartyTrack? get() = queue.firstOrNull { it.id == currentId }
    val upNext: List<PartyTrack>
        get() = queue.indexOfFirst { it.id == currentId }.let { i -> if (i < 0) queue.toList() else queue.drop(i + 1) }

    // ------------------------------------------------------------------ inside
    /** Host time (ns) at which the current song's position 0 plays. */
    private var t0HostNs = 0L
    private var pausedAtMs = 0L
    private val tracks = ConcurrentHashMap<Int, TrackData>()
    /** Songs that are files on this phone (host's own; a guest's own added ones). */
    private val localUris = ConcurrentHashMap<Int, Uri>()
    private var player: PartySyncPlayer? = null
    private var stateSeq = 0
    /** Host: the music was playing when the group switched away (switching back plays on). */
    private var wasPlaying = false

    // host
    private class Guest(val endpointId: String, val name: String) {
        /** Song pieces this guest has, or that are on their way to it (lock the BitSet to use it). */
        val sent = ConcurrentHashMap<Int, BitSet>()
        /** Payload id → (song, piece) of the pieces on their way. */
        val inFlight = ConcurrentHashMap<Long, Pair<Int, Int>>()
        /** Payload id → when it last moved (sent, or bytes reported going). */
        val movedAt = ConcurrentHashMap<Long, Long>()
        /** Song → the piece this guest's player waits for (it asked: "want"). */
        val wanted = ConcurrentHashMap<Int, Int>()
        val wake = Channel<Unit>(Channel.CONFLATED)
        var pump: Job? = null

        fun sentOf(trackId: Int): BitSet = sent.getOrPut(trackId) { BitSet() }
    }
    private val guests = ConcurrentHashMap<String, Guest>()
    private var nextTrackId = 1
    private var heartbeat: Job? = null

    // guest
    private val clock get() = GroupLink.clock
    private var nextReq = 1
    private val myAdds = HashMap<Int, Pair<Uri, PartyTrack>>()
    private val uploadInFlight = ConcurrentHashMap.newKeySet<Long>()
    private val uploadWake = Channel<Unit>(Channel.CONFLATED)
    private var helloSent = false

    // media session (lock screen) + audio focus
    private var controller: MediaController? = null
    private var playerListener: (() -> Unit)? = null
    private var focus: AudioFocusRequest? = null

    fun init(context: Context) {
        if (ready) return
        ready = true
        app = context.applicationContext
        syncAdjustMs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("sync_adjust_ms", 0)
        TrackData.clearCache(app)
        GroupLink.init(app)
        GroupLink.register(GroupMode.MUSIC, channel)
    }

    private val myName: String get() = GroupLink.myName

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

    // ================================================================== starting / leaving
    /**
     * Start a party: a new group in Music party mode — or, hosting a group doing something else,
     * switch it to music.
     */
    fun startHosting(name: String) {
        if (GroupLink.active) {
            if (GroupLink.isHost && GroupLink.mode != GroupMode.MUSIC) GroupLink.switchTo(GroupMode.MUSIC)
            return
        }
        GroupLink.startGroup(name.trim().ifBlank { "$myName's Party" }, GroupMode.MUSIC)
    }

    /** Guest: leave the group. */
    fun leave() = GroupLink.leave()

    /** Host: end the group for everyone. */
    fun end() = GroupLink.end()

    // ================================================================== the group's music channel
    internal val channel = object : GroupChannel {
        override fun onReceive(endpointId: String, body: ByteArray, arrivedNs: Long) {
            when (val m = PartyWire.parse(body)) {
                is PartyWire.Json -> if (role == PartyRole.HOST) onGuestMessage(endpointId, m.o) else if (role == PartyRole.GUEST) onHostMessage(m.o)
                is PartyWire.Chunk -> tracks[m.trackId]?.write(m.offset, m.data, m.start, m.len)
                null -> Unit
            }
        }

        override fun onTransfer(endpointId: String, update: PayloadTransferUpdate) {
            if (role == PartyRole.HOST) {
                val g = guests[endpointId] ?: return
                if (update.status == PayloadTransferUpdate.Status.IN_PROGRESS) {
                    if (g.movedAt.containsKey(update.payloadId)) g.movedAt[update.payloadId] = System.currentTimeMillis()
                    return
                }
                g.movedAt.remove(update.payloadId)
                val (trackId, piece) = g.inFlight.remove(update.payloadId) ?: return
                // Didn't make it: send that piece again.
                if (update.status != PayloadTransferUpdate.Status.SUCCESS) g.sent[trackId]?.let { synchronized(it) { it.clear(piece) } }
                g.wake.trySend(Unit)
            } else if (role == PartyRole.GUEST) {
                if (update.status == PayloadTransferUpdate.Status.IN_PROGRESS) return
                if (uploadInFlight.remove(update.payloadId)) uploadWake.trySend(Unit)
            }
        }

        override fun onMemberIn(endpointId: String, token: String, name: String) {
            if (role != PartyRole.HOST) return
            addGuest(endpointId, name)
            // The songs follow once the guest says what it already has ("hello").
            sendTo(listOf(endpointId), stateJson())
        }

        override fun onMemberOut(endpointId: String) {
            val g = guests.remove(endpointId) ?: return
            g.pump?.cancel()
            addRequests.removeAll { it.endpointId == endpointId }
        }

        override fun onLinkUp() {
            if (role != PartyRole.GUEST) return
            phase = PartyPhase.IN_ROOM
            helloSent = false
            main.postDelayed({ sayHello() }, 2_000)
        }

        override fun onLinkDown() {
            if (role == PartyRole.GUEST) phase = PartyPhase.RECONNECTING
        }

        override fun onClock() {
            if (role != PartyRole.GUEST) return
            if (!helloSent && clock.count >= 6) sayHello()
            if (clock.ready && player == null && !paused && !localPaused) startAudio()
        }

        override fun activate(resume: Boolean) {
            if (GroupLink.role == GroupRole.HOST) {
                role = PartyRole.HOST
                phase = PartyPhase.HOSTING
                GroupLink.guestList().forEach { (ep, _, name) -> addGuest(ep, name) }
                heartbeat?.cancel()
                heartbeat = io.launch {
                    var beats = 0
                    while (isActive) {
                        delay(5_000)
                        val full = ++beats % FULL_STATE_EVERY == 0
                        main.post { if (role == PartyRole.HOST) broadcastState(full) }
                    }
                }
                connectSession()
                if (resume && wasPlaying && current != null) play(currentId, pausedAtMs) else broadcastState()
                wasPlaying = false
                GroupLink.setTitle(current?.title.orEmpty())
            } else {
                role = PartyRole.GUEST
                phase = if (GroupLink.phase == GroupPhase.RECONNECTING) PartyPhase.RECONNECTING else PartyPhase.IN_ROOM
                helloSent = false
                connectSession()
                // Back in music with the clock already measured: straight away; just joined: once it is.
                if (clock.count >= 6) sayHello() else main.postDelayed({ sayHello() }, 2_000)
            }
        }

        override fun deactivate() {
            if (role == PartyRole.HOST) {
                wasPlaying = !paused && current != null
                if (!paused) {
                    pausedAtMs = positionMs()
                    paused = true
                }
                guests.values.forEach { it.pump?.cancel() }
                guests.clear()
            }
            heartbeat?.cancel()
            endTimer?.let { main.removeCallbacks(it) }
            endTimer = null
            stopAudio()
            addRequests.clear()
            helloSent = false
            localPaused = false
            disconnectSession()
            // Guests keep the queue and their cached songs: switching back carries on.
            role = PartyRole.NONE
            phase = PartyPhase.IDLE
        }

        override fun reset() {
            clearParty()
            role = PartyRole.NONE
            phase = PartyPhase.IDLE
            disconnectSession()
        }

        override fun statusText(): String? = current?.let { (if (paused) "Paused: " else "Playing “") + it.title + if (paused) "" else "”" }
    }

    private fun addGuest(endpointId: String, name: String) {
        guests.remove(endpointId)?.pump?.cancel()
        guests[endpointId] = Guest(endpointId, name)
    }

    private fun onGuestMessage(endpointId: String, o: JSONObject) {
        val g = guests[endpointId] ?: return
        when (o.optString("t")) {
            "hello" -> {
                // Back after a drop / a switch: what it already has isn't sent again.
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
            "want" -> {
                val id = o.optInt("track")
                val piece = o.optInt("piece")
                g.wanted[id] = piece
                // Its player waits for a piece already sent (and not on its way): it got lost — again.
                if (g.inFlight.values.none { it.first == id && it.second == piece }) g.sent[id]?.let { synchronized(it) { it.clear(piece) } }
                g.wake.trySend(Unit)
            }
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

    /** Host: this song (a file on this phone) is in the party already. Reads [queue], so screens follow it. */
    fun isQueued(uri: Uri): Boolean = queue.any { localUris[it.id] == uri }

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
        GroupLink.setTitle(current?.title.orEmpty())
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

    /** What guests need to know. [full]: with the queue too (else just the timeline — small, often). */
    private fun stateJson(full: Boolean = true): JSONObject {
        val o = PartyWire.msg("state")
            .put("seq", ++stateSeq)
            .put("adds", guestAdds.key)
            .put("current", currentId)
            .put("t0", t0HostNs)
            .put("paused", paused)
            .put("pos", if (paused) pausedAtMs else positionMs())
        if (full) o.put("queue", queueWindow())
        return o
    }

    /**
     * The queue for guests: from the song before the current one onwards (a whole library can be in
     * it), only as much as fits well inside one Nearby message (32 KB at most).
     */
    private fun queueWindow(): JSONArray {
        val list = queue.toList()
        val from = (list.indexOfFirst { it.id == currentId } - 1).coerceAtLeast(0)
        val q = JSONArray()
        var bytes = 0
        for (t in list.drop(from)) {
            val j = t.toJson()
            bytes += j.toString().toByteArray().size + 1
            if (q.length() > 0 && (bytes > STATE_QUEUE_BYTES || q.length() >= 100)) break
            q.put(j)
        }
        return q
    }

    private fun broadcastState(full: Boolean = true) {
        if (role != PartyRole.HOST) return
        if (guests.isNotEmpty()) sendTo(guests.keys.toList(), stateJson(full))
        scheduleEnd()
        playerListener?.invoke()
        GroupLink.setTitle(current?.title.orEmpty())
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
                resendStuck(g)
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
            val payload = GroupLink.payload(GroupMode.MUSIC, PartyWire.chunk(track.id, offset, buf, n))
            g.inFlight[payload.id] = track.id to piece
            g.movedAt[payload.id] = System.currentTimeMillis()
            if (!GroupLink.send(g.endpointId, payload)) {
                g.inFlight.remove(payload.id)
                g.movedAt.remove(payload.id)
                synchronized(sent) { sent.clear(piece) }
            }
        }
    }

    /**
     * Pieces that stopped moving (their transfer report never came — it happens on real phones when
     * the link changes) would hold their places forever and the guest would get nothing more: free
     * those places and send the pieces again.
     */
    private fun resendStuck(g: Guest) {
        val now = System.currentTimeMillis()
        g.inFlight.keys.filter { now - (g.movedAt[it] ?: 0L) > STUCK_MS }.forEach { id ->
            val (trackId, piece) = g.inFlight.remove(id) ?: return@forEach
            g.movedAt.remove(id)
            GroupLink.cancel(id)
            g.sent[trackId]?.let { synchronized(it) { it.clear(piece) } }
            if (BuildConfig.DEBUG) Log.d(TAG, "piece $piece of song $trackId stuck on the way to ${g.name}: sending again")
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
            val wanted = g.wanted[t.id]
            val piece = synchronized(sent) {
                fun free(i: Int) = i in 0 until count && !sent.get(i)
                when {
                    // The guest's player is waiting for exactly this one.
                    wanted != null && free(wanted) -> wanted
                    free(0) -> 0
                    free(count - 1) -> count - 1
                    else -> {
                        // From where the guest's player reads, else a little before where the song is
                        // now (a guess from the size; fine for constant-bitrate files, close enough for the rest).
                        val nowMs = if (t.id == currentId) positionMs() - 3_000 else 0
                        val from = wanted ?: if (nowMs <= 0 || t.durationMs <= 0) 0 else (t.size * nowMs / t.durationMs / PartyWire.CHUNK).toInt()
                        sent.nextClearBit(from.coerceIn(0, count)).takeIf { it < count } ?: sent.nextClearBit(0).takeIf { it < count }
                    }
                }
            }
            if (piece != null) return t to piece
        }
        return null
    }

    private fun pieceCount(size: Long) = ((size + PartyWire.CHUNK - 1) / PartyWire.CHUNK).toInt()

    // ================================================================== guests
    private fun onHostMessage(o: JSONObject) {
        when (o.optString("t")) {
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
        }
    }

    private fun applyState(o: JSONObject) {
        if (role != PartyRole.GUEST) return
        guestAdds = GuestAdds.of(o.optString("adds"))
        // The queue comes now and then (the heartbeat in between is just the timeline).
        o.optJSONArray("queue")?.let { a ->
            val list = mutableListOf<PartyTrack>()
            for (i in 0 until a.length()) list.add(PartyTrack.fromJson(a.getJSONObject(i)))
            // New songs get a cache to fill; songs gone from the queue lose theirs.
            list.forEach { t ->
                if (!tracks.containsKey(t.id)) {
                    tracks[t.id] = TrackData.incoming(app, t.id, t.size).also { d -> d.onMissing = { piece -> want(t.id, piece) } }
                }
            }
            val keep = list.map { it.id }.toSet()
            tracks.keys.filter { it !in keep }.forEach { id -> tracks.remove(id)?.close(); localUris.remove(id) }
            queue.clear()
            queue.addAll(list)
        }

        val newCurrent = o.optInt("current", -1)
        val newT0 = o.optLong("t0")
        val newPaused = o.optBoolean("paused", true)
        pausedAtMs = o.optLong("pos")
        val changed = newCurrent != currentId || newT0 != t0HostNs || newPaused != paused || player == null
        currentId = newCurrent
        t0HostNs = newT0
        paused = newPaused
        if (changed) {
            if (paused || current == null) stopAudio() else if (!localPaused) startAudio()
        }
        playerListener?.invoke()
    }

    /** Guest → host: this phone's player waits for this piece — send it first (and from there on). */
    private fun want(trackId: Int, piece: Int) {
        if (role != PartyRole.GUEST) return
        sendToHost(PartyWire.msg("want").put("track", trackId).put("piece", piece))
    }

    /** Guest: the host's clock is measured (before that nothing plays). */
    val clockReady: Boolean get() = clock.ready

    /** Guest: how much of the playing song is on this phone (0–100), for "Getting the song…". */
    fun currentSongPercent(): Int {
        val d = tracks[currentId] ?: return 0
        return if (d.pieceCount == 0) 0 else (0 until d.pieceCount).count { d.hasPiece(it) } * 100 / d.pieceCount
    }

    /** Guest → host: in, and these songs (pieces) are already here — send the rest. */
    private fun sayHello() {
        if (helloSent || phase != PartyPhase.IN_ROOM) return
        helloSent = true
        val have = JSONObject()
        tracks.forEach { (id, d) -> have.put(id.toString(), Base64.encodeToString(d.pieces().toByteArray(), Base64.NO_WRAP)) }
        sendToHost(PartyWire.msg("hello").put("have", have))
    }

    /** Guest: ask the host to add one of this phone's songs. */
    fun requestAdd(song: Song) {
        if (role != PartyRole.GUEST || GroupLink.hostEndpoint == null) return
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
            while (offset < size && GroupLink.role == GroupRole.GUEST) {
                if (GroupLink.hostEndpoint == null || uploadInFlight.size >= WINDOW) {
                    withTimeoutOrNull(2_000) { uploadWake.receive() }
                    continue
                }
                val at = if (tailDone) offset else tail
                val n = data.readPiece(at, buf) { GroupLink.role != GroupRole.GUEST }
                if (n <= 0) break
                val payload = GroupLink.payload(GroupMode.MUSIC, PartyWire.chunk(trackId, at, buf, n))
                uploadInFlight.add(payload.id)
                if (!GroupLink.sendToHost(payload)) uploadInFlight.remove(payload.id)
                if (tailDone) offset += n else tailDone = true
            }
        } finally {
            data.close()
        }
    }

    private fun clearParty() {
        stopAudio()
        endTimer?.let { main.removeCallbacks(it) }
        endTimer = null
        heartbeat?.cancel()
        guests.values.forEach { it.pump?.cancel() }
        guests.clear()
        addRequests.clear()
        tracks.values.forEach { it.close() }
        tracks.clear()
        localUris.clear()
        myAdds.clear()
        uploadInFlight.clear()
        queue.clear()
        currentId = -1
        paused = true
        pausedAtMs = 0
        t0HostNs = 0
        localPaused = false
        syncErrorMs = null
        nextTrackId = 1
        helloSent = false
        wasPlaying = false
        guestAdds = GuestAdds.ASK
        if (ready) TrackData.clearCache(app)
    }

    // ================================================================== this phone's sound
    private fun startAudio() {
        stopAudio()
        val track = current ?: return
        if (paused || localPaused || !active) return
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
        GroupLink.send(endpoints, GroupLink.payload(GroupMode.MUSIC, PartyWire.json(o)))
    }

    private fun sendToHost(o: JSONObject) {
        GroupLink.sendToHost(GroupLink.payload(GroupMode.MUSIC, PartyWire.json(o)))
    }
}
