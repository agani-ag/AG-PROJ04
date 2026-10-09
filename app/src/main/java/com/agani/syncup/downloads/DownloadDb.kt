package com.agani.syncup.downloads

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.agani.syncup.browser.DownloadEntry
import org.json.JSONArray

/**
 * The Download Manager's list, on this phone only. Incognito downloads are never written here.
 * A finished download keeps no cookies or parts — only what the list shows and where the file is.
 */
internal class DownloadDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "downloads.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE tasks (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL, file_name TEXT NOT NULL, " +
                "mime TEXT NOT NULL DEFAULT '', total INTEGER NOT NULL DEFAULT -1, state TEXT NOT NULL, " +
                "parts TEXT NOT NULL DEFAULT '[]', resumable INTEGER NOT NULL DEFAULT 0, etag TEXT, last_modified TEXT, " +
                "user_agent TEXT NOT NULL DEFAULT '', referer TEXT NOT NULL DEFAULT '', cookies TEXT, " +
                "source TEXT NOT NULL DEFAULT '', work INTEGER NOT NULL DEFAULT 0, content_uri TEXT, " +
                "legacy_id INTEGER NOT NULL DEFAULT 0, error TEXT, created_at INTEGER NOT NULL, finished_at INTEGER NOT NULL DEFAULT 0)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun insert(t: DownloadTask, cookies: String?): Long =
        writableDatabase.insert("tasks", null, values(t, cookies).apply { put("created_at", t.createdAt) })

    fun save(t: DownloadTask, cookies: String?) {
        if (t.id <= 0) return
        writableDatabase.update("tasks", values(t, cookies), "id = ?", arrayOf(t.id.toString()))
    }

    fun delete(id: Long) {
        writableDatabase.delete("tasks", "id = ?", arrayOf(id.toString()))
    }

    /** Every download, newest first, with the cookies kept for the unfinished ones. */
    fun load(): List<Pair<DownloadTask, String?>> =
        readableDatabase.rawQuery(
            "SELECT id, url, file_name, mime, total, state, parts, resumable, etag, last_modified, user_agent, referer, " +
                "cookies, source, work, content_uri, legacy_id, error, created_at, finished_at FROM tasks ORDER BY created_at DESC LIMIT 1000",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val t = DownloadTask(
                        id = c.getLong(0), url = c.getString(1), fileName = c.getString(2), mime = c.getString(3),
                        total = c.getLong(4), source = c.getString(13), work = c.getInt(14) == 1, incognito = false,
                        userAgent = c.getString(10), referer = c.getString(11), createdAt = c.getLong(18),
                    )
                    t.state = runCatching { DlState.valueOf(c.getString(5)) }.getOrDefault(DlState.FAILED)
                    t.parts = parseParts(c.getString(6))
                    t.resumable = c.getInt(7) == 1
                    t.etag = c.getString(8)
                    t.lastModified = c.getString(9)
                    t.contentUri = c.getString(15)
                    t.legacyId = c.getLong(16)
                    t.error = c.getString(17)
                    t.finishedAt = c.getLong(19)
                    t.downloaded = if (t.state == DlState.DONE) t.total.coerceAtLeast(0) else t.bytesDone()
                    add(t to c.getString(12))
                }
            }
        }

    /** Bring over the list kept before v7 (Android's download manager ids). */
    fun importLegacy(entries: List<DownloadEntry>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (e in entries) {
                db.insert("tasks", null, ContentValues().apply {
                    put("url", ""); put("file_name", e.fileName); put("mime", Files.mimeFor(e.fileName, null))
                    put("state", DlState.DONE.name); put("source", e.source); put("work", if (e.work) 1 else 0)
                    put("legacy_id", e.systemId); put("created_at", e.createdAt); put("finished_at", e.createdAt)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun values(t: DownloadTask, cookies: String?) = ContentValues().apply {
        put("url", t.url); put("file_name", t.fileName); put("mime", t.mime); put("total", t.total)
        put("state", t.state.name); put("parts", partsJson(t.parts)); put("resumable", if (t.resumable) 1 else 0)
        put("etag", t.etag); put("last_modified", t.lastModified); put("user_agent", t.userAgent); put("referer", t.referer)
        put("cookies", cookies); put("source", t.source); put("work", if (t.work) 1 else 0); put("content_uri", t.contentUri)
        put("legacy_id", t.legacyId); put("error", t.error); put("finished_at", t.finishedAt)
    }

    private fun partsJson(parts: List<Part>): String =
        JSONArray().apply { parts.forEach { put(JSONArray().put(it.start).put(it.end).put(it.done.get())) } }.toString()

    private fun parseParts(json: String?): List<Part> = runCatching {
        val arr = JSONArray(json ?: "[]")
        (0 until arr.length()).map { i -> arr.getJSONArray(i).let { Part(it.getLong(0), it.getLong(1), it.getLong(2)) } }
    }.getOrDefault(emptyList())
}
