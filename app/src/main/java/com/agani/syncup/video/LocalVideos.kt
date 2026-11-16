package com.agani.syncup.video

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.agani.syncup.music.SortButton
import com.agani.syncup.music.SortOrder
import com.agani.syncup.music.folderOrder
import org.json.JSONArray
import org.json.JSONObject

/** One video the Video player lists. Nothing about it leaves the device. */
data class LocalVideo(
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val uri: Uri,
    /** Its folder from the folder the person added ("Camera", "Movies/Trip"), or [LocalVideos.PICKED] / [LocalVideos.DOWNLOADS]. */
    val folder: String,
    /** When it last changed, seconds since 1970 (newest first in the list). */
    val addedSec: Long,
) {
    val key: String get() = uri.toString()
    /** Picked on its own, so it can be taken off the list on its own. */
    val picked: Boolean get() = folder == LocalVideos.PICKED
}

/** A folder the person added with Android's folder picker. */
data class VideoFolder(val uri: Uri, val name: String)

/**
 * The Video player's videos, with NO storage permission (Google Play asked SyncUp not to use READ_MEDIA_VIDEO,
 * 16 Nov 2026): the folders the person adds once with Android's folder picker (Camera, Movies, WhatsApp
 * Video… — new videos in them show up by themselves), videos picked with Android's photo picker, and SyncUp's
 * own downloads. Access to each is kept across restarts (persisted URI grants) and can be removed again.
 */
object LocalVideos {
    /** The [LocalVideo.folder] of videos picked one by one. */
    const val PICKED = ":picked"
    /** The [LocalVideo.folder] of SyncUp's own downloads. */
    const val DOWNLOADS = ":downloads"

    private const val PREFS = "video_sources"
    private const val MAX_DEPTH = 8
    private val EXTENSIONS = setOf("mp4", "m4v", "mkv", "webm", "3gp", "mov", "avi", "ts", "flv", "wmv", "mpg", "mpeg")

    // ------------------------------------------------------------------ what the person added
    fun folders(context: Context): List<VideoFolder> = runCatching {
        val a = JSONArray(prefs(context).getString("folders", "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { VideoFolder(Uri.parse(it.getString("u")), it.optString("n", "Folder")) } }
    }.getOrDefault(emptyList())

    fun picked(context: Context): List<Uri> = runCatching {
        val a = JSONArray(prefs(context).getString("picked", "[]"))
        (0 until a.length()).map { Uri.parse(a.getString(it)) }
    }.getOrDefault(emptyList())

    fun hasSources(context: Context) = folders(context).isNotEmpty() || picked(context).isNotEmpty()

    /** Keeps the folder (from the folder picker) for good; false when it was already there. */
    fun addFolder(context: Context, tree: Uri): Boolean {
        val now = folders(context)
        if (now.any { it.uri == tree }) return false
        runCatching { context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val root = runCatching { DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)) }.getOrNull()
        val name = root?.let { displayName(context, it) }
            ?: tree.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Folder"
        saveFolders(context, now + VideoFolder(tree, name))
        return true
    }

    fun removeFolder(context: Context, folder: VideoFolder) {
        runCatching { context.contentResolver.releasePersistableUriPermission(folder.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        saveFolders(context, folders(context).filter { it.uri != folder.uri })
    }

    /** Keeps the videos picked with the photo picker; returns how many were new. */
    fun addPicked(context: Context, uris: List<Uri>): Int {
        val now = picked(context)
        val fresh = uris.distinct().filter { it !in now }
        fresh.forEach { runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        savePicked(context, now + fresh)
        return fresh.size
    }

    fun removePicked(context: Context, uri: Uri) {
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        savePicked(context, picked(context).filter { it != uri })
    }

    // ------------------------------------------------------------------ reading them
    /**
     * Every video in the added folders (and their folders, hidden ones skipped), the picked videos, and
     * [downloads]. Blocking — call it off the main thread. A video whose access is gone is left out.
     */
    fun load(context: Context, downloads: List<LocalVideo> = emptyList()): List<LocalVideo> {
        Durations.init(context)
        val out = LinkedHashMap<String, LocalVideo>()
        val seenDocs = HashSet<String>() // the same file through two added folders (Movies and Movies/Trip)
        for (f in folders(context)) walk(context, f, out, seenDocs)
        for (u in picked(context)) pickedVideo(context, u)?.let { out.putIfAbsent(it.key, it) }
        for (d in downloads) out.putIfAbsent(d.key, d.copy(durationMs = Durations.get(d)))
        return out.values.toList()
    }

    private val CHILD_COLUMNS = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    private fun walk(context: Context, f: VideoFolder, out: MutableMap<String, LocalVideo>, seenDocs: MutableSet<String>) {
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(f.uri) }.getOrNull() ?: return
        val todo = ArrayDeque<Triple<String, String, Int>>() // document id, folder path, depth
        todo.add(Triple(rootId, f.name, 0))
        while (todo.isNotEmpty()) {
            val (dirId, path, depth) = todo.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(f.uri, dirId)
            val cursor = runCatching { context.contentResolver.query(children, CHILD_COLUMNS, null, null, null) }.getOrNull() ?: continue
            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (name.startsWith(".")) continue // .thumbnails, .trashed-…, .Statuses
                    val mime = c.getString(2).orEmpty()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < MAX_DEPTH) todo.add(Triple(id, "$path/$name", depth + 1))
                        continue
                    }
                    if (!isVideo(mime, name) || !seenDocs.add(id)) continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(f.uri, id)
                    val v = LocalVideo(
                        name = cleanName(name),
                        durationMs = 0L,
                        sizeBytes = if (c.isNull(3)) 0L else c.getLong(3),
                        uri = uri,
                        folder = path,
                        addedSec = if (c.isNull(4)) 0L else c.getLong(4) / 1000,
                    )
                    out.putIfAbsent(v.key, v.copy(durationMs = Durations.get(v)))
                }
            }
        }
    }

    /** A picked video: its name, size, date and (from the photo picker) length; null when it can't be read now. */
    private fun pickedVideo(context: Context, uri: Uri): LocalVideo? {
        // Name and size: the columns every content provider must answer.
        val (fileName, size) = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (!c.moveToFirst()) null
                else (if (c.isNull(0)) null else c.getString(0)) to (if (c.isNull(1)) 0L else c.getLong(1))
            }
        }.getOrNull() ?: return null // access gone
        // Date and length where the provider has them: the photo picker (datetaken, duration) or a document (last_modified).
        var atMs = 0L
        var given = 0L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    fun long(col: String): Long? = c.getColumnIndex(col).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }
                    atMs = long(MediaStore.MediaColumns.DATE_TAKEN) ?: long(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                        ?: long(MediaStore.MediaColumns.DATE_MODIFIED)?.times(1000) ?: 0L
                    given = long(MediaStore.MediaColumns.DURATION) ?: 0L
                }
            }
        }
        // Android's photo picker hides file names (it gives a number, "326.mp4"): name those by when they were taken.
        val fromPicker = uri.authority == MediaStore.AUTHORITY && uri.pathSegments.firstOrNull()?.startsWith("picker") == true
        val name = if (fromPicker && atMs > 0) {
            "Video from " + java.text.SimpleDateFormat("d MMM yyyy, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(atMs))
        } else {
            cleanName(fileName ?: uri.lastPathSegment ?: "Video").let { if (fromPicker) "Video $it" else it }
        }
        val v = LocalVideo(name, 0L, size, uri, PICKED, atMs / 1000)
        return if (given > 0) {
            Durations.put(v, given)
            v.copy(durationMs = given)
        } else {
            v.copy(durationMs = Durations.get(v))
        }
    }

    // ------------------------------------------------------------------ lengths
    /** Has its length been looked at yet (found or not)? */
    fun measured(v: LocalVideo) = Durations.known(v)

    /** Reads the video's length (once; it's remembered). Blocking — call it off the main thread. */
    fun measure(context: Context, v: LocalVideo): Long {
        val mmr = MediaMetadataRetriever()
        val ms = try {
            mmr.setDataSource(context, v.uri)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { mmr.release() }
        }
        Durations.put(v, ms)
        return ms
    }

    /** The lengths already found, kept on the phone; a changed file (size or date) is measured again. */
    private object Durations {
        private const val FILE = "video_durations"
        private const val MAX = 5000
        private var map: MutableMap<String, Long>? = null
        private var prefs: android.content.SharedPreferences? = null

        @Synchronized
        fun init(context: Context) {
            if (map != null) return
            val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            prefs = p
            map = HashMap(p.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap())
        }

        private fun key(v: LocalVideo) = "${v.uri}|${v.sizeBytes}|${v.addedSec}"

        @Synchronized
        fun known(v: LocalVideo) = map?.containsKey(key(v)) == true

        @Synchronized
        fun get(v: LocalVideo): Long = (map?.get(key(v)) ?: 0L).coerceAtLeast(0L)

        @Synchronized
        fun put(v: LocalVideo, ms: Long) {
            val m = map ?: return
            val k = key(v)
            val value = if (ms > 0) ms else -1L // -1: looked, no length found — don't try again
            if (m[k] == value) return
            val edit = prefs?.edit() ?: return
            if (m.size >= MAX) {
                m.clear()
                edit.clear()
            }
            m[k] = value
            edit.putLong(k, value).apply()
        }
    }

    // ------------------------------------------------------------------ small helpers
    /** A folder's name from its path ("Movies/Trip" → "Trip"). */
    fun folderName(path: String) = when (path) {
        PICKED -> "Videos you picked"
        DOWNLOADS -> "SyncUp downloads"
        else -> path.substringAfterLast('/').ifBlank { "Other videos" }
    }

    /** The path worth showing under a folder's name ("Movies/Trip"), or "" when it adds nothing. */
    fun folderPath(path: String) = if (path == PICKED || path == DOWNLOADS || path == folderName(path)) "" else path

    /** The file's own name reads best ("Ooty trip.mp4" → "Ooty trip"). */
    fun cleanName(fileName: String) = fileName.substringBeforeLast('.').trim().ifEmpty { fileName.ifBlank { "Video" } }

    private fun isVideo(mime: String, name: String) =
        mime.startsWith("video/") || name.substringAfterLast('.', "").lowercase() in EXTENSIONS

    private fun displayName(context: Context, doc: Uri): String? = runCatching {
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun saveFolders(context: Context, list: List<VideoFolder>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("u", it.uri.toString()).put("n", it.name)) }
        prefs(context).edit().putString("folders", a.toString()).apply()
    }

    private fun savePicked(context: Context, list: List<Uri>) {
        val a = JSONArray()
        list.forEach { a.put(it.toString()) }
        prefs(context).edit().putString("picked", a.toString()).apply()
    }
}

/** The Video player's orders (see [SortButton]). Newest first is how the list always came. */
enum class VideoSort(override val label: String) : SortOrder {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    NAME_AZ("Name A–Z"),
    NAME_ZA("Name Z–A"),
    LONGEST("Longest first"),
    SHORTEST("Shortest first"),
    BIGGEST("Biggest first"),
    SMALLEST("Smallest first"),
    ;

    fun sort(videos: List<LocalVideo>): List<LocalVideo> {
        val byName = compareBy<LocalVideo, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        val c: Comparator<LocalVideo> = when (this) {
            NEWEST -> compareByDescending<LocalVideo> { it.addedSec }.then(byName)
            OLDEST -> compareBy<LocalVideo> { it.addedSec }.then(byName)
            NAME_AZ -> byName
            NAME_ZA -> byName.reversed()
            LONGEST -> compareByDescending<LocalVideo> { it.durationMs }.then(byName)
            SHORTEST -> compareBy<LocalVideo> { it.durationMs }.then(byName)
            BIGGEST -> compareByDescending<LocalVideo> { it.sizeBytes }.then(byName)
            SMALLEST -> compareBy<LocalVideo> { it.sizeBytes }.then(byName)
        }
        return videos.sortedWith(c)
    }

    /** Folders follow the same order: the newest folder, the longest or biggest in total, or by name. */
    fun folders(groups: List<Pair<String, List<LocalVideo>>>): List<Pair<String, List<LocalVideo>>> {
        val name = LocalVideos::folderName
        return groups.sortedWith(
            when (this) {
                NEWEST -> folderOrder(name, { v -> v.maxOf { it.addedSec } }, descending = true)
                OLDEST -> folderOrder(name, { v -> v.minOf { it.addedSec } })
                NAME_AZ -> folderOrder(name)
                NAME_ZA -> folderOrder(name, descending = true)
                LONGEST -> folderOrder(name, { v -> v.sumOf { it.durationMs } }, descending = true)
                SHORTEST -> folderOrder(name, { v -> v.sumOf { it.durationMs } })
                BIGGEST -> folderOrder(name, { v -> v.sumOf { it.sizeBytes } }, descending = true)
                SMALLEST -> folderOrder(name, { v -> v.sumOf { it.sizeBytes } })
            },
        )
    }
}
