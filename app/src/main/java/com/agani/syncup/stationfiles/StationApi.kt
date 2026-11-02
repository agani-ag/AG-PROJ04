package com.agani.syncup.stationfiles

import com.google.gson.annotations.SerializedName

// What AudioSync's files API sends (its API.md). Kept in this package; every field has a default so a
// missing one never breaks the list.

/** A folder: a shared root (listing of "/api/files/") or one inside a folder. */
data class StationFolder(
    val name: String = "",
    @SerializedName("list_url") val listUrl: String = "",
    val modified: String? = null,
)

/** A file in a folder. Every address is relative to the station's current address. */
data class StationFile(
    val name: String = "",
    val size: Long = 0,
    val modified: String? = null,
    val etag: String? = null,
    val mime: String = "",
    /** audio, video, image, pdf, text, archive, doc or file. */
    val kind: String = "file",
    val url: String = "",
    @SerializedName("download_url") val downloadUrl: String = "",
    @SerializedName("preview_url") val previewUrl: String? = null,
    /** A video converted by the station (H.264 HLS), for a phone that can't decode the file itself. */
    @SerializedName("hls_url") val hlsUrl: String? = null,
)

/** One folder's page, or the list of shared roots ([shares]). */
data class StationListing(
    val ok: Boolean = false,
    val protected: Boolean = false,
    val shares: List<StationFolder>? = null,
    val share: String? = null,
    val path: String? = null,
    @SerializedName("parent_url") val parentUrl: String? = null,
    val total: Int? = null,
    val offset: Int? = null,
    /** Where a piece upload into this folder starts (POST /api/uploads "folder"); null = uploads off. */
    @SerializedName("upload_folder") val uploadFolder: String? = null,
    val folders: List<StationFolder> = emptyList(),
    val files: List<StationFile> = emptyList(),
)

/** A station's /api/info, as far as the Wi-Fi check needs it. */
data class StationInfo(@SerializedName("station_id") val stationId: String? = null)

data class StationLoginReply(
    val ok: Boolean = false,
    val token: String? = null,
    val error: String? = null,
    @SerializedName("attempts_left") val attemptsLeft: Int? = null,
    @SerializedName("retry_after") val retryAfter: Int? = null,
)

/** An upload in pieces: its start reply and every status / piece reply. */
data class StationUploadReply(
    val ok: Boolean = false,
    val error: String? = null,
    @SerializedName("upload_url") val uploadUrl: String? = null,
    val received: Long = 0,
    val done: Boolean = false,
    @SerializedName("chunk_size") val chunkSize: Long = 0,
    @SerializedName("max_piece") val maxPiece: Long = 0,
    @SerializedName("retry_after") val retryAfter: Int? = null,
    val file: StationFile? = null,
)

/** A JSON error from a station ("error" is AudioSync's code, such as not_found or busy). */
data class StationError(
    val error: String? = null,
    val received: Long? = null,
    @SerializedName("retry_after") val retryAfter: Int? = null,
)
