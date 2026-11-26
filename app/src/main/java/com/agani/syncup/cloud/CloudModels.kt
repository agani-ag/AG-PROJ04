package com.agani.syncup.cloud

import com.google.gson.annotations.SerializedName

/*
 * Tools → Cloudinary (2026-11-26). The server (AG-PROJ01 mobileapi/cloud_api.py) can't reach Cloudinary: it
 * signs what this phone sends there, keeps the list of each person's own folder, and gives "Everything"
 * accounts the API key. These are its answers, and the one file item the screen shows from either side.
 */

/** GET cloud/access: what this account gets. */
data class CloudAccess(
    val access: String = "off",
    val configured: Boolean = false,
    @SerializedName("cloud_name") val cloudName: String = "",
    val base: String = "syncup",
    /** The account's own folder, e.g. syncup/accounts/12. */
    val root: String = "",
    @SerializedName("folder_mode") val folderMode: String = "dynamic",
    @SerializedName("used_bytes") val usedBytes: Long = 0,
    val cloud: CloudPlan? = null,
    /** Changes whenever the files this account sees change: the open Cloud page reloads when it does. */
    val changes: String = "",
)

/** How much of the Cloudinary plan everyone shares is used and still free (no limit per person). */
data class CloudPlan(
    val plan: String = "",
    @SerializedName("used_bytes") val usedBytes: Long = 0,
    @SerializedName("total_bytes") val totalBytes: Long = 0,
    @SerializedName("free_bytes") val freeBytes: Long = 0,
    @SerializedName("updated_ms") val updatedMs: Long = 0,
)

/** POST cloud/credentials (Everything only). Kept in memory only — never written to the phone's storage. */
data class CloudCreds(
    @SerializedName("cloud_name") val cloudName: String = "",
    @SerializedName("api_key") val apiKey: String = "",
    @SerializedName("api_secret") val apiSecret: String = "",
    val base: String = "syncup",
    @SerializedName("folder_mode") val folderMode: String = "dynamic",
)

/** GET cloud/stats: the Storage details — the person's own folder by kind and file type, and the plan. */
data class CloudStats(
    val files: Int = 0,
    @SerializedName("used_bytes") val usedBytes: Long = 0,
    val kinds: List<CloudCount> = emptyList(),
    val formats: List<CloudCount> = emptyList(),
    val cloud: CloudPlan? = null,
    /** Everything accounts: all people's own folders together. */
    val everyone: CloudEveryone? = null,
    /** Everything (counted on the phone through Cloudinary's API): the whole of Cloudinary, not one folder. */
    val wholeCloud: Boolean = false,
    /** Whole Cloudinary only: downloads this month, and whether the count stopped early (very many files). */
    val bandwidthBytes: Long? = null,
    val partial: Boolean = false,
)

/** A kind (photos, videos, audio, documents, archives, other) or a file type (jpg, pdf…): how many, how big. */
data class CloudCount(val kind: String? = null, val format: String? = null, val count: Int = 0, val bytes: Long = 0)

data class CloudEveryone(val people: Int = 0, val files: Int = 0, val bytes: Long = 0)

/** A file on the server's list (someone's own folder). */
data class CloudFileDto(
    @SerializedName("public_id") val publicId: String = "",
    @SerializedName("resource_type") val resourceType: String = "image",
    val name: String = "",
    val format: String? = null,
    val bytes: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    val duration: Double? = null,
    val url: String = "",
    @SerializedName("created_ms") val createdMs: Long = 0,
)

/** GET cloud/files: one folder of someone's own space. */
data class CloudListingDto(
    val folder: String = "",
    val path: String = "",
    val folders: List<String> = emptyList(),
    val files: List<CloudFileDto> = emptyList(),
    @SerializedName("used_bytes") val usedBytes: Long = 0,
    val cloud: CloudPlan? = null,
)

/** POST cloud/sign: a request the server signed, for this phone to send to Cloudinary. */
data class SignedRequest(
    val url: String = "",
    @SerializedName("resource_type") val resourceType: String? = null,
    @SerializedName("public_id") val publicId: String? = null,
    @SerializedName("to_public_id") val toPublicId: String? = null,
    val name: String? = null,
    val params: Map<String, String> = emptyMap(),
)

data class CloudPerson(val id: Long = 0, val name: String = "", val login: String = "", val access: String = "off", val root: String = "")
data class CloudPeople(val accounts: List<CloudPerson> = emptyList())

data class PendingFile(
    @SerializedName("public_id") val publicId: String = "",
    @SerializedName("resource_type") val resourceType: String = "image",
    val bytes: Long = 0,
)
data class CloudPending(val count: Int = 0, val files: List<PendingFile> = emptyList())

/** One file on the screen, from the server's list or from Cloudinary itself. */
data class CloudItem(
    val publicId: String,
    val resourceType: String,
    val name: String,
    val format: String,
    val bytes: Long,
    val width: Int?,
    val height: Int?,
    val url: String,
    val createdMs: Long,
) {
    val ext: String get() = format.ifBlank { name.substringAfterLast('.', "").takeIf { name.contains('.') }.orEmpty() }.lowercase()
    val isImage: Boolean get() = resourceType == "image"
    val isAudio: Boolean get() = resourceType == "video" && (ext in AUDIO_EXT || (width == null && height == null && ext !in VIDEO_EXT))
    val isVideo: Boolean get() = resourceType == "video" && !isAudio
    val isPdf: Boolean get() = ext == "pdf"

    /** A small square picture for the grid (Cloudinary makes it from the photo or the video's first frame). */
    val thumbUrl: String?
        get() = when {
            isImage -> transformed("c_fill,w_240,h_240,q_auto,f_auto")
            isVideo -> transformed("c_fill,w_240,h_240,so_0,q_auto", ext = "jpg")
            isPdf -> transformed("c_fill,w_240,h_240,pg_1,q_auto", ext = "jpg")?.replace("/raw/upload/", "/image/upload/")
            else -> null
        }

    /** The photo at screen size (never the full original, to save data). */
    val viewUrl: String get() = if (isImage) transformed("c_limit,w_2000,h_2000,q_auto,f_auto") ?: url else url

    private fun transformed(t: String, ext: String? = null): String? {
        val i = url.indexOf("/upload/")
        if (i < 0) return null
        var u = url.substring(0, i + 8) + t + "/" + url.substring(i + 8)
        if (ext != null) {
            val dot = u.lastIndexOf('.')
            u = if (dot > u.lastIndexOf('/')) u.substring(0, dot + 1) + ext else "$u.$ext"
        }
        return u
    }

    companion object {
        val AUDIO_EXT = setOf("mp3", "m4a", "aac", "wav", "ogg", "opus", "flac", "amr", "wma")
        val VIDEO_EXT = setOf("mp4", "mov", "m4v", "webm", "mkv", "avi", "3gp", "flv", "wmv", "ts", "mts")

        fun from(d: CloudFileDto) = CloudItem(
            d.publicId, d.resourceType, d.name.ifBlank { d.publicId.substringAfterLast('/') }, d.format.orEmpty(),
            d.bytes, d.width, d.height, d.url, d.createdMs,
        )
    }
}

/** A folder on the screen. [label]: what to show (a person's name for their own folder, for Everything). */
data class CloudFolderItem(val name: String, val path: String, val label: String = name)
