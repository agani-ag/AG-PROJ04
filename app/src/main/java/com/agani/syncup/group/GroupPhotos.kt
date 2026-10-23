package com.agani.syncup.group

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.agani.syncup.BuildConfig
import com.agani.syncup.downloads.Files
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** A photo in the group's pool, as this phone sees it. */
class GroupPhoto internal constructor(
    val id: Long,
    /** Whose photo it is: their name in the group, and their phone's key (the badge colour follows it). */
    val owner: String,
    internal val ownerKey: String,
    /** The photo file's own name (kept when saving) and its type. */
    val name: String,
    val mime: String,
    /** Bytes: the full photo, and its small preview. */
    internal val size: Long,
    internal val thumbSize: Long,
    /** Added on this phone — it's in this phone's Gallery already. */
    val mine: Boolean,
    seq: Long,
) {
    /** Its place in the pool (the host numbers photos as they come in); the grid shows the newest first. */
    var seq by mutableLongStateOf(seq)
        internal set
    /** The small preview, once it's on this phone. */
    var thumb by mutableStateOf<File?>(null)
        internal set
    /** The full photo, once it's on this phone. */
    var full by mutableStateOf<File?>(null)
        internal set
    /** Saved into this phone's Gallery (Save all / Save this photo). */
    var saved by mutableStateOf(false)
        internal set

    /** For the badge: the first letter of the owner's name. */
    val initial: String get() = owner.trim().take(1).uppercase().ifBlank { "?" }

    // For the senders (read off the main thread): which parts are complete on this phone.
    @Volatile internal var hasThumb = false
    @Volatile internal var hasFull = false
    internal fun has(kind: Int) = if (kind == GroupPhotos.THUMB) hasThumb else hasFull

    internal fun meta(): JSONObject = JSONObject()
        .put("id", id).put("o", owner).put("k", ownerKey).put("n", name).put("m", mime)
        .put("s", size).put("ts", thumbSize).put("q", seq)
}

/**
 * The group's Photo Show, the same on every phone. Whoever started it holds the controls; the host
 * keeps it and passes it on. No message per photo: photo i is up from [t0] + i × [everyMs] on the
 * host's clock ([GroupLink.clock]), so every phone works out the same photo at the same moment.
 * Pause / next / previous / options only send a new copy of this.
 */
data class PhotoShow(
    internal val sid: Int,
    /** Who started it (they have the controls). */
    val by: String,
    internal val byKey: String,
    /** The photos, in the pool's order when it started; photos added since go at the end. */
    internal val ids: List<Long>,
    /** How many of [ids] are shuffled (the ones there at the start); the rest follow in order. */
    internal val base: Int,
    internal val seed: Long,
    val shuffled: Boolean,
    val everyMs: Long,
    /** Host clock (ns) when photo 0 came up. */
    internal val t0: Long,
    val paused: Boolean,
    /** Paused: the photo it stopped on. */
    internal val at: Int,
) {
    /** The order every phone shows: one seeded shuffle, the same everywhere. */
    val order: List<Long> = if (shuffled) seededShuffle(ids.take(base), seed) + ids.drop(base) else ids
    val count: Int get() = order.size
    private val stepNs: Long get() = everyMs * 1_000_000L

    /** The photo on screen at [hostNs] (host clock): the show goes round again after the last one. */
    fun indexAt(hostNs: Long): Int {
        if (count == 0) return 0
        if (paused) return at.coerceIn(0, count - 1)
        return Math.floorMod(Math.floorDiv(hostNs - t0, stepNs), count.toLong()).toInt()
    }

    /** How long the photo on screen at [hostNs] has been up (0 when paused). */
    internal fun shownFor(hostNs: Long): Long = if (paused) 0 else Math.floorMod(hostNs - t0, stepNs)

    /** When the next photo comes up after [hostNs] (host clock). */
    internal fun nextChange(hostNs: Long): Long = t0 + (Math.floorDiv(hostNs - t0, stepNs) + 1) * stepNs

    /** The same show, with photo [i] up now ([hostNs]), [into] it by this much. */
    internal fun showing(i: Int, hostNs: Long, into: Long = 0): PhotoShow =
        if (paused) copy(at = i) else copy(t0 = hostNs - i * stepNs - into)

    internal fun toJson(): JSONObject = JSONObject()
        .put("sid", sid).put("by", by).put("bk", byKey).put("ids", JSONArray(ids)).put("base", base)
        .put("seed", seed).put("shuf", shuffled).put("every", everyMs).put("t0", t0).put("paused", paused).put("at", at)

    companion object {
        internal fun fromJson(o: JSONObject): PhotoShow? = runCatching {
            val a = o.getJSONArray("ids")
            PhotoShow(
                o.getInt("sid"), o.optString("by"), o.optString("bk"), List(a.length()) { a.getLong(it) },
                o.optInt("base"), o.optLong("seed"), o.optBoolean("shuf"), o.optLong("every", 5_000).coerceIn(1_000, 60_000),
                o.getLong("t0"), o.optBoolean("paused"), o.optInt("at"),
            )
        }.getOrNull()

        /** Fisher–Yates with java.util.Random: the same seed gives the same order on every phone. */
        private fun seededShuffle(list: List<Long>, seed: Long): List<Long> {
            val out = list.toMutableList()
            val r = java.util.Random(seed)
            for (i in out.size - 1 downTo 1) {
                val j = r.nextInt(i + 1)
                val t = out[i]
                out[i] = out[j]
                out[j] = t
            }
            return out
        }
    }
}

/** "Save all": how far it has got. [ids] are the photos it was asked for. */
class PhotoSaveJob internal constructor(internal val ids: Set<Long>, val total: Int) {
    /** Saved now, or already in the Gallery (your own photos, ones saved before). */
    var done by mutableIntStateOf(0)
        internal set
    var failed by mutableIntStateOf(0)
        internal set
    val finished: Boolean get() = done + failed >= total
}

/**
 * A group's Group photos mode (Photo Drop + Photo Show): everyone's photos in one place.
 *
 * Anyone adds photos (Android's photo picker — no permission). Each gets an id and its owner, and
 * a small preview (about 320 px, turned the right way up) that goes to every phone first; the full
 * photo follows by itself. Everything goes through the host's phone, which keeps the whole pool and
 * passes it on — to a phone that joins late or comes back, too: the previews first, then the photos.
 * Photos travel in 30 KB pieces on the group's photos channel (like Music party's songs), so they
 * never mix with Share files.
 *
 * **Save all** copies the full photos, as they were taken, into Pictures / SyncUp Group; photos you
 * added (already in your Gallery) and ones saved before aren't saved twice. **Photo Show**: see
 * [PhotoShow].
 *
 * Files live in the app's cache (one folder per group) and are deleted when the group ends or you
 * leave. Switching the group to another mode keeps the pool; switching back carries on.
 *
 * Main thread for everything public (Nearby calls back there too).
 */
object GroupPhotos {
    private const val TAG = "GroupPhotos"
    /** Photo bytes per message (Nearby's limit is 32 KB, with our 14-byte header and the channel byte). */
    private const val PIECE = 30_000
    /** Pieces on their way to one phone at a time (keeps clock pings and other messages from waiting long). */
    private const val WINDOW = 6
    /** A piece on its way that hasn't moved for this long is taken as lost: it goes again. */
    private const val STUCK_MS = 20_000L
    /** The preview's shorter side (px), and the most its longer side may be. */
    private const val THUMB_PX = 320
    private const val THUMB_MAX_PX = 640
    /** Photos in one "pool" message (well under the 32 KB limit). */
    private const val POOL_PART = 80
    /** The most photos one show takes (the newest), so the show fits in one message. */
    private const val SHOW_MAX = 1_500
    /** The host forgets a phone's unfinished photos (and its show) when it's been gone this long. */
    private const val GONE_MS = 60_000L
    /** Photos the picker lets you pick at once. */
    const val MAX_PICK = 50
    /** The Gallery folder: Pictures / SyncUp Group. */
    const val GALLERY_FOLDER = "SyncUp Group"
    private const val FOLDER = "group_photos"

    private const val T_JSON: Byte = 1
    private const val T_PIECE: Byte = 2
    internal const val THUMB = 0
    internal const val FULL = 1

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ready = false

    // ------------------------------------------------------------------ what the screens show
    /** The group is in Group photos mode on this phone. */
    var active by mutableStateOf(false)
        private set
    /** Every photo in the group, newest first. */
    val pool = mutableStateListOf<GroupPhoto>()
    /** Photos being got ready on this phone (copied, preview made) before they go out. */
    var preparing by mutableIntStateOf(0)
        private set
    /** "Save all", while it runs (and after, until the next one). */
    var saveJob by mutableStateOf<PhotoSaveJob?>(null)
        private set
    /** The group's Photo Show, or null. */
    var show by mutableStateOf<PhotoShow?>(null)
        private set
    /** The show's photo on screen now (its place in [PhotoShow.order]). */
    var showIndex by mutableIntStateOf(0)
        private set
    /** This phone shows the show full screen (Back leaves it; the show and the group go on). */
    var showOpen by mutableStateOf(false)
    /** The photo open full screen in the viewer (its id), or null. */
    var viewing by mutableStateOf<Long?>(null)
    /** Something to tell the user once (a toast). */
    var notice by mutableStateOf<String?>(null)

    // ------------------------------------------------------------------ inside
    private val byId = ConcurrentHashMap<Long, GroupPhoto>()
    /** Owners' keys in the order their first photo came: the badge colours (the same on every phone). */
    private val owners = mutableStateListOf<String>()
    /** Photos arriving on this phone (preview or full), by blob key: their part files. Main thread. */
    private val incoming = HashMap<Long, Incoming>()
    /** Host: the guests, by endpoint id. */
    private val peers = ConcurrentHashMap<String, Peer>()
    /** Guest: the host (this phone's own photos go up to it). */
    @Volatile private var hostPeer: Peer? = null
    /** Host: the pool's next number. */
    private var nextSeq = 1L
    /** Guest: numbers for photos added here before the host's number comes back (they sort on top). */
    private var localSeq = 0L
    private var showSeq = 0
    /** Guest: a first guess of the host's clock (from the last show message) until the clock is measured. */
    private var roughOffsetNs = 0L
    private var dir: File? = null
    /** Photos waiting to be saved (ids, in order; screens follow it), and the job saving them. */
    private val toSave = mutableStateListOf<Long>()
    private var saver: Job? = null
    private val ticker = Runnable { tick() }

    private val isHost get() = GroupLink.isHost

    fun init(context: Context) {
        if (ready) return
        ready = true
        app = context.applicationContext
        // Left over from a group the app didn't get to close (killed): moved aside at once (a new
        // group's folder is never caught by it), deleted in the background.
        val left = File(app.cacheDir, FOLDER)
        val old = File(app.cacheDir, "$FOLDER-old-${System.currentTimeMillis()}")
        if (left.exists() && left.renameTo(old)) io.launch { old.deleteRecursively() }
        io.launch { app.cacheDir.listFiles { f -> f.name.startsWith("$FOLDER-old-") && f != old }?.forEach { it.deleteRecursively() } }
        GroupLink.init(app)
        GroupLink.register(GroupMode.PHOTOS, channel)
    }

    // ================================================================== for the screen
    /** Phones whose photos are in the pool. */
    fun phoneCount(): Int = pool.map { it.ownerKey }.distinct().size

    /** Photos not (fully) on this phone yet. */
    fun arrivingCount(): Int = pool.count { it.full == null }

    /** The badge colour's number for this photo's owner (0, 1, 2… in the order people added photos). */
    fun ownerSlot(p: GroupPhoto): Int = owners.indexOf(p.ownerKey).coerceAtLeast(0)

    fun photo(id: Long): GroupPhoto? = byId[id]

    /** The show's photo on screen now. */
    fun showPhoto(): GroupPhoto? = show?.order?.getOrNull(showIndex)?.let { byId[it] }

    /** The show's next photo (to have it ready). */
    fun showNextPhoto(): GroupPhoto? = show?.let { s -> s.order.getOrNull((showIndex + 1) % max(1, s.count)) }?.let { byId[it] }

    /** This phone started the show (it has the controls). */
    val showIsMine: Boolean get() = show?.byKey == GroupLink.myToken

    // ================================================================== adding
    /** Add photos from the picker: each gets a preview, joins the pool and goes to every phone. */
    fun addPhotos(uris: List<Uri>) {
        if (!GroupLink.inGroup || uris.isEmpty()) return
        val folder = folder() ?: return
        preparing += uris.size
        io.launch {
            var failed = 0
            for (uri in uris.take(MAX_PICK)) {
                val ready = runCatching { prepare(uri, folder) }.onFailure { Log.w(TAG, "couldn't add a photo", it) }.getOrNull()
                if (ready == null) failed++
                withContext(Dispatchers.Main) {
                    preparing = (preparing - 1).coerceAtLeast(0)
                    if (ready != null) {
                        // The group may have ended meanwhile (its folder is gone then).
                        if (GroupLink.inGroup && dir == folder) addMine(ready) else ready.delete()
                    }
                }
            }
            if (failed > 0) withContext(Dispatchers.Main) {
                if (GroupLink.inGroup) notice = if (failed == 1) "Couldn't open one photo." else "Couldn't open $failed photos."
            }
        }
    }

    /** A picked photo, copied into the group's folder, with its preview. */
    private class Ready(val id: Long, val name: String, val mime: String, val full: File, val thumb: File) {
        fun delete() {
            full.delete()
            thumb.delete()
        }
    }

    /** Copy the photo into the cache and make its preview. Background thread. */
    private fun prepare(uri: Uri, folder: File): Ready? {
        val cr = app.contentResolver
        var name = ""
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0).orEmpty()
        }
        val mime = cr.getType(uri)?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
        val id = newId()
        val full = File(folder, "$id.f")
        val thumb = File(folder, "$id.t")
        val copied = cr.openInputStream(uri)?.use { input -> FileOutputStream(full).use { out -> input.copyTo(out, 256 * 1024) } }
        if (copied == null || full.length() <= 0 || !makeThumb(full, thumb)) {
            full.delete()
            thumb.delete()
            return null
        }
        return Ready(id, Files.cleanName(saveName(name, full, mime)), mime, full, thumb)
    }

    /**
     * The name the photo is saved under. Android's photo picker hides the real one (it gives a
     * number, "295.jpg"): then "IMG_20261020_174512.jpg", from when it was taken (EXIF), else now.
     */
    private fun saveName(picked: String, file: File, mime: String): String {
        val base = picked.substringBeforeLast('.', picked)
        if (base.isNotBlank() && base.any { !it.isDigit() }) return picked
        val ext = picked.substringAfterLast('.', "").ifBlank { MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg" }
        val exif = runCatching { ExifInterface(file.path) }.getOrNull()
        val taken = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif?.getAttribute(ExifInterface.TAG_DATETIME)
        val stamp = taken?.let { Regex("""(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2}):(\d{2})""").find(it) }
            ?.groupValues?.let { g -> "${g[1]}${g[2]}${g[3]}_${g[4]}${g[5]}${g[6]}" }
            ?: java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        return "IMG_$stamp.$ext"
    }

    private fun addMine(r: Ready) {
        val p = GroupPhoto(
            r.id, GroupLink.myName, GroupLink.myToken, r.name, r.mime, r.full.length(), r.thumb.length(), mine = true,
            seq = if (isHost) nextSeq++ else Long.MAX_VALUE / 2 + ++localSeq,
        )
        p.thumb = r.thumb
        p.full = r.full
        p.hasThumb = true
        p.hasFull = true
        insert(p)
        if (isHost) {
            // To everyone: what's coming, then (by the senders) the preview and the photo.
            sendGuests(msg("photo").put("p", p.meta()))
            addToShow(p.id)
            wakeAll()
        } else {
            sendHost(msg("add").put("p", p.meta()))
            hostPeer?.let { wake(it) }
        }
        GroupService.refresh(app)
    }

    // ================================================================== the pool
    /** Into the pool at its place (newest first). */
    private fun insert(p: GroupPhoto) {
        byId[p.id] = p
        if (p.ownerKey !in owners) owners.add(p.ownerKey)
        val at = pool.indexOfFirst { it.seq < p.seq }
        if (at < 0) pool.add(p) else pool.add(at, p)
    }

    private fun resort(p: GroupPhoto) {
        pool.remove(p)
        val at = pool.indexOfFirst { it.seq < p.seq }
        if (at < 0) pool.add(p) else pool.add(at, p)
    }

    /** A photo from another phone (its details; the preview and the photo follow). */
    private fun upsert(o: JSONObject, seq: Long?): GroupPhoto? {
        val id = o.optLong("id")
        val size = o.optLong("s")
        val thumbSize = o.optLong("ts")
        if (id <= 0 || size <= 0 || size > 400L * 1024 * 1024 || thumbSize <= 0 || thumbSize > 4L * 1024 * 1024) return null
        byId[id]?.let { known ->
            // Ours (or known): the host's number came.
            if (seq != null && known.seq != seq) {
                known.seq = seq
                resort(known)
            }
            return known
        }
        val folder = folder() ?: return null
        val p = GroupPhoto(
            id, o.optString("o").ifBlank { "Someone" }, o.optString("k"), Files.cleanName(o.optString("n").ifBlank { "Photo.jpg" }),
            o.optString("m").ifBlank { "image/jpeg" }, size, thumbSize, mine = false, seq = seq ?: o.optLong("q"),
        )
        incoming[keyOf(id, THUMB)] = Incoming(File(folder, "$id.t"), thumbSize)
        incoming[keyOf(id, FULL)] = Incoming(File(folder, "$id.f"), size)
        insert(p)
        GroupService.refresh(app)
        return p
    }

    private fun removePhoto(id: Long) {
        val p = byId.remove(id) ?: return
        pool.remove(p)
        for (kind in listOf(THUMB, FULL)) incoming.remove(keyOf(id, kind))?.discard()
        p.thumb?.delete()
        p.full?.delete()
        toSave.remove(id)
        if (viewing == id) viewing = null
    }

    // ================================================================== the wire
    private fun msg(type: String) = JSONObject().put("t", type)

    private fun json(o: JSONObject): ByteArray {
        val body = o.toString().toByteArray(Charsets.UTF_8)
        return ByteArray(body.size + 1).also {
            it[0] = T_JSON
            System.arraycopy(body, 0, it, 1, body.size)
        }
    }

    /** One piece: [type][kind][photo id: 8][offset: 4][bytes]. */
    private fun piece(kind: Int, id: Long, offset: Int, data: ByteArray, len: Int): ByteArray =
        ByteBuffer.allocate(14 + len).put(T_PIECE).put(kind.toByte()).putLong(id).putInt(offset).put(data, 0, len).array()

    private fun sendGuests(o: JSONObject, except: String? = null) {
        val eps = GroupLink.guestEndpoints().filter { it != except }
        if (eps.isNotEmpty()) GroupLink.send(eps, GroupLink.payload(GroupMode.PHOTOS, json(o)))
    }

    private fun sendTo(endpointId: String, o: JSONObject) = GroupLink.send(endpointId, GroupLink.payload(GroupMode.PHOTOS, json(o)))

    private fun sendHost(o: JSONObject) = GroupLink.sendToHost(GroupLink.payload(GroupMode.PHOTOS, json(o)))

    private fun keyOf(id: Long, kind: Int): Long = (id shl 1) or kind.toLong()

    private fun pieceCount(size: Long): Int = ((size + PIECE - 1) / PIECE).toInt()

    // ================================================================== the group's photos channel
    internal val channel = object : GroupChannel {
        override fun onReceive(endpointId: String, body: ByteArray, arrivedNs: Long) {
            when (body.firstOrNull()) {
                T_PIECE -> onPiece(body)
                T_JSON -> {
                    val o = runCatching { JSONObject(String(body, 1, body.size - 1, Charsets.UTF_8)) }.getOrNull() ?: return
                    if (isHost) onGuestMessage(endpointId, o) else onHostMessage(o, arrivedNs)
                }
            }
        }

        override fun onTransfer(endpointId: String, update: PayloadTransferUpdate) {
            val p = if (isHost) peers[endpointId] else hostPeer?.takeIf { it.endpointId == endpointId }
            p ?: return
            val id = update.payloadId
            if (update.status == PayloadTransferUpdate.Status.IN_PROGRESS) {
                if (p.movedAt.containsKey(id)) p.movedAt[id] = System.currentTimeMillis()
                return
            }
            p.movedAt.remove(id)
            val (key, piece) = p.inFlight.remove(id) ?: return
            val g = p.going[key]
            if (g != null) {
                synchronized(g) {
                    if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                        g.arrived.set(piece)
                        // All of it is there: that phone has it.
                        if (g.arrived.cardinality() >= g.count) {
                            p.has.add(key)
                            p.going.remove(key)
                        }
                    } else {
                        g.sent.clear(piece) // didn't make it: again
                    }
                }
            }
            p.wake.trySend(Unit)
        }

        override fun onMemberIn(endpointId: String, token: String, name: String) {
            if (!isHost || !active) return
            addPeer(endpointId, token, name)
            sendPool(endpointId)
        }

        override fun onMemberOut(endpointId: String) {
            val p = peers.remove(endpointId) ?: return
            p.stop()
            // Gone for good (not back in a minute): their unfinished photos and their show go too.
            main.postDelayed({ forgetIfGone(p.key) }, GONE_MS)
        }

        override fun onLinkUp() {
            // The host sends the pool again (it sees this phone come back); the uploads restart from that.
        }

        override fun onLinkDown() {
            hostPeer?.stop()
            hostPeer = null
        }

        override fun onClock() {
            // A better measurement of the host's clock: the show's next change moves with it.
            if (show != null) tick()
        }

        override fun activate(resume: Boolean) {
            active = true
            folder()
            if (isHost) {
                // Every guest gets the pool (what it has stays); the photos follow once it says what it has.
                GroupLink.guestList().forEach { (ep, token, name) ->
                    addPeer(ep, token, name)
                    sendPool(ep)
                }
            }
            // A guest waits for the host's pool.
        }

        override fun deactivate() {
            // The pool and its files stay (switching back carries on); the senders and the show stop.
            active = false
            stopSenders()
            endShow(broadcast = false)
        }

        override fun reset() {
            active = false
            stopSenders()
            endShow(broadcast = false)
            saver?.cancel()
            saver = null
            toSave.clear()
            saveJob = null
            incoming.values.forEach { it.discard() }
            incoming.clear()
            pool.clear()
            byId.clear()
            owners.clear()
            preparing = 0
            viewing = null
            nextSeq = 1
            localSeq = 0
            roughOffsetNs = 0
            // The group's photos go from the cache (saved ones are in the Gallery).
            val old = dir
            dir = null
            if (old != null) io.launch { old.deleteRecursively() }
        }

        override fun statusText(): String {
            val s = show
            val n = pool.size
            return when {
                s != null -> "Photo Show · ${showIndex + 1} of ${s.count}"
                n == 0 -> "Group photos"
                else -> "Group photos · $n photo${if (n == 1) "" else "s"}"
            }
        }
    }

    /** This group's folder in the cache. */
    private fun folder(): File? {
        dir?.let { return it }
        val id = GroupLink.id.ifBlank { return null }
        return File(File(app.cacheDir, FOLDER), id).apply { mkdirs() }.also { dir = it }
    }

    // ================================================================== host
    private fun onGuestMessage(endpointId: String, o: JSONObject) {
        val peer = peers[endpointId]
        when (o.optString("t")) {
            "add" -> {
                val m = o.optJSONObject("p") ?: return
                if (byId.containsKey(m.optLong("id"))) return
                val p = upsert(m, nextSeq++) ?: return
                // Everyone hears of it (its owner gets the pool's number back).
                sendGuests(msg("photo").put("p", p.meta()))
                addToShow(p.id)
            }
            // What a guest already has (back after a drop / a switch): not sent again.
            "have" -> peer?.let { p ->
                o.optJSONArray("th")?.let { a -> for (i in 0 until a.length()) p.has.add(keyOf(a.optLong(i), THUMB)) }
                o.optJSONArray("fu")?.let { a -> for (i in 0 until a.length()) p.has.add(keyOf(a.optLong(i), FULL)) }
            }
            "hello" -> peer?.let { startSending(it) }
            "cmd" -> {
                val name = GroupLink.nameOf(endpointId) ?: return
                applyCmd(peer?.key ?: return, name, o)
            }
        }
    }

    private fun addPeer(endpointId: String, token: String, name: String) {
        peers.remove(endpointId)?.stop()
        peers[endpointId] = Peer(endpointId, token, name)
    }

    /** Host → one guest: the whole pool (in parts), then the show if one is on. */
    private fun sendPool(endpointId: String) {
        val list = pool.sortedBy { it.seq }
        val parts = list.chunked(POOL_PART).ifEmpty { listOf(emptyList()) }
        parts.forEachIndexed { i, part ->
            val a = JSONArray()
            part.forEach { a.put(it.meta().put("th", it.hasThumb).put("fu", it.hasFull)) }
            sendTo(endpointId, msg("pool").put("photos", a).put("last", i == parts.lastIndex))
        }
        show?.let { sendTo(endpointId, showMessage(it)) }
    }

    /** Host: a guest that left (and didn't come back) — drop their photos that never arrived, end their show. */
    private fun forgetIfGone(key: String) {
        if (!isHost || !active || GroupLink.guestList().any { it.second == key }) return
        val drop = byId.values.filter { it.ownerKey == key && !it.hasFull }.map { it.id }
        if (drop.isNotEmpty()) {
            drop.forEach { removePhoto(it) }
            sendGuests(msg("gone").put("ids", JSONArray(drop)))
            show?.let { s -> changeShow(s, s.ids.filter { it !in drop }) }
        }
        if (show?.byKey == key) endShow(broadcast = true)
        GroupService.refresh(app)
    }

    // ================================================================== guest
    private fun onHostMessage(o: JSONObject, arrivedNs: Long) {
        when (o.optString("t")) {
            "photo" -> o.optJSONObject("p")?.let { upsert(it, it.optLong("q")) }
            "pool" -> {
                val hostHas = HashMap<Long, Pair<Boolean, Boolean>>()
                o.optJSONArray("photos")?.let { a ->
                    for (i in 0 until a.length()) {
                        val m = a.optJSONObject(i) ?: continue
                        val p = upsert(m, m.optLong("q")) ?: continue
                        hostHas[p.id] = m.optBoolean("th") to m.optBoolean("fu")
                    }
                }
                poolSeen.putAll(hostHas)
                if (o.optBoolean("last")) afterPool()
            }
            "gone" -> o.optJSONArray("ids")?.let { a -> for (i in 0 until a.length()) removePhoto(a.optLong(i)) }
            "show" -> {
                if (!o.optBoolean("on")) {
                    endShow(broadcast = false)
                    return
                }
                val s = PhotoShow.fromJson(o) ?: return
                // A first guess at the host's clock (used until it's measured).
                roughOffsetNs = o.optLong("now") - arrivedNs
                val isNew = show?.sid != s.sid
                show = s
                if (isNew) {
                    // A new show: every phone goes to it, full screen.
                    showOpen = true
                    viewing = null
                    GroupLink.requestOpen()
                }
                tick()
                GroupService.refresh(app)
            }
        }
    }

    /** Guest: what the host has of each photo (from its pool), until the last part comes. */
    private val poolSeen = HashMap<Long, Pair<Boolean, Boolean>>()

    /**
     * Guest: the host's pool is all here. Say what's on this phone already (it isn't sent again), then
     * send up this phone's own photos the host is missing — and the details of any it never heard of.
     */
    private fun afterPool() {
        val hostHas = HashMap(poolSeen)
        poolSeen.clear()
        val ep = GroupLink.hostEndpoint ?: return
        hostPeer?.stop()
        val peer = Peer(ep, "host", GroupLink.hostName)
        hostPeer = peer
        val thumbs = JSONArray()
        val fulls = JSONArray()
        byId.values.sortedBy { it.seq }.forEach { p ->
            if (p.mine) {
                val has = hostHas[p.id]
                if (has == null) sendHost(msg("add").put("p", p.meta())) // never got there (a drop): again
                if (has?.first == true) peer.has.add(keyOf(p.id, THUMB))
                if (has?.second == true) peer.has.add(keyOf(p.id, FULL))
            } else {
                if (p.hasThumb) thumbs.put(p.id)
                if (p.hasFull) fulls.put(p.id)
            }
        }
        // In parts, well under the message limit (an id is up to 17 characters).
        val th = (0 until thumbs.length()).map { thumbs.getLong(it) }.chunked(600)
        val fu = (0 until fulls.length()).map { fulls.getLong(it) }.chunked(600)
        for (i in 0 until max(th.size, fu.size)) {
            sendHost(msg("have").put("th", JSONArray(th.getOrElse(i) { emptyList() })).put("fu", JSONArray(fu.getOrElse(i) { emptyList() })))
        }
        sendHost(msg("hello"))
        startSending(peer)
    }

    // ================================================================== arriving pieces
    private fun onPiece(bytes: ByteArray) {
        if (bytes.size < 14) return
        val b = ByteBuffer.wrap(bytes)
        b.get()
        val kind = b.get().toInt()
        val id = b.long
        val offset = b.int
        val key = keyOf(id, kind)
        val inc = incoming[key] ?: return
        val complete = runCatching { inc.write(offset, bytes, 14, bytes.size - 14) }
            .onFailure { Log.w(TAG, "couldn't write a piece", it) }.getOrDefault(false)
        if (!complete) return
        incoming.remove(key)
        val p = byId[id] ?: run {
            inc.dest.delete()
            return
        }
        if (kind == THUMB) {
            p.hasThumb = true
            p.thumb = inc.dest
        } else {
            p.hasFull = true
            p.full = inc.dest
            if (p.id in toSave) kickSaver()
            GroupService.refresh(app)
        }
        // Host: now it can go on to the others.
        if (isHost) wakeAll()
    }

    /** A preview or photo arriving: a part file that fills up piece by piece, renamed when complete. */
    private class Incoming(val dest: File, val size: Long) {
        private val part = File(dest.path + ".part")
        private val count = ((size + PIECE - 1) / PIECE).toInt()
        private val have = BitSet()
        private var raf: RandomAccessFile? = null

        /** True when this piece made it complete. */
        fun write(offset: Int, data: ByteArray, start: Int, len: Int): Boolean {
            if (offset < 0 || offset % PIECE != 0 || offset >= size || len <= 0) return false
            val i = offset / PIECE
            if (have.get(i)) return false
            val f = raf ?: RandomAccessFile(part, "rw").also { it.setLength(size); raf = it }
            f.seek(offset.toLong())
            f.write(data, start, minOf(len.toLong(), size - offset).toInt())
            have.set(i)
            if (have.cardinality() < count) return false
            f.close()
            raf = null
            dest.delete()
            return part.renameTo(dest)
        }

        fun discard() {
            runCatching { raf?.close() }
            raf = null
            part.delete()
        }
    }

    // ================================================================== sending (host → guests, guest → host)
    /** Another phone, and what this phone sends it: the previews first (newest first), then the photos. */
    private class Peer(val endpointId: String, val key: String, val name: String) {
        /** Previews / photos (blob keys) that phone has: nothing more to send of them. */
        val has: MutableSet<Long> = ConcurrentHashMap.newKeySet()
        /** Ones on their way: pieces sent, pieces that arrived. */
        val going = ConcurrentHashMap<Long, Going>()
        /** Payload id → (blob, piece) of the pieces on their way. */
        val inFlight = ConcurrentHashMap<Long, Pair<Long, Int>>()
        /** Payload id → when it last moved. */
        val movedAt = ConcurrentHashMap<Long, Long>()
        val wake = Channel<Unit>(Channel.CONFLATED)
        @Volatile var pump: Job? = null
        @Volatile var alive = true
        /** Something new to send: look again for what goes first. */
        @Volatile var dirty = true
        @Volatile var current: Long? = null

        fun stop() {
            alive = false
            pump?.cancel()
            inFlight.keys.forEach { GroupLink.cancel(it) }
            inFlight.clear()
            movedAt.clear()
        }
    }

    private class Going(val count: Int) {
        val sent = BitSet()
        val arrived = BitSet()
    }

    private class Next(val photo: GroupPhoto, val kind: Int, val piece: Int, val going: Going)

    private fun startSending(p: Peer) {
        if (!p.alive || p.pump != null) return
        p.pump = io.launch { pump(p) }
    }

    private fun wake(p: Peer) {
        p.dirty = true
        p.wake.trySend(Unit)
    }

    private fun wakeAll() = peers.values.forEach { wake(it) }

    private fun stopSenders() {
        peers.values.forEach { it.stop() }
        peers.clear()
        hostPeer?.stop()
        hostPeer = null
        poolSeen.clear()
    }

    /** Host: everything but the guest's own photos. Guest: only its own photos (the host has the rest). */
    private fun canSend(p: Peer, photo: GroupPhoto): Boolean = if (p === hostPeer) photo.mine else photo.ownerKey != p.key

    /** The next piece for [p]: carry on with the one going, unless something new came (a preview goes first). */
    private fun nextFor(p: Peer): Next? {
        if (!p.dirty) p.current?.let { key -> byId[key shr 1]?.let { ph -> pieceOf(p, ph, (key and 1L).toInt())?.let { return it } } }
        p.dirty = false
        val photos = byId.values.sortedByDescending { it.seq }
        for (kind in listOf(THUMB, FULL)) {
            for (ph in photos) {
                if (!ph.has(kind) || keyOf(ph.id, kind) in p.has || !canSend(p, ph)) continue
                pieceOf(p, ph, kind)?.let {
                    p.current = keyOf(ph.id, kind)
                    return it
                }
            }
        }
        p.current = null
        return null
    }

    private fun pieceOf(p: Peer, ph: GroupPhoto, kind: Int): Next? {
        val key = keyOf(ph.id, kind)
        if (key in p.has) return null
        val g = p.going.getOrPut(key) { Going(pieceCount(if (kind == THUMB) ph.thumbSize else ph.size)) }
        synchronized(g) {
            val i = g.sent.nextClearBit(0)
            if (i >= g.count) return null
            g.sent.set(i)
            return Next(ph, kind, i, g)
        }
    }

    /** Sends to one phone, piece by piece, at most [WINDOW] on the way at a time. */
    private suspend fun pump(p: Peer) {
        val buf = ByteArray(PIECE)
        var openKey = -1L
        var raf: RandomAccessFile? = null
        try {
            while (p.alive) {
                if (p.inFlight.size >= WINDOW) {
                    withTimeoutOrNull(2_000) { p.wake.receive() }
                    resendStuck(p)
                    continue
                }
                val next = nextFor(p)
                if (next == null) {
                    withTimeoutOrNull(5_000) { p.wake.receive() }
                    resendStuck(p)
                    continue
                }
                val key = keyOf(next.photo.id, next.kind)
                val n = runCatching {
                    if (key != openKey) {
                        raf?.close()
                        raf = null
                        val f = dir?.let { File(it, "${next.photo.id}.${if (next.kind == THUMB) "t" else "f"}") } ?: error("no folder")
                        raf = RandomAccessFile(f, "r")
                        openKey = key
                    }
                    val r = raf!!
                    val offset = next.piece.toLong() * PIECE
                    r.seek(offset)
                    val want = min(PIECE.toLong(), r.length() - offset).toInt()
                    r.readFully(buf, 0, want)
                    want
                }.getOrElse {
                    Log.w(TAG, "couldn't read a photo to send", it)
                    -1
                }
                if (n <= 0) {
                    // The file is gone (the photo was dropped): leave it.
                    p.has.add(key)
                    p.dirty = true
                    openKey = -1
                    continue
                }
                val payload = GroupLink.payload(GroupMode.PHOTOS, piece(next.kind, next.photo.id, next.piece * PIECE, buf, n))
                p.inFlight[payload.id] = key to next.piece
                p.movedAt[payload.id] = System.currentTimeMillis()
                if (!GroupLink.send(p.endpointId, payload)) {
                    p.inFlight.remove(payload.id)
                    p.movedAt.remove(payload.id)
                    synchronized(next.going) { next.going.sent.clear(next.piece) }
                    delay(500)
                }
            }
        } finally {
            runCatching { raf?.close() }
        }
    }

    /** Pieces that stopped moving (their report never came): free their places and send them again. */
    private fun resendStuck(p: Peer) {
        val now = System.currentTimeMillis()
        p.inFlight.keys.filter { now - (p.movedAt[it] ?: 0L) > STUCK_MS }.forEach { id ->
            val (key, piece) = p.inFlight.remove(id) ?: return@forEach
            p.movedAt.remove(id)
            GroupLink.cancel(id)
            p.going[key]?.let { g -> synchronized(g) { g.sent.clear(piece) } }
            p.dirty = true
            if (BuildConfig.DEBUG) Log.d(TAG, "piece $piece of ${key shr 1} stuck on the way to ${p.name}: sending again")
        }
    }

    // ================================================================== saving
    /** Save every photo in the pool to the Gallery (photos still arriving are saved when they come). */
    fun saveAll() {
        saveJob?.let { if (!it.finished) return }
        val list = pool.toList()
        if (list.isEmpty()) return
        val want = list.filter { !it.mine && !it.saved }
        val job = PhotoSaveJob(want.map { it.id }.toSet(), list.size)
        job.done = list.size - want.size
        saveJob = job
        want.forEach { if (it.id !in toSave) toSave.add(it.id) }
        kickSaver()
    }

    /** Save one photo (the viewer, the show). */
    fun save(p: GroupPhoto) {
        if (p.mine || p.saved || p.id in toSave) return
        if (p.full == null) {
            notice = "That photo is still arriving — it's saved as soon as it's here."
        }
        toSave.add(p.id)
        kickSaver()
    }

    /** Photos of the running "Save all" still on their way here. Reads the photos' states (screens follow). */
    fun saveWaiting(): Int {
        val job = saveJob ?: return 0
        return job.ids.count { id -> byId[id]?.let { it.full == null && !it.saved } ?: false }
    }

    fun isSaving(p: GroupPhoto): Boolean = p.id in toSave

    /** One at a time, off the main thread, while there are photos here to save. */
    private fun kickSaver() {
        if (saver?.isActive == true) return
        saver = io.launch {
            while (true) {
                val p = withContext(Dispatchers.Main) { toSave.firstNotNullOfOrNull { id -> byId[id]?.takeIf { it.full != null } } } ?: break
                val ok = runCatching { toGallery(p) }.onFailure { Log.w(TAG, "couldn't save a photo", it) }.isSuccess
                withContext(Dispatchers.Main) { savedOne(p, ok) }
            }
        }
    }

    private fun savedOne(p: GroupPhoto, ok: Boolean) {
        if (!toSave.remove(p.id)) return
        if (ok) p.saved = true
        val job = saveJob
        if (job != null && p.id in job.ids) {
            if (ok) job.done++ else job.failed++
            if (job.finished && job.failed > 0) notice = "Couldn't save ${job.failed} photo${if (job.failed == 1) "" else "s"}."
        } else {
            notice = if (ok) "Saved to Pictures / $GALLERY_FOLDER" else "Couldn't save the photo."
        }
    }

    /** Copy the full photo, as it was taken, into Pictures / SyncUp Group. Background thread. */
    private fun toGallery(p: GroupPhoto) {
        val src = p.full ?: error("not here")
        val mime = p.mime.takeIf { it.startsWith("image/") } ?: Files.mimeFor(p.name, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = app.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, p.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$GALLERY_FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val dest = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("can't create the file")
            try {
                resolver.openOutputStream(dest)!!.use { out -> src.inputStream().use { it.copyTo(out, 256 * 1024) } }
                resolver.update(dest, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(dest, null, null)
                throw e
            }
        } else {
            // Android 9 and older: the shared folder itself (the screen asks for storage first).
            @Suppress("DEPRECATION")
            val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), GALLERY_FOLDER).apply { mkdirs() }
            val dest = unusedFile(folder, p.name)
            src.copyTo(dest)
            MediaScannerConnection.scanFile(app, arrayOf(dest.absolutePath), arrayOf(mime), null)
        }
    }

    private fun unusedFile(folder: File, name: String): File {
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var f = File(folder, name)
        var n = 1
        while (f.exists()) f = File(folder, "$base (${n++})$ext")
        return f
    }

    // ================================================================== Photo Show
    /** Start a show of the pool for the whole group ([everyMs] a photo, in order or shuffled). */
    fun startShow(everyMs: Long, shuffled: Boolean) = command(msg("cmd").put("c", "start").put("every", everyMs).put("shuf", shuffled))

    fun showPause() = command(msg("cmd").put("c", if (show?.paused == true) "play" else "pause"))
    fun showNext() = command(msg("cmd").put("c", "next"))
    fun showPrev() = command(msg("cmd").put("c", "prev"))
    fun showJump(i: Int) = command(msg("cmd").put("c", "jump").put("i", i))
    fun showEvery(ms: Long) = command(msg("cmd").put("c", "every").put("every", ms))
    fun showShuffle(on: Boolean) = command(msg("cmd").put("c", "shuffle").put("shuf", on))
    fun stopShow() = command(msg("cmd").put("c", "stop"))

    /** The host applies it; a guest sends it up (the host's answer — the new show — comes to every phone). */
    private fun command(o: JSONObject) {
        if (!GroupLink.inGroup || !active) return
        if (o.optString("c") == "start") {
            showOpen = true
            viewing = null
        }
        if (isHost) applyCmd(GroupLink.myToken, GroupLink.myName, o) else sendHost(o)
    }

    /** Host: a show command from [key] (only whoever started the show controls it). */
    private fun applyCmd(key: String, name: String, o: JSONObject) {
        val now = System.nanoTime()
        val s = show
        val c = o.optString("c")
        if (c == "start") {
            if (s != null && s.byKey != key) return // someone else's show is on
            val ids = pool.sortedBy { it.seq }.map { it.id }.takeLast(SHOW_MAX)
            if (ids.isEmpty()) return
            show = PhotoShow(
                ++showSeq, name, key, ids, ids.size, Random.nextLong(), o.optBoolean("shuf"), every(o),
                t0 = now, paused = false, at = 0,
            )
            // Every phone goes to the show, full screen — this one too.
            showOpen = true
            viewing = null
            if (key != GroupLink.myToken) GroupLink.requestOpen()
            broadcastShow()
            return
        }
        if (s == null || s.byKey != key || s.count == 0) return
        val i = s.indexAt(now)
        show = when (c) {
            "pause" -> s.copy(paused = true, at = i)
            "play" -> s.copy(paused = false).showing(i, now)
            "next" -> s.showing((i + 1) % s.count, now)
            "prev" -> s.showing((i - 1 + s.count) % s.count, now)
            "jump" -> s.showing(o.optInt("i").coerceIn(0, s.count - 1), now)
            "every" -> s.copy(everyMs = every(o)).showing(i, now)
            "shuffle" -> {
                val on = o.optBoolean("shuf")
                if (on == s.shuffled) return
                // The photo on screen stays; the ones after it change.
                val turned = s.copy(shuffled = on, seed = if (on) Random.nextLong() else s.seed)
                turned.showing(turned.order.indexOf(s.order[i]).coerceAtLeast(0), now)
            }
            "stop" -> return endShow(broadcast = true)
            else -> return
        }
        broadcastShow()
    }

    private fun every(o: JSONObject): Long = o.optLong("every", 5_000).coerceIn(1_000, 60_000)

    /** Host: a photo came into the pool during a show — it joins at the end (what's on screen stays). */
    private fun addToShow(id: Long) {
        val s = show ?: return
        if (id in s.ids || s.ids.size >= SHOW_MAX) return
        changeShow(s, s.ids + id)
    }

    /** Host: the show's photos changed — keep the one on screen (and how long it's been up). */
    private fun changeShow(s: PhotoShow, ids: List<Long>) {
        if (ids.isEmpty()) return endShow(broadcast = true)
        val now = System.nanoTime()
        val onScreen = s.order.getOrNull(s.indexAt(now))
        val base = s.ids.take(s.base).count { it in ids }
        val changed = s.copy(ids = ids, base = base)
        val i = changed.order.indexOf(onScreen).coerceAtLeast(0)
        show = changed.showing(i, now, s.shownFor(now))
        broadcastShow()
    }

    private fun showMessage(s: PhotoShow): JSONObject = s.toJson().put("t", "show").put("on", true).put("now", System.nanoTime())

    /** Host: the show changed — every guest gets it; this phone follows it. */
    private fun broadcastShow() {
        val s = show ?: return endShow(broadcast = true)
        sendGuests(showMessage(s))
        tick()
        GroupService.refresh(app)
    }

    /** The show is over (stopped, its starter gone, the group switched): [broadcast] tells the guests (host). */
    private fun endShow(broadcast: Boolean) {
        main.removeCallbacks(ticker)
        val had = show != null
        show = null
        showOpen = false
        showIndex = 0
        if (broadcast && isHost) sendGuests(msg("show").put("on", false))
        if (had) GroupService.refresh(app)
    }

    /** The host's clock now: this phone's own (host), or as measured (guests). */
    private fun hostNow(): Long =
        System.nanoTime() + if (isHost) 0 else if (GroupLink.clock.ready) GroupLink.clock.offsetNs else roughOffsetNs

    /** Put the show's photo for now on screen, and come back when the next one is due. */
    private fun tick() {
        main.removeCallbacks(ticker)
        val s = show ?: return
        val now = hostNow()
        val i = s.indexAt(now)
        if (i != showIndex) {
            showIndex = i
            GroupService.refresh(app)
        }
        if (s.paused || s.count == 0) return
        val waitMs = ((s.nextChange(now) - now) / 1_000_000 + 1).coerceIn(5, s.everyMs)
        main.postDelayed(ticker, waitMs)
    }

    // ================================================================== previews
    /**
     * The small preview: the shorter side [THUMB_PX] (the longer at most [THUMB_MAX_PX]), turned the
     * way the camera's EXIF says, as a JPEG. Background thread.
     */
    private fun makeThumb(src: File, dest: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return false
        var sample = 1
        while (min(w, h) / (sample * 2) >= THUMB_PX && max(w, h) / (sample * 2) >= THUMB_PX) sample *= 2
        val bmp = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
        val scale = min(THUMB_PX.toFloat() / min(bmp.width, bmp.height), THUMB_MAX_PX.toFloat() / max(bmp.width, bmp.height)).coerceAtMost(1f)
        val m = Matrix().apply { postScale(scale, scale) }
        val orientation = runCatching { ExifInterface(src.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.postRotate(90f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.postRotate(-90f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(-90f)
        }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        try {
            FileOutputStream(dest).use { out.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        } finally {
            if (out !== bmp) out.recycle()
            bmp.recycle()
        }
        return dest.length() > 0
    }

    /** A photo's id: random, under 2^52 (exact in JSON everywhere). */
    private fun newId(): Long = (Random.nextLong() ushr 12).coerceAtLeast(1)
}
