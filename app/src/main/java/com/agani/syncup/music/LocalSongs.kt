package com.agani.syncup.music

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.core.content.ContextCompat
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** One song on the phone (from MediaStore). Nothing about it leaves the device. */
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val uri: Uri,
) {
    /** Where the list, the player and its notification load the cover art from (see [Artwork]). */
    val artUri: Uri get() = Artwork.uriFor(uri, albumId)
}

/** The phone's own songs, read from MediaStore. */
object LocalSongs {
    /** Android 13+ asks for music only; older versions only have the storage permission. */
    val permission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun canRead(context: Context) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Every song on the phone (internal storage and SD card), A–Z. MediaStore's music flag already
     * leaves out ringtones, alarms and notification sounds; clips under 20 s, WhatsApp voice notes
     * and call recordings are skipped too. Blocking — call it off the main thread.
     */
    fun load(context: Context): List<Song> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val selection = buildString {
            append("${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 20000")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val path = MediaStore.Audio.Media.RELATIVE_PATH
                append(" AND ($path IS NULL OR ($path NOT LIKE '%Voice Notes%' AND $path NOT LIKE '%Call%record%'))")
            }
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
        )
        val songs = ArrayList<Song>()
        context.contentResolver.query(collection, projection, selection, null, null)?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val album = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (c.moveToNext()) {
                val songId = c.getLong(id)
                songs += Song(
                    id = songId,
                    title = c.getString(title).known() ?: "Unknown song",
                    artist = c.getString(artist).known() ?: "Unknown artist",
                    album = c.getString(album).known().orEmpty(),
                    albumId = c.getLong(albumId),
                    durationMs = c.getLong(duration),
                    uri = ContentUris.withAppendedId(collection, songId),
                )
            }
        }
        return songs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }

    // MediaStore fills missing tags with "<unknown>".
    private fun String?.known() = this?.trim()?.takeIf { it.isNotEmpty() && it != MediaStore.UNKNOWN_STRING }
}

/**
 * Cover art: the song file's own picture on Android 10+, its album's art before that. Decoded
 * pictures are kept in a small memory cache shared by the song list, the player and its notification.
 */
object Artwork {
    /** Pixel sizes the art is decoded at, so rows and the big player reuse cached pictures. */
    const val SMALL = 160
    const val LARGE = 720

    private val ALBUM_ART = Uri.parse("content://media/external/audio/albumart")
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val missing: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    fun uriFor(song: Uri, albumId: Long): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) song else ContentUris.withAppendedId(ALBUM_ART, albumId)

    /** Only MediaStore art is ours to load; anything else goes to the player's default loader. */
    fun isLocal(uri: Uri) = uri.authority == MediaStore.AUTHORITY

    fun cached(uri: Uri, px: Int): Bitmap? = cache.get("$uri@$px")

    /** Blocking. Null when the song has no picture. */
    fun load(context: Context, uri: Uri, px: Int): Bitmap? {
        val key = "$uri@$px"
        cache.get(key)?.let { return it }
        if (key in missing) return null
        val bitmap = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, Size(px, px), null)
            } else {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }?.let { scaled(it, px) }
            }
        }.getOrNull()
        if (bitmap == null) missing += key else cache.put(key, bitmap)
        return bitmap
    }

    private fun scaled(b: Bitmap, px: Int): Bitmap {
        val side = maxOf(b.width, b.height)
        if (side <= px) return b
        val f = px.toFloat() / side
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
    }
}
