package com.agani.syncup.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.agani.syncup.browser.BrowserDb
import com.agani.syncup.browser.BrowserSettings
import com.agani.syncup.browser.PendingSync
import com.agani.syncup.data.ApiClient
import com.agani.syncup.data.AppPrefs
import com.agani.syncup.data.BrowserSyncRequest
import com.agani.syncup.data.OtherDevice
import com.agani.syncup.data.SyncChange
import com.agani.syncup.data.SyncTab
import com.agani.syncup.data.TabsSnapshot
import com.agani.syncup.data.ThemeMode
import com.agani.syncup.data.TokenStore
import com.agani.syncup.push.DeviceRegistrar
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the user can switch on/off under Settings → Sync. */
enum class SyncType(val pref: String, val label: String) {
    BOOKMARKS("bookmarks", "Bookmarks"),
    HISTORY("history", "History"),
    TABS("tabs", "Open tabs"),
    SETTINGS("settings", "Settings"),
}

/**
 * Browser sync: a signed-in user's Normal-section data across their devices.
 *
 * Synced: bookmarks, Normal history (the server keeps 90 days), settings (search engine, pop-up
 * blocking, theme) and the open Normal tabs, shown on the user's other devices. Never synced:
 * passwords, cookies, Work, Incognito, the app lock, downloads and site permissions. The latest
 * change wins and deletions travel as tombstones.
 *
 * This phone remembers the last account that signed in:
 *  - first-ever sign-in → what was browsed while signed out is uploaded;
 *  - the same account again → carry on from the saved cursor;
 *  - a different account → the previous person's Normal data is wiped here and the new account
 *    starts from its own synced data (never merged).
 * Signing out keeps the data on the phone and stops syncing.
 */
object BrowserSync {
    private const val BATCH = 800            // server accepts ≤ 1000 changes per call
    private const val MAX_ROUNDS = 12
    private const val DEBOUNCE_MS = 15_000L
    private const val SETTING_KEYS = "search_engine,block_popups,theme"

    private lateinit var app: Context
    private lateinit var db: BrowserDb
    private lateinit var prefs: SharedPreferences
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var pending: Job? = null
    @Volatile private var accountId: String? = null
    @Volatile private var applyingRemote = false

    // ---- observable state (Settings + tab switcher)
    var enabled by mutableStateOf(true)
        private set
    var syncing by mutableStateOf(false)
        private set
    var lastSyncMs by mutableLongStateOf(0L)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set
    var otherDevices by mutableStateOf<List<OtherDevice>>(emptyList())
        private set
    private val typeOn = SyncType.entries.associateWith { mutableStateOf(true) }

    /** Open Normal tabs to publish to the user's other devices (set by the Activity). */
    @Volatile var tabsProvider: (() -> List<SyncTab>)? = null

    /** A theme chosen on another device (the Activity applies it live). */
    @Volatile var onRemoteTheme: ((ThemeMode) -> Unit)? = null

    /** Bumped after remote changes land, so open lists (history, bookmarks) can reload. */
    var dataVersion by mutableLongStateOf(0L)
        private set

    val signedIn: Boolean get() = accountId != null

    /** Signed in with sync on — local changes will reach the user's other devices. */
    val active: Boolean get() = accountId != null && enabled

    fun isOn(type: SyncType): Boolean = typeOn.getValue(type).value

    fun init(context: Context, browserDb: BrowserDb) {
        if (::prefs.isInitialized) return
        app = context.applicationContext
        db = browserDb
        prefs = app.getSharedPreferences("browser_sync", Context.MODE_PRIVATE)
        enabled = prefs.getBoolean("enabled", true)
        SyncType.entries.forEach { typeOn.getValue(it).value = prefs.getBoolean("on_${it.pref}", true) }
        lastSyncMs = prefs.getLong("last_sync_ms", 0L)
        otherDevices = runCatching {
            gson.fromJson(prefs.getString("other_devices", "[]"), Array<OtherDevice>::class.java)?.toList()
        }.getOrNull().orEmpty()
        db.onLocalChange = { if (!applyingRemote) requestSync() }
        // Never signed in on this phone: deletions have nowhere to go, so drop their tombstones.
        if (prefs.getString("last_account", null) == null) scope.launch { db.purgeTombstones() }
        BrowserSettings.onChange = { kind, key -> localSettingChanged(kind, key) }
    }

    // ------------------------------------------------------------------ account changes
    /**
     * A user is signed in (fresh sign-in or restored session). Returns true when this is a different
     * account than the last one on this phone — the caller then closes the open Normal tabs and
     * clears Normal cookies; this function already wiped history and bookmarks.
     */
    suspend fun onSignedIn(id: String): Boolean = withContext(Dispatchers.IO) {
        if (accountId == id) return@withContext false
        val last = prefs.getString("last_account", null)
        var switched = false
        when {
            last == null -> {
                // First-ever sign-in: everything browsed while signed out goes up to the account.
                db.purgeTombstones()
                db.markAllDirty()
                markExistingSettingsDirty()
                prefs.edit().putString("cursor", "").apply()
            }
            last != id -> {
                switched = true
                applyingRemote = true
                try {
                    db.wipeBrowsingData()
                } finally {
                    applyingRemote = false
                }
                // The new account's own settings win (never merged with the previous person's).
                prefs.edit().apply {
                    prefs.all.keys.filter { it.startsWith("ms_") || it.startsWith("dirty_") }.forEach { remove(it) }
                    putString("cursor", "")
                    putString("other_devices", "[]")
                    putLong("last_sync_ms", 0L)
                }.apply()
                otherDevices = emptyList()
                lastSyncMs = 0L
                dataVersion++
            }
        }
        prefs.edit().putString("last_account", id).apply()
        accountId = id
        lastError = null
        switched
    }

    /** Signed out (or the session ended): stop syncing. Local data stays on the phone. */
    fun onSignedOut() {
        accountId = null
        pending?.cancel()
        syncing = false
    }

    /**
     * Just before a sign-out revokes [token]: push anything not yet uploaded and remove this
     * phone's open tabs from the user's other devices. Best-effort.
     */
    suspend fun finalFlush(token: String) {
        // Called after onSignedOut() (the account is already cleared here), so only the switch counts.
        if (!enabled) return
        withContext(Dispatchers.IO) {
            mutex.withLock { runCatching { runSync("Bearer $token", clearTabs = true) } }
        }
    }

    // ------------------------------------------------------------------ switches
    fun setSyncEnabled(on: Boolean) {
        if (on == enabled) return
        val token = TokenStore(app).token()
        enabled = on
        prefs.edit().putBoolean("enabled", on).apply()
        if (!on && token != null && accountId != null) {
            // One last call: this phone's tabs disappear from the other devices, and the server
            // records that sync is off here.
            scope.launch { mutex.withLock { runCatching { runSync("Bearer $token", clearTabs = true) } } }
        }
        if (on) {
            // Back on: merge both ways from scratch (latest change still wins).
            scope.launch {
                db.markAllDirty()
                markExistingSettingsDirty()
                prefs.edit().putString("cursor", "").apply()
                syncNow()
            }
        }
    }

    fun setOn(type: SyncType, on: Boolean) {
        typeOn.getValue(type).value = on
        prefs.edit().putBoolean("on_${type.pref}", on).apply()
        if (on) {
            // Changes that arrived while this was off were skipped: download everything again.
            scope.launch {
                when (type) {
                    SyncType.BOOKMARKS, SyncType.HISTORY -> db.markAllDirty()
                    SyncType.SETTINGS -> markExistingSettingsDirty()
                    SyncType.TABS -> Unit
                }
                prefs.edit().putString("cursor", "").apply()
                syncNow()
            }
        } else {
            requestSync(0)
        }
    }

    /** Settings → Delete synced data: erase the account's synced copy and turn sync off. */
    suspend fun deleteServerData(): Result<Unit> = runCatching {
        val token = TokenStore(app).token() ?: throw Exception("Sign in first")
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    ApiClient.service.browserSyncDelete("Bearer $token")
                } catch (e: java.io.IOException) {
                    throw Exception("No internet connection. Check your network and try again.")
                }
            }
        }
        enabled = false
        prefs.edit().putBoolean("enabled", false).putString("cursor", "").putString("other_devices", "[]").apply()
        otherDevices = emptyList()
    }

    // ------------------------------------------------------------------ triggers
    /** Sync soon (after local changes). Several changes in a row share one upload. */
    fun requestSync(delayMs: Long = DEBOUNCE_MS) {
        if (!active) return
        if (delayMs > 0 && pending?.isActive == true) return
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            syncNow()
        }
    }

    /** Sync right away (launch, return to the app, Settings → Sync now). */
    fun syncNow() {
        if (!active) return
        scope.launch {
            val token = TokenStore(app).token() ?: return@launch
            if (!mutex.tryLock()) return@launch // one already running
            syncing = true
            try {
                runSync("Bearer $token", clearTabs = false)
                lastError = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                lastError = "Waiting for a connection"
            } catch (e: Exception) {
                lastError = "Couldn't sync. Will try again."
            } finally {
                syncing = false
                mutex.unlock()
            }
        }
    }

    // ------------------------------------------------------------------ the sync round-trip
    private suspend fun runSync(auth: String, clearTabs: Boolean) {
        val deviceId = TokenStore(app).deviceId()
        var rounds = 0
        while (rounds++ < MAX_ROUNDS) {
            val bookmarks = if (isOn(SyncType.BOOKMARKS)) db.pendingSync("bookmark", BATCH) else emptyList()
            val history = if (isOn(SyncType.HISTORY)) db.pendingSync("history", BATCH - bookmarks.size) else emptyList()
            val settings = pendingSettings()
            val changes = bookmarks.map { it.toChange() } + history.map { it.toChange() } + settings.map { it.second }

            val state = com.agani.syncup.data.SyncStateDto(enabled, SyncType.entries.filter { isOn(it) }.map { it.pref })
            val tabs = when {
                clearTabs || !isOn(SyncType.TABS) -> TabsSnapshot(DeviceRegistrar.deviceModel(), emptyList(), state)
                else -> TabsSnapshot(DeviceRegistrar.deviceModel(), tabsProvider?.invoke().orEmpty().take(100), state)
            }
            val resp = ApiClient.service.browserSync(
                auth,
                BrowserSyncRequest(deviceId, prefs.getString("cursor", "").orEmpty(), changes, tabs),
            )
            db.markSynced("bookmark", bookmarks)
            db.markSynced("history", history)
            // Uploaded — unless it changed again meanwhile (then it goes up next time).
            settings.forEach { (flag, ch) ->
                if (prefs.getLong("ms_${ch.kind}/${ch.key}", 0L) == ch.updatedMs) prefs.edit().remove(flag).apply()
            }

            applyRemote(resp.changes.orEmpty())
            prefs.edit().putString("cursor", resp.cursor.orEmpty()).apply()

            val others = resp.otherDevices.orEmpty().filter { !it.tabs.isNullOrEmpty() }
            otherDevices = others
            lastSyncMs = System.currentTimeMillis()
            prefs.edit()
                .putString("other_devices", gson.toJson(others))
                .putLong("last_sync_ms", lastSyncMs)
                .apply()

            // More waiting (a big first upload goes up in batches)?
            val more = (isOn(SyncType.BOOKMARKS) && db.pendingSync("bookmark", 1).isNotEmpty()) ||
                (isOn(SyncType.HISTORY) && db.pendingSync("history", 1).isNotEmpty())
            if (!more || clearTabs) break
        }
    }

    private fun PendingSync.toChange(): SyncChange {
        val data = if (deleted) null else JsonObject().apply {
            addProperty("url", url)
            addProperty("title", title)
            addProperty(if (kind == "bookmark") "created_ms" else "visited_ms", timeMs)
        }
        return SyncChange(kind, key, data, updatedMs, deleted)
    }

    private suspend fun applyRemote(changes: List<SyncChange>) {
        if (changes.isEmpty()) return
        var touched = false
        applyingRemote = true
        try {
            val rows = changes.filter {
                (it.kind == "bookmark" && isOn(SyncType.BOOKMARKS)) || (it.kind == "history" && isOn(SyncType.HISTORY))
            }
            if (rows.isNotEmpty()) {
                db.inTransaction {
                    rows.forEach { c ->
                        val d = c.data
                        val time = d?.long(if (c.kind == "bookmark") "created_ms" else "visited_ms") ?: c.updatedMs
                        if (db.applyRemote(c.kind, c.key, d?.str("url").orEmpty(), d?.str("title").orEmpty(), time, c.updatedMs, c.deleted)) {
                            touched = true
                        }
                    }
                }
            }
            val prefsChanges = changes.filter { it.kind == "setting" && isOn(SyncType.SETTINGS) }
            if (prefsChanges.isNotEmpty()) {
                withContext(Dispatchers.Main) { prefsChanges.forEach { applyRemoteSetting(it) } }
            }
        } finally {
            applyingRemote = false
        }
        if (touched) dataVersion++
    }

    // ------------------------------------------------------------------ settings
    // Each synced setting is one item. "ms_<kind>/<key>" = when this phone last changed it
    // (or applied it from the server); "dirty_<kind>/<key>" = waiting to upload.

    private fun localSettingChanged(kind: String, key: String) {
        if (applyingRemote) return
        val id = "$kind/$key"
        prefs.edit().putLong("ms_$id", System.currentTimeMillis()).putBoolean("dirty_$id", true).apply()
        requestSync()
    }

    /** The theme lives in AppPrefs (the Activity reports changes here). */
    fun themeChanged() = localSettingChanged("setting", "theme")

    private fun allSettingIds(): List<String> = SETTING_KEYS.split(',').map { "setting/$it" }

    /** Mark this phone's settings for upload — only ones the user actually set. */
    private fun markExistingSettingsDirty() {
        val edit = prefs.edit()
        allSettingIds().forEach { id ->
            val ms = prefs.getLong("ms_$id", 0L)
            val nonDefault = when (id) {
                "setting/search_engine" -> BrowserSettings.searchEngine.name != "GOOGLE"
                "setting/block_popups" -> !BrowserSettings.blockPopups
                "setting/theme" -> AppPrefs(app).themeMode() != ThemeMode.SYSTEM
                else -> false
            }
            if (ms > 0L || nonDefault) {
                // Unknown change time counts as very old, so the account's own newer value wins.
                if (ms == 0L) edit.putLong("ms_$id", 1L)
                edit.putBoolean("dirty_$id", true)
            }
        }
        edit.apply()
    }

    /** Dirty settings as (dirty-flag key, change). */
    private fun pendingSettings(): List<Pair<String, SyncChange>> = allSettingIds().mapNotNull { id ->
        if (!prefs.getBoolean("dirty_$id", false)) return@mapNotNull null
        val (kind, key) = id.split('/')
        if (!isOn(SyncType.SETTINGS)) return@mapNotNull null
        val data = JsonObject()
        when (id) {
            "setting/search_engine" -> data.addProperty("value", BrowserSettings.searchEngine.name)
            "setting/block_popups" -> data.addProperty("value", BrowserSettings.blockPopups)
            "setting/theme" -> data.addProperty("value", AppPrefs(app).themeMode().name)
        }
        "dirty_$id" to SyncChange(kind, key, data, prefs.getLong("ms_$id", System.currentTimeMillis()), false)
    }

    /** Runs on the main thread (Compose state). Applied only when newer than this phone's value. */
    private fun applyRemoteSetting(c: SyncChange) {
        val id = "${c.kind}/${c.key}"
        if (id !in allSettingIds()) return
        if (c.updatedMs <= prefs.getLong("ms_$id", 0L)) return
        val d = c.data ?: JsonObject()
        when (id) {
            "setting/search_engine" -> d.str("value")?.let { BrowserSettings.applySyncedSearchEngine(it) }
            "setting/block_popups" -> d.bool("value")?.let { BrowserSettings.applySyncedBlockPopups(it) }
            "setting/theme" -> d.str("value")?.let { v ->
                runCatching { ThemeMode.valueOf(v) }.getOrNull()?.let { mode ->
                    AppPrefs(app).setThemeMode(mode)
                    onRemoteTheme?.invoke(mode)
                }
            }
        }
        prefs.edit().putLong("ms_$id", c.updatedMs).remove("dirty_$id").apply()
    }

    private fun JsonObject.str(k: String): String? = runCatching { get(k)?.takeIf { !it.isJsonNull }?.asString }.getOrNull()
    private fun JsonObject.long(k: String): Long? = runCatching { get(k)?.takeIf { !it.isJsonNull }?.asLong }.getOrNull()
    private fun JsonObject.bool(k: String): Boolean? = runCatching { get(k)?.takeIf { !it.isJsonNull }?.asBoolean }.getOrNull()
}
