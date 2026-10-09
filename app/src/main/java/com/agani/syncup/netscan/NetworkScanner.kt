package com.agani.syncup.netscan

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class ScanDepth { COMMON, EXTENDED, CUSTOM }

class ScannedDevice(val address: String) {
    var name: String? = null
    val openPorts = mutableStateListOf<Int>()
}

data class WifiSubnet(val address: Inet4Address, val prefixLength: Int) {
    /** Every host address in this subnet, capped to a /22 (about 1,000 hosts) so a scan stays bounded. */
    fun hostAddresses(): List<String> {
        val effectivePrefix = prefixLength.coerceAtLeast(22)
        val bytes = address.address
        val ip = ((bytes[0].toInt() and 0xFF) shl 24) or ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        val hostBits = 32 - effectivePrefix
        if (hostBits <= 1) return emptyList()
        val mask = -1 shl hostBits
        val network = ip and mask
        val size = 1 shl hostBits
        return (1 until size - 1).map { h ->
            val a = network + h
            "${(a shr 24) and 0xFF}.${(a shr 16) and 0xFF}.${(a shr 8) and 0xFF}.${a and 0xFF}"
        }
    }
}

/**
 * Finds devices on the phone's own Wi-Fi subnet by trying a list of TCP ports on every address and
 * noting which ones answer — the same thing a NAS/printer-finder app does. Wi-Fi only (refuses to run
 * on mobile data); never reads the Wi-Fi SSID or location, so it needs no extra permission.
 */
object NetworkScanner {
    var scanning by mutableStateOf(false)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    val devices = mutableStateListOf<ScannedDevice>()
    private val stopRequested = AtomicBoolean(false)

    /** The Wi-Fi subnet the phone is on right now, or null (not on Wi-Fi, or it can't be told). */
    fun currentNetwork(context: Context): WifiSubnet? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val props = cm.getLinkProperties(network) ?: return null
        val link = props.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        return WifiSubnet(link.address as Inet4Address, link.prefixLength)
    }

    fun stop() {
        stopRequested.set(true)
    }

    /** Runs a scan, updating [devices] and [progress] as results come in. Returns when done or stopped. */
    suspend fun scan(context: Context, depth: ScanDepth, customRange: IntRange? = null) {
        val net = currentNetwork(context) ?: return
        val ports = when (depth) {
            ScanDepth.COMMON -> PortCatalog.COMMON
            ScanDepth.EXTENDED -> PortCatalog.EXTENDED
            ScanDepth.CUSTOM -> customRange?.toList().orEmpty()
        }
        val addresses = net.hostAddresses()
        if (addresses.isEmpty() || ports.isEmpty()) return

        scanning = true
        stopRequested.set(false)
        devices.clear()
        progress = 0f

        val byHost = ConcurrentHashMap<String, ScannedDevice>()
        val pairs = addresses.flatMap { host -> ports.map { port -> host to port } }
        val total = pairs.size
        val checked = AtomicInteger(0)
        val gate = Semaphore(192)

        withContext(Dispatchers.IO) {
            coroutineScope {
                pairs.map { (host, port) ->
                    async {
                        if (!stopRequested.get()) {
                            gate.withPermit {
                                if (!stopRequested.get() && tryConnect(host, port)) {
                                    val device = byHost.computeIfAbsent(host) { ScannedDevice(host).also { devices.add(it) } }
                                    if (port !in device.openPorts) device.openPorts.add(port)
                                }
                            }
                        }
                        val n = checked.incrementAndGet()
                        if (n % 8 == 0 || n == total) progress = n.toFloat() / total
                    }
                }.awaitAll()
            }
        }

        devices.toList().forEach { device ->
            if (!stopRequested.get()) {
                device.name = runCatching {
                    InetAddress.getByName(device.address).canonicalHostName.takeIf { it != device.address }
                }.getOrNull()
            }
        }
        scanning = false
    }

    private fun tryConnect(host: String, port: Int, timeoutMs: Int = 350): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        true
    } catch (e: Exception) {
        false
    }
}
