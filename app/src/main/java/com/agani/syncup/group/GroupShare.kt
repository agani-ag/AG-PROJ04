package com.agani.syncup.group

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.agani.syncup.downloads.Downloads
import com.agani.syncup.downloads.Files
import com.agani.syncup.quickshare.QuickShare
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

enum class GroupFileState { MOVING, ARRIVED, SAVING, SAVED, SENT, FAILED }

/** A file shared in the group: one this phone sent, or one that came for it. */
class GroupFile(val key: Long, name: String, val size: Long, val mime: String, val from: String, val to: String, val incoming: Boolean) {
    var name by mutableStateOf(name)
    var state by mutableStateOf(GroupFileState.MOVING)
    var done by mutableLongStateOf(0L)
    /** Sent: how many phones have it / should get it. */
    var gotIt by mutableIntStateOf(0)
    var expected by mutableIntStateOf(1)
    var savedUri: String? = null
}

/**
 * A group's Share files mode: anyone sends photos and files to everyone in the group (or to picked
 * people). Files go through the host's phone (it passes them on). What arrives waits in "Received"
 * until you tap Save — then it goes to Download / SyncUp like SyncUp Share's files. Transfers carry on
 * if the group switches to another mode; unsaved files are deleted when the group ends.
 *
 * Main thread for everything public.
 */
object GroupShare {
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ready = false

    // ------------------------------------------------------------------ what the screen shows
    var active by mutableStateOf(false)
        private set
    /** Files this phone sent, newest first. */
    val sent = mutableStateListOf<GroupFile>()
    /** Files that came for this phone, newest first. */
    val received = mutableStateListOf<GroupFile>()
    var notice by mutableStateOf<String?>(null)

    // ------------------------------------------------------------------ inside
    /** What's coming: a file's name, size, sender and who it's for (the message before the file). */
    private class Offer(val name: String, val size: Long, val mime: String, val from: String, val to: List<String>, val fromEndpoint: String)

    private val offers = HashMap<Long, Offer>()
    /** Files arriving / arrived here (Nearby's temporary copies), by payload id. */
    private val incoming = HashMap<Long, Payload>()
    /** Files going out: their open files, by payload id. */
    private val openFiles = HashMap<Long, ParcelFileDescriptor>()
    /** Host: a file passed on → the payload it came in (its temporary copy goes when all are done). */
    private val forwards = HashMap<Long, Long>()
    private val forwardsLeft = HashMap<Long, Int>()
    /** Host: arrived files that are only passing through (not for the host). */
    private val passThrough = HashSet<Long>()

    private val myName get() = GroupLink.myName

    fun init(context: Context) {
        if (ready) return
        ready = true
        app = context.applicationContext
        GroupLink.init(app)
        GroupLink.register(GroupMode.SHARE, channel)
    }

    /** The others in the group, for picking who gets a file. */
    fun others(): List<String> = GroupLink.peopleNames.filter { it != myName }

    // ================================================================== sending
    /** Send these files to [to] (names; empty = everyone). */
    fun send(uris: List<Uri>, to: List<String>) {
        if (!GroupLink.inGroup) return
        val picked = QuickShare.describe(uris)
        if (picked.isEmpty()) {
            notice = "Couldn't open the files."
            return
        }
        val label = if (to.isEmpty()) "everyone" else to.joinToString(", ")
        for (f in picked) {
            val pfd = runCatching { app.contentResolver.openFileDescriptor(f.uri, "r") }.getOrNull() ?: continue
            val payload = Payload.fromFile(pfd)
            val size = f.size.takeIf { it > 0 } ?: pfd.statSize
            openFiles[payload.id] = pfd
            val gf = GroupFile(payload.id, f.name, size, f.mime, from = myName, to = label, incoming = false)
            sent.add(0, gf)
            val offer = JSONObject().put("t", "file").put("id", payload.id).put("name", f.name).put("size", size).put("mime", f.mime)
                .put("from", myName).put("to", JSONArray(to))
            if (GroupLink.isHost) {
                val eps = recipients(to, except = null)
                gf.expected = eps.size.coerceAtLeast(1)
                if (eps.isEmpty()) {
                    gf.state = GroupFileState.FAILED
                    continue
                }
                GroupLink.send(eps, json(offer))
                if (!GroupLink.send(eps, payload)) gf.state = GroupFileState.FAILED
            } else {
                // Through the host, which passes it on.
                gf.expected = if (to.isEmpty()) (GroupLink.people.size - 1).coerceAtLeast(1) else to.size
                GroupLink.sendToHost(json(offer))
                if (!GroupLink.sendToHost(payload)) gf.state = GroupFileState.FAILED
            }
        }
    }

    /** Host: the guests' endpoints among [to] (empty = all), leaving out [except]. */
    private fun recipients(to: List<String>, except: String?): List<String> =
        GroupLink.guestList().filter { (ep, _, name) -> ep != except && (to.isEmpty() || name in to) }.map { it.first }

    // ================================================================== saving
    fun save(f: GroupFile) {
        if (!f.incoming || f.state != GroupFileState.ARRIVED) return
        val payload = incoming[f.key] ?: return
        f.state = GroupFileState.SAVING
        val mime = Files.mimeFor(f.name, f.mime)
        io.launch {
            val saved = runCatching { QuickShare.publish(payload, f.name, mime) }.getOrNull()
            main.post {
                if (saved == null) {
                    f.state = GroupFileState.FAILED
                    notice = "Couldn't save “${f.name}”."
                } else {
                    incoming.remove(f.key)
                    f.name = saved.first
                    f.savedUri = saved.second
                    f.state = GroupFileState.SAVED
                    Downloads.recordSaved(saved.first, mime, f.size.coerceAtLeast(0), saved.second, "Group · from ${f.from}", work = false, incognito = false)
                }
            }
        }
    }

    fun saveAll() = received.filter { it.state == GroupFileState.ARRIVED }.forEach { save(it) }

    // ================================================================== the group's share channel
    internal val channel = object : GroupChannel {
        override fun onReceive(endpointId: String, body: ByteArray, arrivedNs: Long) {
            val o = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull() ?: return
            if (o.optString("t") != "file") return
            val to = o.optJSONArray("to")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty()
            val offer = Offer(
                Files.cleanName(o.optString("name").ifBlank { "File" }), o.optLong("size", -1), o.optString("mime"),
                o.optString("from"), to, endpointId,
            )
            offers[o.optLong("id")] = offer
            // The file may already be on its way.
            incoming[o.optLong("id")]?.let { arriving(o.optLong("id"), it, offer) }
        }

        override fun onFile(endpointId: String, payload: Payload) {
            incoming[payload.id] = payload
            val offer = offers[payload.id] ?: Offer("File", payload.asFile()?.size ?: -1, "", GroupLink.nameOf(endpointId) ?: "Someone", emptyList(), endpointId)
            offers[payload.id] = offer
            arriving(payload.id, payload, offer)
        }

        override fun onTransfer(endpointId: String, update: PayloadTransferUpdate) {
            val id = update.payloadId
            // Going out (sent from here, or passed on by the host).
            if (openFiles.containsKey(id)) {
                val f = sent.firstOrNull { it.key == id } ?: forwards[id]?.let { null }
                when (update.status) {
                    PayloadTransferUpdate.Status.IN_PROGRESS -> f?.done = update.bytesTransferred
                    PayloadTransferUpdate.Status.SUCCESS -> {
                        if (f != null) {
                            f.gotIt++
                            f.done = f.size
                            if (GroupLink.isHost) {
                                if (f.gotIt >= f.expected) closeOut(id)
                                f.state = if (f.gotIt >= f.expected) GroupFileState.SENT else GroupFileState.MOVING
                            } else {
                                // A guest's send reached the host, which passes it on.
                                f.gotIt = f.expected
                                f.state = GroupFileState.SENT
                                closeOut(id)
                            }
                        }
                        forwards[id]?.let { forwardDone(id, it) }
                    }
                    else -> {
                        f?.state = GroupFileState.FAILED
                        forwards[id]?.let { forwardDone(id, it) }
                        if (f != null) closeOut(id)
                    }
                }
                return
            }
            // Coming in.
            if (!incoming.containsKey(id)) return
            val f = received.firstOrNull { it.key == id }
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> f?.done = update.bytesTransferred
                PayloadTransferUpdate.Status.SUCCESS -> {
                    f?.done = f?.size?.coerceAtLeast(update.bytesTransferred) ?: 0
                    f?.state = GroupFileState.ARRIVED
                    if (GroupLink.isHost) passOn(id)
                    GroupService.refresh(app)
                }
                else -> {
                    f?.state = GroupFileState.FAILED
                    incoming.remove(id)
                }
            }
        }

        override fun activate(resume: Boolean) {
            active = true
        }

        override fun deactivate() {
            // Files keep moving (the link stays); only the screen goes.
            active = false
        }

        override fun reset() {
            active = false
            openFiles.values.forEach { runCatching { it.close() } }
            openFiles.clear()
            // Arrived but not saved: Nearby's temporary copies go.
            incoming.values.forEach { deleteTemp(it) }
            incoming.clear()
            offers.clear()
            forwards.clear()
            forwardsLeft.clear()
            passThrough.clear()
            sent.clear()
            received.clear()
        }

        override fun statusText(): String {
            val waiting = received.count { it.state == GroupFileState.ARRIVED }
            val moving = (sent + received).count { it.state == GroupFileState.MOVING }
            return when {
                moving > 0 -> "Sharing $moving file${if (moving == 1) "" else "s"}"
                waiting > 0 -> "$waiting file${if (waiting == 1) "" else "s"} to save"
                else -> "Share files"
            }
        }
    }

    /** A file started coming in: list it here if it's for this phone. */
    private fun arriving(id: Long, payload: Payload, offer: Offer) {
        val forMe = !GroupLink.isHost || offer.to.isEmpty() || myName in offer.to
        if (GroupLink.isHost && !forMe) passThrough.add(id)
        if (!forMe || received.any { it.key == id }) return
        received.add(0, GroupFile(id, offer.name, offer.size.takeIf { it > 0 } ?: (payload.asFile()?.size ?: -1), offer.mime, offer.from, "you", incoming = true))
    }

    /** Host: a guest's file arrived — pass it on to the others it's for. */
    private fun passOn(id: Long) {
        val offer = offers[id] ?: return
        val payload = incoming[id] ?: return
        val eps = recipients(offer.to, except = offer.fromEndpoint)
        if (eps.isEmpty()) {
            if (id in passThrough) {
                deleteTemp(payload)
                incoming.remove(id)
            }
            return
        }
        val pfd = openTemp(payload) ?: return
        val out = Payload.fromFile(pfd)
        openFiles[out.id] = pfd
        forwards[out.id] = id
        forwardsLeft[id] = eps.size
        val msg = JSONObject().put("t", "file").put("id", out.id).put("name", offer.name).put("size", offer.size).put("mime", offer.mime)
            .put("from", offer.from).put("to", JSONArray(offer.to))
        GroupLink.send(eps, json(msg))
        GroupLink.send(eps, out)
    }

    /** Host: one phone got a passed-on file; when all have, its temporary copy can go (unless it's also for the host). */
    private fun forwardDone(outId: Long, srcId: Long) {
        val left = (forwardsLeft[srcId] ?: 1) - 1
        if (left > 0) {
            forwardsLeft[srcId] = left
            return
        }
        forwardsLeft.remove(srcId)
        forwards.remove(outId)
        closeOut(outId)
        if (srcId in passThrough) {
            passThrough.remove(srcId)
            incoming.remove(srcId)?.let { deleteTemp(it) }
        }
    }

    private fun closeOut(id: Long) {
        openFiles.remove(id)?.let { runCatching { it.close() } }
    }

    @Suppress("DEPRECATION")
    private fun openTemp(payload: Payload): ParcelFileDescriptor? = runCatching {
        val pf = payload.asFile() ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pf.asUri()?.let { app.contentResolver.openFileDescriptor(it, "r") }
        else pf.asJavaFile()?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun deleteTemp(payload: Payload) {
        runCatching {
            val pf = payload.asFile() ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pf.asUri()?.let { app.contentResolver.delete(it, null, null) }
            else pf.asJavaFile()?.delete()
        }
    }

    private fun json(o: JSONObject) = GroupLink.payload(GroupMode.SHARE, o.toString().toByteArray(Charsets.UTF_8))
}
