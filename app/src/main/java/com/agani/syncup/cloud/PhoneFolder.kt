package com.agani.syncup.cloud

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/** A file inside a folder picked on the phone: [rel] is its folder path from the picked folder down ("" = in it). */
class PhoneFile(val rel: String, val uri: Uri, val name: String, val mime: String, val size: Long)

/**
 * A whole folder picked with Android's folder picker (OpenDocumentTree): SyncUp needs no storage permission, and
 * only sees the folder the person chose. Hidden files (".nomedia", ".thumbnails") are left out.
 */
object PhoneFolder {
    private const val MAX_FILES = 5000

    /**
     * Keep reading [tree] after an app restart (station uploads carry on then). Android allows a limited number
     * of these, so it's best-effort.
     */
    fun keep(context: Context, tree: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /** The folder's name and every file in it and its folders. Blocking: off the main thread. */
    fun walk(context: Context, tree: Uri): Pair<String, List<PhoneFile>> {
        val resolver = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        var name = "Folder"
        runCatching {
            resolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, rootId), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { name = it } }
        }
        val out = ArrayList<PhoneFile>()
        val todo = ArrayDeque(listOf(rootId to ""))
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        while (todo.isNotEmpty() && out.size < MAX_FILES) {
            val (id, rel) = todo.removeFirst()
            resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), cols, null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < MAX_FILES) {
                    val childId = c.getString(0) ?: continue
                    val childName = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    if (childName.startsWith(".")) continue
                    val mime = c.getString(2).orEmpty()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        todo.addLast(childId to if (rel.isEmpty()) childName else "$rel/$childName")
                    } else {
                        val size = if (c.isNull(3)) -1L else c.getLong(3)
                        out += PhoneFile(rel, DocumentsContract.buildDocumentUriUsingTree(tree, childId), childName, mime.ifBlank { "application/octet-stream" }, size)
                    }
                }
            }
        }
        return name to out
    }
}
