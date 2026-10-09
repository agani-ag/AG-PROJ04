package com.agani.syncup.downloads

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.agani.syncup.browser.BrowserDb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * SyncUp's own download manager (like IDM / 1DM).
 *
 * - A file the server can serve in byte ranges is split into up to [partsPerFile] parts, each
 *   downloaded on its own connection at the same time and written straight to its place in the file.
 * - Pause / resume / retry; a part that fails retries on its own; a dropped network waits and resumes.
 *   Progress is saved every few seconds, so a closed app or a restarted phone carries on.
 * - [atOnce] files download at the same time; the rest wait in line. "Wi-Fi only" holds them on
 *   mobile data.
 * - Files go to Download / SyncUp / <kind> — on Android 10+ straight into the shared Downloads
 *   collection (hidden until finished), so a big file never needs room twice.
 * - [DownloadService] keeps it running with the app closed and shows the progress notification.
 *
 * All public functions are for the main thread. Incognito downloads live in memory only.
 */
object Downloads {
    private const val MIN_PART = 1L shl 20 // parts of at least 1 MB
    private const val SAVE_EVERY_MS = 3_000L
    private const val TICK_MS = 500L

    private lateinit var app: Context
    private lateinit var store: DownloadDb
    private lateinit var prefs: SharedPreferences
    private var ready = false

    /** Every download, newest first. */
    val tasks = mutableStateListOf<DownloadTask>()

    /** A browser download waiting for the user's OK (the "Download file" sheet), or null. */
    var prompt by mutableStateOf<DownloadRequest?>(null)

    /** The browser's user agent, for links added by hand (set by the browser). */
    @Volatile var defaultUserAgent: String = ""

    // ---- settings (this phone only)
    var partsPerFile by mutableIntStateOf(8)
        private set
    var atOnce by mutableIntStateOf(2)
        private set
    var wifiOnly by mutableStateOf(false)
        private set
    var askFirst by mutableStateOf(true)
        private set

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val io = Executors.newSingleThreadExecutor()
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val calls = ConcurrentHashMap<Long, MutableSet<Call>>()
    /** Cookies of unfinished downloads (Incognito ones only here, never on disk). */
    private val cookies = ConcurrentHashMap<Long, String>()
    private var nextIncognitoId = -1L
    private val lastSaved = HashMap<Long, Long>()
    private val lastBytes = HashMap<Long, Pair<Long, Long>>() // id -> (time, bytes)
    private var ticking = false
    private var serviceOn = false

    /** HTTP/1.1 only: each part gets a connection of its own (HTTP/2 would share one). */
    internal val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .followRedirects(false) // followed by hand, so cookies never go to another site
            .followSslRedirects(false)
            .build()
    }

    fun init(context: Context) {
        if (ready) return
        ready = true
        app = context.applicationContext
        prefs = app.getSharedPreferences("download_settings", Context.MODE_PRIVATE)
        partsPerFile = prefs.getInt("parts", 8)
        atOnce = prefs.getInt("at_once", 2)
        wifiOnly = prefs.getBoolean("wifi_only", false)
        askFirst = prefs.getBoolean("ask_first", true)
        store = DownloadDb(app)
        val browserDb = BrowserDb.get(app)
        val legacy = runCatching { browserDb.downloads() }.getOrDefault(emptyList())
        if (legacy.isNotEmpty()) {
            store.importLegacy(legacy)
            browserDb.clearDownloads()
        }
        for ((t, c) in store.load()) {
            if (c != null) cookies[t.id] = c
            // Interrupted by the app closing: carry on where it stopped.
            if (t.state == DlState.RUNNING || t.state == DlState.WAITING_NETWORK || t.state == DlState.WAITING_WIFI) t.state = DlState.QUEUED
            tasks.add(t)
        }
        watchNetwork()
        pump()
    }

    // ------------------------------------------------------------------ settings
    fun updatePartsPerFile(n: Int) {
        partsPerFile = n
        prefs.edit().putInt("parts", n).apply()
    }

    fun updateAtOnce(n: Int) {
        atOnce = n
        prefs.edit().putInt("at_once", n).apply()
        pump()
    }

    fun updateWifiOnly(on: Boolean) {
        wifiOnly = on
        prefs.edit().putBoolean("wifi_only", on).apply()
        if (on) tasks.filter { it.state == DlState.RUNNING && !canDownloadNow() }.forEach { stop(it, DlState.WAITING_WIFI) }
        wake()
    }

    fun updateAskFirst(on: Boolean) {
        askFirst = on
        prefs.edit().putBoolean("ask_first", on).apply()
    }

    // ------------------------------------------------------------------ asking for downloads
    /** The browser wants a file: show the "Download file" sheet, or start straight away. */
    fun request(req: DownloadRequest) {
        if (askFirst) prompt = req else start(req)
    }

    /** Start downloading. [name] is the user's file name (the sheet / Add link), [info] what a check found. */
    fun start(req: DownloadRequest, name: String? = null, info: LinkInfo? = null): DownloadTask {
        val fileName = Files.cleanName(name?.takeIf { it.isNotBlank() } ?: info?.fileName ?: req.guessName())
        val t = DownloadTask(
            id = 0, url = info?.url ?: req.url, fileName = fileName,
            mime = Files.mimeFor(fileName, info?.mime ?: req.mime),
            total = info?.size?.takeIf { it > 0 } ?: req.contentLength,
            source = req.source, work = req.work, incognito = req.incognito,
            userAgent = req.userAgent, referer = req.referer, createdAt = System.currentTimeMillis(),
        )
        if (info != null) {
            t.probed = true
            t.resumable = info.resumable
            t.etag = info.etag
            t.lastModified = info.lastModified
        }
        t.id = if (req.incognito) nextIncognitoId-- else store.insert(t, req.cookies)
        req.cookies?.let { cookies[t.id] = it }
        tasks.add(0, t)
        pump()
        return t
    }

    /** A file the page made itself (blob: / data:) was saved in one go: list it as finished. */
    fun recordSaved(fileName: String, mime: String, size: Long, contentUri: String, source: String, work: Boolean, incognito: Boolean) {
        val now = System.currentTimeMillis()
        val t = DownloadTask(0, "", fileName, mime, size, source, work, incognito, "", "", now)
        t.state = DlState.DONE
        t.contentUri = contentUri
        t.finishedAt = now
        t.downloaded = size
        t.id = if (incognito) nextIncognitoId-- else store.insert(t, null)
        tasks.add(0, t)
    }

    // ------------------------------------------------------------------ controls
    fun pause(t: DownloadTask) {
        if (t.state == DlState.DONE) return
        stop(t, DlState.PAUSED)
        pump()
    }

    fun resume(t: DownloadTask) {
        if (t.state == DlState.DONE || t.state == DlState.RUNNING) return
        t.state = DlState.QUEUED
        t.error = null
        persist(t)
        pump()
    }

    fun pauseAll() {
        tasks.filter { it.state != DlState.DONE && it.state != DlState.FAILED && it.state != DlState.PAUSED }.forEach { stop(it, DlState.PAUSED) }
        syncService()
    }

    fun cancelAll() {
        tasks.filter { it.state != DlState.DONE }.forEach { cancel(it) }
    }

    /** Stop and remove an unfinished download, and its partly written file. */
    fun cancel(t: DownloadTask) {
        stop(t, DlState.PAUSED)
        tasks.remove(t)
        cookies.remove(t.id)
        val uri = t.contentUri
        io.execute {
            deleteFile(uri)
            if (t.id > 0) store.delete(t.id)
        }
        pump()
    }

    /** Remove a finished download from the list; [deleteFile] also deletes the file from the phone. */
    fun remove(t: DownloadTask, deleteFile: Boolean) {
        if (t.state != DlState.DONE) {
            cancel(t)
            return
        }
        tasks.remove(t)
        val uri = t.contentUri
        io.execute {
            if (deleteFile) deleteFile(uri)
            if (t.id > 0) store.delete(t.id)
        }
    }

    /** Rename a finished file. Returns false when it couldn't be renamed. */
    fun rename(t: DownloadTask, newName: String): Boolean {
        val name = Files.cleanName(newName)
        val uri = t.contentUri ?: return false
        val ok = runCatching {
            if (uri.startsWith("content://")) {
                app.contentResolver.update(Uri.parse(uri), ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }, null, null) > 0
            } else {
                val f = File(uri)
                val target = File(f.parentFile, name)
                if (!target.exists() && f.renameTo(target)) {
                    t.contentUri = target.absolutePath
                    true
                } else {
                    false
                }
            }
        }.getOrDefault(false)
        if (ok) {
            t.fileName = name
            persist(t)
        }
        return ok
    }

    fun hasActive(): Boolean = tasks.any { it.state == DlState.RUNNING || it.state == DlState.QUEUED }

    /** Where to open / share a finished file from (content:// address), or null if it's gone. */
    fun openUri(t: DownloadTask): Uri? {
        if (t.legacyId != 0L) {
            val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            return runCatching { dm.getUriForDownloadedFile(t.legacyId) }.getOrNull()
        }
        val uri = t.contentUri ?: return null
        return if (uri.startsWith("content://")) {
            Uri.parse(uri).takeIf { u -> runCatching { app.contentResolver.query(u, null, null, null, null)?.use { it.count > 0 } }.getOrNull() == true }
        } else {
            val f = File(uri)
            if (!f.exists()) null else runCatching {
                androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", f)
            }.getOrNull()
        }
    }

    /** The folder files of [kind] are saved in, as people see it. */
    fun folderLabel(kind: FileKind) = "Download / SyncUp / ${kind.folder}"

    // ------------------------------------------------------------------ checking a link
    /**
     * Find out what [url] is without downloading it: name, type, size, whether it resumes.
     * Redirects are followed by hand; [cookies] go only to the link's own site.
     */
    suspend fun probe(url: String, userAgent: String = "", referer: String = "", cookies: String? = null): LinkInfo =
        withContext(Dispatchers.IO) {
            val origin = hostOf(url)
            var current = url
            repeat(10) {
                val b = Request.Builder().url(current).header("Range", "bytes=0-0").header("Accept-Encoding", "identity")
                if (userAgent.isNotBlank()) b.header("User-Agent", userAgent)
                if (referer.isNotBlank()) b.header("Referer", referer)
                if (cookies != null && sameSite(hostOf(current), origin)) b.header("Cookie", cookies)
                client.newCall(b.build()).execute().use { resp ->
                    if (resp.isRedirect) {
                        val next = resp.header("Location")?.let { resp.request.url.resolve(it)?.toString() } ?: throw IOException("Bad redirect")
                        current = next
                        return@repeat
                    }
                    if (!resp.isSuccessful) throw HttpStatus(resp.code)
                    return@withContext infoFrom(resp, current)
                }
            }
            throw IOException("Too many redirects")
        }

    private fun infoFrom(resp: Response, url: String): LinkInfo {
        val partial = resp.code == 206
        val size = if (partial) {
            resp.header("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull() ?: -1
        } else {
            resp.header("Content-Length")?.toLongOrNull() ?: -1
        }
        val mime = resp.header("Content-Type")?.substringBefore(';')?.trim().orEmpty()
        val name = Files.cleanName(android.webkit.URLUtil.guessFileName(url, resp.header("Content-Disposition"), mime.ifBlank { null }))
        return LinkInfo(
            url = url, fileName = name, mime = Files.mimeFor(name, mime), size = size,
            resumable = partial && size > 0, etag = resp.header("ETag")?.takeUnless { it.startsWith("W/") },
            lastModified = resp.header("Last-Modified"),
        )
    }

    // ------------------------------------------------------------------ the queue
    private fun pump() {
        if (!ready) return
        var free = atOnce - tasks.count { it.state == DlState.RUNNING }
        for (t in tasks.filter { it.state == DlState.QUEUED }.sortedBy { it.createdAt }) {
            if (free <= 0) break
            if (!canDownloadNow()) {
                t.state = if (online()) DlState.WAITING_WIFI else DlState.WAITING_NETWORK
                persist(t)
                continue
            }
            launch(t)
            free--
        }
        syncService()
        startTicker()
    }

    /** Network back / Wi-Fi back: waiting downloads carry on. */
    private fun wake() {
        tasks.filter { it.state == DlState.WAITING_NETWORK || it.state == DlState.WAITING_WIFI }.forEach { it.state = DlState.QUEUED }
        pump()
    }

    private fun stop(t: DownloadTask, state: DlState) {
        t.state = state
        t.speed = 0
        jobs.remove(t.id)?.cancel()
        calls.remove(t.id)?.forEach { it.cancel() }
        t.downloaded = t.bytesDone()
        persist(t)
    }

    private fun launch(t: DownloadTask) {
        t.state = DlState.RUNNING
        t.error = null
        lastBytes.remove(t.id)
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            val result = runCatching { download(t) }
            main.post {
                // Paused, cancelled or started again since: this run's result no longer counts.
                if (jobs[t.id] !== job) return@post
                jobs.remove(t.id)
                if (t.state != DlState.RUNNING) return@post
                result.onSuccess { finished(t) }.onFailure { if (it !is CancellationException) failed(t, it) }
                pump()
            }
        }
        jobs[t.id] = job
        job.start()
    }

    // ------------------------------------------------------------------ downloading (IO threads)
    private class HttpStatus(val code: Int) : IOException("HTTP $code")
    private class RestartNeeded : IOException("The server sent the whole file instead of a part")

    private suspend fun download(t: DownloadTask) {
        if (t.parts.isEmpty()) {
            // First start: learn the size and whether the file can be split (unless the sheet already did).
            if (!t.probed) {
                runCatching { probe(t.url, t.userAgent, t.referer, cookies[t.id]) }.getOrNull()?.let { info ->
                    t.url = info.url
                    t.resumable = info.resumable
                    t.etag = info.etag
                    t.lastModified = info.lastModified
                    if (info.size > 0) t.total = info.size
                }
            }
            t.parts = plan(t.total, t.resumable)
            persist(t)
        } else if (!t.resumable) {
            // The server can't continue a file from the middle: start it again.
            t.parts = listOf(Part(0, -1))
        }
        openOutput(t).use { out ->
            if (!t.resumable) out.truncate(0)
            try {
                coroutineScope {
                    for (p in t.parts) if (!p.finished) launch { fetchPart(t, p, out) }
                }
            } catch (e: RestartNeeded) {
                // The file changed on the server, or it ignores ranges after all: one connection, from the start.
                t.resumable = false
                t.parts = listOf(Part(0, -1))
                out.truncate(0)
                fetchPart(t, t.parts[0], out)
            }
        }
        publish(t)
    }

    private fun plan(total: Long, resumable: Boolean): List<Part> {
        if (!resumable || total <= 0) return listOf(Part(0, -1))
        val n = partsPerFile.coerceAtLeast(1).toLong().coerceAtMost((total / MIN_PART).coerceAtLeast(1)).toInt()
        val size = total / n
        return (0 until n).map { i -> Part(i * size, if (i == n - 1) total - 1 else (i + 1) * size - 1) }
    }

    private suspend fun fetchPart(t: DownloadTask, p: Part, out: Output) {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                transfer(t, p, out)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: RestartNeeded) {
                throw e
            } catch (e: HttpStatus) {
                // 4xx won't get better by trying again (except "too many requests" / timeouts).
                if (e.code in 400..499 && e.code != 408 && e.code != 429) throw e
                if (++attempt > 5) throw e
            } catch (e: IOException) {
                if (!currentCoroutineContext().isActiveSafe()) throw CancellationException()
                if (!online() || ++attempt > 5) throw e
            }
            delay((1000L shl attempt).coerceAtMost(30_000))
        }
    }

    private fun kotlin.coroutines.CoroutineContext.isActiveSafe() = this[Job]?.isActive != false

    /** Download one part from where it stopped, writing each block at its place in the file. */
    private fun transfer(t: DownloadTask, p: Part, out: Output) {
        val from = p.start + p.done.get()
        if (p.end >= 0 && from > p.end) return
        val ranged = t.resumable && (from > 0 || p.end >= 0)
        val b = Request.Builder().url(t.url).header("Accept-Encoding", "identity")
        if (t.userAgent.isNotBlank()) b.header("User-Agent", t.userAgent)
        if (t.referer.isNotBlank()) b.header("Referer", t.referer)
        cookies[t.id]?.let { b.header("Cookie", it) }
        if (ranged) {
            b.header("Range", "bytes=$from-" + if (p.end >= 0) "${p.end}" else "")
            (t.etag ?: t.lastModified)?.let { b.header("If-Range", it) }
        }
        val call = client.newCall(b.build())
        calls.getOrPut(t.id) { ConcurrentHashMap.newKeySet() }.add(call)
        try {
            call.execute().use { resp ->
                if (ranged && resp.code == 200) throw RestartNeeded()
                if (!resp.isSuccessful) throw HttpStatus(resp.code)
                val src = resp.body?.byteStream() ?: throw IOException("Empty response")
                val buf = ByteArray(64 * 1024)
                var pos = from
                while (true) {
                    val max = if (p.end >= 0) (p.end - pos + 1).coerceAtMost(buf.size.toLong()).toInt() else buf.size
                    if (max <= 0) break
                    val n = src.read(buf, 0, max)
                    if (n < 0) break
                    out.write(buf, n, pos)
                    pos += n
                    p.done.addAndGet(n.toLong())
                }
                if (p.end >= 0 && pos <= p.end) throw IOException("The connection closed early")
            }
        } finally {
            calls[t.id]?.remove(call)
        }
    }

    /** Where the bytes go: a positional-write channel into the saved file. */
    private class Output(private val channel: FileChannel, private val onClose: () -> Unit) : Closeable {
        fun write(buf: ByteArray, n: Int, pos: Long) {
            val bb = ByteBuffer.wrap(buf, 0, n)
            var at = pos
            while (bb.hasRemaining()) at += channel.write(bb, at)
        }

        fun truncate(size: Long) {
            channel.truncate(size)
        }

        override fun close() {
            runCatching { channel.close() }
            runCatching { onClose() }
        }
    }

    private fun openOutput(t: DownloadTask): Output {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = app.contentResolver
            val existing = t.contentUri?.let(Uri::parse)?.takeIf { u ->
                runCatching { resolver.query(u, null, null, null, null)?.use { it.count > 0 } }.getOrNull() == true
            }
            val uri = existing ?: createPending(t).also { if (t.parts.any { p -> p.done.get() > 0 }) t.parts = plan(t.total, t.resumable) }
            val pfd = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("Can't open the file")
            val fos = FileOutputStream(pfd.fileDescriptor)
            return Output(fos.channel) {
                fos.close()
                pfd.close()
            }
        }
        val existing = t.contentUri?.let(::File)?.takeIf { it.exists() }
        val file = existing ?: run {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SyncUp/${t.kind.folder}")
            dir.mkdirs()
            unusedFile(dir, t.fileName).also { f ->
                t.contentUri = f.absolutePath
                t.fileName = f.name
                if (t.parts.any { p -> p.done.get() > 0 }) t.parts = plan(t.total, t.resumable)
                persist(t)
            }
        }
        val raf = RandomAccessFile(file, "rw")
        return Output(raf.channel) { raf.close() }
    }

    /** A new, hidden (pending) file in the shared Downloads collection. */
    private fun createPending(t: DownloadTask): Uri {
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, t.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, t.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/SyncUp/${t.kind.folder}")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Can't create the file")
        t.contentUri = uri.toString()
        // Android keeps names unique ("name (1).pdf"): show the name it really got.
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { real -> t.fileName = real }
        }
        persist(t)
        return uri
    }

    /** Finished: make the file visible to the phone (gallery, music players, file managers). */
    private fun publish(t: DownloadTask) {
        val uri = t.contentUri ?: return
        if (uri.startsWith("content://")) {
            val u = Uri.parse(uri)
            app.contentResolver.update(u, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            // Android settles the final name ("name (1).bin") when the file stops being pending.
            app.contentResolver.query(u, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { t.fileName = it }
            }
        } else {
            MediaScannerConnection.scanFile(app, arrayOf(uri), arrayOf(t.mime), null)
        }
    }

    private fun deleteFile(uri: String?) {
        uri ?: return
        runCatching {
            if (uri.startsWith("content://")) app.contentResolver.delete(Uri.parse(uri), null, null) else File(uri).delete()
        }
    }

    private fun unusedFile(dir: File, name: String): File {
        var f = File(dir, name)
        var n = 1
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        while (f.exists()) f = File(dir, "$stem (${n++})$ext")
        return f
    }

    // ------------------------------------------------------------------ results (main thread)
    private fun finished(t: DownloadTask) {
        val size = t.bytesDone()
        t.total = if (t.total > 0) t.total else size
        t.downloaded = t.total
        t.speed = 0
        t.state = DlState.DONE
        t.finishedAt = System.currentTimeMillis()
        t.parts = emptyList()
        t.partProgress = emptyList()
        cookies.remove(t.id)
        persist(t)
        DownloadService.notifyDone(app, t)
    }

    private fun failed(t: DownloadTask, e: Throwable) {
        t.speed = 0
        t.downloaded = t.bytesDone()
        if (!online()) {
            t.state = DlState.WAITING_NETWORK
            t.error = null
        } else {
            t.state = DlState.FAILED
            t.error = when {
                e is HttpStatus && (e.code == 401 || e.code == 403) -> "The site refused the download (${e.code}). Open the page again and retry."
                e is HttpStatus && e.code == 404 -> "The file isn't on the site any more (404)."
                e is HttpStatus && e.code == 410 -> "The link has expired (410). Open the page again and retry."
                e is HttpStatus -> "The site answered with an error (${e.code})."
                e.message?.contains("ENOSPC") == true || e.message?.contains("No space") == true -> "The phone's storage is full."
                else -> "Connection problem. Tap retry."
            }
        }
        persist(t)
    }

    // ------------------------------------------------------------------ progress
    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            var running = false
            for (t in tasks) {
                if (t.state != DlState.RUNNING) continue
                running = true
                val done = t.bytesDone()
                val prev = lastBytes[t.id]
                if (prev != null && now > prev.first) {
                    val instant = (done - prev.second) * 1000 / (now - prev.first)
                    t.speed = if (t.speed == 0L) instant else (t.speed * 7 + instant * 3) / 10
                }
                lastBytes[t.id] = now to done
                t.downloaded = done
                t.partProgress = t.parts.map { p ->
                    when {
                        p.length > 0 -> (p.done.get().toFloat() / p.length).coerceIn(0f, 1f)
                        t.total > 0 -> (p.done.get().toFloat() / t.total).coerceIn(0f, 1f)
                        else -> 0f
                    }
                }
                if (now - (lastSaved[t.id] ?: 0L) > SAVE_EVERY_MS) {
                    lastSaved[t.id] = now
                    persist(t)
                }
            }
            if (serviceOn) DownloadService.update(app)
            ticking = running
            if (running) main.postDelayed(this, TICK_MS)
        }
    }

    private fun startTicker() {
        if (ticking || tasks.none { it.state == DlState.RUNNING }) return
        ticking = true
        main.postDelayed(ticker, TICK_MS)
    }

    /** Seconds left at the current speed, or -1. */
    fun secondsLeft(t: DownloadTask): Long =
        if (t.speed > 0 && t.total > 0) ((t.total - t.downloaded).coerceAtLeast(0) / t.speed) else -1

    // ------------------------------------------------------------------ service, storage, network
    private fun syncService() {
        val active = tasks.any { it.state == DlState.RUNNING || it.state == DlState.QUEUED }
        if (active && !serviceOn) {
            serviceOn = DownloadService.start(app)
        } else if (!active && serviceOn) {
            serviceOn = false
            DownloadService.stop(app)
        }
    }

    /** Android stopped the service (time limit): keep what's done, carry on when the app is opened. */
    internal fun onServiceStopped() {
        serviceOn = false
        tasks.filter { it.state == DlState.RUNNING || it.state == DlState.QUEUED }.forEach { stop(it, DlState.PAUSED) }
    }

    private fun persist(t: DownloadTask) {
        if (t.incognito || t.id <= 0) return
        val c = if (t.state == DlState.DONE) null else cookies[t.id]
        io.execute { runCatching { store.save(t, c) } }
    }

    private fun canDownloadNow(): Boolean {
        val caps = caps() ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return !wifiOnly || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun online(): Boolean = caps()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    private fun caps(): NetworkCapabilities? {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        return cm.getNetworkCapabilities(cm.activeNetwork ?: return null)
    }

    private fun watchNetwork() {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    main.postDelayed({ wake() }, 1_000)
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    main.post {
                        if (tasks.any { it.state == DlState.WAITING_WIFI || it.state == DlState.WAITING_NETWORK }) wake()
                    }
                }

                override fun onLost(network: Network) {
                    // Running parts fail and wait on their own; nothing to do here.
                }
            })
        }
    }

    private fun hostOf(url: String): String = runCatching { Uri.parse(url).host.orEmpty().lowercase() }.getOrDefault("")

    private fun sameSite(a: String, b: String): Boolean {
        if (a == b) return true
        val ra = com.agani.syncup.browser.UrlInput.registrableDomain(a)
        return ra.isNotEmpty() && ra == com.agani.syncup.browser.UrlInput.registrableDomain(b)
    }
}
