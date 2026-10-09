package com.agani.syncup.downloads

import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicLong

/** Where a download is. A cancelled download is removed from the list. */
enum class DlState { QUEUED, RUNNING, PAUSED, WAITING_NETWORK, WAITING_WIFI, FAILED, DONE }

/** The kind of file, for the filter chips and the Download / SyncUp / <folder> it's saved in. */
enum class FileKind(val label: String, val folder: String) {
    VIDEO("Video", "Videos"),
    MUSIC("Music", "Music"),
    IMAGE("Images", "Images"),
    DOCUMENT("Docs", "Documents"),
    APP("Apps", "Apps"),
    OTHER("Other", "Other");

    companion object {
        private val VIDEO_EXT = setOf("mp4", "m4v", "webm", "mkv", "mov", "3gp", "avi", "wmv", "flv", "mpeg", "mpg", "ts")
        private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "wma", "amr", "mid")
        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg", "avif")
        private val DOC_EXT = setOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "txt", "csv", "rtf", "epub", "json", "xml", "html", "htm",
        )
        private val APP_EXT = setOf("apk", "xapk", "apks", "apkm")

        fun of(name: String, mime: String): FileKind {
            val m = mime.lowercase()
            val ext = name.substringAfterLast('.', "").lowercase()
            return when {
                ext in APP_EXT || m == "application/vnd.android.package-archive" -> APP
                ext in VIDEO_EXT || m.startsWith("video/") -> VIDEO
                ext in AUDIO_EXT || m.startsWith("audio/") -> MUSIC
                ext in IMAGE_EXT || m.startsWith("image/") -> IMAGE
                ext in DOC_EXT || m == "application/pdf" || m.startsWith("text/") || m.contains("officedocument") ||
                    m.contains("msword") || m.contains("ms-excel") || m.contains("ms-powerpoint") -> DOCUMENT
                else -> OTHER
            }
        }

        /** A file type name for people ("PDF document", "MP4 video"). */
        fun describe(name: String, mime: String): String {
            val ext = name.substringAfterLast('.', "").uppercase()
            return when (of(name, mime)) {
                VIDEO -> if (ext.isNotEmpty()) "$ext video" else "Video"
                MUSIC -> if (ext.isNotEmpty()) "$ext audio" else "Audio"
                IMAGE -> if (ext.isNotEmpty()) "$ext image" else "Image"
                APP -> "Android app"
                DOCUMENT -> if (ext == "PDF") "PDF document" else if (ext.isNotEmpty()) "$ext document" else "Document"
                OTHER -> if (ext.isNotEmpty()) "$ext file" else "File"
            }
        }
    }
}

/** A download someone asked for: the browser (a download link, long-press, the detector) or "Add link". */
data class DownloadRequest(
    val url: String,
    val userAgent: String = "",
    /** The page the link was on (sent as Referer, like a browser). */
    val referer: String = "",
    /** The page's cookies for [url], so downloads behind a sign-in work. */
    val cookies: String? = null,
    val contentDisposition: String? = null,
    val mime: String? = null,
    val contentLength: Long = -1,
    /** Shown under the file name: the site, or the SyncUp link's name (never its address). */
    val source: String = "",
    val work: Boolean = false,
    val incognito: Boolean = false,
    val suggestedName: String? = null,
) {
    /** Best guess at the file name before the link is checked. */
    fun guessName(): String = Files.cleanName(
        suggestedName?.takeIf { it.isNotBlank() } ?: URLUtil.guessFileName(url, contentDisposition, mime),
    )
}

/** What a quick check of a link found. */
data class LinkInfo(
    /** The address after redirects. */
    val url: String,
    val fileName: String,
    val mime: String,
    /** Bytes, or -1 when the server doesn't say. */
    val size: Long,
    /** The server serves byte ranges: the file can resume and download in parts. */
    val resumable: Boolean,
    val etag: String?,
    val lastModified: String?,
)

/** One byte range of a file, downloaded on its own connection. [end] is inclusive; -1 = to the end. */
class Part(val start: Long, val end: Long, done: Long = 0) {
    val done = AtomicLong(done)
    val length: Long get() = if (end < 0) -1 else end - start + 1
    val finished: Boolean get() = end >= 0 && done.get() >= length
}

/** A download in the list. Compose-observable; the engine updates it on the main thread. */
class DownloadTask(
    var id: Long,
    url: String,
    fileName: String,
    mime: String,
    total: Long,
    val source: String,
    val work: Boolean,
    val incognito: Boolean,
    val userAgent: String,
    val referer: String,
    val createdAt: Long,
) {
    @Volatile var url: String = url
    var fileName by mutableStateOf(fileName)
    var mime by mutableStateOf(mime)
    var total by mutableLongStateOf(total)
    var state by mutableStateOf(DlState.QUEUED)
    var downloaded by mutableLongStateOf(0L)
    /** Bytes per second right now. */
    var speed by mutableLongStateOf(0L)
    /** 0..1 for each part (the small bars). */
    var partProgress by mutableStateOf<List<Float>>(emptyList())
    var error by mutableStateOf<String?>(null)
    var finishedAt by mutableLongStateOf(0L)

    /** The saved file: a MediaStore content:// address (Android 10+) or a file path (older). */
    @Volatile var contentUri: String? = null
    /** The link was already checked (the "Download file" sheet / Add link): no need to again. */
    @Volatile var probed = false
    @Volatile var resumable = false
    @Volatile var etag: String? = null
    @Volatile var lastModified: String? = null
    @Volatile var parts: List<Part> = emptyList()
    /** Downloads from before v7 were done by Android's download manager: its id. */
    @Volatile var legacyId: Long = 0

    val kind: FileKind get() = FileKind.of(fileName, mime)
    val active: Boolean get() = state != DlState.DONE && state != DlState.FAILED && state != DlState.PAUSED

    fun bytesDone(): Long = parts.sumOf { it.done.get() }
}

/** File-name helpers. */
object Files {
    /** A name that is safe to save: no folders or characters Android rejects, not too long. */
    fun cleanName(raw: String): String {
        val n = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("""[\x00-\x1f:*?"<>|]"""), "_")
            .trim().trim('.')
            .take(150)
        return n.ifBlank { "download" }
    }

    /** The MIME type that matches the name's extension (so Android keeps the name as it is), else [fallback]. */
    fun mimeFor(name: String, fallback: String?): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: fallback?.substringBefore(';')?.trim()?.takeIf { it.contains('/') }
            ?: "application/octet-stream"
    }

    /** "18.4 MB" */
    fun size(bytes: Long): String = when {
        bytes < 0 -> "Unknown size"
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    /** "1 min 17 s" */
    fun duration(seconds: Long): String = when {
        seconds < 60 -> "$seconds s"
        seconds < 3600 -> "${seconds / 60} min ${seconds % 60} s"
        else -> "${seconds / 3600} h ${seconds % 3600 / 60} min"
    }
}
