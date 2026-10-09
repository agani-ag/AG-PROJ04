package com.agani.syncup.files

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Something that can be pasted elsewhere: a file (or folder) on this phone, or on a network share.
 * [files] (local folders) and [smb] (network folders) each implement this for their own kind of
 * file, so Copy/Paste works between them without either package knowing the other's internals.
 */
interface ClipItem {
    val name: String
    val isDirectory: Boolean
    /** Bytes, or -1 when unknown. Meaningless for a directory. */
    val sizeHint: Long

    /** Opens the file for reading. Only valid when ![isDirectory]. Call off the main thread. */
    fun openInput(context: Context): InputStream

    /** This item's children, when [isDirectory]; empty otherwise. Call off the main thread. */
    fun listChildren(context: Context): List<ClipItem>

    /** Removes the original (for Move, after every child pasted without error). Call off the main thread. */
    fun delete(context: Context): Boolean
}

/** Where a paste lands: a local folder or a network-folder directory, created on demand. */
interface PasteTarget {
    /** Opens [name] for writing inside this folder (replacing it if it already exists), or null on failure. */
    fun createFile(context: Context, name: String): OutputStream?

    /** A sub-folder named [name] (reusing it if it's already there), or null on failure. */
    fun createDirectory(context: Context, name: String): PasteTarget?
}

enum class ClipMode { COPY, MOVE }

/**
 * The in-app "clipboard" for file transfers: select items anywhere, Copy or Move, browse to another
 * folder (local or a network share), Paste. One small shared holder, so Files and Network folders
 * can paste into each other.
 */
object FileClipboard {
    val items = mutableStateListOf<ClipItem>()
    var mode by mutableStateOf(ClipMode.COPY)
        private set

    val isEmpty: Boolean get() = items.isEmpty()

    fun set(newItems: List<ClipItem>, newMode: ClipMode) {
        items.clear()
        items.addAll(newItems)
        mode = newMode
    }

    fun clear() = items.clear()
}

/** Copies (or moves) [items] into [dest], recursing into folders. Returns (succeeded, failed) file counts. Call off the main thread. */
fun pasteInto(context: Context, dest: PasteTarget, items: List<ClipItem>, move: Boolean): Pair<Int, Int> {
    var ok = 0
    var fail = 0
    for (item in items) {
        if (item.isDirectory) {
            val sub = dest.createDirectory(context, item.name)
            if (sub == null) {
                fail++
                continue
            }
            val (o, f) = pasteInto(context, sub, item.listChildren(context), move)
            ok += o
            fail += f
            if (move && f == 0) item.delete(context)
        } else {
            val out = dest.createFile(context, item.name)
            if (out == null) {
                fail++
                continue
            }
            val copied = runCatching {
                item.openInput(context).use { ins -> out.use { o -> ins.copyTo(o) } }
            }.isSuccess
            if (copied) {
                ok++
                if (move) item.delete(context)
            } else {
                fail++
            }
        }
    }
    return ok to fail
}

/** [ClipItem] for a file or folder already on this phone (a SAF document, or a SyncUp download cached from a network share). */
class LocalClipItem(val doc: DocumentFile) : ClipItem {
    override val name: String get() = doc.name ?: "file"
    override val isDirectory: Boolean get() = doc.isDirectory
    override val sizeHint: Long get() = doc.length()
    override fun openInput(context: Context): InputStream =
        context.contentResolver.openInputStream(doc.uri) ?: throw IOException("Can't open $name")
    override fun listChildren(context: Context): List<ClipItem> = doc.listFiles().map { LocalClipItem(it) }
    override fun delete(context: Context): Boolean = runCatching { doc.delete() }.getOrDefault(false)
}
