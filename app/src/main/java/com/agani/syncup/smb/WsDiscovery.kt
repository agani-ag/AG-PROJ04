package com.agani.syncup.smb

import android.content.Context
import android.net.wifi.WifiManager
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.Collections

/**
 * Finds Windows PCs on this Wi-Fi that have "Network Discovery" turned on, using WS-Discovery — the
 * same protocol Windows itself uses to populate the "Network" view in File Explorer. This is separate
 * from [SmbDiscovery] (NSD/mDNS, "_smb._tcp") because Windows file sharing doesn't advertise itself
 * over mDNS at all; WS-Discovery is the actual protocol it speaks.
 *
 * A Probe is sent once to the WS-Discovery multicast group (239.255.255.250:3702); any device that
 * answers is added. This only finds the computer (by address) — it doesn't confirm a share exists or
 * is reachable without a password, same as a saved server you type in by hand.
 */
class WsDiscovery(private val context: Context) {
    val found = mutableStateListOf<FoundHost>()

    private var job: Job? = null
    private var lock: WifiManager.MulticastLock? = null

    fun start(scope: CoroutineScope, timeoutMs: Long = 4000) {
        if (job?.isActive == true) return
        found.clear()
        job = scope.launch(Dispatchers.IO) { discover(timeoutMs) }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun discover(timeoutMs: Long) {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return
        val mcLock = wifi.createMulticastLock("syncup-wsdiscovery").apply { setReferenceCounted(true); acquire() }
        lock = mcLock
        try {
            val ownAddresses = localAddresses()
            MulticastSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.bind(java.net.InetSocketAddress(3702))
                socket.soTimeout = 500
                runCatching { socket.loopbackMode = true } // "true" = DISABLE loopback (Java's naming, not a typo) — best-effort, not guaranteed by every network stack
                val group = InetAddress.getByName("239.255.255.250")
                runCatching { socket.joinGroup(group) }

                val probe = PROBE_TEMPLATE.replace("{id}", UUID.randomUUID().toString()).toByteArray(Charsets.UTF_8)
                socket.send(DatagramPacket(probe, probe.size, group, 3702))

                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(8192)
                while (System.currentTimeMillis() < deadline && currentCoroutineContext().isActive) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val addr = packet.address?.hostAddress ?: continue
                        // loopbackMode is only a hint — some stacks still deliver our own probe back to us.
                        if (addr in ownAddresses) continue
                        if (found.none { it.host == addr }) {
                            val name = runCatching { packet.address.canonicalHostName.takeIf { it != addr } }.getOrNull() ?: addr
                            found.add(FoundHost(name, addr, 445))
                        }
                    } catch (e: SocketTimeoutException) {
                        // Just means "nothing arrived in the last 500ms" — keep polling until the deadline.
                    }
                }
                runCatching { socket.leaveGroup(group) }
            }
        } finally {
            runCatching { mcLock.release() }
            lock = null
        }
    }

    /** Every IP address this phone itself answers to, so a looped-back probe never shows up as a "found" PC. */
    private fun localAddresses(): Set<String> = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .flatMap { Collections.list(it.inetAddresses) }
            .mapNotNull { it.hostAddress }
            .toSet()
    }.getOrDefault(emptySet())

    companion object {
        private const val PROBE_TEMPLATE = """<?xml version="1.0" encoding="UTF-8"?>
<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope" xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:wsd="http://schemas.xmlsoap.org/ws/2005/04/discovery">
<soap:Header><wsa:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</wsa:To><wsa:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</wsa:Action><wsa:MessageID>urn:uuid:{id}</wsa:MessageID></soap:Header>
<soap:Body><wsd:Probe/></soap:Body>
</soap:Envelope>"""
    }
}
