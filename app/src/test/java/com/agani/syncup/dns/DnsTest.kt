package com.agani.syncup.dns

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class DnsTest {
    private fun ip(text: String) = InetAddress.getByName(text)

    // ------------------------------------------------------------------ addresses
    @Test
    fun the_type_comes_from_what_was_typed() {
        assertEquals(DnsKind.HTTPS, DnsKind.of("https://dns.google/dns-query"))
        assertEquals(DnsKind.TLS, DnsKind.of("dns.google"))
        assertEquals(DnsKind.PLAIN, DnsKind.of("8.8.8.8"))
        assertEquals(DnsKind.PLAIN, DnsKind.of("2001:4860:4860::8888"))
        assertEquals(DnsKind.TLS, DnsKind.of("  dns.google  "))
    }

    @Test
    fun other_things_arent_addresses() {
        listOf("", "nonsense here", "http://dns.google/dns-query", "https://dns.google", "999.1.1.1", "localhost", "1.2.3").forEach {
            assertNull(it, DnsKind.of(it))
        }
    }

    // ------------------------------------------------------------------ the message format
    /** A reply as a DNS service would send it, with its name compressed to a pointer. */
    private fun reply(id: Int, code: Int = 0, vararg records: Triple<Int, Int, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeShort(id)
        out.writeShort(0x8180 or code)
        out.writeShort(1)
        out.writeShort(records.size)
        out.writeShort(0)
        out.writeShort(0)
        out.write(byteArrayOf(1, 'a'.code.toByte(), 3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0))
        out.writeShort(1)
        out.writeShort(1)
        records.forEach { (type, ttl, data) ->
            out.writeShort(0xC00C)
            out.writeShort(type)
            out.writeShort(1)
            out.writeInt(ttl)
            out.writeShort(data.size)
            out.write(data)
        }
        return bytes.toByteArray()
    }

    private fun v4(text: String) = Triple(DnsWire.A, 300, ip(text).address)

    @Test
    fun a_question_is_built_correctly() {
        val q = DnsWire.query("a.com", DnsWire.A, 0x1234)
        assertArrayEquals(
            byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1, 'a'.code.toByte(), 3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0, 0, 1, 0, 1),
            q,
        )
    }

    @Test(expected = IOException::class)
    fun a_silly_name_isnt_asked() {
        DnsWire.query("a..com", DnsWire.A, 1)
    }

    @Test
    fun addresses_and_the_shortest_time_are_read() {
        val answer = DnsWire.parse(reply(7, 0, Triple(DnsWire.A, 300, ip("1.2.3.4").address), Triple(DnsWire.A, 60, ip("5.6.7.8").address)), 7)
        assertEquals(DnsWire.FOUND, answer.code)
        assertEquals(listOf(ip("1.2.3.4"), ip("5.6.7.8")), answer.addresses)
        assertEquals(60, answer.ttlSeconds)
    }

    @Test
    fun other_record_types_are_stepped_over() {
        val cname = Triple(5, 300, byteArrayOf(0xC0.toByte(), 0x0C))
        assertEquals(listOf(ip("1.2.3.4")), DnsWire.parse(reply(7, 0, cname, v4("1.2.3.4")), 7).addresses)
    }

    @Test
    fun ipv6_is_read() {
        val six = Triple(DnsWire.AAAA, 300, ip("2001:db8::1").address)
        assertEquals(listOf(ip("2001:db8::1")), DnsWire.parse(reply(7, 0, six), 7).addresses)
    }

    @Test(expected = IOException::class)
    fun a_reply_to_another_question_is_refused() {
        DnsWire.parse(reply(7), 8)
    }

    @Test(expected = IOException::class)
    fun rubbish_is_refused() {
        DnsWire.parse("hello".toByteArray(), 0x6865)
    }

    @Test(expected = IOException::class)
    fun a_cut_off_reply_is_refused() {
        val whole = reply(7, 0, v4("1.2.3.4"))
        DnsWire.parse(whole.copyOf(whole.size - 2), 7)
    }

    // ------------------------------------------------------------------ choosing who answers
    private var clock = 1_000_000L
    private val results = ArrayList<Pair<String, Long?>>()
    private var phoneAsked = 0

    private fun resolver() = DnsResolver(
        phone = { phoneAsked++; listOf(ip("9.9.9.9")) },
        now = { clock },
        onResult = { id, ms -> results += id to ms },
    )

    /** A service that answers every question with [address] (A) and counts them. */
    private class Fake(val id: String, val address: String? = "1.1.1.1", val code: Int = 0, val fails: Boolean = false, val ttl: Int = 300) {
        var asked = 0

        fun line() = DnsLine(id) { q ->
            asked++
            if (fails) throw IOException("no answer")
            val qid = (q[0].toInt() and 0xFF shl 8) or (q[1].toInt() and 0xFF)
            val isA = q[q.size - 3].toInt() == DnsWire.A
            val bytes = ByteArrayOutputStream()
            val out = DataOutputStream(bytes)
            out.writeShort(qid)
            out.writeShort(0x8180 or code)
            out.writeShort(0)
            out.writeShort(if (address != null && isA) 1 else 0)
            out.writeShort(0)
            out.writeShort(0)
            if (address != null && isA) {
                out.write(0)
                out.writeShort(DnsWire.A)
                out.writeShort(1)
                out.writeInt(ttl)
                out.writeShort(4)
                out.write(InetAddress.getByName(address).address)
            }
            bytes.toByteArray()
        }
    }

    @Test
    fun only_the_first_is_asked() {
        val first = Fake("a", "1.1.1.1")
        val second = Fake("b", "2.2.2.2")
        val r = resolver().apply { lines = listOf(first.line(), second.line()) }
        assertEquals(listOf(ip("1.1.1.1")), r.resolve("site.com"))
        assertEquals(1, first.asked)
        assertEquals(0, second.asked)
        assertEquals(0, phoneAsked)
    }

    @Test
    fun the_next_takes_over_when_the_first_fails() {
        val first = Fake("a", fails = true)
        val second = Fake("b", "2.2.2.2")
        val r = resolver().apply { lines = listOf(first.line(), second.line()) }
        assertEquals(listOf(ip("2.2.2.2")), r.resolve("site.com"))
        assertEquals("a" to null, results.first())
        assertEquals("b", results.last().first)
        assertNotNull(results.last().second)
    }

    @Test
    fun a_service_error_counts_as_failing() {
        val first = Fake("a", null, code = 2)
        val second = Fake("b", "2.2.2.2")
        val r = resolver().apply { lines = listOf(first.line(), second.line()) }
        assertEquals(listOf(ip("2.2.2.2")), r.resolve("site.com"))
    }

    @Test
    fun a_blocked_site_stays_blocked_instead_of_asking_the_next() {
        val blocking = Fake("a", null, code = DnsWire.NO_SUCH_NAME)
        val second = Fake("b", "2.2.2.2")
        val r = resolver().apply { lines = listOf(blocking.line(), second.line()) }
        assertTrue(r.resolve("ads.com").isEmpty())
        assertEquals(0, second.asked)
        assertEquals(0, phoneAsked)
    }

    @Test
    fun a_zero_address_means_blocked_too() {
        val blocking = Fake("a", "0.0.0.0")
        val second = Fake("b", "2.2.2.2")
        val r = resolver().apply { lines = listOf(blocking.line(), second.line()) }
        assertTrue(r.resolve("ads.com").isEmpty())
        assertEquals(0, second.asked)
    }

    @Test
    fun when_all_fail_the_phones_dns_answers() {
        val r = resolver().apply { lines = listOf(Fake("a", fails = true).line(), Fake("b", fails = true).line()) }
        assertEquals(listOf(ip("9.9.9.9")), r.resolve("site.com"))
        assertEquals(1, phoneAsked)
    }

    @Test
    fun a_phone_dns_failure_just_finds_nothing() {
        val r = DnsResolver(phone = { throw IOException("no network") }, now = { clock })
        r.lines = listOf(Fake("a", fails = true).line())
        assertTrue(r.resolve("site.com").isEmpty())
    }

    @Test
    fun a_failed_service_rests_then_gets_another_chance() {
        val first = Fake("a", fails = true)
        val second = Fake("b", "2.2.2.2", ttl = 30)
        val r = resolver().apply { lines = listOf(first.line(), second.line()) }
        r.resolve("one.com")
        assertEquals(1, first.asked)
        r.resolve("two.com")
        assertEquals(1, first.asked)
        clock += DnsResolver.REST_MS + 1
        r.resolve("three.com")
        assertEquals(2, first.asked)
    }

    @Test
    fun answers_are_remembered_for_their_time_within_limits() {
        val short = Fake("a", "1.1.1.1", ttl = 1)
        val r = resolver().apply { lines = listOf(short.line()) }
        r.resolve("site.com")
        r.resolve("SITE.com.")
        assertEquals(1, short.asked)
        clock += (DnsResolver.MIN_TTL_SECONDS - 1) * 1_000L
        r.resolve("site.com")
        assertEquals(1, short.asked)
        clock += 2_000
        r.resolve("site.com")
        assertEquals(2, short.asked)
    }

    @Test
    fun blocked_answers_are_only_remembered_briefly() {
        val blocking = Fake("a", null, code = DnsWire.NO_SUCH_NAME)
        val r = resolver().apply { lines = listOf(blocking.line()) }
        r.resolve("ads.com")
        clock += (DnsResolver.NOTHING_FOUND_SECONDS + 1) * 1_000L
        r.resolve("ads.com")
        assertEquals(2, blocking.asked)
    }

    @Test
    fun changing_the_order_starts_afresh() {
        val first = Fake("a", "1.1.1.1")
        val second = Fake("b", "2.2.2.2")
        val r = resolver().apply { lines = listOf(first.line(), second.line()) }
        assertEquals(listOf(ip("1.1.1.1")), r.resolve("site.com"))
        r.lines = listOf(second.line(), first.line())
        assertEquals(listOf(ip("2.2.2.2")), r.resolve("site.com"))
    }

    @Test
    fun an_ip_address_is_nobodys_question() {
        val first = Fake("a")
        val r = resolver().apply { lines = listOf(first.line()) }
        assertEquals(listOf(ip("203.0.113.5")), r.resolve("203.0.113.5"))
        assertEquals(0, first.asked)
    }

    @Test
    fun a_site_with_only_ipv6_is_still_found() {
        val six = DnsLine("a") { q ->
            val qid = (q[0].toInt() and 0xFF shl 8) or (q[1].toInt() and 0xFF)
            if (q[q.size - 3].toInt() == DnsWire.AAAA) reply(qid, 0, Triple(DnsWire.AAAA, 300, ip("2001:db8::1").address)) else reply(qid)
        }
        val r = resolver().apply { lines = listOf(six) }
        assertEquals(listOf(ip("2001:db8::1")), r.resolve("site.com"))
    }

    @Test
    fun the_check_reports_time_or_failure_and_can_end_a_rest() {
        val r = resolver()
        val down = Fake("a", fails = true)
        assertNull(r.check(down.line()))
        assertEquals("a" to null, results.last())
        val up = Fake("a", "1.1.1.1")
        r.lines = listOf(up.line())
        r.check(down.line())
        assertNotNull(r.check(up.line()))
        assertEquals(listOf(ip("1.1.1.1")), r.resolve("site.com"))
    }

    // ------------------------------------------------------------------ reading a browser's request
    @Test
    fun a_tunnel_request_is_read() {
        val r = ProxyRequest.parse("CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443")!!
        assertEquals("example.com", r.host)
        assertEquals(443, r.port)
        assertTrue(r.tunnel)
        assertNull(r.forward)
    }

    @Test
    fun an_ipv6_tunnel_request_is_read() {
        val r = ProxyRequest.parse("CONNECT [2001:db8::1]:443 HTTP/1.1")!!
        assertEquals("2001:db8::1", r.host)
        assertEquals(443, r.port)
    }

    @Test
    fun a_plain_request_is_rewritten_for_the_site() {
        val r = ProxyRequest.parse("GET http://example.com:8080/a/b?c=1 HTTP/1.1\r\nHost: example.com:8080\r\nProxy-Connection: keep-alive\r\nConnection: keep-alive\r\nUser-Agent: x")!!
        assertEquals("example.com", r.host)
        assertEquals(8080, r.port)
        assertFalse(r.tunnel)
        assertEquals("GET /a/b?c=1 HTTP/1.1\r\nHost: example.com:8080\r\nUser-Agent: x\r\nConnection: close\r\n\r\n", String(r.forward!!))
    }

    @Test
    fun a_plain_request_with_no_path_asks_for_the_root() {
        val r = ProxyRequest.parse("GET http://example.com HTTP/1.1\r\nHost: example.com")!!
        assertEquals(80, r.port)
        assertTrue(String(r.forward!!).startsWith("GET / HTTP/1.1\r\n"))
    }

    @Test
    fun other_requests_are_refused() {
        listOf("", "GET / HTTP/1.1", "CONNECT nowhere HTTP/1.1", "CONNECT a.com:99999 HTTP/1.1", "GET ftp://a.com/ HTTP/1.1", "nonsense").forEach {
            assertNull(it, ProxyRequest.parse(it))
        }
    }

    // ------------------------------------------------------------------ the proxy, over real sockets
    private val closers = ArrayList<() -> Unit>()

    @After
    fun closeAll() = closers.forEach { runCatching { it() } }

    /** A "website" on this machine: runs [talk] for each connection. */
    private fun site(talk: (Socket) -> Unit): Int {
        val server = ServerSocket(0)
        closers += { server.close() }
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { s.use(talk) }
            }
        }
        return server.localPort
    }

    private fun proxy(lookup: (String) -> List<InetAddress> = { listOf(InetAddress.getLoopbackAddress()) }, allowed: (InetAddress) -> Boolean = { true }): BrowsingProxy {
        val p = BrowsingProxy(lookup, allowed)
        closers += { p.stop() }
        return p
    }

    private fun Socket.send(text: String) {
        getOutputStream().write(text.toByteArray())
        getOutputStream().flush()
    }

    private fun Socket.readHead(): String {
        val out = StringBuilder()
        while (!out.endsWith("\r\n\r\n")) {
            val b = getInputStream().read()
            if (b < 0) break
            out.append(b.toChar())
        }
        return out.toString()
    }

    @Test(timeout = 10_000)
    fun a_tunnel_carries_bytes_both_ways_untouched() {
        val all = ByteArray(256) { it.toByte() }
        val port = site { s ->
            val got = ByteArray(all.size)
            java.io.DataInputStream(s.getInputStream()).readFully(got)
            s.getOutputStream().write(got)
            s.getOutputStream().flush()
        }
        val proxyPort = proxy().start()
        Socket("127.0.0.1", proxyPort).use { c ->
            c.send("CONNECT example.com:$port HTTP/1.1\r\nHost: example.com\r\n\r\n")
            assertTrue(c.readHead().startsWith("HTTP/1.1 200"))
            c.getOutputStream().write(all)
            c.getOutputStream().flush()
            val back = ByteArray(all.size)
            java.io.DataInputStream(c.getInputStream()).readFully(back)
            assertArrayEquals(all, back)
        }
    }

    @Test(timeout = 10_000)
    fun bytes_sent_right_after_the_head_arent_lost() {
        val port = site { s ->
            val got = ByteArray(5)
            java.io.DataInputStream(s.getInputStream()).readFully(got)
            s.getOutputStream().write(got)
        }
        val proxyPort = proxy().start()
        Socket("127.0.0.1", proxyPort).use { c ->
            c.send("CONNECT example.com:$port HTTP/1.1\r\n\r\nHELLO")
            assertTrue(c.readHead().startsWith("HTTP/1.1 200"))
            val back = ByteArray(5)
            java.io.DataInputStream(c.getInputStream()).readFully(back)
            assertEquals("HELLO", String(back))
        }
    }

    @Test(timeout = 10_000)
    fun a_plain_page_goes_through_rewritten_and_ends_the_connection() {
        var seen = ""
        val port = site { s ->
            seen = s.readHead()
            s.send("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nhi")
        }
        val proxyPort = proxy().start()
        Socket("127.0.0.1", proxyPort).use { c ->
            c.send("GET http://example.com:$port/page HTTP/1.1\r\nHost: example.com\r\nProxy-Connection: keep-alive\r\n\r\n")
            val all = c.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
            assertTrue(all, all.endsWith("hi"))
        }
        assertEquals("GET /page HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n", seen)
    }

    @Test(timeout = 10_000)
    fun a_blocked_site_is_refused() {
        val proxyPort = proxy(lookup = { emptyList() }).start()
        Socket("127.0.0.1", proxyPort).use { c ->
            c.send("CONNECT ads.com:443 HTTP/1.1\r\n\r\n")
            assertTrue(c.readHead().startsWith("HTTP/1.1 502"))
        }
    }

    @Test(timeout = 10_000)
    fun it_wont_connect_back_into_this_phone() {
        val port = site { }
        val p = BrowsingProxy({ listOf(InetAddress.getLoopbackAddress()) })
        closers += { p.stop() }
        Socket("127.0.0.1", p.start()).use { c ->
            c.send("CONNECT sneaky.example.com:$port HTTP/1.1\r\n\r\n")
            assertTrue(c.readHead().startsWith("HTTP/1.1 502"))
        }
    }

    @Test(timeout = 10_000)
    fun a_site_that_wont_connect_is_refused_and_the_proxy_keeps_working() {
        val closed = ServerSocket(0).let { val p = it.localPort; it.close(); p }
        val port = site { it.getOutputStream().write('k'.code) }
        val p = proxy(lookup = { listOf(InetAddress.getLoopbackAddress()) })
        val proxyPort = p.start()
        Socket("127.0.0.1", proxyPort).use { c ->
            c.send("CONNECT example.com:$closed HTTP/1.1\r\n\r\n")
            assertTrue(c.readHead().startsWith("HTTP/1.1 502"))
        }
        Socket("127.0.0.1", proxyPort).use { c ->
            c.send("CONNECT example.com:$port HTTP/1.1\r\n\r\n")
            assertTrue(c.readHead().startsWith("HTTP/1.1 200"))
            assertEquals('k'.code, c.getInputStream().read())
        }
    }

    @Test(timeout = 10_000)
    fun a_bad_request_gets_a_clear_refusal() {
        val proxyPort = proxy().start()
        Socket("127.0.0.1", proxyPort).use { c ->
            c.send("hello there\r\n\r\n")
            assertTrue(c.readHead().startsWith("HTTP/1.1 400"))
        }
    }

    @Test(timeout = 10_000)
    fun stopping_ends_open_connections_and_listening() {
        val port = site { it.getInputStream().read() }
        val p = BrowsingProxy({ listOf(InetAddress.getLoopbackAddress()) }, { true })
        val proxyPort = p.start()
        val c = Socket("127.0.0.1", proxyPort)
        c.send("CONNECT example.com:$port HTTP/1.1\r\n\r\n")
        assertTrue(c.readHead().startsWith("HTTP/1.1 200"))
        p.stop()
        assertEquals(-1, runCatching { c.getInputStream().read() }.getOrDefault(-1))
        c.close()
        assertTrue(runCatching { Socket("127.0.0.1", proxyPort).close() }.isFailure)
    }
}
