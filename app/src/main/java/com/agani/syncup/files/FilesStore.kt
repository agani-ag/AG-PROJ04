package com.agani.syncup.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream

/** A folder the user opened once via Android's own folder picker (SAF); SyncUp keeps the right to it. */
data class OpenedFolder(val uri: Uri, val name: String, val addedAt: Long)

/** A file the user opened from Files (for the Recent list). */
data class RecentFile(val uri: Uri, val name: String, val size: Long, val openedAt: Long)

/**
 * "Your folders" (opened once, kept), a short Recent list, and how much room is on the phone.
 * No broad storage permission anywhere here — every folder was chosen by the user through Android's
 * own picker, which is what grants SyncUp access to it.
 */
object FilesStore {
    private const val PREFS = "files_store"
    private const val KEY_FOLDERS = "folders"
    private const val KEY_RECENT = "recent"
    private const val MAX_RECENT = 30

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun folders(context: Context): List<OpenedFolder> = runCatching {
        val a = JSONArray(prefs(context).getString(KEY_FOLDERS, "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            OpenedFolder(Uri.parse(o.getString("u")), o.optString("n"), o.optLong("a"))
        }
    }.getOrDefault(emptyList())

    /** Persists the grant and remembers the folder. Call after [Intent.FLAG_GRANT_*] is taken. */
    fun addFolder(context: Context, uri: Uri) {
        val name = DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment ?: "Folder"
        val list = listOf(OpenedFolder(uri, name, System.currentTimeMillis())) + folders(context).filter { it.uri != uri }
        saveFolders(context, list)
    }

    fun removeFolder(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        saveFolders(context, folders(context).filter { it.uri != uri })
    }

    private fun saveFolders(context: Context, list: List<OpenedFolder>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("u", it.uri.toString()).put("n", it.name).put("a", it.addedAt)) }
        prefs(context).edit().putString(KEY_FOLDERS, a.toString()).apply()
    }

    fun recent(context: Context): List<RecentFile> = runCatching {
        val a = JSONArray(prefs(context).getString(KEY_RECENT, "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            RecentFile(Uri.parse(o.getString("u")), o.optString("n"), o.optLong("s"), o.optLong("a"))
        }
    }.getOrDefault(emptyList())

    fun recordOpened(context: Context, uri: Uri, name: String, size: Long) {
        val list = listOf(RecentFile(uri, name, size, System.currentTimeMillis())) + recent(context).filter { it.uri != uri }
        val a = JSONArray()
        list.take(MAX_RECENT).forEach { a.put(JSONObject().put("u", it.uri.toString()).put("n", it.name).put("s", it.size).put("a", it.openedAt)) }
        prefs(context).edit().putString(KEY_RECENT, a.toString()).apply()
    }

    /** Total/used/free bytes on the phone's main storage. No permission needed. */
    fun storageStats(): Triple<Long, Long, Long> = runCatching {
        val stat = StatFs(android.os.Environment.getExternalStorageDirectory().path)
        val total = stat.blockCountLong * stat.blockSizeLong
        val free = stat.availableBlocksLong * stat.blockSizeLong
        Triple(total, total - free, free)
    }.getOrDefault(Triple(0L, 0L, 0L))
}

/** [ClipItem] for a file or folder already on this phone (a SAF document, a SyncUp download, a picked file). */
class LocalClipItem(val doc: DocumentFile) : ClipItem {
    override val name: String get() = doc.name ?: "file"
    override val isDirectory: Boolean get() = doc.isDirectory
    override val sizeHint: Long get() = doc.length()
    override fun openInput(context: Context): InputStream =
        context.contentResolver.openInputStream(doc.uri) ?: throw IOException("Can't open $name")
    override fun listChildren(context: Context): List<ClipItem> = doc.listFiles().map { LocalClipItem(it) }
    override fun delete(context: Context): Boolean = runCatching { doc.delete() }.getOrDefault(false)
}

/** [PasteTarget] for a local folder (an opened SAF tree, or a folder navigated into inside it). */
class LocalPasteTarget(private val doc: DocumentFile) : PasteTarget {
    override fun createFile(context: Context, name: String): java.io.OutputStream? {
        // "application/octet-stream" keeps the name exact — a real mime can make some providers
        // quietly append their own extension, giving files like "photo.jpg.jpg".
        val target = doc.findFile(name)?.takeIf { !it.isDirectory } ?: doc.createFile("application/octet-stream", name) ?: return null
        return context.contentResolver.openOutputStream(target.uri, "wt")
    }

    override fun createDirectory(context: Context, name: String): PasteTarget? {
        val dir = doc.findFile(name)?.takeIf { it.isDirectory } ?: doc.createDirectory(name) ?: return null
        return LocalPasteTarget(dir)
    }
}
