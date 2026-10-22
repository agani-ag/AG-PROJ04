package com.agani.syncup.decide

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.agani.syncup.group.GroupLink
import com.agani.syncup.group.GroupPhase
import com.agani.syncup.group.GroupRole
import org.json.JSONObject
import kotlin.random.Random

/**
 * One toss or spin played by a SyncUp Group: every phone runs the same animation, from the same
 * moment, to the same result. The result comes from [seed] (and [payload]), so each phone works it
 * out the same way — nothing else needs to travel.
 */
@Immutable
data class SharedPlay(
    val id: Long,
    /** [Together.TOOL_COIN] or [Together.TOOL_WHEEL]. */
    val tool: String,
    /** Who started it, as the group shows their name; empty when it was this phone. */
    val by: String,
    /** Decides the result — the same number on every phone. */
    val seed: Long,
    /** The tool's own data as JSON: the coin's side names, or the wheel's options, mode and round. */
    val payload: String,
    /** When the animation starts, on THIS phone's System.nanoTime() clock. */
    val startAtNs: Long,
) {
    val mine: Boolean get() = by.isEmpty()
}

/**
 * Decide's "…for everyone": Coin toss and Wheel played on every phone of a SyncUp Group at once.
 * Anyone in the group can start one. It rides on the group's link while Music / Watch keep playing.
 *
 * On the wire it's [GroupLink]'s side channel (CH_DECIDE), JSON:
 *  - guest → host  {"t":"play","id","tool","seed","payload"} — "please start this for everyone";
 *  - host → guests {"t":"go","id","tool","seed","payload","by","at"} — to every guest, the asker too.
 * The host stamps "at" = its own clock + [LEAD_NS] and plays it itself from there; each guest turns
 * "at" into its own clock with the group's clock offset. A guest's own toss plays when the host's
 * "go" comes back, so it starts with everyone else's.
 *
 * Main thread (Nearby calls back there too).
 */
object Together {
    const val TOOL_COIN = "coin"
    const val TOOL_WHEEL = "wheel"

    /** How far ahead a shared play starts, so it reaches every phone first. */
    const val LEAD_NS = 1_200_000_000L

    private const val TAG = "Together"
    /** The biggest message sent (Nearby carries 32 KB in one BYTES payload; one byte is the channel's). */
    private const val MAX_BYTES = 30_000

    /**
     * True while this phone is in a SyncUp Group that can play together: the host with at least one
     * guest in, or a guest linked to the host whose clock is measured. Compose state.
     */
    val available: Boolean
        get() = when (GroupLink.role) {
            // people = the host + the guests in now.
            GroupRole.HOST -> GroupLink.phase == GroupPhase.HOSTING && GroupLink.people.size >= 2
            GroupRole.GUEST -> GroupLink.phase == GroupPhase.IN_GROUP && clockReady
            GroupRole.NONE -> false
        }

    /** Phones in the group, this one included; 0 when not in a group. */
    val phones: Int
        get() = when {
            !GroupLink.inGroup -> 0
            GroupLink.isHost -> GroupLink.people.size.coerceAtLeast(1)
            // A guest knows everyone from the host's group message; before the first one: the host and me.
            else -> GroupLink.people.size.coerceAtLeast(2)
        }

    /** The latest shared play — started here or by anyone in the group. Compose state. */
    var latest by mutableStateOf<SharedPlay?>(null)
        internal set

    /**
     * Starts a toss / spin for everyone. False when this phone isn't in a group — the caller then
     * offers to open Group.
     */
    fun play(tool: String, payload: String, seed: Long = Random.nextLong()): Boolean {
        if (!available || tool.isBlank()) return false
        val id = newId()
        return when (GroupLink.role) {
            GroupRole.HOST -> {
                val at = System.nanoTime() + LEAD_NS
                val bytes = encode(go(id, tool, seed, payload, GroupLink.myName, at)) ?: return false
                firstTime(id)
                GroupLink.send(GroupLink.guestEndpoints(), GroupLink.decidePayload(bytes))
                // This phone plays from the same moment (the host's clock is the group's clock).
                show(SharedPlay(id, tool, "", seed, payload, at))
                true
            }
            GroupRole.GUEST -> {
                // The host's "go" carries a name and a time too: it must fit as well.
                if (encode(go(id, tool, seed, payload, GroupLink.myName, Long.MAX_VALUE)) == null) return false
                val ask = JSONObject().put("t", "play").put("id", id).put("tool", tool).put("seed", seed).put("payload", payload)
                val bytes = encode(ask) ?: return false
                synchronized(asked) {
                    asked.add(id)
                    trim(asked, 32)
                }
                // Nothing plays here yet: the host's "go" comes back to this phone too.
                GroupLink.sendToHost(GroupLink.decidePayload(bytes))
            }
            GroupRole.NONE -> false
        }
    }

    // ------------------------------------------------------------------ inside
    /** Guest: the clock to the host is measured (follows [GroupLink.clock]; Compose state for [available]). */
    private var clockReady by mutableStateOf(false)
    /** Guest: the plays this phone asked for (their "go" shows as this phone's own). */
    private val asked = LinkedHashSet<Long>()
    /** Plays already shown here (never twice). */
    private val shown = LinkedHashSet<Long>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** GroupLink: a message on Decide's channel (the channel byte already taken off). */
    internal fun onReceive(endpointId: String, body: ByteArray) = onMain { handle(endpointId, body) }

    /** GroupLink: a new clock measurement (guests). */
    internal fun onClock() = onMain {
        val r = GroupLink.clock.ready
        if (clockReady != r) clockReady = r
    }

    /** GroupLink: the group (and its clock) is gone, or a new one starts. */
    internal fun reset() = onMain {
        clockReady = false
        synchronized(asked) { asked.clear() }
        synchronized(shown) { shown.clear() }
    }

    private fun handle(endpointId: String, body: ByteArray) {
        if (body.isEmpty() || body.size > MAX_BYTES + 1_024) return
        val o = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull() ?: return
        runCatching<Unit> {
            when (GroupLink.role) {
                GroupRole.HOST -> if (o.optString("t") == "play") onAsk(endpointId, o)
                GroupRole.GUEST -> if (o.optString("t") == "go") onGo(endpointId, o)
                GroupRole.NONE -> Unit
            }
        }.onFailure { Log.w(TAG, "bad message", it) }
    }

    /** Host: a guest asks to play — stamp the moment, send it to everyone (the asker too), play it here. */
    private fun onAsk(endpointId: String, o: JSONObject) {
        // Only from a phone in the group.
        val name = GroupLink.nameOf(endpointId)?.ifBlank { null } ?: return
        val id = o.optLong("id")
        val tool = o.optString("tool")
        if (id == 0L || tool.isBlank() || !o.has("seed")) return
        if (!firstTime(id)) return
        val seed = o.optLong("seed")
        val payload = o.optString("payload")
        val at = System.nanoTime() + LEAD_NS
        val bytes = encode(go(id, tool, seed, payload, name, at)) ?: return
        GroupLink.send(GroupLink.guestEndpoints(), GroupLink.decidePayload(bytes))
        show(SharedPlay(id, tool, name, seed, payload, at))
    }

    /** Guest: the host says play — at its moment, turned into this phone's clock. */
    private fun onGo(endpointId: String, o: JSONObject) {
        if (endpointId != GroupLink.hostEndpoint) return
        val id = o.optLong("id")
        val tool = o.optString("tool")
        if (id == 0L || tool.isBlank() || !o.has("seed") || !o.has("at")) return
        if (!firstTime(id)) return
        val startAt = o.optLong("at") - GroupLink.clock.offsetNs
        val mine = synchronized(asked) { asked.remove(id) }
        val by = if (mine) "" else o.optString("by").ifBlank { "Someone" }
        show(SharedPlay(id, tool, by, o.optLong("seed"), o.optString("payload"), startAt))
    }

    private fun go(id: Long, tool: String, seed: Long, payload: String, by: String, at: Long) = JSONObject()
        .put("t", "go").put("id", id).put("tool", tool).put("seed", seed).put("payload", payload).put("by", by).put("at", at)

    /** The message's bytes, or null (and a log line) when it's too big to send. */
    private fun encode(o: JSONObject): ByteArray? {
        val b = o.toString().toByteArray(Charsets.UTF_8)
        if (b.size <= MAX_BYTES) return b
        Log.w(TAG, "play too big to send (${b.size} bytes)")
        return null
    }

    /** First time this play is seen here? (Remembers it.) */
    private fun firstTime(id: Long): Boolean = synchronized(shown) {
        if (!shown.add(id)) return false
        trim(shown, 64)
        true
    }

    private fun show(p: SharedPlay) = onMain { latest = p }

    private fun newId(): Long {
        var id = 0L
        while (id == 0L) id = Random.nextLong()
        return id
    }

    private fun trim(set: LinkedHashSet<Long>, max: Int) {
        while (set.size > max) set.remove(set.first())
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
