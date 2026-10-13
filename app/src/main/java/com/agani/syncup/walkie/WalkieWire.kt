package com.agani.syncup.walkie

import org.json.JSONObject
import java.nio.ByteBuffer

/**
 * What the phones in a channel say to each other — every message is one Nearby BYTES payload: a
 * control message (JSON) or a bit of voice (a few 20 ms frames from one person).
 *
 * Control messages ("t" = type):
 *   member → hub  hello {token, name}   talk {on}   call   bye
 *   hub → member  state {…channel, members, floor}   call {from}   end {by}   handover {to, epoch, …}
 */
internal object WalkieWire {
    const val SERVICE_ID = "com.agani.syncup.walkie"
    /** Separates the parts of an endpoint name. */
    const val SEP = '\u001F'

    private const val T_JSON: Byte = 1
    private const val T_VOICE: Byte = 3

    fun json(o: JSONObject): ByteArray {
        val body = o.toString().toByteArray(Charsets.UTF_8)
        return ByteArray(body.size + 1).also {
            it[0] = T_JSON
            System.arraycopy(body, 0, it, 1, body.size)
        }
    }

    /** [frames] of one person's voice ([codec] = [Voice.CODEC_AMR_WB] or [Voice.CODEC_PCM]). */
    fun voice(sender: Int, seq: Int, codec: Int, frames: List<ByteArray>): ByteArray {
        val size = 1 + 4 + 4 + 1 + 1 + frames.sumOf { 2 + it.size }
        val b = ByteBuffer.allocate(size).put(T_VOICE).putInt(sender).putInt(seq).put(codec.toByte()).put(frames.size.toByte())
        frames.forEach { b.putShort(it.size.toShort()).put(it) }
        return b.array()
    }

    sealed interface Msg
    class Json(val o: JSONObject) : Msg
    class Voice(val sender: Int, val seq: Int, val codec: Int, val frames: List<ByteArray>) : Msg

    fun parse(bytes: ByteArray): Msg? = runCatching {
        when (bytes.firstOrNull()) {
            T_JSON -> Json(JSONObject(String(bytes, 1, bytes.size - 1, Charsets.UTF_8)))
            T_VOICE -> {
                val b = ByteBuffer.wrap(bytes)
                b.get()
                val sender = b.int
                val seq = b.int
                val codec = b.get().toInt()
                val n = b.get().toInt() and 0xFF
                Voice(sender, seq, codec, List(n) { ByteArray(b.short.toInt() and 0xFFFF).also { b.get(it) } })
            }
            else -> null
        }
    }.getOrNull()

    fun isVoice(bytes: ByteArray) = bytes.firstOrNull() == T_VOICE

    fun msg(type: String) = JSONObject().put("t", type)
}
