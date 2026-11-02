package com.agani.syncup.dns

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** What a browser asked this proxy for: the place to connect to, and for plain http the request to pass on. */
internal class ProxyRequest(val host: String, val port: Int, val tunnel: Boolean, val forward: ByteArray?) {
    companion object {
        /** Reads the head of a request ("CONNECT a.com:443 HTTP/1.1…" or "GET http://a.com/x HTTP/1.1…"). */
        fun parse(head: String): ProxyRequest? {
            val lines = head.split("\r\n")
            val first = lines.firstOrNull()?.split(' ')?.takeIf { it.size == 3 } ?: return null
            val (method, target, version) = first
            if (method == "CONNECT") {
                val colon = target.lastIndexOf(':')
                if (colon <= 0) return null
                val port = target.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65_535 } ?: return null
                return ProxyRequest(target.substring(0, colon).trim('[', ']'), port, tunnel = true, forward = null)
            }
            val uri = runCatching { URI(target) }.getOrNull() ?: return null
            if (!uri.scheme.equals("http", ignoreCase = true)) return null
            val host = uri.host ?: return null
            val path = (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
            // Only ever one request per connection: its answer ends the connection, so nothing later can go astray.
            val headers = lines.drop(1).filter { line ->
                val name = line.substringBefore(':').trim().lowercase()
                line.isNotEmpty() && name !in setOf("proxy-connection", "proxy-authorization", "connection", "keep-alive")
            }
            val rewritten = "$method $path $version\r\n" + headers.joinToString("") { "$it\r\n" } + "Connection: close\r\n\r\n"
            return ProxyRequest(host, if (uri.port > 0) uri.port else 80, tunnel = false, forward = rewritten.toByteArray(Charsets.ISO_8859_1))
        }
    }
}

/**
 * A small proxy on this phone that SyncUp's web pages go through while a DNS is on: the only way to give a
 * WebView different DNS. It looks up each site with [lookup] and joins the two ends; https stays encrypted
 * end to end, because it only ever passes the bytes along. It only runs while a DNS is on, listens only on
 * this phone, and won't connect back into it.
 */
internal class BrowsingProxy(
    private val lookup: (String) -> List<InetAddress>,
    private val allowed: (InetAddress) -> Boolean = { !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isLinkLocalAddress && !it.isMulticastAddress },
) {
    private val threads = Executors.newCachedThreadPool { r -> Thread(r, "browsing-proxy").apply { isDaemon = true } }
    private val open = ConcurrentHashMap.newKeySet<Socket>()
    private var server: ServerSocket? = null

    /** Starts listening. Returns the port, which only this phone can reach. */
    fun start(): Int {
        val listening = ServerSocket()
        listening.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0), BACKLOG)
        server = listening
        threads.execute {
            while (!listening.isClosed) {
                val client = try {
                    listening.accept()
                } catch (_: IOException) {
                    break
                }
                threads.execute { serve(client) }
            }
        }
        return listening.localPort
    }

    fun stop() {
        runCatching { server?.close() }
        open.forEach { closeQuietly(it) }
        threads.shutdown()
    }

    private fun serve(client: Socket) {
        open += client
        var remote: Socket? = null
        try {
            client.tcpNoDelay = true
            client.soTimeout = HEAD_WAIT_MS
            val input = BufferedInputStream(client.getInputStream(), BUFFER)
            val out = client.getOutputStream()
            val request = readHead(input)?.let(ProxyRequest::parse)
            if (request == null) {
                refuse(out, "400 Bad Request")
                return
            }
            remote = connect(request)
            if (remote == null) {
                refuse(out, "502 Bad Gateway")
                return
            }
            open += remote
            client.soTimeout = 0
            if (request.tunnel) {
                out.write(ESTABLISHED)
                out.flush()
            } else {
                remote.getOutputStream().write(request.forward!!)
                remote.getOutputStream().flush()
            }
            relay(client, input, remote)
        } catch (_: IOException) {
            // The page or the site went away: its connection just ends.
        } finally {
            closeQuietly(client)
            open -= client
            remote?.let {
                closeQuietly(it)
                open -= it
            }
        }
    }

    private fun connect(request: ProxyRequest): Socket? {
        for (address in lookup(request.host).filter(allowed)) {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.connect(InetSocketAddress(address, request.port), CONNECT_WAIT_MS)
                return socket
            } catch (_: IOException) {
                closeQuietly(socket)
            }
        }
        return null
    }

    /** Both directions at once, until the two ends have finished with each other. */
    private fun relay(client: Socket, clientInput: InputStream, remote: Socket) {
        val up = threads.submit {
            copy(clientInput, remote.getOutputStream())
            runCatching { remote.shutdownOutput() }
            // The page has finished sending: the site gets a moment to finish too, then it's over.
            runCatching { remote.soTimeout = AFTER_CLOSE_WAIT_MS }
        }
        copy(remote.getInputStream(), client.getOutputStream())
        runCatching { client.shutdownOutput() }
        runCatching { up.get(AFTER_CLOSE_WAIT_MS.toLong(), TimeUnit.MILLISECONDS) }
    }

    private fun copy(from: InputStream, to: OutputStream) {
        val buffer = ByteArray(BUFFER)
        try {
            while (true) {
                val n = from.read(buffer)
                if (n < 0) return
                to.write(buffer, 0, n)
                to.flush()
            }
        } catch (_: IOException) {
            // One end closed; the caller finishes the other.
        }
    }

    /** Up to the blank line that ends a request's head; null if there's no such thing. */
    private fun readHead(input: InputStream): String? {
        val head = ByteArrayOutputStream(512)
        var matched = 0
        while (head.size() < MAX_HEAD) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            matched = when {
                matched % 2 == 0 && b == '\r'.code -> matched + 1
                matched % 2 == 1 && b == '\n'.code -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) return head.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r\n\r\n")
        }
        return null
    }

    private fun refuse(out: OutputStream, status: String) {
        runCatching {
            out.write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
        }
    }

    private fun closeQuietly(socket: Socket) {
        runCatching { socket.close() }
    }

    private companion object {
        const val BACKLOG = 128
        const val BUFFER = 32 * 1024
        const val MAX_HEAD = 64 * 1024
        const val HEAD_WAIT_MS = 30_000
        const val CONNECT_WAIT_MS = 6_000
        const val AFTER_CLOSE_WAIT_MS = 5_000
        val ESTABLISHED = "HTTP/1.1 200 Connection established\r\n\r\n".toByteArray()
    }
}
