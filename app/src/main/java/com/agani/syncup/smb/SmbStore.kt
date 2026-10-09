package com.agani.syncup.smb

import android.content.Context
import android.content.SharedPreferences
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/** Saved servers, encrypted on the phone (passwords included) — same pattern as the sign-in token. */
object SmbStore {
    private const val PREFS = "smb_servers"
    private const val KEY = "servers"

    private var prefs: SharedPreferences? = null

    private fun open(context: Context): SharedPreferences = prefs ?: run {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, PREFS, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ).also { prefs = it }
    }

    fun servers(context: Context): List<SmbServer> = runCatching {
        val a = JSONArray(open(context).getString(KEY, "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            SmbServer(o.getString("id"), o.optString("n"), o.getString("h"), o.getString("sh"), o.optString("u"), o.optString("p"), o.optLong("t"))
        }
    }.getOrDefault(emptyList()).sortedByDescending { it.lastUsed }

    fun save(context: Context, server: SmbServer) {
        val list = listOf(server) + servers(context).filter { it.id != server.id }
        write(context, list)
    }

    fun remove(context: Context, id: String) {
        write(context, servers(context).filter { it.id != id })
    }

    fun touch(context: Context, id: String) {
        val list = servers(context).map { if (it.id == id) it.copy(lastUsed = System.currentTimeMillis()) else it }
        write(context, list)
    }

    private fun write(context: Context, list: List<SmbServer>) {
        val a = JSONArray()
        list.forEach {
            a.put(
                JSONObject().put("id", it.id).put("n", it.name).put("h", it.host).put("sh", it.share)
                    .put("u", it.username).put("p", it.password).put("t", it.lastUsed),
            )
        }
        open(context).edit().putString(KEY, a.toString()).apply()
    }
}

/**
 * Servers that advertise file sharing on this Wi-Fi (NSD/mDNS, "_smb._tcp"). Finds Samba and most
 * NAS boxes; a plain Windows PC share usually won't show here (Windows doesn't advertise this way) —
 * add it by address instead, or find it with the Network scanner.
 */
class SmbDiscovery(private val context: Context) {
    val found = mutableStateListOf<FoundHost>()
    var discovering by mutableStateOf(false)
        private set

    private val manager by lazy { context.getSystemService(Context.NSD_SERVICE) as NsdManager }
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (discovering) return
        found.clear()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) { discovering = true }
            override fun onDiscoveryStopped(serviceType: String) { discovering = false }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { discovering = false }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { discovering = false }
            override fun onServiceFound(info: NsdServiceInfo) {
                runCatching {
                    manager.resolveService(
                        info,
                        object : NsdManager.ResolveListener {
                            override fun onResolveFailed(i: NsdServiceInfo, errorCode: Int) {}
                            override fun onServiceResolved(i: NsdServiceInfo) {
                                @Suppress("DEPRECATION") val address = i.host?.hostAddress ?: return
                                if (found.none { it.host == address }) found.add(FoundHost(i.serviceName ?: address, address, i.port))
                            }
                        },
                    )
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        listener = l
        runCatching { manager.discoverServices("_smb._tcp.", NsdManager.PROTOCOL_DNS_SD, l) }
    }

    fun stop() {
        listener?.let { runCatching { manager.stopServiceDiscovery(it) } }
        listener = null
        discovering = false
    }
}
