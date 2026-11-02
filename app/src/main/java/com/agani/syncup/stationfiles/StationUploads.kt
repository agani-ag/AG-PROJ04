package com.agani.syncup.stationfiles

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.FileInputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** One upload to a station's folder, as it's kept on this phone (so it carries on after the app restarts). */
data class StationUpload(
    val id: Long,
    val key: String,
    val station: String,
    /** The folder's upload_folder (where AudioSync starts the upload) and its name, for people. */
    val folder: String,
    val folderName: String,
    val uri: String,
    val name: String,
    val size: Long,
    /** AudioSync's upload address (path), once started: the way back to it after any break. */
    val uploadUrl: String? = null,
    val received: Long = 0,
    /** queued, running, done, failed or cancelled. */
    val state: String = QUEUED,
    val note: String? = null,
    val finalName: String? = null,
    val error: String? = null,
) {
    val active: Boolean get() = state == QUEUED || state == RUNNING

    companion object {
        const val QUEUED = "queued"
        const val RUNNING = "running"
        const val DONE = "done"
        const val FAILED = "failed"
        const val CANCELLED = "cancelled"
    }
}

/**
 * Uploads to stations, in pieces (AudioSync's /api/uploads): a 10 GB file survives a dropped connection, a new
 * station address or an AudioSync restart, carrying on from what the station says arrived. One at a time,
 * run by [UploadService] so it keeps going with the app closed.
 */
object StationUploads {
    /** AudioSync's max_piece: 64 MB, under the public link's 100 MB request limit. Until it says, 32 MB. */
    private const val MAX_PIECE = 64L * 1024 * 1024
    @Volatile private var piece = 32L * 1024 * 1024

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var runningId = 0L

    /** Every upload, newest first. */
    val items = mutableStateListOf<StationUpload>()

    /** Bytes sent so far while a piece is going up (the saved [StationUpload.received] lags behind). */
    val sent = mutableStateMapOf<Long, Long>()

    /** Goes up each time an upload finishes; [lastFinished] says where (key to folder), so that folder reloads. */
    var finishedTick by mutableIntStateOf(0)
        private set
    var lastFinished: Pair<String, String>? = null
        private set

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        val saved = runCatching {
            Stations.gson.fromJson<List<StationUpload>>(prefs().getString("uploads", null), object : TypeToken<List<StationUpload>>() {}.type)
        }.getOrNull().orEmpty()
        // Interrupted by the app closing: carry on.
        items.addAll(saved.map { if (it.state == StationUpload.RUNNING) it.copy(state = StationUpload.QUEUED, note = null) else it })
        pump()
    }

    private fun prefs() = app.getSharedPreferences("station_uploads", Context.MODE_PRIVATE)

    private fun save() {
        prefs().edit().putString("uploads", Stations.gson.toJson(items.toList())).apply()
    }

    private fun update(id: Long, change: (StationUpload) -> StationUpload) {
        main.post {
            val i = items.indexOfFirst { it.id == id }
            if (i >= 0) {
                items[i] = change(items[i])
                save()
                UploadService.update(app)
            }
        }
    }

    /** Add [uris] to [folder] on [key]'s station. Main thread. */
    fun add(key: String, station: String, folder: String, folderName: String, uris: List<Uri>) {
        val now = System.currentTimeMillis()
        uris.forEachIndexed { i, uri ->
            runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
            var size = -1L
            runCatching {
                app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.takeIf { it.isNotBlank() }?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            }
            items.add(0, StationUpload(now + i, key, station, folder, folderName, uri.toString(), name, size))
        }
        save()
        pump()
    }

    /** Stop an upload and take it off the list. Main thread. */
    fun cancel(id: Long) {
        val item = items.firstOrNull { it.id == id } ?: return
        if (runningId == id) job?.cancel()
        items.remove(item)
        sent.remove(id)
        save()
        // Free the space on the PC too.
        item.uploadUrl?.let { url -> scope.launch { runCatching { Stations.call(item.key, url, { it.delete() }) { } } } }
        pump()
    }

    fun retry(id: Long) {
        update(id) { it.copy(state = StationUpload.QUEUED, error = null, note = null) }
        main.post { pump() }
    }

    /** Uploads waiting on [key]'s PIN carry on after it's entered. */
    fun pinEntered(key: String) {
        items.filter { it.key == key && it.state == StationUpload.FAILED && it.error == PIN_ERROR }.forEach { retry(it.id) }
    }

    private const val PIN_ERROR = "The station wants its PIN. Open its files to enter it."

    private fun pump() {
        if (items.any { it.state == StationUpload.RUNNING }) return
        val next = items.filter { it.state == StationUpload.QUEUED }.minByOrNull { it.id }
        if (next == null) {
            UploadService.stop(app)
            return
        }
        UploadService.start(app)
        runningId = next.id
        items[items.indexOf(next)] = next.copy(state = StationUpload.RUNNING)
        save()
        job = scope.launch {
            val result = runCatching { run(next) }
            main.post {
                val now = items.firstOrNull { it.id == next.id }
                if (now != null && now.state == StationUpload.RUNNING) {
                    result.onFailure { e ->
                        if (e !is CancellationException) {
                            val message = when {
                                e is Stations.PinNeeded -> PIN_ERROR
                                e is Stations.Refused && e.error == "bad_size" -> "This phone can't tell the file's size, so it can't be sent."
                                e is Stations.Refused && e.error == "uploads_disabled" -> "${now.station} doesn't allow uploads now."
                                e is Stations.Refused && e.error == "too_large" -> "The file is bigger than ${now.station} allows."
                                e is Stations.Refused && e.error == "no_space" -> "There isn't enough space on ${now.station}'s PC."
                                e is Stations.Refused && (e.error == "destination_gone" || e.code == 404) -> "The folder isn't shared any more."
                                e is SecurityException || e is java.io.FileNotFoundException -> "The file on this phone can't be read any more."
                                else -> "Couldn't send it (${e.message})."
                            }
                            items[items.indexOf(now)] = now.copy(state = StationUpload.FAILED, error = message, note = null)
                            save()
                        }
                    }
                }
                sent.remove(next.id)
                runningId = 0
                pump()
            }
        }
    }

    // ------------------------------------------------------------------ sending (IO thread)
    private suspend fun run(start: StationUpload) {
        var u = start
        var waiting = 0
        // Where the next piece starts, when the station's last reply said so; null = ask it first (after a break).
        var from: Long? = null
        while (true) {
            coroutineContext.ensureActive()
            try {
                u = step(u, from)
                from = if (u.uploadUrl != null) u.received else null
                waiting = 0
                if (u.state == StationUpload.DONE) return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Stations.PinNeeded) {
                throw e
            } catch (e: Stations.Refused) {
                from = null
                when {
                    e.code == 503 -> {
                        val wait = (e.retryAfter ?: 20).coerceIn(2, 120)
                        note(u, "${u.station} is busy · trying again in $wait s")
                        delay(wait * 1000L)
                    }
                    e.error == "offset_mismatch" && e.received != null -> u = saveProgress(u, e.received).also { from = e.received }
                    e.error == "incomplete_piece" && e.received != null -> u = saveProgress(u, e.received).also { from = e.received }
                    // The station forgot this upload (24 h without a piece): start it again.
                    e.code == 404 && u.uploadUrl != null && e.error == "not_found" -> u = restartUpload(u)
                    else -> throw e
                }
            } catch (e: IOException) {
                from = null
                if (e is java.io.FileNotFoundException) throw e
                Stations.markStale()
                waiting++
                note(u, if (waiting <= 6) "Reconnecting to ${u.station}…" else "Waiting for ${u.station} to come back")
                delay(if (waiting <= 1) 2_000L else 5_000L)
            }
        }
    }

    private fun note(u: StationUpload, text: String?) = update(u.id) { it.copy(note = text) }

    private fun saveProgress(u: StationUpload, received: Long): StationUpload {
        val next = u.copy(received = received, note = null)
        update(u.id) { it.copy(received = received, note = null) }
        return next
    }

    private fun restartUpload(u: StationUpload): StationUpload {
        val next = u.copy(uploadUrl = null, received = 0)
        update(u.id) { it.copy(uploadUrl = null, received = 0) }
        return next
    }

    /**
     * One step: start the upload, or send the next piece. The next piece starts at [known] (what the station's
     * last reply said); after any break, the station is asked first: its count, never our own.
     */
    private fun step(u: StationUpload, known: Long?): StationUpload {
        val reply: StationUploadReply
        if (u.size < 0) throw Stations.Refused(0, "bad_size", null, null)
        if (u.uploadUrl == null) {
            val body = Stations.gson.toJson(mapOf("name" to u.name, "size" to u.size.coerceAtLeast(0), "folder" to u.folder)).toRequestBody(Stations.JSON)
            reply = Stations.call(u.key, "/api/uploads", { it.post(body) }) { r -> if (!r.isSuccessful) throw Stations.refused(r); parse(r) }
            // The biggest piece it takes (64 MB): fewer, bigger requests go faster through the tunnel.
            if (reply.maxPiece > 0) piece = reply.maxPiece.coerceAtMost(MAX_PIECE)
            val next = u.copy(uploadUrl = reply.uploadUrl, received = reply.received, note = null)
            update(u.id) { it.copy(uploadUrl = reply.uploadUrl, received = reply.received, note = null) }
            return next
        }
        val from = known ?: run {
            val status = Stations.call(u.key, u.uploadUrl) { r -> if (!r.isSuccessful) throw Stations.refused(r); parse(r) }
            if (status.done) return finished(u, status)
            if (status.maxPiece > 0) piece = status.maxPiece.coerceAtMost(MAX_PIECE)
            status.received
        }
        val size = u.size
        val end = if (size >= 0) minOf(from + piece, size) else from + piece
        if (size in 0..from) {
            // Everything is there but the last reply got lost; the status says done next time.
            return u.copy(received = from)
        }
        val body = PieceBody(Uri.parse(u.uri), from, end - from) { n -> main.post { sent[u.id] = from + n } }
        val range = "bytes $from-${end - 1}/" + if (size >= 0) "$size" else "*"
        val piece = Stations.call(u.key, u.uploadUrl, { it.put(body).header("Content-Range", range) }) { r ->
            if (!r.isSuccessful) throw Stations.refused(r)
            parse(r)
        }
        return if (piece.done) finished(u, piece) else saveProgress(u, piece.received)
    }

    private fun parse(r: okhttp3.Response): StationUploadReply =
        Stations.gson.fromJson(r.body?.charStream(), StationUploadReply::class.java) ?: StationUploadReply()

    /** Done: off the list; the file now shows in its folder like any other. */
    private fun finished(u: StationUpload, reply: StationUploadReply): StationUpload {
        val done = u.copy(state = StationUpload.DONE, received = u.size, note = null, finalName = reply.file?.name ?: u.name)
        main.post {
            items.removeAll { it.id == u.id }
            sent.remove(u.id)
            save()
            lastFinished = u.key to u.folder
            finishedTick++
            UploadService.update(app)
        }
        return done
    }

    /** One piece of the file, read straight from where it stands on the phone (opened again on any retry). */
    private class PieceBody(private val uri: Uri, private val start: Long, private val length: Long, private val onSent: (Long) -> Unit) : RequestBody() {
        override fun contentType(): MediaType = "application/octet-stream".toMediaType()
        override fun contentLength(): Long = length

        override fun writeTo(sink: BufferedSink) {
            val pfd = app.contentResolver.openFileDescriptor(uri, "r") ?: throw java.io.FileNotFoundException("Can't open the file")
            pfd.use {
                FileInputStream(it.fileDescriptor).use { input ->
                    // Jump straight there; a stream that can't (some cloud files) is read up to it.
                    if (runCatching { input.channel.position(start) }.isFailure) {
                        var skip = start
                        while (skip > 0) {
                            val n = input.skip(skip)
                            if (n <= 0) throw IOException("The file is shorter than it was")
                            skip -= n
                        }
                    }
                    val buf = ByteArray(256 * 1024)
                    var left = length
                    var done = 0L
                    var shown = 0L
                    while (left > 0) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) throw IOException("The file is shorter than it was")
                        sink.write(buf, 0, n)
                        left -= n
                        done += n
                        if (done - shown >= 512 * 1024 || left == 0L) {
                            shown = done
                            onSent(done)
                        }
                    }
                }
            }
        }
    }
}
