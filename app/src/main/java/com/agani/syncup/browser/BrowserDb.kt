package com.agani.syncup.browser

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

data class HistoryEntry(val id: Long, val url: String, val title: String, val visitedAt: Long)
data class Bookmark(val id: Long, val url: String, val title: String, val createdAt: Long)

/**
 * One shared downloads list for every section. Work downloads carry the link name as their source
 * (never the URL) and are labelled "SyncUp" in the list.
 */
data class DownloadEntry(
    val id: Long,
    val fileName: String,
    val source: String,
    val work: Boolean,
    val systemId: Long,
    val createdAt: Long,
)

/** One row waiting to be uploaded by browser sync (see [com.agani.syncup.sync.SyncManager]). */
data class PendingSync(
    val kind: String,      // "bookmark" | "history"
    val key: String,
    val url: String,
    val title: String,
    val timeMs: Long,      // created_at / visited_at
    val updatedMs: Long,
    val deleted: Boolean,
)

/**
 * On-device browser data. History and bookmarks are recorded for the NORMAL section only — Work and
 * Incognito keep none. Plain SQLite without Room.
 *
 * Sync bookkeeping (v2): every row has a stable `sync_key`, the time of its last change
 * (`updated_ms`), a `dirty` flag (waiting to upload) and a `deleted` tombstone, so a deletion on one
 * phone reaches the user's other phones. Tombstones are dropped once uploaded (or when the user
 * isn't syncing). Reads always skip deleted rows.
 */
class BrowserDb private constructor(context: Context) : SQLiteOpenHelper(context.applicationContext, "browser.db", null, 2) {

    companion object {
        @Volatile private var instance: BrowserDb? = null

        /** One helper per process (the Activity, tabs and sync share it). */
        fun get(context: Context): BrowserDb =
            instance ?: synchronized(this) { instance ?: BrowserDb(context).also { instance = it } }
    }

    /** Called after any local change to history or bookmarks (sync schedules an upload). */
    @Volatile var onLocalChange: (() -> Unit)? = null

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL, " +
                "title TEXT NOT NULL DEFAULT '', visited_at INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX idx_history_time ON history(visited_at)")
        db.execSQL(
            "CREATE TABLE bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL UNIQUE, " +
                "title TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE downloads (id INTEGER PRIMARY KEY AUTOINCREMENT, file_name TEXT NOT NULL, " +
                "source TEXT NOT NULL DEFAULT '', work INTEGER NOT NULL DEFAULT 0, " +
                "system_id INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)",
        )
        addSyncColumns(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) addSyncColumns(db)
    }

    private fun addSyncColumns(db: SQLiteDatabase) {
        for ((table, timeCol) in listOf("history" to "visited_at", "bookmarks" to "created_at")) {
            db.execSQL("ALTER TABLE $table ADD COLUMN sync_key TEXT")
            db.execSQL("ALTER TABLE $table ADD COLUMN updated_ms INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $table ADD COLUMN dirty INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE $table ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
            // Existing rows get a key and count as changed when they were made.
            db.execSQL("UPDATE $table SET sync_key = lower(hex(randomblob(16))), updated_ms = $timeCol")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_${table}_key ON $table(sync_key)")
        }
    }

    private fun changed() = onLocalChange?.invoke()

    private fun newKey() = UUID.randomUUID().toString().replace("-", "")

    // ------------------------------------------------------------------ history
    /** Record a visit; repeated loads of the same URL within a minute update the one row. */
    fun addVisit(url: String, title: String) {
        if (url.isBlank() || url.startsWith("about:")) return
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.rawQuery(
            "SELECT id FROM history WHERE url = ? AND visited_at > ? AND deleted = 0 ORDER BY visited_at DESC LIMIT 1",
            arrayOf(url, (now - 60_000).toString()),
        ).use { c ->
            if (c.moveToFirst()) {
                db.update(
                    "history",
                    ContentValues().apply { put("title", title); put("visited_at", now); put("updated_ms", now); put("dirty", 1) },
                    "id = ?", arrayOf(c.getLong(0).toString()),
                )
                changed()
                return
            }
        }
        db.insert("history", null, ContentValues().apply {
            put("url", url); put("title", title); put("visited_at", now)
            put("sync_key", newKey()); put("updated_ms", now); put("dirty", 1)
        })
        changed()
    }

    fun history(query: String = "", limit: Int = 300): List<HistoryEntry> {
        val (where, args) = if (query.isBlank()) "WHERE deleted = 0" to emptyArray() else
            "WHERE deleted = 0 AND (url LIKE ? OR title LIKE ?)" to arrayOf("%$query%", "%$query%")
        return readableDatabase.rawQuery(
            "SELECT id, url, title, visited_at FROM history $where ORDER BY visited_at DESC LIMIT $limit", args,
        ).use { c ->
            buildList { while (c.moveToNext()) add(HistoryEntry(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3))) }
        }
    }

    fun deleteHistory(id: Long) {
        tombstone("history", "id = ?", arrayOf(id.toString()))
    }

    fun clearHistory() {
        tombstone("history", "deleted = 0", emptyArray())
    }

    // ------------------------------------------------------------------ bookmarks
    fun isBookmarked(url: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM bookmarks WHERE url = ? AND deleted = 0 LIMIT 1", arrayOf(url)).use { it.moveToFirst() }

    /** Add if missing, remove if present. Returns true when the page is now bookmarked. */
    fun toggleBookmark(url: String, title: String): Boolean {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        if (isBookmarked(url)) {
            tombstone("bookmarks", "url = ?", arrayOf(url))
            return false
        }
        val values = ContentValues().apply {
            put("title", title.ifBlank { UrlInput.display(url) }); put("created_at", now)
            put("updated_ms", now); put("dirty", 1); put("deleted", 0)
        }
        // A bookmark removed earlier comes back under its old key (same item on every phone).
        if (db.update("bookmarks", values, "url = ?", arrayOf(url)) == 0) {
            db.insert("bookmarks", null, values.apply { put("url", url); put("sync_key", newKey()) })
        }
        changed()
        return true
    }

    fun bookmarks(query: String = ""): List<Bookmark> {
        val (where, args) = if (query.isBlank()) "WHERE deleted = 0" to emptyArray() else
            "WHERE deleted = 0 AND (url LIKE ? OR title LIKE ?)" to arrayOf("%$query%", "%$query%")
        return readableDatabase.rawQuery(
            "SELECT id, url, title, created_at FROM bookmarks $where ORDER BY created_at DESC", args,
        ).use { c ->
            buildList { while (c.moveToNext()) add(Bookmark(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3))) }
        }
    }

    fun deleteBookmark(id: Long) {
        tombstone("bookmarks", "id = ?", arrayOf(id.toString()))
    }

    private fun tombstone(table: String, where: String, args: Array<String>) {
        writableDatabase.update(
            table,
            ContentValues().apply { put("deleted", 1); put("dirty", 1); put("updated_ms", System.currentTimeMillis()) },
            where, args,
        )
        changed()
    }

    // ------------------------------------------------------------------ sync bookkeeping
    /** Rows waiting to upload (oldest first), at most [limit]. */
    fun pendingSync(kind: String, limit: Int): List<PendingSync> {
        val (table, timeCol) = tableFor(kind)
        return readableDatabase.rawQuery(
            "SELECT sync_key, url, title, $timeCol, updated_ms, deleted FROM $table WHERE dirty = 1 ORDER BY updated_ms LIMIT $limit",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    PendingSync(kind, c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4), c.getInt(5) == 1),
                )
            }
        }
    }

    /** Uploaded: clear the dirty flag (unless the row changed again since) and drop sent tombstones. */
    fun markSynced(kind: String, sent: List<PendingSync>) {
        if (sent.isEmpty()) return
        val (table, _) = tableFor(kind)
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (p in sent) {
                db.execSQL(
                    "UPDATE $table SET dirty = 0 WHERE sync_key = ? AND updated_ms = ?",
                    arrayOf<Any>(p.key, p.updatedMs),
                )
            }
            db.execSQL("DELETE FROM $table WHERE deleted = 1 AND dirty = 0")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * A change from the user's other devices. Applied only if newer than what this phone has
     * (latest change wins). Returns true when something visible changed.
     */
    fun applyRemote(kind: String, key: String, url: String, title: String, timeMs: Long, updatedMs: Long, deleted: Boolean): Boolean {
        val (table, timeCol) = tableFor(kind)
        val db = writableDatabase
        val local = db.rawQuery("SELECT updated_ms FROM $table WHERE sync_key = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }
        if (local != null && local >= updatedMs) return false
        if (deleted) {
            if (local != null) db.delete(table, "sync_key = ?", arrayOf(key))
            return local != null
        }
        if (url.isBlank()) return false
        // Bookmarks are unique by URL: a same-URL row under another key (made on this phone) gives
        // way to the synced one.
        if (kind == "bookmark") db.delete(table, "url = ? AND sync_key != ?", arrayOf(url, key))
        val values = ContentValues().apply {
            put("url", url); put("title", title); put(timeCol, timeMs)
            put("updated_ms", updatedMs); put("dirty", 0); put("deleted", 0)
        }
        if (local != null) {
            db.update(table, values, "sync_key = ?", arrayOf(key))
        } else {
            db.insert(table, null, values.apply { put("sync_key", key) })
        }
        return true
    }

    /** Run [block] in one transaction (applying a large download row by row is much faster). */
    fun <T> inTransaction(block: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            return block().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    /** Everything counts as new again (e.g. before the first upload to a newly signed-in account). */
    fun markAllDirty() {
        writableDatabase.execSQL("UPDATE history SET dirty = 1")
        writableDatabase.execSQL("UPDATE bookmarks SET dirty = 1")
    }

    /** Never signed in: nothing will upload the tombstones, so drop them. */
    fun purgeTombstones() {
        writableDatabase.execSQL("DELETE FROM history WHERE deleted = 1")
        writableDatabase.execSQL("DELETE FROM bookmarks WHERE deleted = 1")
    }

    /** A different person signed in on this phone: remove the previous person's leftover data. */
    fun wipeBrowsingData() {
        writableDatabase.delete("history", null, null)
        writableDatabase.delete("bookmarks", null, null)
    }

    private fun tableFor(kind: String) = when (kind) {
        "bookmark" -> "bookmarks" to "created_at"
        else -> "history" to "visited_at"
    }

    // ------------------------------------------------------------------ downloads
    fun addDownload(fileName: String, source: String, work: Boolean, systemId: Long) {
        writableDatabase.insert("downloads", null, ContentValues().apply {
            put("file_name", fileName); put("source", source); put("work", if (work) 1 else 0)
            put("system_id", systemId); put("created_at", System.currentTimeMillis())
        })
    }

    fun downloads(): List<DownloadEntry> =
        readableDatabase.rawQuery(
            "SELECT id, file_name, source, work, system_id, created_at FROM downloads ORDER BY created_at DESC LIMIT 300", null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    DownloadEntry(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3) == 1, c.getLong(4), c.getLong(5)),
                )
            }
        }

    fun deleteDownload(id: Long) { writableDatabase.delete("downloads", "id = ?", arrayOf(id.toString())) }
}
