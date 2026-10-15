package com.agani.syncup.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.agani.syncup.data.ApiClient
import com.agani.syncup.data.BrowserSyncRequest
import com.agani.syncup.data.SyncChange
import com.agani.syncup.data.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Sync for one app feature (Calendar, Forest) through the same account sync as the browser, but
 * with its own item kinds and its own cursor — so it can run on its own: from a silent "sync" push
 * in the background, or when the feature changes something.
 *
 * It follows Settings → Sync: the "Sync on this phone" switch and the feature's own switch there.
 * The latest change wins (each item carries the time it was changed); deletions are tombstones.
 */
abstract class ItemSync(private val name: String, val type: SyncType, val kinds: List<String>) {
    protected lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var pending: Job? = null

    var syncing by mutableStateOf(false)
        private set
    var lastSyncMs by mutableLongStateOf(0L)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set

    /** Changes waiting to go up — at most [limit]. */
    protected abstract suspend fun pending(limit: Int): List<SyncChange>

    /** [sent] reached the server (each one is done unless it changed again meanwhile). */
    protected abstract suspend fun uploaded(sent: List<SyncChange>)

    /** The server's changes for this feature's kinds. Apply each one only when it's newer. */
    protected abstract suspend fun applyRemote(changes: List<SyncChange>)

    /**
     * An account signed in that isn't the last one this feature synced with. [previous] is null on
     * the first sign-in ever; else the previous person's synced items should leave this phone.
     */
    protected abstract suspend fun accountChanged(previous: String?, current: String)

    /** Everything here goes up again (sync switched back on). */
    protected abstract suspend fun markAllDirty()

    fun attach(context: Context) {
        if (::prefs.isInitialized) return
        app = context.applicationContext
        prefs = app.getSharedPreferences("item_sync_$name", Context.MODE_PRIVATE)
        lastSyncMs = prefs.getLong("last_sync_ms", 0L)
        all.add(this)
    }

    /** Signed in now, as this account (set at sign-in, cleared at sign-out). */
    val signedIn: Boolean get() = ::prefs.isInitialized && prefs.getString("active", null) != null

    /** Signed in with sync on for this feature: changes here will reach the other phones. */
    val active: Boolean
        get() = signedIn && TokenStore(app).token() != null && BrowserSync.switchOn(app, type)

    suspend fun onSignedIn(accountId: String) = withContext(Dispatchers.IO) {
        val last = prefs.getString("account", null)
        if (last != accountId) {
            accountChanged(last, accountId)
            prefs.edit().putString("account", accountId).putString("cursor", "").apply()
        }
        prefs.edit().putString("active", accountId).apply()
    }

    fun onSignedOut() {
        if (!::prefs.isInitialized) return
        pending?.cancel()
        prefs.edit().remove("active").apply()
    }

    /** Sync soon — several changes in a row share one upload. */
    fun requestSync(delayMs: Long = 8_000) {
        if (!active) return
        if (delayMs > 0 && pending?.isActive == true) return
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            syncBlocking()
        }
    }

    fun syncNow() {
        if (!active) return
        scope.launch { syncBlocking() }
    }

    /** One full round (also used by the background ping). True when it reached the server. */
    suspend fun syncBlocking(token: String? = null): Boolean = withContext(Dispatchers.IO) {
        if (token == null && !active) return@withContext false
        val t = token ?: TokenStore(app).token() ?: return@withContext false
        if (!mutex.tryLock()) return@withContext false
        syncing = true
        try {
            runSync("Bearer $t")
            lastError = null
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            lastError = "Waiting for a connection"
            false
        } catch (e: Exception) {
            lastError = "Couldn't sync. Will try again."
            false
        } finally {
            syncing = false
            mutex.unlock()
        }
    }

    /** The feature's switch came back on (or sync as a whole): send everything, fetch everything. */
    fun resync() {
        if (!::prefs.isInitialized) return
        scope.launch {
            markAllDirty()
            prefs.edit().putString("cursor", "").apply()
            syncBlocking()
        }
    }

    /** The account's synced copy was erased: start over next time. */
    fun forgetCursor() {
        if (::prefs.isInitialized) prefs.edit().putString("cursor", "").apply()
    }

    private suspend fun runSync(auth: String) {
        val deviceId = TokenStore(app).deviceId()
        var rounds = 0
        while (rounds++ < 10) {
            val changes = pending(BATCH)
            val resp = ApiClient.service.browserSync(
                auth,
                BrowserSyncRequest(deviceId, prefs.getString("cursor", "").orEmpty(), changes, tabs = null, kinds = kinds),
            )
            uploaded(changes)
            applyRemote(resp.changes.orEmpty().filter { it.kind in kinds })
            prefs.edit().putString("cursor", resp.cursor.orEmpty()).apply()
            lastSyncMs = System.currentTimeMillis()
            prefs.edit().putLong("last_sync_ms", lastSyncMs).apply()
            if (changes.size < BATCH) break
        }
    }

    companion object {
        private const val BATCH = 500
        private val all = mutableListOf<ItemSync>()

        fun forType(type: SyncType): ItemSync? = all.firstOrNull { it.type == type }

        suspend fun signedInAll(accountId: String) = all.forEach { it.onSignedIn(accountId) }

        fun signedOutAll() = all.forEach { it.onSignedOut() }

        fun syncAllNow() = all.forEach { it.syncNow() }

        /** Just before sign-out revokes [token]: send what's waiting. */
        suspend fun finalFlushAll(token: String) = all.forEach { runCatching { it.syncBlocking(token) } }

        fun forgetAllCursors() = all.forEach { it.forgetCursor() }

        fun resyncAll() = all.forEach { if (it.active) it.resync() }
    }
}

/**
 * A silent "sync" push arrived (another phone changed the user's to-dos). Fetch them in the
 * background — even with SyncUp closed — so this phone's reminders follow.
 */
class SyncPingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        // "forest": trees added to the account from the server (manage.py forest_trees).
        if (inputData.getString(KEY_WHAT) == "forest") {
            com.agani.syncup.forest.ForestStore.init(ctx)
            val ok = com.agani.syncup.forest.ForestSync.syncBlocking()
            return if (ok || !com.agani.syncup.forest.ForestSync.active) Result.success() else Result.retry()
        }
        com.agani.syncup.calendar.CalendarStore.init(ctx)
        val ok = com.agani.syncup.calendar.CalendarSync.syncBlocking()
        com.agani.syncup.calendar.CalendarReminders.reschedule(ctx)
        return if (ok || !com.agani.syncup.calendar.CalendarSync.active) Result.success() else Result.retry()
    }

    companion object {
        private const val KEY_WHAT = "what"

        /** [what]: "calendar" (the default) or "forest" — the push's "what". */
        fun enqueue(context: Context, what: String? = null) {
            val forest = what == "forest"
            WorkManager.getInstance(context).enqueueUniqueWork(
                if (forest) "sync_ping_forest" else "sync_ping", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncPingWorker>()
                    .setInputData(androidx.work.workDataOf(KEY_WHAT to (if (forest) "forest" else "calendar")))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }
}
