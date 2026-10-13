package com.agani.syncup.watchparty

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.agani.syncup.group.GroupChannel
import com.agani.syncup.group.GroupLink
import com.agani.syncup.group.GroupMode
import com.agani.syncup.group.GroupPhase
import com.agani.syncup.group.GroupRole
import com.agani.syncup.group.GroupService
import com.agani.syncup.party.PartyWire
import com.agani.syncup.party.TrackData
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
import java.io.File
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap

enum class WpRole { NONE, HOST, GUEST }

enum class WpPhase {
    IDLE,
    /** Host: the Watch Party is on. */
    HOSTING,
    IN_ROOM,
    /** Guest: the host's phone dropped — playing on from what's here while it reconnects. */
    RECONNECTING,
}

/** Where the current video is: everyone getting ready, playing, or paused. */
enum class WpStage(val key: String) {
    PREPARING("prep"), PLAYING("play"), PAUSED("pause");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: PAUSED
    }
}

/** A video in the Watch Party. A [link] plays from the internet on every phone; otherwise it streams from the host's phone. */
class WatchVideo(
    val id: Int, val title: String, val durationMs: Long, val size: Long, val mime: String, val link: String, val addedBy: String,
) {
    val isLink: Boolean get() = link.isNotBlank()

    fun withDuration(ms: Long) = WatchVideo(id, title, ms, size, mime, link, addedBy)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("ms", durationMs).put("size", size).put("mime", mime).put("link", link).put("by", addedBy)

    companion object {
        fun fromJson(o: JSONObject) = WatchVideo(
            o.optInt("id"), o.optString("title"), o.optLong("ms"), o.optLong("size"), o.optString("mime"), o.optString("link"), o.optString("by"),
        )
    }
}

/** What the host picks: a video on this phone (content:// / file://) or a link — from [startMs]. */
class WatchSource(val uri: String, val title: String, val startMs: Long = 0) {
    val isLink: Boolean get() = uri.startsWith("http://") || uri.startsWith("https://")
}

/** An emoji someone sent; it floats up on every screen for a moment. */
class Reaction(val key: Long, val emoji: String, val from: String)

/**
 * Watch Party — a group's Watch together mode (Tools → Video player → Watch Party): the same video at
 * the same moment on phones nearby. The host picks the video and controls it (play, pause, seek,
 * Up next); guests watch, react and can ask the host to pause. Everyone sets their own sound.
 *
 * Runs on the group's link ([GroupLink]: finding, joining, who's in, the clock, reconnecting): the host
 * says "position P plays at host time T" and every phone's player keeps itself there
 * ([WatchPartyActivity] does the playing). A video on the host's phone is streamed to the guests piece
 * by piece — the piece each guest's player is waiting for first, then onwards — into a temporary cache
 * deleted when the group ends. A link plays from the internet on every phone. Before a start / seek /
 * next video everyone gets a few seconds in hand ("Getting everyone ready"). When the group switches
 * to another mode the video pauses where it is — switching back carries on.
 *
 * Main thread for everything public (Nearby calls back there too).
 */
object WatchParty {
    private const val PREFS = "watch_party"
    const val CACHE = "watch"
    /** Ahead of "now" that a start happens, so every phone has the order in time. */
    private const val LEAD_NS = 1_000_000_000L
    /** Video in hand (after the start point) for a phone to count as ready. */
    const val READY_MS = 3_000L
    /** Nobody waits longer than this for a slow phone. */
    private const val GATE_TIMEOUT_MS = 20_000L
    /** Pieces on their way to one guest at a time. */
    private const val WINDOW = 8
    /** A piece on its way that hasn't moved for this long is taken as lost: its place frees up and it goes again. */
    private const val STUCK_MS = 20_000L
    /** The queue in a "state" stays well under Nearby's 32 KB message limit (bytes). */
    private const val STATE_QUEUE_BYTES = 18_000
    const val MAX_GUESTS = GroupLink.MAX_PHONES - 1
    private const val CHUNK = PartyWire.CHUNK

    private lateinit var app: Context
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private var ready = false

    // ------------------------------------------------------------------ what the screens show
    var role by mutableStateOf(WpRole.NONE)
        private set
    var phase by mutableStateOf(WpPhase.IDLE)
        private set
    val roomName: String get() = GroupLink.groupName
    val hostName: String get() = GroupLink.hostName
    /** Everyone in the group, host first. */
    val people: List<String> get() = GroupLink.peopleNames
    /** Every video in the party, in order (the played ones stay, before the current one). */
    val queue = mutableStateListOf<WatchVideo>()
    var currentId by mutableIntStateOf(-1)
        private set
    var stage by mutableStateOf(WpStage.PAUSED)
        private set
    /** Paused / getting ready: where the video is (ms). */
    var posMs by mutableLongStateOf(0L)
        private set
    /** Host, getting ready: each phone's readiness (0–100), by name. */
    val readiness = mutableStateMapOf<String, Int>()
    var notice by mutableStateOf<String?>(null)
    val reactions = mutableStateListOf<Reaction>()
    /** Host: a guest asked to pause. */
    var askFrom by mutableStateOf<String?>(null)
        private set
    /** This phone's distance from where it should be (ms), from its player; null when not playing. */
    var syncErrorMs by mutableStateOf<Double?>(null)
        private set
    /** "Sync adjust": play this phone earlier (+) or later (−), for Bluetooth earbuds. */
    var syncAdjustMs by mutableIntStateOf(0)
        private set
    /** The group just came to Watch together: the player should open (the Watch Party tab opens it). */
    var wantsPlayer by mutableStateOf(false)
    /** The player screen is open on this phone (it plays the video). */
    var playerOpen = false
        set(value) {
            field = value
            if (value) playerOpening = false
            // Not watching: don't hold up the others.
            if (!value && stage == WpStage.PREPARING && !playerOpening) reportReady(100)
        }
    /** The group just switched to Watch together and the player is on its way: this phone counts as watching. */
    private var playerOpening = false

    /** The player is about to open (a switch): hold the start for it — but not for ever. */
    private fun expectPlayer() {
        if (playerOpen) return
        playerOpening = true
        main.postDelayed({
            if (playerOpening && !playerOpen) {
                playerOpening = false
                if (stage == WpStage.PREPARING) reportReady(100)
            }
        }, 10_000)
    }

    /** The group is in Watch together mode on this phone. */
    val active: Boolean get() = phase != WpPhase.IDLE
    val isHost: Boolean get() = role == WpRole.HOST
    val current: WatchVideo? get() = queue.firstOrNull { it.id == currentId }
    val upNext: List<WatchVideo>
        get() = queue.indexOfFirst { it.id == currentId }.let { i -> if (i < 0) queue.toList() else queue.drop(i + 1) }
    /** Guest: can play in time (the clock is measured). */
    val clockReady: Boolean get() = role != WpRole.GUEST || clock.ready

    // ------------------------------------------------------------------ inside
    private var t0HostNs = 0L
    private var stateSeq = 0
    private val tracks = ConcurrentHashMap<Int, TrackData>()
    /** Host: its own videos, by id. */
    private val localUris = ConcurrentHashMap<Int, String>()
    /** Host: the video was playing when the group switched away (switching back plays on). */
    private var wasPlaying = false
    /** Host: starting the group from the Watch Party tab (it opens the player itself). */
    private var startingHere = false

    // host
    private class Guest(val endpointId: String, val name: String) {
        val sent = ConcurrentHashMap<Int, BitSet>()
        val cursor = ConcurrentHashMap<Int, Int>()
        val inFlight = ConcurrentHashMap<Long, Pair<Int, Int>>()
        /** Payload id → when it last moved (sent, or bytes reported going). */
        val movedAt = ConcurrentHashMap<Long, Long>()
        val wake = Channel<Unit>(Channel.CONFLATED)
        var pump: Job? = null
        var readyPct = 0
        var lastAsk = 0L

        fun sentOf(id: Int): BitSet = sent.getOrPut(id) { BitSet() }
    }
    private val guests = ConcurrentHashMap<String, Guest>()
    private var nextVideoId = 1
    private var heartbeat: Job? = null
    private var gateTimer: Runnable? = null
    private var endTimer: Runnable? = null

    // guest
    private val clock get() = GroupLink.clock
    private var helloSent = false
    private var lastReadySent = -1

    fun init(context: Context) {
        if (ready) return
        ready = true
        app = context.applicationContext
        syncAdjustMs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("sync_adjust_ms", 0)
        TrackData.clearCache(app, CACHE)
        GroupLink.init(app)
        GroupLink.register(GroupMode.WATCH, channel)
    }

    private val myName: String get() = GroupLink.myName

    fun setSyncAdjust(ms: Int) {
        syncAdjustMs = ms.coerceIn(-400, 400)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("sync_adjust_ms", syncAdjustMs).apply()
    }

    private fun hostNow(): Long = System.nanoTime() + if (role == WpRole.GUEST) clock.offsetNs else 0

    /** Where the video should be right now (ms) — negative just before a start. */
    fun targetPositionMs(): Long = when (stage) {
        WpStage.PLAYING -> (hostNow() + syncAdjustMs * 1_000_000L - t0HostNs) / 1_000_000
        else -> posMs
    }

    /** Where the video is, for the screens (never negative). */
    fun positionMs(): Long = targetPositionMs().coerceAtLeast(0).let { p -> current?.durationMs?.takeIf { it > 0 }?.let { p.coerceAtMost(it) } ?: p }

    /** Guest: the video's bytes as they arrive (null for a link, or on the host). */
    internal fun dataFor(id: Int): TrackData? = if (role == WpRole.GUEST) tracks[id] else null

    /** Host: the phone's own file for a video. */
    fun localUri(id: Int): String? = localUris[id]

    // ================================================================== starting / leaving
    /**
     * Start a Watch Party with [source]: a new group in Watch together mode — or, hosting a group
     * doing something else, switch it to watching [source]. [onStarted] runs once it's on (the player
     * opens then).
     */
    fun startHosting(name: String, source: WatchSource, onStarted: () -> Unit) {
        if (GroupLink.active) {
            if (!GroupLink.isHost) return
            expectPlayer()
            startingHere = true
            if (GroupLink.mode != GroupMode.WATCH) GroupLink.switchTo(GroupMode.WATCH, resume = false)
            startingHere = false
            addVideo(source, startNow = true)
            onStarted()
            return
        }
        expectPlayer()
        startingHere = true
        val ok = GroupLink.startGroup(name.trim().ifBlank { "$myName's Watch Party" }, GroupMode.WATCH)
        startingHere = false
        if (!ok) return
        addVideo(source, startNow = true)
        onStarted()
    }

    fun leave() = GroupLink.leave()

    fun end() = GroupLink.end()

    /** Host: a video into the queue (the first one starts — from [WatchSource.startMs]). */
    fun addVideo(source: WatchSource, startNow: Boolean = false) {
        if (role != WpRole.HOST) return
        io.launch {
            val v = describe(source) ?: run {
                main.post { notice = "Couldn't open that video." }
                return@launch
            }
            main.post {
                if (role != WpRole.HOST) return@post
                val id = nextVideoId++
                val video = WatchVideo(id, v.title, v.durationMs, v.size, v.mime, if (source.isLink) source.uri else "", myName)
                if (!source.isLink) {
                    localUris[id] = source.uri
                    tracks[id] = TrackData.local(app, Uri.parse(source.uri), v.size)
                }
                queue.add(video)
                val from = source.startMs.takeIf { v.durationMs <= 0 || it < v.durationMs - 1_000 } ?: 0L
                if (startNow || current == null) prepare(id, from) else broadcastState()
            }
        }
    }

    private class Described(val title: String, val durationMs: Long, val size: Long, val mime: String)

    private fun describe(source: WatchSource): Described? = runCatching {
        if (source.isLink) return@runCatching Described(source.title.ifBlank { Uri.parse(source.uri).lastPathSegment ?: source.uri }, 0, 0, "")
        val uri = Uri.parse(source.uri)
        val size = app.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: File(uri.path ?: "").length()
        if (size <= 0) return@runCatching null
        var name = source.title
        if (name.isBlank()) app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0).orEmpty()
        }
        val mime = app.contentResolver.getType(uri) ?: "video/mp4"
        val duration = runCatching {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(app, uri)
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                } finally {
                    release()
                }
            }
        }.getOrDefault(0L)
        Described(name.ifBlank { "Video" }, duration, size, mime)
    }.getOrNull()

    /** Host: play this Up next video now — the rest stay queued after it. */
    fun playNow(id: Int) {
        if (role != WpRole.HOST || id == currentId) return
        val v = queue.firstOrNull { it.id == id } ?: return
        queue.remove(v)
        queue.add(queue.indexOfFirst { it.id == currentId } + 1, v)
        prepare(id, 0)
    }

    fun removeFromQueue(id: Int) {
        if (role != WpRole.HOST) return
        if (id == currentId) next()
        queue.removeAll { it.id == id }
        tracks.remove(id)?.close()
        localUris.remove(id)
        broadcastState()
    }

    // ================================================================== the group's watch channel
    internal val channel = object : GroupChannel {
        override fun onReceive(endpointId: String, body: ByteArray, arrivedNs: Long) {
            when (val m = PartyWire.parse(body)) {
                is PartyWire.Json -> if (role == WpRole.HOST) onGuestMessage(endpointId, m.o) else if (role == WpRole.GUEST) onHostMessage(m.o)
                is PartyWire.Chunk -> tracks[m.trackId]?.write(m.offset, m.data, m.start, m.len)
                null -> Unit
            }
        }

        override fun onTransfer(endpointId: String, update: PayloadTransferUpdate) {
            if (role != WpRole.HOST) return
            val g = guests[endpointId] ?: return
            if (update.status == PayloadTransferUpdate.Status.IN_PROGRESS) {
                if (g.movedAt.containsKey(update.payloadId)) g.movedAt[update.payloadId] = System.currentTimeMillis()
                return
            }
            g.movedAt.remove(update.payloadId)
            val (id, piece) = g.inFlight.remove(update.payloadId) ?: return
            if (update.status != PayloadTransferUpdate.Status.SUCCESS) g.sent[id]?.let { synchronized(it) { it.clear(piece) } }
            g.wake.trySend(Unit)
        }

        override fun onMemberIn(endpointId: String, token: String, name: String) {
            if (role != WpRole.HOST) return
            addGuest(endpointId, name)
            if (stage == WpStage.PREPARING) readiness[name] = 0
            sendTo(listOf(endpointId), stateJson())
        }

        override fun onMemberOut(endpointId: String) {
            val g = guests.remove(endpointId) ?: return
            g.pump?.cancel()
            readiness.remove(g.name)
            checkGate()
        }

        override fun onLinkUp() {
            if (role != WpRole.GUEST) return
            phase = WpPhase.IN_ROOM
            helloSent = false
            main.postDelayed({ sayHello() }, 2_000)
        }

        override fun onLinkDown() {
            if (role == WpRole.GUEST) phase = WpPhase.RECONNECTING
        }

        override fun onClock() {
            if (role == WpRole.GUEST && !helloSent && clock.count >= 6) sayHello()
        }

        override fun activate(resume: Boolean) {
            if (GroupLink.role == GroupRole.HOST) {
                role = WpRole.HOST
                phase = WpPhase.HOSTING
                GroupLink.guestList().forEach { (ep, _, name) -> addGuest(ep, name) }
                heartbeat?.cancel()
                heartbeat = io.launch {
                    while (isActive) {
                        delay(5_000)
                        main.post { if (role == WpRole.HOST) broadcastState() }
                    }
                }
                if (!startingHere) {
                    wantsPlayer = true
                    expectPlayer()
                }
                if (resume && current != null && wasPlaying) prepare(currentId, posMs) else broadcastState()
                wasPlaying = false
            } else {
                role = WpRole.GUEST
                phase = if (GroupLink.phase == GroupPhase.RECONNECTING) WpPhase.RECONNECTING else WpPhase.IN_ROOM
                helloSent = false
                if (clock.count >= 6) sayHello() else main.postDelayed({ sayHello() }, 2_000)
                wantsPlayer = true
                expectPlayer()
            }
            GroupLink.setTitle(current?.title.orEmpty())
        }

        override fun deactivate() {
            if (role == WpRole.HOST) {
                wasPlaying = stage != WpStage.PAUSED && current != null
                if (stage == WpStage.PLAYING) posMs = positionMs()
                stage = WpStage.PAUSED
                guests.values.forEach { it.pump?.cancel() }
                guests.clear()
            }
            heartbeat?.cancel()
            listOf(gateTimer, endTimer).forEach { r -> r?.let { main.removeCallbacks(it) } }
            readiness.clear()
            reactions.clear()
            askFrom = null
            syncErrorMs = null
            helloSent = false
            lastReadySent = -1
            wantsPlayer = false
            playerOpening = false
            // The queue and a guest's cached video stay: switching back carries on.
            role = WpRole.NONE
            phase = WpPhase.IDLE
        }

        override fun reset() {
            clearParty()
            role = WpRole.NONE
            phase = WpPhase.IDLE
        }

        override fun statusText(): String? = current?.let {
            when (stage) {
                WpStage.PLAYING -> "Watching “${it.title}”"
                WpStage.PREPARING -> "Getting everyone ready"
                WpStage.PAUSED -> "Paused: ${it.title}"
            }
        }
    }

    private fun addGuest(endpointId: String, name: String) {
        guests.remove(endpointId)?.pump?.cancel()
        guests[endpointId] = Guest(endpointId, name)
    }

    private fun onGuestMessage(endpointId: String, o: JSONObject) {
        val g = guests[endpointId] ?: return
        when (o.optString("t")) {
            "hello" -> {
                o.optJSONObject("have")?.let { have ->
                    have.keys().forEach { k ->
                        val id = k.toIntOrNull() ?: return@forEach
                        runCatching { BitSet.valueOf(Base64.decode(have.optString(k), Base64.NO_WRAP)) }.getOrNull()?.let { g.sent[id] = it }
                    }
                }
                if (g.pump == null) g.pump = io.launch { pump(g) }
                g.wake.trySend(Unit)
            }
            "want" -> {
                val id = o.optInt("track")
                val piece = o.optInt("piece")
                g.cursor[id] = piece
                // Missing although sent (and not on its way): send it again.
                if (g.inFlight.values.none { it.first == id && it.second == piece }) g.sent[id]?.let { synchronized(it) { it.clear(piece) } }
                g.wake.trySend(Unit)
            }
            "ready" -> if (o.optInt("track") == currentId) {
                g.readyPct = o.optInt("pct")
                readiness[g.name] = g.readyPct
                checkGate()
            }
            "ask" -> {
                val now = System.currentTimeMillis()
                if (now - g.lastAsk > 10_000 && stage == WpStage.PLAYING) {
                    g.lastAsk = now
                    askFrom = g.name
                    main.postDelayed({ if (askFrom == g.name) askFrom = null }, 12_000)
                }
            }
            "react" -> react(o.optString("e"), from = g.name, except = endpointId)
        }
    }

    fun dismissAsk() {
        askFrom = null
    }

    // ------------------------------------------------------------------ host: the timeline
    /** Everyone to [ms] of video [id], paused, until each has a few seconds in hand. */
    private fun prepare(id: Int, ms: Long) {
        currentId = id
        stage = WpStage.PREPARING
        posMs = ms.coerceAtLeast(0)
        readiness.clear()
        guests.values.forEach { it.readyPct = 0 }
        readiness[myName] = if (playerOpen || playerOpening) 0 else 100
        guests.values.forEach { readiness[it.name] = 0 }
        endTimer?.let { main.removeCallbacks(it) }
        gateTimer?.let { main.removeCallbacks(it) }
        gateTimer = Runnable { if (stage == WpStage.PREPARING) startNow() }.also { main.postDelayed(it, GATE_TIMEOUT_MS) }
        broadcastState()
        guests.values.forEach { it.wake.trySend(Unit) }
    }

    private fun checkGate() {
        if (role != WpRole.HOST || stage != WpStage.PREPARING) return
        if (readiness.values.all { it >= 100 }) startNow()
    }

    /** Host: go — "Start anyway" too. */
    fun startNow() {
        if (role != WpRole.HOST || current == null) return
        gateTimer?.let { main.removeCallbacks(it) }
        stage = WpStage.PLAYING
        t0HostNs = System.nanoTime() + LEAD_NS - posMs * 1_000_000
        broadcastState()
    }

    fun togglePause() {
        if (role != WpRole.HOST || current == null) return
        when (stage) {
            WpStage.PLAYING -> {
                posMs = positionMs()
                stage = WpStage.PAUSED
                broadcastState()
            }
            WpStage.PAUSED -> {
                // At the end: on to the next video if one is queued, else this one again from the start.
                val atEnd = current?.durationMs?.let { it > 0 && posMs >= it - 1_000 } == true
                if (atEnd && upNext.isNotEmpty()) next() else prepare(currentId, if (atEnd) 0 else posMs)
            }
            WpStage.PREPARING -> startNow()
        }
    }

    fun seekTo(ms: Long) {
        if (role != WpRole.HOST || current == null) return
        if (stage == WpStage.PAUSED) {
            posMs = ms.coerceAtLeast(0)
            broadcastState()
        } else {
            prepare(currentId, ms)
        }
    }

    fun next() {
        if (role != WpRole.HOST) return
        val i = queue.indexOfFirst { it.id == currentId }
        val n = queue.getOrNull(i + 1)
        if (n != null) {
            prepare(n.id, 0)
        } else {
            posMs = current?.durationMs ?: posMs
            stage = WpStage.PAUSED
            broadcastState()
        }
    }

    /** The player learned a link's length: the end-of-video timer can work. */
    fun reportDuration(id: Int, ms: Long) {
        if (ms <= 0) return
        val i = queue.indexOfFirst { it.id == id }
        if (i < 0 || queue[i].durationMs > 0) return
        queue[i] = queue[i].withDuration(ms)
        if (role == WpRole.HOST) broadcastState()
    }

    private fun scheduleEnd() {
        endTimer?.let { main.removeCallbacks(it) }
        endTimer = null
        val c = current ?: return
        if (role != WpRole.HOST || stage != WpStage.PLAYING || c.durationMs <= 0) return
        val forT0 = t0HostNs
        val at = t0HostNs + c.durationMs * 1_000_000 + 400_000_000
        endTimer = Runnable { if (role == WpRole.HOST && stage == WpStage.PLAYING && t0HostNs == forT0) next() }
            .also { main.postDelayed(it, ((at - System.nanoTime()) / 1_000_000).coerceAtLeast(0)) }
    }

    private fun stateJson(): JSONObject {
        // From the video before the current one, as much as fits well inside one Nearby message (32 KB max).
        val list = queue.toList()
        val q = JSONArray()
        var bytes = 0
        for (v in list.drop((list.indexOfFirst { it.id == currentId } - 1).coerceAtLeast(0))) {
            val j = v.toJson()
            bytes += j.toString().toByteArray().size + 1
            if (q.length() > 0 && (bytes > STATE_QUEUE_BYTES || q.length() >= 60)) break
            q.put(j)
        }
        return PartyWire.msg("state")
            .put("seq", ++stateSeq)
            .put("queue", q).put("current", currentId)
            .put("stage", stage.key).put("t0", t0HostNs).put("pos", posMs)
    }

    private fun broadcastState() {
        if (role != WpRole.HOST) return
        if (guests.isNotEmpty()) sendTo(guests.keys.toList(), stateJson())
        scheduleEnd()
        GroupLink.setTitle(current?.title.orEmpty())
        GroupService.refresh(app)
    }

    /** Host → one guest: the current video, piece by piece — the one its player waits for first, then onwards. */
    private suspend fun pump(g: Guest) {
        val buf = ByteArray(CHUNK)
        while (guests[g.endpointId] === g) {
            if (g.inFlight.size >= WINDOW) {
                withTimeoutOrNull(2_000) { g.wake.receive() }
                resendStuck(g)
                continue
            }
            val (v, piece) = nextFor(g) ?: run {
                withTimeoutOrNull(3_000) { g.wake.receive() }
                null
            } ?: continue
            val data = tracks[v.id] ?: continue
            val offset = piece.toLong() * CHUNK
            val n = data.readPiece(offset, buf) { guests[g.endpointId] !== g }
            if (n <= 0) {
                delay(200)
                continue
            }
            val sent = g.sentOf(v.id)
            synchronized(sent) { sent.set(piece) }
            g.cursor[v.id] = piece + 1
            val payload = GroupLink.payload(GroupMode.WATCH, PartyWire.chunk(v.id, offset, buf, n))
            g.inFlight[payload.id] = v.id to piece
            g.movedAt[payload.id] = System.currentTimeMillis()
            if (!GroupLink.send(g.endpointId, payload)) {
                g.inFlight.remove(payload.id)
                g.movedAt.remove(payload.id)
                synchronized(sent) { sent.clear(piece) }
            }
        }
    }

    /** Pieces that stopped moving (their transfer report never came) free their places and go again. */
    private fun resendStuck(g: Guest) {
        val now = System.currentTimeMillis()
        g.inFlight.keys.filter { now - (g.movedAt[it] ?: 0L) > STUCK_MS }.forEach { id ->
            val (videoId, piece) = g.inFlight.remove(id) ?: return@forEach
            g.movedAt.remove(id)
            GroupLink.cancel(id)
            g.sent[videoId]?.let { synchronized(it) { it.clear(piece) } }
        }
    }

    private fun nextFor(g: Guest): Pair<WatchVideo, Int>? {
        val v = queue.toList().firstOrNull { it.id == currentId } ?: return null
        if (v.isLink || v.size <= 0 || !tracks.containsKey(v.id)) return null
        val count = ((v.size + CHUNK - 1) / CHUNK).toInt()
        val sent = g.sentOf(v.id)
        val from = (g.cursor[v.id] ?: 0).coerceIn(0, count)
        val piece = synchronized(sent) {
            sent.nextClearBit(from).takeIf { it < count } ?: sent.nextClearBit(0).takeIf { it < count }
        } ?: return null
        return v to piece
    }

    // ================================================================== reactions & asking
    /** Five fixed emoji (nothing typed). */
    val EMOJI = listOf("😂", "❤️", "👏", "😮", "🔥")
    private var reactionKey = 0L

    fun react(emoji: String) {
        if (emoji !in EMOJI || !active) return
        if (role == WpRole.HOST) react(emoji, from = myName, except = null) else {
            show(emoji, "You")
            sendToHost(PartyWire.msg("react").put("e", emoji))
        }
    }

    private fun react(emoji: String, from: String, except: String?) {
        if (emoji !in EMOJI) return
        show(emoji, if (from == myName && role == WpRole.HOST) "You" else from)
        val to = guests.keys.filter { it != except }
        sendTo(to, PartyWire.msg("react").put("e", emoji).put("from", from))
    }

    private fun show(emoji: String, from: String) {
        val r = Reaction(++reactionKey, emoji, from)
        reactions.add(r)
        while (reactions.size > 12) reactions.removeAt(0)
        main.postDelayed({ reactions.remove(r) }, 2_600)
    }

    /** Guest: ask the host to pause. */
    private var lastAskAt = 0L

    fun askToPause() {
        if (role != WpRole.GUEST) return
        val now = System.currentTimeMillis()
        notice = when {
            stage != WpStage.PLAYING -> "It's already paused."
            // The host hears one ask per 10 seconds from each phone.
            now - lastAskAt < 10_000 -> "You asked a moment ago."
            else -> {
                lastAskAt = now
                sendToHost(PartyWire.msg("ask"))
                "Asked $hostName to pause."
            }
        }
    }

    // ================================================================== guests
    private fun sayHello() {
        if (helloSent || phase != WpPhase.IN_ROOM) return
        helloSent = true
        val have = JSONObject()
        tracks.forEach { (id, d) ->
            val bits = BitSet()
            for (i in 0 until d.pieceCount) if (d.hasPiece(i)) bits.set(i)
            have.put(id.toString(), Base64.encodeToString(bits.toByteArray(), Base64.NO_WRAP))
        }
        sendToHost(PartyWire.msg("hello").put("have", have))
    }

    private fun onHostMessage(o: JSONObject) {
        when (o.optString("t")) {
            "state" -> applyState(o)
            "react" -> show(o.optString("e"), o.optString("from"))
        }
    }

    private fun applyState(o: JSONObject) {
        if (role != WpRole.GUEST) return
        val list = mutableListOf<WatchVideo>()
        o.optJSONArray("queue")?.let { a -> for (i in 0 until a.length()) list.add(WatchVideo.fromJson(a.getJSONObject(i))) }
        val newCurrent = o.optInt("current", -1)
        // A cache only for the video being watched (videos are big); old ones go.
        list.firstOrNull { it.id == newCurrent }?.let { v ->
            if (!v.isLink && v.size > 0 && !tracks.containsKey(v.id)) {
                val free = app.cacheDir.usableSpace
                if (free < v.size + 100L * 1024 * 1024) {
                    notice = "Not enough free space for “${v.title}” (needs ${v.size / (1024 * 1024)} MB). Free some space to watch."
                } else {
                    tracks[v.id] = TrackData.incoming(app, v.id, v.size, CACHE).also { d ->
                        d.onMissing = { piece -> main.post { sendToHost(PartyWire.msg("want").put("track", v.id).put("piece", piece)) } }
                    }
                }
            }
        }
        tracks.keys.filter { it != newCurrent }.forEach { id -> tracks.remove(id)?.close() }
        queue.clear()
        queue.addAll(list)
        val newStage = WpStage.of(o.optString("stage"))
        val newPos = o.optLong("pos")
        val restart = newCurrent != currentId || newStage != stage || (newStage == WpStage.PREPARING && newPos != posMs)
        currentId = newCurrent
        stage = newStage
        t0HostNs = o.optLong("t0")
        posMs = newPos
        if (restart) lastReadySent = -1
        // Not watching (player closed): don't hold anyone up.
        if (stage == WpStage.PREPARING && !playerOpen && !playerOpening) reportReady(100)
        GroupService.refresh(app)
    }

    /** From the player: how ready this phone is (0–100) for the start / seek. */
    fun reportReady(pct: Int) {
        val p = pct.coerceIn(0, 100)
        if (role == WpRole.HOST) {
            if (stage != WpStage.PREPARING) return
            readiness[myName] = p
            checkGate()
        } else if (role == WpRole.GUEST && stage == WpStage.PREPARING) {
            // Only on change (by 10s), to keep the line quiet.
            val bucket = p / 10
            if (bucket == lastReadySent) return
            lastReadySent = bucket
            if (com.agani.syncup.BuildConfig.DEBUG) android.util.Log.d("WatchParty", "ready -> host: $p% (player open=$playerOpen opening=$playerOpening)")
            sendToHost(PartyWire.msg("ready").put("track", currentId).put("pct", p))
        }
    }

    /** From the player: how far off this phone is (ms, + = ahead). */
    fun reportSync(errorMs: Double?) {
        syncErrorMs = errorMs
    }

    private fun clearParty() {
        heartbeat?.cancel()
        listOf(gateTimer, endTimer).forEach { r -> r?.let { main.removeCallbacks(it) } }
        guests.values.forEach { it.pump?.cancel() }
        guests.clear()
        tracks.values.forEach { it.close() }
        tracks.clear()
        localUris.clear()
        queue.clear()
        readiness.clear()
        reactions.clear()
        askFrom = null
        currentId = -1
        stage = WpStage.PAUSED
        posMs = 0
        t0HostNs = 0
        syncErrorMs = null
        nextVideoId = 1
        wasPlaying = false
        wantsPlayer = false
        playerOpening = false
        helloSent = false
        lastReadySent = -1
        if (ready) TrackData.clearCache(app, CACHE)
    }

    /** One line for the Tools tile. */
    fun statusLine(): String? = when {
        !active -> null
        else -> "Watch Party: $roomName · ${people.size}"
    }

    // ================================================================== sending
    private fun sendTo(endpoints: List<String>, o: JSONObject) {
        if (endpoints.isEmpty()) return
        GroupLink.send(endpoints, GroupLink.payload(GroupMode.WATCH, PartyWire.json(o)))
    }

    private fun sendToHost(o: JSONObject) {
        GroupLink.sendToHost(GroupLink.payload(GroupMode.WATCH, PartyWire.json(o)))
    }
}
