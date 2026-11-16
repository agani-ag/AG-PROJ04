package com.agani.syncup.video

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.agani.syncup.music.LocalSongs
import com.agani.syncup.music.SortButton
import com.agani.syncup.music.SortOrder
import com.agani.syncup.music.folderOrder

/** One video on the phone or TV (from MediaStore). Nothing about it leaves the device. */
data class LocalVideo(
    val id: Long,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val uri: Uri,
    /** The folder it's in, from the storage's top ("DCIM/Camera"); empty when the device doesn't say. */
    val folder: String,
    /** When it was added, seconds since 1970 (newest first in the list). */
    val addedSec: Long,
)

/**
 * The device's own videos, read from MediaStore — the Video player's list, like Music's songs (user,
 * 16 Nov 2026: "all the device's videos and a folder view, like the Music player"). Android 13+ asks for
 * videos only (READ_MEDIA_VIDEO; Play's "Photos and videos" declaration covers it); Android 14+ may give
 * just the videos the person picked; older versions use the storage permission.
 */
object LocalVideos {
    val permission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    /** What to ask for: on Android 14+ also "selected videos only", so Android can offer that choice. */
    val permissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        } else {
            arrayOf(permission)
        }

    private fun granted(context: Context, p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    /** Every video can be read. */
    fun canReadAll(context: Context) = granted(context, permission)

    /** Only the videos the person picked (Android 14+ "Select videos"). */
    fun onlyPicked(context: Context) = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !canReadAll(context) &&
        granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    fun canRead(context: Context) = canReadAll(context) || onlyPicked(context)

    /**
     * Every video on the device (internal storage, SD card, a USB drive), newest first. Blocking — call
     * it off the main thread.
     */
    fun load(context: Context): List<LocalVideo> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        @Suppress("DEPRECATION")
        val folderColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Video.Media.RELATIVE_PATH else MediaStore.Video.Media.DATA
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            folderColumn,
        )
        val videos = ArrayList<LocalVideo>()
        context.contentResolver.query(collection, projection, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val display = c.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
            val title = c.getColumnIndex(MediaStore.Video.Media.TITLE)
            val duration = c.getColumnIndex(MediaStore.Video.Media.DURATION)
            val size = c.getColumnIndex(MediaStore.Video.Media.SIZE)
            val added = c.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
            val folder = c.getColumnIndex(folderColumn)
            while (c.moveToNext()) {
                val videoId = c.getLong(id)
                // The file's own name reads best ("Ooty trip.mp4" → "Ooty trip"); the title is often a camera code.
                val name = (if (display >= 0) c.getString(display) else null)?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotEmpty() }
                    ?: (if (title >= 0) c.getString(title) else null)?.trim()?.takeIf { it.isNotEmpty() } ?: "Video"
                videos += LocalVideo(
                    id = videoId,
                    name = name,
                    durationMs = if (duration >= 0) c.getLong(duration) else 0L,
                    sizeBytes = if (size >= 0) c.getLong(size) else 0L,
                    uri = ContentUris.withAppendedId(collection, videoId),
                    folder = if (folder < 0) "" else LocalSongs.folderOf(c.getString(folder)),
                    addedSec = if (added >= 0) c.getLong(added) else 0L,
                )
            }
        }
        return videos
    }

    /** A folder's name from its path ("DCIM/Camera" → "Camera"). */
    fun folderName(path: String) = path.substringAfterLast('/').ifBlank { "Other videos" }
}

/** The Video player's orders (see [SortButton]). Newest first is how the list always came. */
enum class VideoSort(override val label: String) : SortOrder {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    NAME_AZ("Name A–Z"),
    NAME_ZA("Name Z–A"),
    LONGEST("Longest first"),
    SHORTEST("Shortest first"),
    BIGGEST("Biggest first"),
    SMALLEST("Smallest first"),
    ;

    fun sort(videos: List<LocalVideo>): List<LocalVideo> {
        val byName = compareBy<LocalVideo, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        val c: Comparator<LocalVideo> = when (this) {
            NEWEST -> compareByDescending<LocalVideo> { it.addedSec }.then(byName)
            OLDEST -> compareBy<LocalVideo> { it.addedSec }.then(byName)
            NAME_AZ -> byName
            NAME_ZA -> byName.reversed()
            LONGEST -> compareByDescending<LocalVideo> { it.durationMs }.then(byName)
            SHORTEST -> compareBy<LocalVideo> { it.durationMs }.then(byName)
            BIGGEST -> compareByDescending<LocalVideo> { it.sizeBytes }.then(byName)
            SMALLEST -> compareBy<LocalVideo> { it.sizeBytes }.then(byName)
        }
        return videos.sortedWith(c)
    }

    /** Folders follow the same order: the newest folder, the longest or biggest in total, or by name. */
    fun folders(groups: List<Pair<String, List<LocalVideo>>>): List<Pair<String, List<LocalVideo>>> {
        val name = LocalVideos::folderName
        return groups.sortedWith(
            when (this) {
                NEWEST -> folderOrder(name, { v -> v.maxOf { it.addedSec } }, descending = true)
                OLDEST -> folderOrder(name, { v -> v.minOf { it.addedSec } })
                NAME_AZ -> folderOrder(name)
                NAME_ZA -> folderOrder(name, descending = true)
                LONGEST -> folderOrder(name, { v -> v.sumOf { it.durationMs } }, descending = true)
                SHORTEST -> folderOrder(name, { v -> v.sumOf { it.durationMs } })
                BIGGEST -> folderOrder(name, { v -> v.sumOf { it.sizeBytes } }, descending = true)
                SMALLEST -> folderOrder(name, { v -> v.sumOf { it.sizeBytes } })
            },
        )
    }
}
