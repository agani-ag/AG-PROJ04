package com.agani.syncup.browser

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class HistoryEntry(val id: Long, val url: String, val title: String, val visitedAt: Long)
data class Bookmark(val id: Long, val url: String, val title: String, val createdAt: Long)

/**
 * One shared downloads list for every section. Work downloads carry the link name as their source
 * (never the URL) and are labelled "Work" in the list.
 */
data class DownloadEntry(
    val id: Long,
    val fileName: String,
    val source: String,
    val work: Boolean,
    val systemId: Long,
    val createdAt: Long,
)

/**
 * On-device browser data (never uploaded). History and bookmarks are recorded for the NORMAL section
 * only — Work and Incognito keep none. Tiny tables, so plain SQLite without Room.
 */
class BrowserDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "browser.db", null, 1) {

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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // ------------------------------------------------------------------ history
    /** Record a visit; repeated loads of the same URL within a minute update the one row. */
    fun addVisit(url: String, title: String) {
        if (url.isBlank() || url.startsWith("about:")) return
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.rawQuery(
            "SELECT id FROM history WHERE url = ? AND visited_at > ? ORDER BY visited_at DESC LIMIT 1",
            arrayOf(url, (now - 60_000).toString()),
        ).use { c ->
            if (c.moveToFirst()) {
                db.update(
                    "history",
                    ContentValues().apply { put("title", title); put("visited_at", now) },
                    "id = ?", arrayOf(c.getLong(0).toString()),
                )
                return
            }
        }
        db.insert("history", null, ContentValues().apply {
            put("url", url); put("title", title); put("visited_at", now)
        })
    }

    fun history(query: String = "", limit: Int = 300): List<HistoryEntry> {
        val (where, args) = if (query.isBlank()) "" to emptyArray() else
            "WHERE url LIKE ? OR title LIKE ?" to arrayOf("%$query%", "%$query%")
        return readableDatabase.rawQuery(
            "SELECT id, url, title, visited_at FROM history $where ORDER BY visited_at DESC LIMIT $limit", args,
        ).use { c ->
            buildList { while (c.moveToNext()) add(HistoryEntry(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3))) }
        }
    }

    fun deleteHistory(id: Long) { writableDatabase.delete("history", "id = ?", arrayOf(id.toString())) }
    fun clearHistory() { writableDatabase.delete("history", null, null) }

    // ------------------------------------------------------------------ bookmarks
    fun isBookmarked(url: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM bookmarks WHERE url = ? LIMIT 1", arrayOf(url)).use { it.moveToFirst() }

    /** Add if missing, remove if present. Returns true when the page is now bookmarked. */
    fun toggleBookmark(url: String, title: String): Boolean {
        if (isBookmarked(url)) {
            writableDatabase.delete("bookmarks", "url = ?", arrayOf(url))
            return false
        }
        writableDatabase.insert("bookmarks", null, ContentValues().apply {
            put("url", url); put("title", title.ifBlank { UrlInput.display(url) }); put("created_at", System.currentTimeMillis())
        })
        return true
    }

    fun bookmarks(query: String = ""): List<Bookmark> {
        val (where, args) = if (query.isBlank()) "" to emptyArray() else
            "WHERE url LIKE ? OR title LIKE ?" to arrayOf("%$query%", "%$query%")
        return readableDatabase.rawQuery(
            "SELECT id, url, title, created_at FROM bookmarks $where ORDER BY created_at DESC", args,
        ).use { c ->
            buildList { while (c.moveToNext()) add(Bookmark(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3))) }
        }
    }

    fun deleteBookmark(id: Long) { writableDatabase.delete("bookmarks", "id = ?", arrayOf(id.toString())) }

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
