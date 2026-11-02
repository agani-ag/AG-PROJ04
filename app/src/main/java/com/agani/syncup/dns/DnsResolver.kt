package com.agani.syncup.dns

import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** One DNS service the person turned on: its id, and how to put a question to it. */
internal class DnsLine(val id: String, val ask: (ByteArray) -> ByteArray)

/**
 * Finds a site's addresses through the DNS services the person turned on. Only the first working one is
 * asked; the rest are backups for when it fails. An answer always stands: "no such site" or a blocked
 * address isn't a failure, so a DNS that blocks ads or adult sites keeps blocking instead of passing the
 * question on. Only when every service fails does the phone's own DNS answer, so pages still open.
 */
internal class DnsResolver(
    private val phone: (String) -> List<InetAddress>,
    private val now: () -> Long,
    /** Each result as it happens: a service's id with how long it took, or null when it failed. */
    private val onResult: (String, Long?) -> Unit = { _, _ -> },
) {
    private class Cached(val addresses: List<InetAddress>, val until: Long)

    private val cache = ConcurrentHashMap<String, Cached>()
    private val resting = ConcurrentHashMap<String, Long>()
    private val locks = Array(32) { Any() }

    /** In the person's order. Changing it starts afresh. */
    @Volatile var lines: List<DnsLine> = emptyList()
        set(value) {
            field = value
            cache.clear()
            resting.clear()
        }

    /** The addresses for [host], best first. Empty when the DNS says there are none, or blocks it. */
    fun resolve(host: String): List<InetAddress> {
        val name = host.trimEnd('.').lowercase()
        if (DnsKind.isIp(name)) return listOf(InetAddress.getByName(name))
        synchronized(locks[(name.hashCode() and 0x7FFFFFFF) % locks.size]) {
            cache[name]?.takeIf { it.until > now() }?.let { return it.addresses }
            for (line in lines) {
                if ((resting[line.id] ?: 0L) > now()) continue
                val answer = try {
                    askTimed(line, name)
                } catch (_: IOException) {
                    resting[line.id] = now() + REST_MS
                    onResult(line.id, null)
                    continue
                }
                val usable = answer.addresses.filterNot { it.isAnyLocalAddress }
                val keep = if (usable.isEmpty()) NOTHING_FOUND_SECONDS else answer.ttlSeconds.coerceIn(MIN_TTL_SECONDS, MAX_TTL_SECONDS)
                remember(name, usable, keep)
                return usable
            }
            val fromPhone = try {
                phone(name)
            } catch (_: IOException) {
                emptyList()
            }
            remember(name, fromPhone, NOTHING_FOUND_SECONDS)
            return fromPhone
        }
    }

    /** Asks [line] about a known name, for the DNS page: how long it took, or null when it failed. */
    fun check(line: DnsLine): Long? = try {
        val started = now()
        ask(line, CHECK_NAME)
        val took = now() - started
        resting.remove(line.id)
        onResult(line.id, took)
        took
    } catch (_: IOException) {
        resting[line.id] = now() + REST_MS
        onResult(line.id, null)
        null
    }

    private fun remember(name: String, addresses: List<InetAddress>, seconds: Int) {
        if (cache.size >= MAX_CACHED) cache.clear()
        cache[name] = Cached(addresses, now() + seconds * 1_000L)
    }

    private fun askTimed(line: DnsLine, name: String): DnsWire.Answer {
        val started = now()
        val answer = ask(line, name)
        onResult(line.id, now() - started)
        return answer
    }

    /** IPv4 first, which works nearly everywhere; IPv6 only for a site that has nothing else. */
    private fun ask(line: DnsLine, name: String): DnsWire.Answer {
        val first = question(line, name, DnsWire.A)
        if (first.code == DnsWire.NO_SUCH_NAME || first.addresses.isNotEmpty()) return first
        return try {
            question(line, name, DnsWire.AAAA)
        } catch (_: IOException) {
            first
        }
    }

    private fun question(line: DnsLine, name: String, type: Int): DnsWire.Answer {
        val id = Random.nextInt(0x10000)
        val answer = try {
            DnsWire.parse(line.ask(DnsWire.query(name, type, id)), id)
        } catch (e: RuntimeException) {
            throw IOException(e)
        }
        if (answer.code != DnsWire.FOUND && answer.code != DnsWire.NO_SUCH_NAME) throw IOException("Service failed: ${answer.code}")
        return answer
    }

    companion object {
        /** A service that failed isn't asked again for this long, so pages don't keep waiting on it. */
        const val REST_MS = 60_000L
        const val MIN_TTL_SECONDS = 30
        const val MAX_TTL_SECONDS = 3_600
        const val NOTHING_FOUND_SECONDS = 30
        const val MAX_CACHED = 2_000
        const val CHECK_NAME = "example.com"
    }
}
