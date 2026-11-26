package com.agani.syncup.cloud

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Something went wrong, in words to show the person. */
class CloudException(message: String) : Exception(message)

/** A file to upload: one the person picked, or (with [opener]) one read from somewhere else, such as a station. */
class UploadSource(
    private val context: Context,
    val uri: Uri,
    val name: String,
    val mime: String,
    val size: Long,
    private val opener: (() -> InputStream)? = null,
) {
    fun open(): InputStream = opener?.invoke() ?: context.contentResolver.openInputStream(uri) ?: throw CloudException("Couldn't read “$name”.")

    companion object {
        /** A file read by [opener] — called once for a big file (sent in pieces), maybe more often for a small one. */
        fun stream(context: Context, name: String, mime: String, size: Long, opener: () -> InputStream) =
            UploadSource(context.applicationContext, Uri.EMPTY, name, mime, size, opener)

        /** Name, type and size of a picked file (from the system picker — no storage permission needed). */
        fun of(context: Context, uri: Uri): UploadSource {
            var name = "file"
            var size = -1L
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { i -> c.getString(i)?.let { name = it } }
                        c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { i -> size = c.getLong(i) }
                    }
                }
            }
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            // The photo picker hides real names ("336.png"): call it "Photo 2026-11-26 12.51.07.png" from when it was taken.
            if (Regex("^\\d+(\\.\\w+)?$").matches(name)) {
                val taken = runCatching {
                    context.contentResolver.query(uri, arrayOf("datetaken"), null, null, null)?.use { c ->
                        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
                    } ?: 0L
                }.getOrDefault(0L)
                val ext = name.substringAfterLast('.', "").ifBlank {
                    android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime).orEmpty()
                }
                val kind = if (mime.startsWith("video/")) "Video" else "Photo"
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH.mm.ss", java.util.Locale.US)
                    .format(java.util.Date(if (taken > 0) taken else System.currentTimeMillis()))
                name = "$kind $stamp" + if (ext.isNotBlank()) ".$ext" else ""
            }
            return UploadSource(context.applicationContext, uri, name, mime, size)
        }
    }
}

/**
 * Talking to Cloudinary from the phone (the server can't reach it). Upload-API calls are signed — by the
 * server for an "Own folder" account, on the phone for "Everything"; Admin-API calls (Everything only) use the
 * API key with basic auth.
 */
internal object CloudHttp {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    /** Files bigger than this go up in pieces of [CHUNK], so a weak connection doesn't lose the whole file. */
    const val CHUNKED_ABOVE = 20L * 1024 * 1024
    private const val CHUNK = 6L * 1024 * 1024

    /** Cloudinary's request signature: params sorted by name as key=value joined with &, the secret added, SHA-1 hex. */
    fun sign(params: Map<String, String>, secret: String): String {
        val text = params.filterValues { it.isNotEmpty() }.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" } + secret
        return MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * A Cloudinary search link ("cacheable search URL") for [query] (the search's JSON), signed on this phone —
     * Everything phones only (they hold the key); Own-folder phones get theirs from the server. Its ttl changes
     * every second, so each look is fresh.
     */
    fun searchLink(cloudName: String, secret: String, query: String): String {
        val b64 = android.util.Base64.encodeToString(query.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
        val ttl = 300 + (System.currentTimeMillis() / 1000 % 300).toInt()
        return "https://res.cloudinary.com/$cloudName/search/${searchSignature(ttl, b64, secret)}/$ttl/$b64"
    }

    /** A search link's signature: SHA-256 hex of ttl + the base64 query + the secret (as Cloudinary's SDKs sign it). */
    fun searchSignature(ttl: Int, b64: String, secret: String): String =
        MessageDigest.getInstance("SHA-256").digest("$ttl$b64$secret".toByteArray()).joinToString("") { "%02x".format(it) }

    /** A signed Upload-API request sent as a form (destroy, rename). */
    suspend fun postForm(url: String, params: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build()
        execute(Request.Builder().url(url).post(body).build())
    }

    /** Uploads [source] with the signed [params]; [onProgress] gets the bytes sent so far. */
    suspend fun upload(url: String, params: Map<String, String>, source: UploadSource, onProgress: (Long) -> Unit): JSONObject =
        withContext(Dispatchers.IO) {
            if (source.size > CHUNKED_ABOVE) chunked(url, params, source, onProgress) else whole(url, params, source, onProgress)
        }

    private fun whole(url: String, params: Map<String, String>, source: UploadSource, onProgress: (Long) -> Unit): JSONObject {
        val file = object : RequestBody() {
            override fun contentType() = source.mime.toMediaTypeOrNull()
            override fun contentLength() = if (source.size > 0) source.size else -1L
            override fun writeTo(sink: BufferedSink) {
                source.open().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var sent = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        sent += n
                        onProgress(sent)
                    }
                }
            }
        }
        return execute(Request.Builder().url(url).post(form(params, source, file)).build())
    }

    private fun chunked(url: String, params: Map<String, String>, source: UploadSource, onProgress: (Long) -> Unit): JSONObject {
        val total = source.size
        val uploadId = UUID.randomUUID().toString().replace("-", "")
        var result = JSONObject()
        source.open().use { input ->
            var start = 0L
            while (start < total) {
                val len = minOf(CHUNK, total - start).toInt()
                val bytes = ByteArray(len)
                var got = 0
                while (got < len) {
                    val n = input.read(bytes, got, len - got)
                    if (n < 0) throw CloudException("“${source.name}” got shorter while uploading.")
                    got += n
                }
                val from = start
                val piece = object : RequestBody() {
                    override fun contentType() = source.mime.toMediaTypeOrNull()
                    override fun contentLength() = len.toLong()
                    override fun writeTo(sink: BufferedSink) {
                        var off = 0
                        while (off < len) {
                            val n = minOf(64 * 1024, len - off)
                            sink.write(bytes, off, n)
                            off += n
                            onProgress(from + off)
                        }
                    }
                }
                val req = Request.Builder().url(url).post(form(params, source, piece))
                    .header("X-Unique-Upload-Id", uploadId)
                    .header("Content-Range", "bytes $start-${start + len - 1}/$total")
                    .build()
                result = execute(req)
                start += len
            }
        }
        return result
    }

    /** A cloud file onto this phone, into [out]; [onProgress] gets the bytes so far. Stops when cancelled. */
    suspend fun download(url: String, out: java.io.File, onProgress: (Long) -> Unit): Unit = withContext(Dispatchers.IO) {
        val resp = try {
            client.newCall(Request.Builder().url(url).build()).execute()
        } catch (e: java.io.IOException) {
            throw CloudException("No connection to the cloud — check the internet and try again.")
        }
        resp.use {
            if (!it.isSuccessful) {
                android.util.Log.w("SyncUpCloud", "Download ${it.code}")
                throw CloudException(friendly(it.code, ""))
            }
            val body = it.body ?: throw CloudException("Something went wrong. Please try again.")
            try {
                out.outputStream().use { o ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var got = 0L
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            got += n
                            onProgress(got)
                        }
                    }
                }
            } catch (e: java.io.IOException) {
                throw CloudException("The connection dropped while getting “${out.name}”. Please try again.")
            }
        }
    }

    private fun form(params: Map<String, String>, source: UploadSource, file: RequestBody) =
        MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            params.forEach { (k, v) -> addFormDataPart(k, v) }
            addFormDataPart("file", source.name.ifBlank { "file" }, file)
        }.build()

    // ---------------------------------------------------------------- Admin API (Everything accounts)
    fun adminGet(creds: CloudCreds, path: String, query: Map<String, String?> = emptyMap()): JSONObject =
        execute(adminRequest(creds, path, query).get().build())

    /** A ready-made Cloudinary address (the server signed it, e.g. a folder's search link): its JSON. */
    fun getJson(url: String): JSONObject = execute(Request.Builder().url(url).get().build())

    fun adminPost(creds: CloudCreds, path: String): JSONObject =
        execute(adminRequest(creds, path).post(FormBody.Builder().build()).build())

    fun adminDelete(creds: CloudCreds, path: String): JSONObject =
        execute(adminRequest(creds, path).delete().build())

    private fun adminRequest(creds: CloudCreds, path: String, query: Map<String, String?> = emptyMap()): Request.Builder {
        val url = "https://api.cloudinary.com/v1_1/${creds.cloudName}".toHttpUrl().newBuilder().apply {
            path.split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) }
            query.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) }
        }.build()
        return Request.Builder().url(url).header("Authorization", Credentials.basic(creds.apiKey, creds.apiSecret))
    }

    private fun execute(req: Request): JSONObject {
        val resp = try {
            client.newCall(req).execute()
        } catch (e: java.io.IOException) {
            throw CloudException("No connection to the cloud — check the internet and try again.")
        }
        resp.use {
            val text = it.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (!it.isSuccessful) {
                val raw = json?.optJSONObject("error")?.str("message").orEmpty()
                android.util.Log.w("SyncUpCloud", "Cloudinary ${it.code}: $raw")  // the details stay in the log
                throw CloudException(friendly(it.code, raw))
            }
            return json ?: JSONObject()
        }
    }

    /**
     * What to tell the person when Cloudinary says no — plain words, never its own wording (user, 26 Nov 2026:
     * "no developer details, end-user info"). Keys, signatures and set-up problems all read as "not available".
     */
    fun friendly(code: Int, raw: String): String {
        val m = raw.lowercase()
        return when {
            code == 401 || "api_key" in m || "api key" in m || "signature" in m || "cloud_name" in m || "cloud name" in m ||
                "disabled" in m || "account" in m ->
                "Cloud isn't available right now. Please try again later."
            "too large" in m || "file size" in m || "maximum" in m -> "That file is too big for the cloud."
            "invalid image" in m || "unsupported" in m || "invalid file" in m || "format" in m -> "That kind of file can't be added."
            code == 404 || "not found" in m || "can't find" in m -> "It isn't there any more — refresh to see what's in the folder now."
            code == 420 || code == 429 || "rate limit" in m -> "The cloud is busy. Please try again in a few minutes."
            code == 403 -> "That isn't allowed here."
            code == 409 || "already exists" in m -> "Something with that name is already there."
            code >= 500 -> "The cloud is having trouble. Please try again in a little while."
            else -> "Something went wrong. Please try again."
        }
    }
}
