package com.agani.syncup.party

import org.json.JSONObject
import java.nio.ByteBuffer

/**
 * What the host and its guests say to each other — every message is one Nearby BYTES payload
 * (32 KB max): a control message (JSON) or a piece of a song (track id + byte offset + bytes).
 *
 * Control messages ("t" = type):
 *   guest → host  hello {token, name, have{track: pieces (BitSet, base64)}}   ping {id, a}
 *                 add {req, title, artist, ms, size, mime}   bye
 *   host → guest  state {…room, queue, current, t0, paused, pos}   pong {id, a, b, c}
 *                 addok {req, track}   addno {req}   end
 */
internal object PartyWire {
    const val SERVICE_ID = "com.agani.syncup.party"
    /** Separates the parts of an endpoint name ("room id␟room name␟host␟people␟playing"). */
    const val SEP = '\u001F'
    /** Song bytes per payload (the limit is 32 KB including our 13-byte header). */
    const val CHUNK = 30_000

    private const val T_JSON: Byte = 1
    private const val T_CHUNK: Byte = 2

    fun json(o: JSONObject): ByteArray {
        val body = o.toString().toByteArray(Charsets.UTF_8)
        return ByteArray(body.size + 1).also {
            it[0] = T_JSON
            System.arraycopy(body, 0, it, 1, body.size)
        }
    }

    fun chunk(trackId: Int, offset: Long, data: ByteArray, len: Int): ByteArray =
        ByteBuffer.allocate(13 + len).put(T_CHUNK).putInt(trackId).putLong(offset).put(data, 0, len).array()

    sealed interface Msg
    class Json(val o: JSONObject) : Msg
    class Chunk(val trackId: Int, val offset: Long, val data: ByteArray, val start: Int, val len: Int) : Msg

    fun parse(bytes: ByteArray): Msg? = runCatching {
        when (bytes.firstOrNull()) {
            T_JSON -> Json(JSONObject(String(bytes, 1, bytes.size - 1, Charsets.UTF_8)))
            T_CHUNK -> {
                val b = ByteBuffer.wrap(bytes)
                b.get()
                Chunk(b.int, b.long, bytes, 13, bytes.size - 13)
            }
            else -> null
        }
    }.getOrNull()

    fun msg(type: String) = JSONObject().put("t", type)
}

/**
 * A guest's view of the host's clock (the host's System.nanoTime), NTP style: ping, the host
 * stamps arrival and reply, and the round trips with the least delay say best how far apart the
 * two clocks are (a slow one — stuck behind song pieces — can be off by half its time). Re-measured
 * all the time, so slow clock drift is followed too.
 */
internal class PartyClock {
    private class Sample(val rttNs: Long, val offsetNs: Long, val atNs: Long)

    private val samples = ArrayDeque<Sample>()

    /** Host clock minus this phone's clock. */
    @Volatile var offsetNs: Long = 0L
        private set

    /** Enough measurements to play in time. */
    @Volatile var ready: Boolean = false
        private set

    /** The best round trip lately (how good the measurement is). */
    @Volatile var rttNs: Long = 0L
        private set

    /** Measurements kept. */
    val count: Int get() = synchronized(this) { samples.size }

    /** One ping: sent at [a] (here), arrived at [b] and answered at [c] (host), back at [d] (here). */
    fun add(a: Long, b: Long, c: Long, d: Long) {
        val rtt = (d - a) - (c - b)
        if (rtt < 0) return
        val offset = ((b - a) + (c - d)) / 2
        synchronized(this) {
            samples.addLast(Sample(rtt, offset, d))
            while (samples.size > 40) samples.removeFirst()
            val recent = samples.filter { d - it.atNs < 120_000_000_000L }.ifEmpty { samples.toList() }
            // Only the round trips close to the quickest one; their middle offset (robust to one odd one).
            val minRtt = recent.minOf { it.rttNs }
            val good = recent.filter { it.rttNs <= minRtt + maxOf(minRtt / 2, 3_000_000L) }
            val target = good.map { it.offsetNs }.sorted()[good.size / 2]
            // A much better measurement than before: take it now. Otherwise ease in (no audible jumps).
            val better = ready && minRtt < rttNs * 6 / 10
            offsetNs = if (!ready || better) target else offsetNs + (target - offsetNs) / 4
            rttNs = minRtt
            if (samples.size >= 4) ready = true
        }
    }

    fun reset() = synchronized(this) {
        samples.clear()
        ready = false
        offsetNs = 0
        rttNs = 0
    }
}
