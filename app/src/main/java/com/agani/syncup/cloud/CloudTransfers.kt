package com.agani.syncup.cloud

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.agani.syncup.downloads.Files
import com.agani.syncup.stationfiles.StationFile
import com.agani.syncup.stationfiles.StationUpload
import com.agani.syncup.stationfiles.StationUploads
import com.agani.syncup.stationfiles.Stations
import com.agani.syncup.stationfiles.USE_HEADER
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/**
 * A station folder path as cloud folders: letters (with vowel signs, as in Tamil), digits, spaces and _ . ( ) ' -
 * only (what the server takes), others become "_"; at most 10 deep.
 */
internal fun cloudFolderPath(path: String): String =
    path.split('/').map { it.replace(Regex("""[^\p{L}\p{M}\p{N}_ .()'-]"""), "_").trim().take(80) }
        .filter { it.isNotEmpty() && it != "." && it != ".." }.take(10).joinToString("/")

/**
 * Every file in the cloud folder [path] and the folders inside it, each with its folder path from [name] down
 * ("Trips/2026"), and the folders themselves, deepest first (what a move empties). At most 2,000 files.
 */
internal suspend fun walkCloud(source: CloudSource, path: String, name: String): Pair<List<Pair<String, CloudItem>>, List<String>> {
    val files = ArrayList<Pair<String, CloudItem>>()
    val folders = ArrayList<String>()
    val todo = ArrayDeque(listOf(path to name))
    while (todo.isNotEmpty() && files.size < 2000) {
        val (p, rel) = todo.removeFirst()
        folders += p
        val page = source.list(p)
        page.files.forEach { files += rel to it }
        page.folders.forEach { todo.addLast(it.path to "$rel/${it.name}") }
    }
    return files to folders.reversed()
}

/** One copy or move between Cloud and a station's shared folder (AudioSync), as the Transfers card shows it. */
class Transfer(
    val id: Long,
    val name: String,
    val size: Long,
    /** Shared → Cloud (true) or Cloud → Shared (false). */
    val toCloud: Boolean,
    /** Cloud → Shared only: the cloud file is deleted once the station has it. */
    val move: Boolean,
    /** Where it's going, for people: "Studio › Music" or "Cloud › Trips". */
    val where: String,
) {
    var state by mutableStateOf(WAITING)
    /** Bytes so far in this step (getting it, or sending it). */
    var done by mutableLongStateOf(0L)
    var error by mutableStateOf<String?>(null)
    /** Cloud → Shared: the station upload carrying it, once it's on this phone. */
    var uploadId by mutableLongStateOf(0L)

    internal var job: Job? = null
    internal var stop: (() -> Unit)? = null
    internal var again: (() -> Unit)? = null

    val active: Boolean get() = state == WAITING || state == GETTING || state == SENDING || state == REMOVING

    companion object {
        const val WAITING = "waiting"
        /** Cloud → Shared: coming down from the cloud. Shared → Cloud: a small file read from the station first. */
        const val GETTING = "getting"
        const val SENDING = "sending"
        /** Move: the station has it; now it's being deleted from the cloud. */
        const val REMOVING = "removing"
        const val DONE = "done"
        const val FAILED = "failed"
    }
}

/**
 * Copy and move between Cloud (Cloudinary) and stations' shared folders (user, 26 Nov 2026: "copy or move between
 * the storages they're connected to — simple and seamless").
 *
 * - Cloud → Shared: the file comes down to this phone, then goes up through [StationUploads] (in pieces, carrying
 *   on after a break or an app restart). A move would delete the cloud file only once the station says it has it;
 *   the screens offer copies only for now (user, 26 Nov 2026), so [toShared]'s move is always false today.
 * - Shared → Cloud: streamed from the station straight on to the cloud, no copy kept on the phone. Copy only:
 *   AudioSync can't delete or rename (its API: "files can only be added or read").
 *
 * One file at a time each way.
 */
object CloudTransfers {
    val list = mutableStateListOf<Transfer>()

    /** Goes up when something arrived in or left the cloud: an open Cloud folder reloads. */
    var finished by mutableIntStateOf(0)
        private set

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fromCloud = Mutex()
    private val toCloud = Mutex()
    private var nextId = 1L
    @Volatile private var lastSource: CloudSource? = null

    /** At app start, before [StationUploads.init]: moves still on their way finish after a restart too. */
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        StationUploads.ended += { u, done -> stationEnded(u, done) }
    }

    /** Signed out: forget the cloud access and the list. */
    fun forget() {
        lastSource = null
        list.filter { !it.active }.forEach { list.remove(it) }
    }

    val busy: Boolean get() = list.any { it.active }

    fun clearDone() {
        list.filter { !it.active }.forEach { batchOf.remove(it.id) }
        list.removeAll { !it.active }
    }

    fun cancel(t: Transfer) {
        t.job?.cancel()
        runCatching { t.stop?.invoke() }
        if (t.uploadId != 0L) StationUploads.cancel(t.uploadId) // removes it from the list through [stationEnded]
        list.remove(t)
        batchOf.remove(t.id)
    }

    fun retry(t: Transfer) {
        val station = StationUploads.items.firstOrNull { it.id == t.uploadId }
        if (station != null && station.state == StationUpload.FAILED) StationUploads.retry(station.id) else t.again?.invoke()
    }

    // ------------------------------------------------------------------ Cloud → Shared
    /**
     * One copy / move to a station: where it goes ([folder] is that folder's upload_folder) and the folders made
     * there so far. A moved cloud folder ([emptied], deepest first) is deleted once all its files arrived.
     */
    private class ShareBatch(
        val key: String,
        val station: String,
        val folder: String,
        val folderName: String,
        val emptied: List<String>,
    ) {
        val made = java.util.concurrent.ConcurrentHashMap<String, String>()
        val transfers = ArrayList<Transfer>()
    }

    private val batchOf = HashMap<Long, ShareBatch>()

    /**
     * Copy (or move) cloud files to [folder] (a listing's upload_folder) on [key]'s station. Each comes with a
     * folder path ("" = straight in; "Trips/2026" for a whole cloud folder), made on the station as needed.
     * [emptied]: cloud folders that a move leaves empty, deleted at the end (deepest first). Main thread.
     */
    fun toShared(
        source: CloudSource,
        entries: List<Pair<String, CloudItem>>,
        key: String,
        station: String,
        folder: String,
        folderName: String,
        move: Boolean,
        emptied: List<String> = emptyList(),
    ) {
        lastSource = source
        val batch = ShareBatch(key, station, folder, folderName, if (move) emptied else emptyList())
        for ((rel, item) in entries) {
            val sub = com.agani.syncup.stationfiles.stationFolderPath(rel)
            val where = listOf("$station › $folderName", sub.replace("/", " › ")).filter { it.isNotEmpty() }.joinToString(" › ")
            val t = Transfer(nextId++, item.name, item.bytes, toCloud = false, move = move, where = where)
            list += t
            batch.transfers += t
            batchOf[t.id] = batch
            startToShared(t, item, batch, sub)
        }
    }

    private fun startToShared(t: Transfer, item: CloudItem, batch: ShareBatch, sub: String) {
        t.state = Transfer.WAITING
        t.error = null
        t.done = 0
        t.uploadId = 0
        t.again = { startToShared(t, item, batch, sub) }
        t.job = scope.launch {
            fromCloud.withLock {
                t.state = Transfer.GETTING
                var dir: File? = null
                try {
                    // Its folder on the station first (made once per batch): no point getting the file otherwise.
                    val upload = Stations.folderFor(batch.key, batch.folder, sub, batch.made)
                    dir = File(StationUploads.transferDir(app), "c${System.currentTimeMillis()}_${t.id}").apply { mkdirs() }
                    if (item.bytes > 0 && dir.usableSpace < item.bytes + 50L * 1024 * 1024) {
                        throw CloudException("There isn't enough space on this phone to pass it on.")
                    }
                    val name = fileName(item)
                    val out = File(dir, name)
                    CloudHttp.download(item.url, out) { t.done = it }
                    val tag = if (t.move) {
                        JSONObject().put("kind", "cloud_move").put("public_id", item.publicId).put("resource_type", item.resourceType).toString()
                    } else null
                    val shownFolder = if (sub.isEmpty()) batch.folderName else sub.substringAfterLast('/')
                    withContext(Dispatchers.Main) {
                        t.uploadId = StationUploads.addFile(batch.key, batch.station, upload, shownFolder, out, name, tag)
                        t.done = 0
                        t.state = Transfer.SENDING
                    }
                } catch (e: CancellationException) {
                    dir?.deleteRecursively()
                    throw e
                } catch (e: Exception) {
                    dir?.deleteRecursively()
                    fail(t, e, batch.station)
                }
            }
        }
    }

    /** The name it gets on the station: the cloud name, with the file's extension. */
    private fun fileName(item: CloudItem): String {
        var n = Files.cleanName(item.name).ifBlank { "file" }
        if (!n.contains('.') && item.ext.isNotBlank()) n += ".${item.ext}"
        return n
    }

    /** A station upload left the list: done (a move now deletes the cloud file) or cancelled. Main thread. */
    private fun stationEnded(u: StationUpload, done: Boolean) {
        val t = list.firstOrNull { it.uploadId == u.id }
        if (!done) {
            t?.let { list.remove(it) }
            return
        }
        val move = u.tag?.let { runCatching { JSONObject(it) }.getOrNull() }?.takeIf { it.optString("kind") == "cloud_move" }
        if (move == null) {
            t?.state = Transfer.DONE
            return
        }
        // The station has it: now it leaves the cloud (also after an app restart, from the saved tag).
        val item = CloudItem(move.str("public_id"), move.str("resource_type"), u.name, "", 0, null, null, "", 0)
        removeFromCloud(t, item, u.station)
    }

    private fun removeFromCloud(t: Transfer?, item: CloudItem, station: String) {
        t?.state = Transfer.REMOVING
        t?.error = null
        t?.again = { removeFromCloud(t, item, station) }
        scope.launch {
            runCatching { source().delete(item) }
                .onSuccess {
                    t?.state = Transfer.DONE
                    finished++
                    t?.let { emptyMovedFolders(it) }
                }
                .onFailure { e ->
                    t?.state = Transfer.FAILED
                    t?.error = "It's on $station, but it's still in Cloud. ${e.message ?: "Please try again."}"
                }
        }
    }

    private suspend fun source(): CloudSource = lastSource ?: CloudSession.open(app).second.also { lastSource = it }

    /** A moved cloud folder: once every file in it reached the station, its (now empty) folders go too. */
    private suspend fun emptyMovedFolders(t: Transfer) {
        val batch = withContext(Dispatchers.Main) { batchOf[t.id] } ?: return
        if (batch.emptied.isEmpty()) return
        val allDone = withContext(Dispatchers.Main) { batch.transfers.all { it.state == Transfer.DONE } }
        if (!allDone) return
        val src = source()
        for (path in batch.emptied) runCatching { src.deleteFolder(path) }
        withContext(Dispatchers.Main) { batch.transfers.forEach { batchOf.remove(it.id) } }
        finished++
    }

    // ------------------------------------------------------------------ Shared → Cloud
    /**
     * Copy station [files] into [dest] in the cloud. Each comes with a folder path inside [dest] ("" = straight in),
     * so a whole station folder keeps its folders. Main thread.
     */
    fun toCloud(source: CloudSource, files: List<Pair<String, StationFile>>, key: String, station: String, dest: String, destLabel: String) {
        lastSource = source
        for ((folder, f) in files) {
            val rel = cloudFolderPath(folder)
            val path = listOf(dest.trim('/'), rel).filter { it.isNotEmpty() }.joinToString("/")
            val label = listOf(destLabel, rel.replace("/", " › ")).filter { it.isNotEmpty() }.joinToString(" › ")
            val t = Transfer(nextId++, f.name, f.size, toCloud = true, move = false, where = label)
            list += t
            startToCloud(t, source, f, key, station, path)
        }
    }

    private fun startToCloud(t: Transfer, source: CloudSource, f: StationFile, key: String, station: String, path: String) {
        t.state = Transfer.WAITING
        t.error = null
        t.done = 0
        t.again = { startToCloud(t, source, f, key, station, path) }
        t.job = scope.launch {
            toCloud.withLock {
                try {
                    sendToCloud(t, source, f, key, path)
                    t.state = Transfer.DONE
                    t.done = t.size
                    finished++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail(t, e, station)
                } finally {
                    t.stop = null
                }
            }
        }
    }

    private suspend fun sendToCloud(t: Transfer, source: CloudSource, f: StationFile, key: String, path: String) {
        t.state = Transfer.GETTING
        val resp = withContext(Dispatchers.IO) {
            Stations.open(key, f.downloadUrl.ifBlank { f.url + "?dl=1" }, { it.header(USE_HEADER, "download") })
        }
        resp.use { r ->
            t.stop = { r.close() } // Cancel: closing the station's reply stops the upload reading it
            if (!r.isSuccessful) throw Stations.refused(r)
            val body = r.body ?: throw IOException("Empty reply")
            val size = f.size.takeIf { it > 0 } ?: body.contentLength()
            val mime = Files.mimeFor(f.name, f.mime)
            val src = if (size !in 0..CloudHttp.CHUNKED_ABOVE) {
                // Big: straight through, piece by piece, as it arrives from the station.
                var opened = false
                UploadSource.stream(app, f.name, mime, size) {
                    if (opened) throw IOException("Can't read it twice")
                    opened = true
                    body.byteStream()
                }
            } else {
                // Small: read whole first (at most 20 MB), so the upload can read it again if it has to.
                val bytes = withContext(Dispatchers.IO) { body.bytes() }
                UploadSource.stream(app, f.name, mime, bytes.size.toLong()) { ByteArrayInputStream(bytes) }
            }
            t.state = Transfer.SENDING
            t.done = 0
            source.upload(path, src) { sent -> t.done = sent }
        }
    }

    // ------------------------------------------------------------------ errors, in plain words
    private fun fail(t: Transfer, e: Throwable, station: String) {
        t.state = Transfer.FAILED
        t.error = when {
            e is CloudException -> e.message
            e is Stations.PinNeeded -> "$station wants its PIN — open it in Shared to enter it, then try again."
            e is Stations.Gone -> "Couldn't reach $station. Try again when it's online."
            e is Stations.Refused && e.error == "no_folders" -> "$station's AudioSync can't take folders yet — update it on that PC (2.3.8 or newer)."
            e is Stations.Refused && e.error == "uploads_disabled" -> "$station doesn't allow uploads now."
            e is Stations.Refused && e.error == "destination_gone" -> "That folder isn't on $station any more."
            e is Stations.Refused && e.code == 404 -> "It isn't on $station any more."
            e is Stations.Refused && e.code == 503 -> "$station is busy. Please try again in a moment."
            e is IOException -> "The connection dropped. Please try again."
            else -> "Something went wrong. Please try again."
        }
        android.util.Log.w("SyncUpCloud", "Transfer failed", e)
    }
}
