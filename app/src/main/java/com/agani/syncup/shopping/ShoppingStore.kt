package com.agani.syncup.shopping

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
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

/**
 * Tools → Shopping: the lists and their items, kept in one small file on the phone (works offline and
 * signed out). Signed in, [ShoppingSync] carries them to the user's other phones: the latest change
 * wins, deletions travel as tombstones, what was made signed out joins the account at the next
 * sign-in, and a different account's lists leave the phone. The names bought before ([history]) stay
 * on this phone only — they feed the quick chips.
 */
internal object ShoppingStore {
    private const val FILE = "shopping.json"
    private const val TOMBSTONE_DAYS = 60L
    private const val HISTORY_MAX = 150

    private lateinit var app: Context
    private val lock = Any()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    /** Every list and item, deleted ones too (their deletion still has to reach the other phones). */
    private var allLists: List<ShopList> = emptyList()
    private var allItems: List<ShopItem> = emptyList()
    private var past: List<PastItem> = emptyList()

    /** Live lists, in order. */
    var lists by mutableStateOf<List<ShopList>>(emptyList())
        private set
    /** Live items whose list is live. */
    var items by mutableStateOf<List<ShopItem>>(emptyList())
        private set
    /** Things bought before, newest first (this phone only). */
    var history by mutableStateOf<List<PastItem>>(emptyList())
        private set
    /** Bumped on every change. */
    var version by mutableLongStateOf(0L)
        private set

    val ready: Boolean get() = ::app.isInitialized

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        load()
        ShoppingSync.attach(app)
    }

    // ------------------------------------------------------------------ reading
    fun list(id: String): ShopList? = lists.firstOrNull { it.id == id }

    fun itemsOf(listId: String): List<ShopItem> = items.filter { it.listId == listId }

    /** To buy, and bought, in [listId]. */
    fun counts(listId: String): Pair<Int, Int> {
        var toBuy = 0
        var bought = 0
        for (i in items) if (i.listId == listId) if (i.bought) bought++ else toBuy++
        return toBuy to bought
    }

    /** Things bought before that aren't in [listId] now — for the quick chips. */
    fun suggestions(listId: String, max: Int = 12): List<PastItem> {
        val here = itemsOf(listId).map { it.name.trim().lowercase() }.toSet()
        return history.filter { it.name.lowercase() !in here }.take(max)
    }

    fun pastFor(name: String): PastItem? {
        val k = name.trim().lowercase()
        if (k.isEmpty()) return null
        return history.firstOrNull { it.name.lowercase() == k }
    }

    // ------------------------------------------------------------------ lists
    fun newList(name: String, color: String): ShopList {
        val l = ShopList(name = name.trim(), color = color, order = (allLists.filter { !it.deleted }.maxOfOrNull { it.order } ?: -1) + 1)
        saveList(l)
        return l
    }

    fun saveList(list: ShopList) {
        val now = System.currentTimeMillis()
        update {
            val old = allLists.firstOrNull { it.id == list.id }
            val l = list.copy(updatedMs = now, dirty = true, local = old?.local ?: !ShoppingSync.signedIn)
            allLists = allLists.filterNot { it.id == list.id } + l
        }
    }

    /** A list goes, with its items. */
    fun deleteList(id: String) {
        val now = System.currentTimeMillis()
        update {
            allLists = allLists.map { if (it.id == id && !it.deleted) it.copy(deleted = true, updatedMs = now, dirty = true) else it }
            allItems = allItems.map { if (it.listId == id && !it.deleted) it.copy(deleted = true, updatedMs = now, dirty = true) else it }
        }
    }

    // ------------------------------------------------------------------ items
    /**
     * Adds a thing to [listId]. One already there by the same name is updated instead (and back to
     * buy), so a list never has it twice.
     */
    fun addItem(listId: String, name: String, qty: Double, unit: String, aisle: String, note: String = "") {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val same = allItems.firstOrNull { it.listId == listId && !it.deleted && it.name.trim().equals(clean, ignoreCase = true) }
        if (same != null) {
            saveItem(same.copy(name = clean, qty = qty, unit = unit, aisle = aisle, note = note.trim(), bought = false, boughtMs = 0))
            return
        }
        val order = (allItems.filter { it.listId == listId && !it.deleted }.maxOfOrNull { it.order } ?: -1) + 1
        saveItem(ShopItem(listId = listId, name = clean, qty = qty, unit = unit, aisle = aisle, note = note.trim(), order = order))
    }

    fun saveItem(item: ShopItem) {
        val now = System.currentTimeMillis()
        update {
            val old = allItems.firstOrNull { it.id == item.id }
            val i = item.copy(name = item.name.trim(), updatedMs = now, dirty = true, local = old?.local ?: !ShoppingSync.signedIn)
            allItems = allItems.filterNot { it.id == item.id } + i
            recordPast(i.name, i.qty, i.unit, i.aisle, now)
        }
    }

    fun setBought(id: String, bought: Boolean) {
        val i = allItems.firstOrNull { it.id == id && !it.deleted } ?: return
        if (i.bought == bought) return
        saveItem(i.copy(bought = bought, boughtMs = if (bought) System.currentTimeMillis() else 0))
    }

    fun deleteItem(id: String) {
        val now = System.currentTimeMillis()
        update { allItems = allItems.map { if (it.id == id && !it.deleted) it.copy(deleted = true, updatedMs = now, dirty = true) else it } }
    }

    /** The Bought pile empties (the names stay in [history] for the chips). */
    fun clearBought(listId: String) {
        val now = System.currentTimeMillis()
        update {
            allItems = allItems.map { if (it.listId == listId && it.bought && !it.deleted) it.copy(deleted = true, updatedMs = now, dirty = true) else it }
        }
    }

    fun uncheckAll(listId: String) {
        val now = System.currentTimeMillis()
        update {
            allItems = allItems.map {
                if (it.listId == listId && it.bought && !it.deleted) it.copy(bought = false, boughtMs = 0, updatedMs = now, dirty = true) else it
            }
        }
    }

    fun forgetHistory() = update(sync = false) { past = emptyList() }

    /** Called inside [update]. */
    private fun recordPast(name: String, qty: Double, unit: String, aisle: String, at: Long) {
        val k = name.trim().lowercase()
        if (k.isEmpty()) return
        val older = past.firstOrNull { it.name.lowercase() == k }
        if (older != null && older.usedMs > at) return
        past = (listOf(PastItem(name.trim(), qty, unit, aisle, at)) + past.filterNot { it.name.lowercase() == k })
            .sortedByDescending { it.usedMs }.take(HISTORY_MAX)
    }

    // ------------------------------------------------------------------ for ShoppingSync
    internal fun pendingChanges(limit: Int): List<SyncChange> = synchronized(lock) {
        val lists = allLists.filter { it.dirty }.map {
            SyncChange("shoplist", it.id, if (it.deleted) null else JsonParser.parseString(it.syncJson().toString()).asJsonObject, it.updatedMs, it.deleted)
        }
        val items = allItems.filter { it.dirty }.take(limit).map {
            SyncChange("shopitem", it.id, if (it.deleted) null else JsonParser.parseString(it.syncJson().toString()).asJsonObject, it.updatedMs, it.deleted)
        }
        (lists + items).take(limit)
    }

    internal fun markUploaded(sent: List<SyncChange>) {
        if (sent.isEmpty()) return
        val done = sent.associate { "${it.kind}/${it.key}" to it.updatedMs }
        update(sync = false) {
            allLists = allLists.map { if (done["shoplist/${it.id}"] == it.updatedMs) it.copy(dirty = false, local = false) else it }
            allItems = allItems.map { if (done["shopitem/${it.id}"] == it.updatedMs) it.copy(dirty = false, local = false) else it }
        }
    }

    internal fun applyRemote(changes: List<SyncChange>) {
        if (changes.isEmpty()) return
        update(sync = false) {
            val lists = allLists.associateBy { it.id }.toMutableMap()
            val items = allItems.associateBy { it.id }.toMutableMap()
            for (c in changes) {
                val data = c.data?.let { runCatching { JSONObject(it.toString()) }.getOrNull() } ?: JSONObject()
                when (c.kind) {
                    "shoplist" -> {
                        val old = lists[c.key]
                        if (old != null && old.updatedMs >= c.updatedMs) continue
                        lists[c.key] = ShopList.fromJson(c.key, data, c.updatedMs, c.deleted)
                    }
                    "shopitem" -> {
                        val old = items[c.key]
                        if (old != null && old.updatedMs >= c.updatedMs) continue
                        val i = ShopItem.fromJson(c.key, data, c.updatedMs, c.deleted)
                        items[c.key] = i
                        if (!i.deleted) recordPast(i.name, i.qty, i.unit, i.aisle, i.updatedMs)
                    }
                }
            }
            allLists = lists.values.toList()
            allItems = items.values.toList()
        }
    }

    /**
     * An account signed in that isn't the last one here. [previous] null = the first sign-in ever.
     * Either way what was made signed out joins the new account; a previous person's synced lists leave.
     */
    internal fun accountChanged(previous: String?) {
        update(sync = false) {
            if (previous != null) {
                allLists = allLists.filter { it.local }
                allItems = allItems.filter { it.local }
                if (allLists.none { it.id == ShopList.DEFAULT_ID }) allLists = allLists + ShopList.groceries()
            }
            allLists = allLists.map { if (it.local) it.copy(local = false, dirty = true) else it }
            allItems = allItems.map { if (it.local) it.copy(local = false, dirty = true) else it }
        }
    }

    /** Everything goes up again (sync switched back on). The untouched starting list stays put. */
    internal fun markAllDirty() {
        update(sync = false) {
            allLists = allLists.map { if (it.updatedMs > 1) it.copy(dirty = true) else it }
            allItems = allItems.map { it.copy(dirty = true) }
        }
    }

    // ------------------------------------------------------------------ storage
    private fun update(sync: Boolean = true, block: () -> Unit) {
        synchronized(lock) {
            block()
            publish()
        }
        scheduleSave()
        if (sync) ShoppingSync.requestSync()
    }

    private fun publish() {
        val live = allLists.filter { !it.deleted }.sortedWith(compareBy({ it.order }, { it.name.lowercase() }))
        val ids = live.mapTo(HashSet()) { it.id }
        lists = live
        items = allItems.filter { !it.deleted && it.listId in ids }
        history = past
        version++
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = io.launch {
            delay(250)
            flush()
        }
    }

    fun flush() {
        if (!ready) return
        val (l, i, h) = synchronized(lock) { Triple(allLists, allItems, past) }
        val cutoff = System.currentTimeMillis() - TOMBSTONE_DAYS * 86_400_000
        val json = JSONObject()
            .put("lists", JSONArray(l.filter { !(it.deleted && !it.dirty && it.updatedMs < cutoff) }.map { it.storeJson() }))
            .put("items", JSONArray(i.filter { !(it.deleted && !it.dirty && it.updatedMs < cutoff) }.map { it.storeJson() }))
            .put("history", JSONArray(h.map { it.json() }))
        runCatching {
            val file = File(app.filesDir, FILE)
            val tmp = File(app.filesDir, "$FILE.tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun load() {
        val file = File(app.filesDir, FILE)
        val first = !file.exists()
        val json = if (first) null else runCatching { JSONObject(file.readText()) }.getOrNull()
        synchronized(lock) {
            if (json != null) {
                val ls = json.optJSONArray("lists") ?: JSONArray()
                allLists = (0 until ls.length()).mapNotNull { runCatching { ShopList.fromStore(ls.getJSONObject(it)) }.getOrNull() }.filter { it.id.isNotBlank() }
                val its = json.optJSONArray("items") ?: JSONArray()
                allItems = (0 until its.length()).mapNotNull { runCatching { ShopItem.fromStore(its.getJSONObject(it)) }.getOrNull() }.filter { it.id.isNotBlank() }
                val hs = json.optJSONArray("history") ?: JSONArray()
                past = (0 until hs.length()).mapNotNull { hs.optJSONObject(it)?.let(PastItem::from) }
            } else if (first) {
                // The very first open: one list to start with.
                allLists = listOf(ShopList.groceries())
            }
            publish()
        }
        if (first) scheduleSave()
    }
}

/** Shopping's own sync: lists and items, through the account sync (see [ItemSync]). */
internal object ShoppingSync : ItemSync("shopping", SyncType.SHOPPING, listOf("shoplist", "shopitem")) {
    override suspend fun pending(limit: Int) = ShoppingStore.pendingChanges(limit)
    override suspend fun uploaded(sent: List<SyncChange>) = ShoppingStore.markUploaded(sent)
    override suspend fun applyRemote(changes: List<SyncChange>) = ShoppingStore.applyRemote(changes)
    override suspend fun markAllDirty() = ShoppingStore.markAllDirty()
    override suspend fun accountChanged(previous: String?, current: String) = ShoppingStore.accountChanged(previous)
}
