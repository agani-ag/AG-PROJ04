package com.agani.syncup.dns

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Puts a question to a DNS service over what its address calls for. These go straight to the service: only
 * web pages pass through the browsing proxy, and a service's own host name comes from the phone's DNS.
 */
internal object DnsTransports {
    private const val TLS_PORT = 853
    private const val PLAIN_PORT = 53
    private const val TIMEOUT_MS = 4_000
    private const val PLAIN_WAIT_MS = 2_500
    private val MESSAGE = "application/dns-message".toMediaType()

    private val https by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .build()
    }

    /** How to ask the service at [address]; null when it isn't a DNS address. */
    fun forAddress(address: String): ((ByteArray) -> ByteArray)? {
        val a = address.trim()
        return when (DnsKind.of(a)) {
            DnsKind.HTTPS -> { q -> overHttps(a, q) }
            DnsKind.TLS -> { q -> overTls(a, q) }
            DnsKind.PLAIN -> { q -> overPlain(a, q) }
            null -> null
        }
    }

    private fun overHttps(url: String, query: ByteArray): ByteArray {
        val request = Request.Builder().url(url).post(query.toRequestBody(MESSAGE)).header("Accept", MESSAGE.toString()).build()
        https.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.body?.bytes() ?: throw IOException("Empty reply")
        }
    }

    private fun overTls(host: String, query: ByteArray): ByteArray {
        Socket().use { plain ->
            plain.soTimeout = TIMEOUT_MS
            plain.connect(InetSocketAddress(host, TLS_PORT), TIMEOUT_MS)
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, TLS_PORT, false) as SSLSocket
            tls.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tls.session)) throw IOException("Wrong certificate")
            return overStream(tls, query)
        }
    }

    private fun overPlain(ip: String, query: ByteArray): ByteArray {
        val server = InetAddress.getByName(ip)
        DatagramSocket().use { s ->
            s.soTimeout = PLAIN_WAIT_MS
            s.connect(server, PLAIN_PORT)
            s.send(DatagramPacket(query, query.size))
            val buffer = ByteArray(4_096)
            val reply = DatagramPacket(buffer, buffer.size)
            s.receive(reply)
            val data = buffer.copyOf(reply.length)
            if (!DnsWire.isTruncated(data)) return data
        }
        // Too long for one packet: the same question over TCP.
        Socket().use { s ->
            s.soTimeout = TIMEOUT_MS
            s.connect(InetSocketAddress(server, PLAIN_PORT), TIMEOUT_MS)
            return overStream(s, query)
        }
    }

    /** TCP and TLS put the message's length in front of it. */
    private fun overStream(socket: Socket, query: ByteArray): ByteArray {
        val out = DataOutputStream(socket.getOutputStream())
        out.writeShort(query.size)
        out.write(query)
        out.flush()
        val input = DataInputStream(socket.getInputStream())
        val reply = ByteArray(input.readUnsignedShort())
        input.readFully(reply)
        return reply
    }
}
