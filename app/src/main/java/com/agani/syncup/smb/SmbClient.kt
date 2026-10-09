package com.agani.syncup.smb

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.agani.syncup.files.ClipItem
import com.agani.syncup.files.LocalClipItem
import com.agani.syncup.files.PasteTarget
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * One open connection to a share: list, read, write, delete, rename. Everything here is blocking —
 * call off the main thread. One session per screen visit (opened when the browser appears, closed
 * when it's left); re-browsing reconnects, which is simpler and more robust than keeping a pool of
 * connections alive across network changes.
 */
class SmbSession private constructor(
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
    val share: DiskShare,
) {
    fun list(path: String): List<SmbEntry> =
        share.list(path).filter { it.fileName != "." && it.fileName != ".." }.map { info ->
            val dir = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
            SmbEntry(
                name = info.fileName,
                path = if (path.isBlank()) info.fileName else "$path\\${info.fileName}",
                isDirectory = dir,
                size = info.endOfFile,
                lastModified = runCatching { info.lastWriteTime.toEpochMillis() }.getOrDefault(0L),
            )
        }.sortedWith(compareByDescending<SmbEntry> { it.isDirectory }.thenBy { it.name.lowercase() })

    fun openRead(path: String): InputStream =
        share.openFile(path, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null).inputStream

    fun openWrite(path: String): OutputStream =
        share.openFile(
            path, EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF, null,
        ).outputStream

    fun mkdir(path: String) {
        if (!share.folderExists(path)) share.mkdir(path)
    }

    fun delete(entry: SmbEntry) {
        if (entry.isDirectory) share.rmdir(entry.path, true) else share.rm(entry.path)
    }

    fun rename(entry: SmbEntry, newName: String) {
        val mask = EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_WRITE, AccessMask.GENERIC_READ)
        if (entry.isDirectory) {
            share.openDirectory(entry.path, mask, null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null).use { it.rename(newName, false) }
        } else {
            share.openFile(entry.path, mask, null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null).use { it.rename(newName, false) }
        }
    }

    fun close() {
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }

    companion object {
        /** Opens a session to [server]. Throws on failure (bad host, wrong password, …) — catch and show the message. */
        fun open(server: SmbServer, timeoutMs: Long = 10_000): SmbSession {
            val config = SmbConfig.builder()
                .withTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .withSoTimeout(timeoutMs * 2, TimeUnit.MILLISECONDS)
                .build()
            val client = SMBClient(config)
            val connection = client.connect(server.host)
            val ac = AuthenticationContext(server.username, server.password.toCharArray(), null)
            val session = connection.authenticate(ac)
            val share = session.connectShare(server.share) as? DiskShare ?: run {
                session.close(); connection.close(); client.close()
                throw IOException("“${server.share}” isn't a shared folder on this server")
            }
            return SmbSession(client, connection, session, share)
        }
    }
}

/** Where a paste lands inside a share: a folder path, created on demand. */
class SmbPasteTarget(private val session: SmbSession, private val path: String) : PasteTarget {
    override fun createFile(context: Context, name: String): OutputStream? = runCatching {
        session.openWrite(join(path, name))
    }.getOrNull()

    override fun createDirectory(context: Context, name: String): PasteTarget? = runCatching {
        val p = join(path, name)
        session.mkdir(p)
        SmbPasteTarget(session, p)
    }.getOrNull()

    companion object {
        fun join(path: String, name: String) = if (path.isBlank()) name else "$path\\$name"
    }
}

/**
 * Downloads [entry] from the share into the app's own cache (recursing into folders) and wraps it as
 * a plain local [ClipItem] — so pasting a network file anywhere afterwards reuses the same local
 * copy/move code as any file already on the phone, with no dependency on the SMB session staying open.
 */
fun cacheForClipboard(context: Context, session: SmbSession, entry: SmbEntry): ClipItem {
    val root = File(context.cacheDir, "smb_clip/${UUID.randomUUID()}").apply { mkdirs() }
    downloadInto(session, entry, root)
    val doc = DocumentFile.fromFile(File(root, entry.name))
    return LocalClipItem(doc)
}

private fun downloadInto(session: SmbSession, entry: SmbEntry, destDir: File) {
    val target = File(destDir, entry.name)
    if (entry.isDirectory) {
        target.mkdirs()
        session.list(entry.path).forEach { child -> downloadInto(session, child, target) }
    } else {
        session.openRead(entry.path).use { input -> target.outputStream().use { out -> input.copyTo(out) } }
    }
}
