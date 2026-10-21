package com.agani.syncup.wellbeing

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import com.agani.syncup.data.SyncChange
import com.agani.syncup.sync.ItemSync
import com.agani.syncup.sync.SyncType
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One stored item of a Wellbeing tool: [data] as JSON, when it last changed, and its sync state. */
internal class WItem(
    val kind: String,
    val key: String,
    val data: JSONObject,
    val updatedMs: Long,
    val deleted: Boolean = false,
    /** Not uploaded yet. */
    val dirty: Boolean = true,
    /** Made while signed out — joins the account at the first sign-in. */
    val local: Boolean = false,
)

/**
 * A Wellbeing tool's items in one small file on the phone. Signed in, its [WellSync] keeps them on the
 * user's other phones the way Forest's trees are kept: the latest change wins, deletions travel as
 * tombstones, what was made signed out joins the account at the first sign-in, and a different
 * account's items leave the phone. Each tool's settings are one item with the key "settings".
 */
internal class WellItems(private val file: String) {
    private lateinit var app: Context
    private val lock = Any()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null
    private val all = LinkedHashMap<String, WItem>()

    /** Bumped on every change — screens read it to redraw. */
    var version by mutableLongStateOf(0L)
        private set

    /** Set by the tool; null for a tool that never syncs (Breaks). */
    var sync: ItemSync? = null

    /** After changes from another phone (reminders follow). */
    var onRemote: (() -> Unit)? = null

    val ready: Boolean get() = ::app.isInitialized

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        load()
    }

    // Reading goes through [watch], so a screen showing these items redraws when they change.
    fun items(kind: String): List<WItem> {
        watch()
        return synchronized(lock) { all.values.filter { it.kind == kind && !it.deleted && it.key != SETTINGS } }
    }

    fun item(kind: String, key: String): WItem? {
        watch()
        return synchronized(lock) { all["$kind/$key"]?.takeIf { !it.deleted } }
    }

    private fun watch(): Long = version

    fun settings(kind: String): JSONObject = item(kind, SETTINGS)?.data ?: JSONObject()

    fun put(kind: String, key: String, data: JSONObject, at: Long = System.currentTimeMillis()) {
        update { all["$kind/$key"] = WItem(kind, key, data, at, dirty = true, local = all["$kind/$key"]?.local ?: (sync?.signedIn != true)) }
    }

    fun putSettings(kind: String, data: JSONObject) = put(kind, SETTINGS, data)

    fun delete(kind: String, key: String) {
        val now = System.currentTimeMillis()
        update {
            val old = all["$kind/$key"] ?: return@update
            all["$kind/$key"] = WItem(kind, key, JSONObject(), now, deleted = true, dirty = true, local = old.local)
        }
    }

    // ------------------------------------------------------------------ for WellSync
    fun pendingChanges(limit: Int): List<SyncChange> = synchronized(lock) {
        all.values.filter { it.dirty }.take(limit).map {
            SyncChange(it.kind, it.key, if (it.deleted) null else JsonParser.parseString(it.data.toString()).asJsonObject, it.updatedMs, it.deleted)
        }
    }

    fun markUploaded(sent: List<SyncChange>) {
        if (sent.isEmpty()) return
        update(sync = false) {
            for (c in sent) {
                val k = "${c.kind}/${c.key}"
                val it = all[k] ?: continue
                if (it.updatedMs == c.updatedMs) all[k] = WItem(it.kind, it.key, it.data, it.updatedMs, it.deleted, dirty = false, local = false)
            }
        }
    }

    fun applyRemote(changes: List<SyncChange>) {
        if (changes.isEmpty()) return
        update(sync = false) {
            for (c in changes) {
                val k = "${c.kind}/${c.key}"
                val old = all[k]
                if (old != null && old.updatedMs >= c.updatedMs) continue
                val data = c.data?.let { runCatching { JSONObject(it.toString()) }.getOrNull() } ?: JSONObject()
                all[k] = WItem(c.kind, c.key, data, c.updatedMs, c.deleted, dirty = false, local = false)
            }
        }
        onRemote?.invoke()
    }

    fun accountChanged(previous: String?) {
        update(sync = false) {
            if (previous != null) all.entries.removeAll { !it.value.local }
            for ((k, v) in all.entries.toList()) if (v.local) all[k] = WItem(v.kind, v.key, v.data, v.updatedMs, v.deleted, dirty = true, local = false)
        }
        onRemote?.invoke()
    }

    fun markAllDirty() {
        update(sync = false) {
            for ((k, v) in all.entries.toList()) all[k] = WItem(v.kind, v.key, v.data, v.updatedMs, v.deleted, dirty = true, local = v.local)
        }
    }

    // ------------------------------------------------------------------ storage
    private fun update(sync: Boolean = true, block: () -> Unit) {
        synchronized(lock) {
            block()
            version++
        }
        saveJob?.cancel()
        saveJob = io.launch {
            delay(250)
            flush()
        }
        if (sync) this.sync?.requestSync()
    }

    fun flush() {
        if (!ready) return
        val arr = synchronized(lock) {
            JSONArray(all.values.map {
                JSONObject().put("kind", it.kind).put("key", it.key).put("data", it.data).put("updated_ms", it.updatedMs)
                    .put("deleted", it.deleted).put("dirty", it.dirty).put("local", it.local)
            })
        }
        runCatching {
            val f = File(app.filesDir, file)
            val tmp = File(app.filesDir, "$file.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }
    }

    private fun load() {
        val arr = runCatching { JSONArray(File(app.filesDir, file).readText()) }.getOrNull() ?: JSONArray()
        synchronized(lock) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val kind = o.optString("kind")
                val key = o.optString("key")
                if (kind.isBlank() || key.isBlank()) continue
                all["$kind/$key"] = WItem(kind, key, o.optJSONObject("data") ?: JSONObject(), o.optLong("updated_ms"), o.optBoolean("deleted"), o.optBoolean("dirty"), o.optBoolean("local"))
            }
            version++
        }
    }

    companion object {
        const val SETTINGS = "settings"
    }
}

/** A Wellbeing tool's own sync (Settings → Sync has a switch for each). */
internal class WellSync(name: String, type: SyncType, kinds: List<String>, private val items: WellItems) : ItemSync(name, type, kinds) {
    override suspend fun pending(limit: Int) = items.pendingChanges(limit)
    override suspend fun uploaded(sent: List<SyncChange>) = items.markUploaded(sent)
    override suspend fun applyRemote(changes: List<SyncChange>) = items.applyRemote(changes)
    override suspend fun markAllDirty() = items.markAllDirty()
    override suspend fun accountChanged(previous: String?, current: String) = items.accountChanged(previous)
}

/** A JSON array of strings as a list. */
internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }

/** A JSON array of ints as a list. */
internal fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else (0 until length()).map { optInt(it) }
