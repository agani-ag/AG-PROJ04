package com.agani.syncup.quickshare

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.agani.syncup.downloads.Downloads
import com.agani.syncup.downloads.FileKind
import com.agani.syncup.downloads.Files
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Where SyncUp Share is right now. */
enum class QsPhase {
    IDLE,
    /** Sender: looking for phones that are on Receive. */
    SEARCHING,
    /** Receiver: visible to nearby senders. */
    VISIBLE,
    /** Receiver: a sender asked — waiting for this user's Accept / Decline. */
    CONFIRM,
    /** Asked / accepted, waiting for the connection to come up (and, for the sender, the other side's Accept). */
    CONNECTING,
    TRANSFERRING,
    DONE,
    FAILED,
}

enum class QsRole { NONE, SEND, RECEIVE }

enum class QsFileState { WAITING, MOVING, SAVING, DONE, FAILED }

class NearbyPhone(val endpointId: String, val name: String)

/** A file picked to send: what the picker gave, resolved once. [size] is -1 when unknown. */
class PickedFile(val uri: Uri, val name: String, val size: Long, val mime: String)

/** One file in the current transfer (either direction). Compose-observable; changed on the main thread. */
class QsFile(val payloadId: Long, name: String, size: Long, mime: String) {
    var name by mutableStateOf(name)
    var size by mutableLongStateOf(size)
    var mime by mutableStateOf(mime)
    var done by mutableLongStateOf(0L)
    var state by mutableStateOf(QsFileState.WAITING)
    /** Receiver: where it was saved (content:// on Android 10+, a file path before). */
    var savedUri by mutableStateOf<String?>(null)
}

/**
 * A finished transfer, for "Recent" on the SyncUp Share page. A text send has [isText]; its words are in
 * [text] — null for a secret, which is never kept.
 */
class QsRecord(
    val incoming: Boolean, val peer: String, val count: Int, val bytes: Long, val at: Long, val ok: Boolean,
    val isText: Boolean = false, val text: String? = null, val secret: Boolean = false,
)

/**
 * SyncUp Share: send files straight from one SyncUp phone to another nearby, using Google Nearby
 * Connections (the same engine as Android's own Quick Share). Bluetooth finds the other phone; the
 * files themselves go over a direct Wi-Fi link. Nothing is uploaded — SyncUp's server never sees the
 * files, and no internet or mobile data is used.
 *
 * Flow: the receiver taps Receive (visible only while that page is open). The sender picks files and
 * taps the receiver in the list; both phones show the same 4-digit code; the receiver accepts. The
 * sender then sends a small list of what's coming (names, sizes), then each file. Received files land
 * in Download / SyncUp / <kind> and in SyncUp's Downloads list, like any other download.
 *
 * Text or link: the same connection, but the text goes as one small message (no files) and shows up on
 * the receiver's screen with Copy / Open link / Call…. A secret is hidden there until tapped and is
 * never kept in Recent.
 *
 * All public functions are for the main thread (Nearby delivers its callbacks there too).
 */
object QuickShare {
    private const val SERVICE_ID = "com.agani.syncup.quickshare"
    /** Separates name / file count / total size in the sender's connection name. */
    private const val SEP = '\u001F'
    private const val PREFS = "quick_share"
    private const val MAX_HISTORY = 30
    /** Texts kept in Recent (on this phone only). */
    private const val MAX_TEXTS = 20
    /** Longest text that can be sent. */
    const val MAX_TEXT = 5_000

    private val strategy = Strategy.P2P_POINT_TO_POINT
    private lateinit var app: Context
    private val client: ConnectionsClient by lazy { Nearby.getConnectionsClient(app) }
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    var phase by mutableStateOf(QsPhase.IDLE)
        private set
    var role by mutableStateOf(QsRole.NONE)
        private set
    /** What other phones see. Defaults to the phone's own name ("Galaxy A32"). */
    var myName by mutableStateOf("")
        private set
    val nearby = mutableStateListOf<NearbyPhone>()
    var peerName by mutableStateOf("")
        private set
    /** The 4 digits both phones show, so people can check they're connected to each other. */
    var code by mutableStateOf("")
        private set
    /** Receiver: what the sender says is coming, shown before Accept. */
    var offerCount by mutableIntStateOf(0)
        private set
    var offerBytes by mutableLongStateOf(0L)
        private set
    val files = mutableStateListOf<QsFile>()
    var error by mutableStateOf<String?>(null)
        private set
    /** Bytes per second right now (whole transfer). */
    var speed by mutableLongStateOf(0L)
        private set
    /** Sender: the files picked, waiting to go. */
    val picked = mutableStateListOf<PickedFile>()
    /** Sender: the text picked to send instead of files (Text or link); null when sending files. */
    var outText by mutableStateOf<String?>(null)
        private set
    var outSecret by mutableStateOf(false)
        private set
    /** This transfer is a text, not files (both sides). */
    var textMode by mutableStateOf(false)
        private set
    /** The text that went (sender) or arrived (receiver). */
    var text by mutableStateOf("")
        private set
    var secret by mutableStateOf(false)
        private set
    /** Receiver: how long the offered text is, shown before Accept. */
    var offerChars by mutableIntStateOf(0)
        private set
    /** Files shared into SyncUp from another app ("Share → SyncUp Share"), until the page picks them up. */
    var sharedIn by mutableStateOf<List<Uri>>(emptyList())
    /** Text shared into SyncUp from another app, until the page picks it up (it opens Send text with it). */
    var sharedText by mutableStateOf<String?>(null)
    val history = mutableStateListOf<QsRecord>()
    /** The SyncUp Share page is on screen (no "received" notification needed then). */
    var screenVisible = false
    /** Set by the Quick Settings tile: the page should go straight to Receive. */
    var receiveRequested by mutableStateOf(false)
    /** The SyncUp Share tile is in the phone's Quick Settings, so the page stops offering to add it. */
    var tileAdded by mutableStateOf(false)
        private set

    private var ready = false
    private var peerId: String? = null
    /** Receiver: file payloads by id, needed to read the file once it's complete. */
    private val incoming = HashMap<Long, Payload>()
    /** Sender: open files, closed when each one is done. */
    private val openFiles = HashMap<Long, ParcelFileDescriptor>()
    private var expected = 0
    private var manifestSeen = false
    private var lastSpeedAt = 0L
    private var lastSpeedBytes = 0L
    private var lastNotifyAt = 0L
    /** Sender: the text's payload, to know when it got there. */
    private var textPayloadId = 0L

    val busy: Boolean get() = phase == QsPhase.CONNECTING || phase == QsPhase.TRANSFERRING
    val totalBytes: Long get() = files.sumOf { it.size.coerceAtLeast(0) }
    val doneBytes: Long get() = files.sumOf { it.done }

    fun init(context: Context) {
        if (ready) return
        ready = true
        app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        myName = prefs.getString("name", null)?.takeIf { it.isNotBlank() } ?: deviceName()
        tileAdded = prefs.getBoolean("tile_added", false)
        history.addAll(loadHistory())
    }

    /** The tile was added to / removed from Quick Settings. Callable before [init] (the tile can run alone). */
    fun setTileAdded(context: Context, added: Boolean) {
        tileAdded = added
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("tile_added", added).commit()
    }

    /** The one automatic "Add tile?" offer (on first launch) was already made — never nag twice. */
    fun tileOffered(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("tile_offered", false)

    fun markTileOffered(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("tile_offered", true).commit()
    }

    fun rename(name: String) {
        val n = clean(name).ifBlank { deviceName() }
        myName = n
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("name", n).apply()
    }

    // ------------------------------------------------------------------ permissions
    /** What Nearby needs on this Android version. [receiving] adds storage on Android 9 and older. */
    fun requiredPermissions(receiving: Boolean): List<String> = buildList {
        when {
            Build.VERSION.SDK_INT >= 33 -> addAll(
                listOf(
                    Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES,
                ),
            )
            Build.VERSION.SDK_INT >= 31 -> addAll(
                listOf(
                    Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION,
                ),
            )
            else -> add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (receiving && Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    fun missingPermissions(receiving: Boolean): List<String> =
        requiredPermissions(receiving).filter { ContextCompat.checkSelfPermission(app, it) != PackageManager.PERMISSION_GRANTED }

    // ------------------------------------------------------------------ sending
    /** Read names and sizes of what was picked. Off the main thread is fine; returns the ones it could read. */
    fun describe(uris: List<Uri>): List<PickedFile> = uris.mapNotNull { uri ->
        runCatching {
            var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
            var size = -1L
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.takeIf { it.isNotBlank() }?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
            val mime = app.contentResolver.getType(uri) ?: Files.mimeFor(name, null)
            PickedFile(uri, Files.cleanName(name), size, mime)
        }.getOrNull()
    }

    fun setPicked(list: List<PickedFile>) {
        outText = null
        outSecret = false
        picked.clear()
        picked.addAll(list)
    }

    /** Send [value] (cut to [MAX_TEXT]) instead of files. */
    fun setText(value: String, isSecret: Boolean) {
        picked.clear()
        outText = value.take(MAX_TEXT)
        outSecret = isSecret
    }

    fun removePicked(p: PickedFile) {
        picked.remove(p)
    }

    /** Look for phones that are on Receive. */
    fun startSending() {
        if (picked.isEmpty() && outText.isNullOrBlank()) return
        stopRadios()
        clearTransfer()
        textMode = !outText.isNullOrBlank()
        role = QsRole.SEND
        moveTo(QsPhase.SEARCHING)
        client.startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { e -> if (!isAlready(e)) fail(friendly(e)) }
    }

    /** Ask [phone] to take the picked files. */
    fun sendTo(phone: NearbyPhone) {
        if (phase != QsPhase.SEARCHING) return
        peerId = phone.endpointId
        peerName = phone.name
        moveTo(QsPhase.CONNECTING)
        client.stopDiscovery()
        // The receiver sees what's coming before Accept: "T" + length + secret for a text, else count + size.
        val offer = if (textMode) {
            "T$SEP${outText.orEmpty().length}$SEP${if (outSecret) 1 else 0}"
        } else {
            "${picked.size}$SEP${picked.sumOf { it.size.coerceAtLeast(0) }}"
        }
        client.requestConnection("$myName$SEP$offer", phone.endpointId, lifecycle)
            .addOnFailureListener { e -> fail(friendly(e)) }
    }

    /** After a failed or declined send: look again, same files. */
    fun retrySend() {
        if (picked.isNotEmpty() || !outText.isNullOrBlank()) startSending() else reset()
    }

    // ------------------------------------------------------------------ receiving
    /** Be visible to nearby senders until [reset] (or the page closes). */
    fun startReceiving() {
        stopRadios()
        clearTransfer()
        role = QsRole.RECEIVE
        moveTo(QsPhase.VISIBLE)
        advertise()
    }

    private fun advertise() {
        client.startAdvertising(myName, SERVICE_ID, lifecycle, AdvertisingOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { e -> if (!isAlready(e)) fail(friendly(e)) }
    }

    fun accept() {
        val id = peerId ?: return
        if (phase != QsPhase.CONFIRM) return
        client.stopAdvertising()
        moveTo(QsPhase.CONNECTING)
        client.acceptConnection(id, payloads).addOnFailureListener { e -> fail(friendly(e)) }
    }

    fun decline() {
        val id = peerId ?: return
        client.rejectConnection(id)
        peerId = null
        peerName = ""
        code = ""
        moveTo(QsPhase.VISIBLE)
    }

    // ------------------------------------------------------------------ stopping
    /** Stop whatever is going on (a transfer is cancelled) and go back to the start. */
    fun cancel() {
        if (phase == QsPhase.TRANSFERRING) {
            files.filter { it.state == QsFileState.WAITING || it.state == QsFileState.MOVING }.forEach {
                runCatching { client.cancelPayload(it.payloadId) }
            }
            record(ok = false)
        }
        reset()
    }

    fun reset() {
        stopRadios()
        clearTransfer()
        role = QsRole.NONE
        moveTo(QsPhase.IDLE)
    }

    /** The page went away: stay visible / searching only while it's open; a transfer carries on. */
    fun onScreenLeft() {
        screenVisible = false
        if (!busy) reset()
    }

    private fun stopRadios() {
        runCatching { client.stopAdvertising() }
        runCatching { client.stopDiscovery() }
        runCatching { client.stopAllEndpoints() }
        nearby.clear()
    }

    private fun clearTransfer() {
        openFiles.values.forEach { runCatching { it.close() } }
        openFiles.clear()
        incoming.clear()
        files.clear()
        peerId = null
        peerName = ""
        code = ""
        offerCount = 0
        offerBytes = 0
        expected = 0
        manifestSeen = false
        textMode = false
        text = ""
        secret = false
        offerChars = 0
        textPayloadId = 0
        error = null
        speed = 0
        lastSpeedAt = 0
        lastSpeedBytes = 0
    }

    private fun moveTo(p: QsPhase) {
        val wasBusy = busy
        phase = p
        if (busy && !wasBusy) QuickShareService.start(app)
        if (!busy && wasBusy) QuickShareService.stop(app)
        if (busy) QuickShareService.update(app)
        SyncUpShareTileService.refresh(app)
    }

    private fun fail(message: String) {
        error = message
        stopRadios()
        openFiles.values.forEach { runCatching { it.close() } }
        openFiles.clear()
        moveTo(QsPhase.FAILED)
    }

    // ------------------------------------------------------------------ Nearby callbacks
    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID || nearby.any { it.endpointId == endpointId }) return
            nearby.add(NearbyPhone(endpointId, info.endpointName.ifBlank { "A nearby phone" }))
        }

        override fun onEndpointLost(endpointId: String) {
            nearby.removeAll { it.endpointId == endpointId }
        }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (info.isIncomingConnection) {
                // Only one sender at a time: a second request while busy is turned away.
                if (role != QsRole.RECEIVE || phase != QsPhase.VISIBLE) {
                    client.rejectConnection(endpointId)
                    return
                }
                code = info.authenticationDigits
                peerId = endpointId
                val parts = info.endpointName.split(SEP)
                peerName = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "A nearby phone"
                if (parts.getOrNull(1) == "T") {
                    textMode = true
                    offerChars = parts.getOrNull(2)?.toIntOrNull() ?: 0
                    secret = parts.getOrNull(3) == "1"
                } else {
                    offerCount = parts.getOrNull(1)?.toIntOrNull() ?: 0
                    offerBytes = parts.getOrNull(2)?.toLongOrNull() ?: 0
                }
                moveTo(QsPhase.CONFIRM)
            } else {
                if (endpointId != peerId) return
                code = info.authenticationDigits
                // We asked: accept our side; the transfer starts once the receiver accepts theirs.
                client.acceptConnection(endpointId, payloads).addOnFailureListener { e -> fail(friendly(e)) }
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (endpointId != peerId) return
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    moveTo(QsPhase.TRANSFERRING)
                    if (role == QsRole.SEND) {
                        if (textMode) sendText(endpointId) else sendFiles(endpointId)
                    }
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED ->
                    if (role == QsRole.SEND) fail("$peerName declined.") else startReceiving()
                else -> fail("Couldn't connect to $peerName. Keep the phones close together and try again.")
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (endpointId != peerId) return
            peerId = null
            if (phase == QsPhase.TRANSFERRING || phase == QsPhase.CONNECTING) {
                if (files.isNotEmpty() && files.all { it.state == QsFileState.DONE }) {
                    finish()
                } else if (role == QsRole.RECEIVE && files.any { it.state == QsFileState.SAVING }) {
                    // Everything arrived; the last files are still being saved here — finish then.
                } else {
                    record(ok = false)
                    fail("The connection to $peerName was lost.")
                }
            }
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> payload.asBytes()?.let { if (textMode) readText(it) else readManifest(it) }
                Payload.Type.FILE -> {
                    incoming[payload.id] = payload
                    fileFor(payload.id, payload.asFile()?.size ?: -1)
                }
                else -> Unit
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (textMode && role == QsRole.SEND && update.payloadId == textPayloadId) {
                when (update.status) {
                    PayloadTransferUpdate.Status.SUCCESS -> finish()
                    PayloadTransferUpdate.Status.IN_PROGRESS -> Unit
                    else -> fail("The text didn't get to $peerName. Try again.")
                }
                return
            }
            val f = files.firstOrNull { it.payloadId == update.payloadId } ?: return
            if (f.size <= 0 && update.totalBytes > 0) f.size = update.totalBytes
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    f.done = update.bytesTransferred
                    f.state = QsFileState.MOVING
                }
                PayloadTransferUpdate.Status.SUCCESS -> {
                    f.done = f.size.coerceAtLeast(update.bytesTransferred)
                    if (role == QsRole.RECEIVE) {
                        save(f)
                    } else {
                        f.state = QsFileState.DONE
                        openFiles.remove(f.payloadId)?.let { runCatching { it.close() } }
                        checkFinished()
                    }
                }
                else -> { // FAILURE / CANCELED
                    f.state = QsFileState.FAILED
                    openFiles.remove(f.payloadId)?.let { runCatching { it.close() } }
                    incoming.remove(f.payloadId)
                    checkFinished()
                }
            }
            tick()
        }
    }

    // ------------------------------------------------------------------ transfer
    private fun sendFiles(endpointId: String) {
        val list = picked.toList()
        val manifest = JSONArray()
        val ready = ArrayList<Payload>()
        for (p in list) {
            val pfd = runCatching { app.contentResolver.openFileDescriptor(p.uri, "r") }.getOrNull()
            if (pfd == null) {
                files.add(QsFile(-1L - files.size, p.name, p.size, p.mime).apply { state = QsFileState.FAILED })
                continue
            }
            val payload = Payload.fromFile(pfd)
            val size = p.size.takeIf { it > 0 } ?: pfd.statSize
            openFiles[payload.id] = pfd
            files.add(QsFile(payload.id, p.name, size, p.mime))
            manifest.put(JSONObject().put("id", payload.id).put("name", p.name).put("size", size).put("mime", p.mime))
            ready.add(payload)
        }
        if (ready.isEmpty()) {
            fail("Couldn't open the files to send.")
            return
        }
        val head = JSONObject().put("v", 1).put("from", myName).put("files", manifest).toString()
        client.sendPayload(endpointId, Payload.fromBytes(head.toByteArray(Charsets.UTF_8)))
        for (payload in ready) {
            client.sendPayload(endpointId, payload).addOnFailureListener { e -> fail(friendly(e)) }
        }
        lastSpeedAt = SystemClock.elapsedRealtime()
    }

    /** Sender: the text as one small message — it arrives whole, there's nothing to track but "got there". */
    private fun sendText(endpointId: String) {
        val t = outText?.takeIf { it.isNotBlank() } ?: return fail("There's no text to send.")
        val body = JSONObject().put("v", 1).put("from", myName).put("text", t).put("secret", outSecret).toString().toByteArray(Charsets.UTF_8)
        if (body.size > ConnectionsClient.MAX_BYTES_DATA_SIZE) return fail("That text is too long to send.")
        val payload = Payload.fromBytes(body)
        textPayloadId = payload.id
        text = t
        secret = outSecret
        client.sendPayload(endpointId, payload).addOnFailureListener { e -> fail(friendly(e)) }
    }

    /** Receiver: the text arrived — that's the whole transfer. */
    private fun readText(bytes: ByteArray) {
        val o = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return
        if (!o.has("text")) return
        text = o.optString("text").take(MAX_TEXT)
        secret = o.optBoolean("secret", secret)
        finish()
    }

    /** Receiver: the sender's list of what's coming. Fills in names on files that already started. */
    private fun readManifest(bytes: ByteArray) {
        val o = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return
        val arr = o.optJSONArray("files") ?: return
        manifestSeen = true
        expected = arr.length()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val f = fileFor(e.optLong("id"), e.optLong("size", -1))
            f.name = Files.cleanName(e.optString("name").ifBlank { f.name })
            f.mime = e.optString("mime").ifBlank { Files.mimeFor(f.name, null) }
        }
        lastSpeedAt = SystemClock.elapsedRealtime()
    }

    private fun fileFor(payloadId: Long, size: Long): QsFile =
        files.firstOrNull { it.payloadId == payloadId }
            ?: QsFile(payloadId, "File ${files.size + 1}", size, "application/octet-stream").also { files.add(it) }

    /** Receiver: a file arrived in full — move it into Download / SyncUp and list it in Downloads. */
    private fun save(f: QsFile) {
        val payload = incoming.remove(f.payloadId)
        if (payload == null) {
            f.state = QsFileState.FAILED
            checkFinished()
            return
        }
        f.state = QsFileState.SAVING
        val name = f.name
        val mime = Files.mimeFor(name, f.mime)
        val from = peerName
        io.launch {
            val saved = runCatching { publish(payload, name, mime) }.getOrNull()
            main.post {
                if (saved == null) {
                    f.state = QsFileState.FAILED
                } else {
                    f.name = saved.first
                    f.savedUri = saved.second
                    f.state = QsFileState.DONE
                    Downloads.recordSaved(saved.first, mime, f.size.coerceAtLeast(0), saved.second, "SyncUp Share · from $from", work = false, incognito = false)
                }
                checkFinished()
            }
        }
    }

    /** Copy Nearby's temporary file into the shared Downloads folder. Background thread. Returns (final name, address). Also used by a group's Share files. */
    internal fun publish(payload: Payload, name: String, mime: String): Pair<String, String> {
        val pf = payload.asFile() ?: error("not a file")
        val kind = FileKind.of(name, mime)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val src = pf.asUri() ?: error("no file")
            val resolver = app.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/SyncUp/${kind.folder}")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val dest = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("can't create the file")
            try {
                resolver.openInputStream(src)!!.use { input ->
                    resolver.openOutputStream(dest)!!.use { out -> input.copyTo(out, 256 * 1024) }
                }
                resolver.update(dest, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(dest, null, null)
                throw e
            }
            runCatching { resolver.delete(src, null, null) } // Nearby's temporary copy
            var real = name
            resolver.query(dest, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { real = it }
            }
            return real to dest.toString()
        } else {
            @Suppress("DEPRECATION")
            val src = pf.asJavaFile() ?: error("no file")
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SyncUp/${kind.folder}").apply { mkdirs() }
            val dest = unusedFile(dir, name)
            if (!src.renameTo(dest)) {
                src.copyTo(dest)
                src.delete()
            }
            MediaScannerConnection.scanFile(app, arrayOf(dest.absolutePath), arrayOf(mime), null)
            return dest.name to dest.absolutePath
        }
    }

    private fun checkFinished() {
        // Stays TRANSFERRING even if the sender hangs up while the last files are still being saved here.
        if (phase != QsPhase.TRANSFERRING || files.isEmpty()) return
        if (role == QsRole.RECEIVE && (!manifestSeen || files.size < expected)) return
        if (files.any { it.state != QsFileState.DONE && it.state != QsFileState.FAILED }) return
        if (files.all { it.state == QsFileState.DONE }) {
            finish()
        } else {
            val ok = files.count { it.state == QsFileState.DONE }
            record(ok = false)
            stopRadios()
            error = "$ok of ${files.size} files ${if (role == QsRole.SEND) "were sent" else "arrived"}."
            moveTo(QsPhase.FAILED)
        }
    }

    private fun finish() {
        if (phase == QsPhase.DONE) return
        record(ok = true)
        val count = files.size
        val from = peerName
        speed = 0
        moveTo(QsPhase.DONE)
        // Give the other phone a moment to see the last bytes, then hang up.
        val id = peerId
        main.postDelayed({ id?.let { runCatching { client.disconnectFromEndpoint(it) } } }, 1_500)
        if (role == QsRole.RECEIVE && !screenVisible) {
            if (textMode) QuickShareService.notifyText(app, from, secret) else QuickShareService.notifyReceived(app, from, count)
        }
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val bytes = doneBytes
        if (lastSpeedAt == 0L) {
            lastSpeedAt = now
            lastSpeedBytes = bytes
        } else if (now - lastSpeedAt >= 1_000) {
            speed = ((bytes - lastSpeedBytes) * 1000 / (now - lastSpeedAt)).coerceAtLeast(0)
            lastSpeedAt = now
            lastSpeedBytes = bytes
        }
        if (now - lastNotifyAt >= 700) {
            lastNotifyAt = now
            QuickShareService.update(app)
            SyncUpShareTileService.redraw()
        }
    }

    // ------------------------------------------------------------------ history
    private fun record(ok: Boolean) {
        if (peerName.isBlank()) return
        val incoming = role == QsRole.RECEIVE
        val now = System.currentTimeMillis()
        val r = if (textMode) {
            // A text that didn't get there isn't worth a row; a secret's words are never kept.
            if (!ok || text.isEmpty()) return
            QsRecord(incoming, peerName, 0, 0, now, true, isText = true, text = if (secret) null else text, secret = secret)
        } else {
            if (files.isEmpty()) return
            QsRecord(incoming, peerName, files.size, totalBytes, now, ok)
        }
        history.add(0, r)
        val oldTexts = history.filter { it.isText }.drop(MAX_TEXTS)
        if (oldTexts.isNotEmpty()) history.removeAll(oldTexts)
        while (history.size > MAX_HISTORY) history.removeAt(history.lastIndex)
        val a = JSONArray()
        history.forEach {
            val o = JSONObject().put("in", it.incoming).put("peer", it.peer).put("n", it.count).put("b", it.bytes).put("at", it.at).put("ok", it.ok)
            if (it.isText) {
                o.put("k", "text").put("s", it.secret)
                it.text?.let { t -> o.put("x", t) }
            }
            a.put(o)
        }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("history", a.toString()).commit()
    }

    private fun loadHistory(): List<QsRecord> = runCatching {
        val a = JSONArray(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("history", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            QsRecord(
                o.optBoolean("in"), o.optString("peer"), o.optInt("n"), o.optLong("b"), o.optLong("at"), o.optBoolean("ok", true),
                isText = o.optString("k") == "text", text = if (o.has("x")) o.optString("x") else null, secret = o.optBoolean("s"),
            )
        }
    }.getOrDefault(emptyList())

    fun clearHistory() {
        history.clear()
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("history").commit()
    }

    // ------------------------------------------------------------------ helpers
    private fun deviceName(): String {
        val n = if (Build.VERSION.SDK_INT >= 25) {
            runCatching { Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        } else {
            null
        }
        return clean(n?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Android phone")
    }

    private fun clean(name: String): String = name.replace(SEP, ' ').replace(Regex("""[\x00-\x1f]"""), " ").trim().take(40)

    private fun isAlready(e: Exception): Boolean {
        val c = (e as? ApiException)?.statusCode ?: return false
        return c == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING || c == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING
    }

    /** A Nearby failure in words people can act on. */
    private fun friendly(e: Exception): String {
        val code = (e as? ApiException)?.statusCode
            ?: return "Something went wrong. Make sure Bluetooth and Wi-Fi are on, then try again."
        val name = runCatching { ConnectionsStatusCodes.getStatusCodeString(code) }.getOrDefault("")
        return when {
            name.contains("MISSING_PERMISSION") -> "SyncUp needs the Nearby devices permission for SyncUp Share."
            name.contains("BLUETOOTH") || name.contains("RADIO") -> "Turn on Bluetooth and Wi-Fi, then try again."
            name.contains("ENDPOINT_UNKNOWN") || name.contains("NOT_CONNECTED") -> "That phone isn't nearby any more."
            name.contains("API_NOT_CONNECTED") || code == 17 -> "SyncUp Share needs Google Play services on this phone."
            Build.VERSION.SDK_INT <= 32 -> "Couldn't start SyncUp Share (code $code). Turn on Bluetooth, Wi-Fi and Location, then try again."
            else -> "Couldn't start SyncUp Share (code $code). Turn on Bluetooth and Wi-Fi, then try again."
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
}
