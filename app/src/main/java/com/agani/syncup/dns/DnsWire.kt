package com.agani.syncup.dns

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress

/** How a DNS address is reached, worked out from what was typed. */
internal enum class DnsKind {
    /** `https://dns.google/dns-query`: encrypted. */
    HTTPS,

    /** `dns.google`: encrypted, on port 853. */
    TLS,

    /** `8.8.8.8`: not encrypted, so the internet provider can see or change the answers. */
    PLAIN;

    companion object {
        private val HTTPS_ADDRESS = Regex("""^https://[^\s/]+\.[^\s/]+/\S*$""", RegexOption.IGNORE_CASE)
        private val HOST = Regex("""^[a-z0-9-]+(\.[a-z0-9-]+)+$""", RegexOption.IGNORE_CASE)
        private val IPV6 = Regex("""^[0-9a-f:]*:[0-9a-f:]+$""", RegexOption.IGNORE_CASE)

        fun of(address: String): DnsKind? {
            val a = address.trim()
            return when {
                HTTPS_ADDRESS.matches(a) -> HTTPS
                isIp(a) -> PLAIN
                HOST.matches(a) && !a.all { it.isDigit() || it == '.' } -> TLS
                else -> null
            }
        }

        fun isIp(text: String): Boolean {
            if (IPV6.matches(text)) return true
            val parts = text.split('.')
            return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() < 256 }
        }
    }
}

/** The DNS message format: the same bytes over https, TLS and plain UDP. */
internal object DnsWire {
    const val A = 1
    const val AAAA = 28
    const val FOUND = 0
    const val NO_SUCH_NAME = 3

    class Answer(val code: Int, val addresses: List<InetAddress>, val ttlSeconds: Int)

    fun query(host: String, type: Int, id: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun short(v: Int) {
            out.write(v shr 8 and 0xFF)
            out.write(v and 0xFF)
        }
        short(id)
        short(0x0100) // please look it up for me
        short(1)
        short(0)
        short(0)
        short(0)
        host.trimEnd('.').split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            if (bytes.isEmpty() || bytes.size > 63) throw IOException("Not a host name: $host")
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        short(type)
        short(1)
        return out.toByteArray()
    }

    fun isTruncated(data: ByteArray): Boolean = data.size > 2 && data[2].toInt() and 0x02 != 0

    /** Reads a reply to the question with [id]. Throws IOException on anything that isn't one. */
    fun parse(data: ByteArray, id: Int): Answer {
        fun u8(at: Int): Int = if (at < data.size) data[at].toInt() and 0xFF else throw IOException("Short reply")
        fun u16(at: Int): Int = u8(at) shl 8 or u8(at + 1)
        fun u32(at: Int): Long = u16(at).toLong() shl 16 or u16(at + 2).toLong()

        /** Past a name, which may end in a pointer to an earlier one. */
        fun skipName(start: Int): Int {
            var at = start
            while (true) {
                val len = u8(at)
                when {
                    len == 0 -> return at + 1
                    len and 0xC0 == 0xC0 -> return at + 2
                    len and 0xC0 != 0 -> throw IOException("Bad name")
                    else -> at += len + 1
                }
            }
        }

        if (u16(0) != id) throw IOException("Reply to another question")
        val flags = u16(2)
        if (flags and 0x8000 == 0) throw IOException("Not a reply")
        val questions = u16(4)
        val answers = u16(6)
        var at = 12
        repeat(questions) { at = skipName(at) + 4 }
        val found = ArrayList<InetAddress>()
        var ttl = Long.MAX_VALUE
        repeat(answers) {
            at = skipName(at)
            val type = u16(at)
            val recordTtl = u32(at + 4)
            val length = u16(at + 8)
            at += 10
            if (at + length > data.size) throw IOException("Short reply")
            if ((type == A && length == 4) || (type == AAAA && length == 16)) {
                found += InetAddress.getByAddress(data.copyOfRange(at, at + length))
                ttl = minOf(ttl, recordTtl)
            }
            at += length
        }
        return Answer(flags and 0x000F, found, if (ttl == Long.MAX_VALUE) 0 else ttl.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }
}
