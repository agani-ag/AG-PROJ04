package com.agani.syncup.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject

/**
 * Thumbnails and watch positions for videos SyncUp plays — a SyncUp download, a video picked once
 * from the phone, or a pasted link. SyncUp never browses the phone's whole video library (no
 * READ_MEDIA_VIDEO): every URI here is one the user explicitly opened.
 */
object VideoStore {
    private val thumbs = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** A small frame of the video at [uri] (content://, file:// or http(s)://), or null. Call off the main thread. */
    fun thumbnail(context: Context, uri: Uri): Bitmap? {
        val key = uri.toString()
        thumbs.get(key)?.let { return it }
        // release(), not close() — close() only exists from API 29, and minSdk here is 24.
        val mmr = MediaMetadataRetriever()
        val bmp = try {
            if (uri.scheme == "http" || uri.scheme == "https") mmr.setDataSource(uri.toString(), emptyMap())
            else mmr.setDataSource(context, uri)
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                    mmr.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
                } else null
            }.getOrNull() ?: runCatching { mmr.frameAtTime }.getOrNull()
        } catch (e: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        }
        bmp?.let { thumbs.put(key, it) }
        return bmp
    }
}

/** One video that was being watched: where it stopped, for "Continue watching" and resuming. */
data class Watched(val uri: String, val title: String, val source: String, val positionMs: Long, val durationMs: Long, val at: Long) {
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** Where each video stopped, kept on the phone (the last 40). */
object WatchHistory {
    private const val PREFS = "video_history"
    private const val KEY = "items"
    private const val MAX = 40

    fun all(context: Context): List<Watched> = runCatching {
        val a = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Watched(o.getString("u"), o.optString("t"), o.optString("s"), o.optLong("p"), o.optLong("d"), o.optLong("a"))
        }
    }.getOrDefault(emptyList())

    /** Videos stopped part-way, most recent first. */
    fun continueWatching(context: Context): List<Watched> =
        all(context).filter { it.positionMs > 5_000 && (it.durationMs <= 0 || it.progress < 0.95f) }

    fun positionOf(context: Context, uri: String): Long =
        all(context).firstOrNull { it.uri == uri }?.takeIf { it.durationMs <= 0 || it.progress < 0.95f }?.positionMs ?: 0L

    fun record(context: Context, w: Watched) {
        val list = listOf(w) + all(context).filter { it.uri != w.uri }
        val a = JSONArray()
        list.take(MAX).forEach {
            a.put(JSONObject().put("u", it.uri).put("t", it.title).put("s", it.source).put("p", it.positionMs).put("d", it.durationMs).put("a", it.at))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }

    fun forget(context: Context, uri: String) {
        val a = JSONArray()
        all(context).filter { it.uri != uri }.forEach {
            a.put(JSONObject().put("u", it.uri).put("t", it.title).put("s", it.source).put("p", it.positionMs).put("d", it.durationMs).put("a", it.at))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }
}
