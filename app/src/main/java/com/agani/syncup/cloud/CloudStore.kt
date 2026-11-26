package com.agani.syncup.cloud

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.agani.syncup.data.ApiClient
import com.agani.syncup.data.TokenStore
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import retrofit2.HttpException
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * A string from Cloudinary's / the server's JSON. A JSON null is "" — org.json's optString gives the word "null",
 * which made a next page called "null" ("Invalid next_cursor", 26 Nov 2026) and names like "null".
 */
internal fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)

/** One folder's contents, as the screen shows it, with what the person uses and the plan's use. */
data class CloudPage(
    val folders: List<CloudFolderItem>,
    val files: List<CloudItem>,
    val usedBytes: Long? = null,
    val plan: CloudPlan? = null,
)

/**
 * Where the Cloudinary screen gets its folders and does its work. Two kinds:
 * - [OwnSource] ("Own folder"): the server's list of the person's folder, and the server's signature for every
 *   upload, delete and rename, which the phone then sends to Cloudinary itself;
 * - [AdminSource] ("Everything"): Cloudinary's full API from the phone, with the key the server handed over.
 */
interface CloudSource {
    val everything: Boolean
    /** Where the screen starts: the person's own folder, or "" (the whole of Cloudinary). */
    val root: String
    suspend fun list(path: String): CloudPage
    /** Only the subfolders of [path] (for choosing where something goes): quicker than [list] for Everything. */
    suspend fun folders(path: String): List<CloudFolderItem> = list(path).folders
    suspend fun upload(path: String, src: UploadSource, onProgress: (Long) -> Unit): CloudItem?
    suspend fun delete(item: CloudItem)
    suspend fun rename(item: CloudItem, newName: String): CloudItem
    suspend fun createFolder(path: String)
    suspend fun deleteFolder(path: String)
}

/** Server calls with the person's sign-in; errors in the server's own words. */
internal suspend fun <T> server(context: Context, call: suspend (auth: String) -> T): T {
    val token = TokenStore(context).token() ?: throw CloudException("Sign in again to use Cloud.")
    return try {
        call("Bearer $token")
    } catch (e: HttpException) {
        val msg = runCatching {
            JSONObject(e.response()?.errorBody()?.string().orEmpty()).str("message")
        }.getOrNull()?.takeIf { it.isNotBlank() }
        // The server's own messages are written for people; anything else (a crash, a gateway) isn't.
        throw CloudException(msg ?: if (e.code() >= 500) "Cloud is having trouble. Please try again in a little while." else "Something went wrong. Please try again.")
    } catch (e: java.io.IOException) {
        throw CloudException("No connection — check the internet and try again.")
    }
}

/** The resource type Cloudinary keeps a file under: image, video (sound too) or raw (anything else). */
internal fun kindOf(mime: String, name: String): String {
    val m = mime.lowercase()
    if (m.startsWith("image/")) return "image"
    if (m.startsWith("video/") || m.startsWith("audio/")) return "video"
    val ext = name.substringAfterLast('.', "").lowercase()
    if (m.isBlank() || m == "application/octet-stream") {
        if (ext in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "avif")) return "image"
        if (ext in CloudItem.VIDEO_EXT || ext in CloudItem.AUDIO_EXT) return "video"
    }
    return "raw"
}

/** photos | videos | audio | documents | archives | other — as the server's Storage details count them. */
internal fun kindOfFile(resourceType: String, format: String, width: Int): String = when {
    resourceType == "image" -> "photos"
    resourceType == "video" -> if (format in CloudItem.AUDIO_EXT || width <= 0) "audio" else "videos"
    format in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf", "odt", "ods", "odp", "md", "json", "xml", "html", "htm", "epub") -> "documents"
    format in setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz") -> "archives"
    else -> "other"
}

/** <folder>/<name>_<6 random hex>, like the server makes them; other files keep their extension. */
internal fun newPublicId(folder: String, name: String, kind: String): String {
    val base = name.substringAfterLast('/').trim().ifBlank { "file" }
    val dot = base.lastIndexOf('.')
    val stem = (if (dot > 0) base.substring(0, dot) else base).replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").trim('_').take(60).ifBlank { "file" }
    val ext = if (dot > 0) base.substring(dot + 1).replace(Regex("[^A-Za-z0-9]"), "").take(10).lowercase() else ""
    val rand = ByteArray(3).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    val id = (if (folder.isBlank()) "" else "$folder/") + "${stem}_$rand"
    return if (kind == "raw" && ext.isNotEmpty()) "$id.$ext" else id
}

private fun parent(path: String) = path.substringBeforeLast('/', "")

/** "2026-11-26T10:00:00Z" → millis (no java.time: the app runs from Android 7). */
internal fun isoMillis(s: String?): Long {
    if (s.isNullOrBlank()) return 0
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s.take(19))?.time ?: 0
    }.getOrDefault(0)
}

// =============================================================================================== Own folder
class OwnSource(private val context: Context, private val access: CloudAccess) : CloudSource {
    override val everything = false
    override val root = access.root
    private val api get() = ApiClient.service

    private fun rel(path: String) = path.removePrefix(root).trim('/')

    override suspend fun list(path: String): CloudPage {
        val d = server(context) { api.cloudFiles(it, folder = rel(path)) }
        return CloudPage(
            folders = d.folders.map { CloudFolderItem(it, if (path.isEmpty()) it else "$path/$it") },
            files = d.files.map(CloudItem::from),
            usedBytes = d.usedBytes, plan = d.cloud,
        )
    }

    override suspend fun upload(path: String, src: UploadSource, onProgress: (Long) -> Unit): CloudItem? {
        val signed = server(context) {
            api.cloudSign(it, mapOf("action" to "upload", "folder" to rel(path), "name" to src.name, "mime" to src.mime, "bytes" to src.size))
        }
        val result = CloudHttp.upload(signed.url, signed.params, src, onProgress)
        return report(result, src.name)
    }

    /** Tell the server about the upload (it checks Cloudinary's signature) so it's on the person's list. */
    private suspend fun report(result: JSONObject, name: String): CloudItem? {
        val body = JsonParser.parseString(JSONObject().put("result", result).put("name", name).toString()).asJsonObject
        val answer = server(context) { api.cloudUploaded(it, body) }
        val f = answer.getAsJsonObject("file") ?: return null
        return CloudItem.from(com.google.gson.Gson().fromJson(f, CloudFileDto::class.java))
    }

    override suspend fun delete(item: CloudItem) {
        val signed = server(context) { api.cloudSign(it, mapOf("action" to "destroy", "public_id" to item.publicId, "resource_type" to item.resourceType)) }
        CloudHttp.postForm(signed.url, signed.params)  // "ok", or "not found" when it was already gone
        server(context) { api.cloudDeleted(it, mapOf("public_id" to item.publicId, "resource_type" to item.resourceType)) }
    }

    override suspend fun rename(item: CloudItem, newName: String): CloudItem {
        val signed = server(context) {
            api.cloudSign(it, mapOf("action" to "rename", "public_id" to item.publicId, "resource_type" to item.resourceType, "name" to newName))
        }
        val r = CloudHttp.postForm(signed.url, signed.params)
        val to = r.str("public_id").ifBlank { signed.toPublicId.orEmpty() }
        val url = r.str("secure_url").ifBlank { item.url }
        val name = signed.name ?: newName
        server(context) {
            api.cloudRenamed(it, mapOf("from_public_id" to item.publicId, "to_public_id" to to, "resource_type" to item.resourceType,
                "name" to name, "url" to url, "version" to r.str("version")))
        }
        return item.copy(publicId = to, name = name, url = url)
    }

    override suspend fun createFolder(path: String) {
        server(context) { api.cloudFolderCreate(it, mapOf("folder" to rel(path))) }
    }

    override suspend fun deleteFolder(path: String) {
        server(context) { api.cloudFolderDelete(it, mapOf("folder" to rel(path))) }
    }
}

// =============================================================================================== Everything
class AdminSource(private val context: Context, private val creds: CloudCreds) : CloudSource {
    override val everything = true
    override val root = ""
    private val api get() = ApiClient.service
    private val accountsPrefix = creds.base.trim('/') + "/accounts/"

    /** Cloudinary's real folder mode (from its config), else what the server assumes. */
    var folderMode = creds.folderMode
        private set
    /** The server assumes another folder mode than Cloudinary has: Own-folder uploads would land wrongly. */
    var modeMismatch = false
        private set
    /** Own folders by path → the person's name (from the server). */
    private var people: Map<String, String> = emptyMap()
    /** The plan's use, straight from Cloudinary (and passed on to the server for everyone else). */
    var plan: CloudPlan? = null
        private set

    suspend fun prepare() = withContext(Dispatchers.IO) {
        runCatching {
            val mode = CloudHttp.adminGet(creds, "config", mapOf("settings" to "true"))
                .optJSONObject("settings")?.str("folder_mode").orEmpty()
            if (mode == "dynamic" || mode == "fixed") {
                modeMismatch = mode != creds.folderMode
                folderMode = mode
            }
        }
        runCatching {
            people = server(context) { api.cloudAccounts(it) }.accounts.associate { it.root to it.name.ifBlank { it.login } }
        }
        // How much of the plan is used: only an Everything phone can ask Cloudinary, so it tells the server too.
        runCatching {
            val usage = CloudHttp.adminGet(creds, "usage")
            val body = JsonParser.parseString(JSONObject().put("usage", usage).toString()).asJsonObject
            val answer = server(context) { api.cloudUsage(it, body) }
            answer.getAsJsonObject("cloud")?.let { plan = com.google.gson.Gson().fromJson(it, CloudPlan::class.java) }
        }
    }

    private fun isOwnFolder(path: String) = path.startsWith(accountsPrefix)

    override suspend fun folders(path: String): List<CloudFolderItem> = withContext(Dispatchers.IO) {
        val folders = cloudinaryFolders(path)
        if (isOwnFolder(path)) runCatching {
            server(context) { api.cloudFiles(it, path = path) }.folders.forEach { n -> val p = "$path/$n"; folders.getOrPut(p) { CloudFolderItem(n, p) } }
        }
        folders.values.sortedBy { it.label.lowercase() }
    }

    /** Cloudinary's subfolders of [path], by path. */
    private fun cloudinaryFolders(path: String): LinkedHashMap<String, CloudFolderItem> {
        val folders = linkedMapOf<String, CloudFolderItem>()
        runCatching {
            val d = CloudHttp.adminGet(creds, if (path.isEmpty()) "folders" else "folders/$path", mapOf("max_results" to "500"))
            val arr = d.optJSONArray("folders")
            for (i in 0 until (arr?.length() ?: 0)) {
                val f = arr!!.getJSONObject(i)
                val p = f.str("path")
                folders[p] = CloudFolderItem(f.str("name"), p, people[p]?.let { "$it · ${f.str("name")}" } ?: f.str("name"))
            }
        }.onFailure { if (path.isEmpty()) throw it } // a subfolder Cloudinary doesn't have yet: just none
        return folders
    }

    override suspend fun list(path: String): CloudPage = withContext(Dispatchers.IO) {
        // Subfolders: Cloudinary's, plus empty folders the person made (only on the server's list).
        val folders = cloudinaryFolders(path)

        val names = mutableMapOf<String, String>()
        if (isOwnFolder(path)) runCatching {
            val d = server(context) { api.cloudFiles(it, path = path) }
            d.folders.forEach { n -> val p = "$path/$n"; folders.getOrPut(p) { CloudFolderItem(n, p) } }
            d.files.forEach { names[it.publicId] = it.name }
        }

        val files = if (folderMode == "fixed") listFixed(path) else listDynamic(path)
        CloudPage(
            folders = folders.values.sortedBy { it.label.lowercase() },
            files = files.map { it.copy(name = names[it.publicId] ?: it.name) },
            plan = if (path.isEmpty()) plan else null,
        )
    }

    private fun listDynamic(path: String): List<CloudItem> {
        if (path.isEmpty()) return listRootDynamic()
        val out = mutableListOf<CloudItem>()
        var cursor: String? = null
        repeat(4) {
            val d = runCatching {
                CloudHttp.adminGet(creds, "resources/by_asset_folder", mapOf("asset_folder" to path, "max_results" to "500", "next_cursor" to cursor))
            }.getOrElse { e -> if (out.isEmpty() && path.isNotEmpty()) throw e else return out }
            out += items(d)
            cursor = d.str("next_cursor").ifBlank { null } ?: return out
        }
        return out
    }

    /** Files right at Cloudinary's root (in no folder): every file of each kind, kept when its folder is empty. */
    private fun listRootDynamic(): List<CloudItem> {
        val out = mutableListOf<CloudItem>()
        for (type in listOf("image", "video", "raw")) {
            var cursor: String? = null
            for (page in 0 until 6) {
                val d = runCatching {
                    CloudHttp.adminGet(creds, "resources/$type/upload", mapOf("max_results" to "500", "next_cursor" to cursor))
                }.getOrNull() ?: break
                val arr = d.optJSONArray("resources")
                val atRoot = org.json.JSONArray()
                for (i in 0 until (arr?.length() ?: 0)) {
                    val r = arr!!.getJSONObject(i)
                    if (r.str("asset_folder").isEmpty()) atRoot.put(r)
                }
                out += items(JSONObject().put("resources", atRoot))
                cursor = d.str("next_cursor").ifBlank { null } ?: break
            }
        }
        return out
    }

    /**
     * Storage for the whole of Cloudinary: its own usage report (plan, storage, downloads) and every file counted
     * by kind and type. Up to 10,000 files of each kind are counted ([CloudStats.partial] beyond that).
     */
    suspend fun wholeStats(): CloudStats = withContext(Dispatchers.IO) {
        val usage = runCatching { CloudHttp.adminGet(creds, "usage") }.getOrNull()
        val kinds = linkedMapOf<String, LongArray>()
        val formats = linkedMapOf<String, LongArray>()
        var files = 0
        var bytes = 0L
        var partial = false
        for (type in listOf("image", "video", "raw")) {
            var cursor: String? = null
            var page = 0
            while (true) {
                val d = CloudHttp.adminGet(creds, "resources/$type", mapOf("max_results" to "500", "next_cursor" to cursor))
                val arr = d.optJSONArray("resources")
                for (i in 0 until (arr?.length() ?: 0)) {
                    val r = arr!!.getJSONObject(i)
                    val size = r.optLong("bytes")
                    val fmt = r.str("format").ifBlank { r.str("public_id").substringAfterLast('.', "") }.lowercase()
                    val kind = kindOfFile(type, fmt, r.optInt("width"))
                    kinds.getOrPut(kind) { LongArray(2) }.let { it[0]++; it[1] += size }
                    formats.getOrPut(fmt.ifBlank { "?" }) { LongArray(2) }.let { it[0]++; it[1] += size }
                    files++
                    bytes += size
                }
                cursor = d.str("next_cursor").ifBlank { null } ?: break
                if (++page >= 20) { partial = true; break }
            }
        }
        val plan = usage?.let { planFrom(it) }
        CloudStats(
            files = files,
            usedBytes = usage?.optJSONObject("storage")?.optLong("usage")?.takeIf { it > 0 } ?: bytes,
            kinds = kinds.map { (k, v) -> CloudCount(kind = k, count = v[0].toInt(), bytes = v[1]) }.sortedByDescending { it.bytes },
            formats = formats.map { (k, v) -> CloudCount(format = k, count = v[0].toInt(), bytes = v[1]) }.sortedByDescending { it.bytes }.take(12),
            cloud = plan ?: this@AdminSource.plan,
            wholeCloud = true,
            bandwidthBytes = usage?.optJSONObject("bandwidth")?.optLong("usage"),
            partial = partial,
        )
    }

    /** The plan from Cloudinary's usage report: a storage limit, or credits (1 credit = 1 GB). */
    private fun planFrom(u: JSONObject): CloudPlan? {
        val gb = 1024.0 * 1024 * 1024
        val storage = u.optJSONObject("storage")
        val credits = u.optJSONObject("credits")
        val (used, total) = when {
            storage != null && storage.optDouble("limit", 0.0) > 0 -> storage.optDouble("usage", 0.0) to storage.optDouble("limit")
            credits != null && credits.optDouble("limit", 0.0) > 0 -> credits.optDouble("usage", 0.0) * gb to credits.optDouble("limit") * gb
            else -> return null
        }
        return CloudPlan(u.str("plan"), used.toLong(), total.toLong(), (total - used).toLong().coerceAtLeast(0), System.currentTimeMillis())
    }

    private fun listFixed(path: String): List<CloudItem> {
        val out = mutableListOf<CloudItem>()
        for (type in listOf("image", "video", "raw")) {
            var cursor: String? = null
            for (page in 0 until 4) {
                val d = CloudHttp.adminGet(creds, "resources/$type/upload",
                    mapOf("prefix" to if (path.isEmpty()) null else "$path/", "max_results" to "500", "next_cursor" to cursor))
                out += items(d).filter { parent(it.publicId) == path }
                cursor = d.str("next_cursor").ifBlank { null } ?: break
            }
        }
        return out
    }

    private fun items(d: JSONObject): List<CloudItem> {
        val arr = d.optJSONArray("resources") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            val pid = r.str("public_id")
            val format = r.str("format")
            val shown = r.str("display_name").ifBlank { pid.substringAfterLast('/') }
            CloudItem(
                publicId = pid,
                resourceType = r.str("resource_type").ifBlank { "image" },
                name = if (format.isNotBlank() && !shown.endsWith(".$format", true)) "$shown.$format" else shown,
                format = format,
                bytes = r.optLong("bytes"),
                width = r.optInt("width").takeIf { it > 0 },
                height = r.optInt("height").takeIf { it > 0 },
                url = r.str("secure_url"),
                createdMs = isoMillis(r.str("created_at")),
            )
        }
    }

    private fun signed(params: Map<String, String>) =
        params + mapOf("signature" to CloudHttp.sign(params, creds.apiSecret), "api_key" to creds.apiKey)

    private val apiBase get() = "https://api.cloudinary.com/v1_1/${creds.cloudName}"
    private fun now() = (System.currentTimeMillis() / 1000).toString()

    override suspend fun upload(path: String, src: UploadSource, onProgress: (Long) -> Unit): CloudItem? {
        val kind = kindOf(src.mime, src.name)
        val params = mutableMapOf("public_id" to newPublicId(path, src.name, kind), "timestamp" to now())
        if (folderMode == "dynamic" && path.isNotEmpty()) params["asset_folder"] = path
        // Tags, as the server gives every upload: all SyncUp Cloud files, and the person's own (acct_<id>).
        val owner = path.removePrefix(accountsPrefix).substringBefore('/').takeIf { isOwnFolder(path) && it.all(Char::isDigit) && it.isNotEmpty() }
        params["tags"] = if (owner != null) "syncup_cloud,acct_$owner" else "syncup_cloud"
        val r = CloudHttp.upload("$apiBase/$kind/upload", signed(params), src, onProgress)
        val item = items(JSONObject().put("resources", org.json.JSONArray().put(r))).firstOrNull()?.copy(name = src.name)
        if (isOwnFolder(r.str("public_id"))) runCatching {
            val body = JsonParser.parseString(JSONObject().put("result", r).put("name", src.name).toString()).asJsonObject
            server(context) { api.cloudUploaded(it, body) }
        }
        return item
    }

    override suspend fun delete(item: CloudItem) {
        CloudHttp.postForm("$apiBase/${item.resourceType}/destroy",
            signed(mapOf("public_id" to item.publicId, "timestamp" to now(), "invalidate" to "true")))
        if (isOwnFolder(item.publicId)) runCatching {
            server(context) { api.cloudDeleted(it, mapOf("public_id" to item.publicId, "resource_type" to item.resourceType)) }
        }
    }

    override suspend fun rename(item: CloudItem, newName: String): CloudItem {
        var name = newName.trim()
        if (item.resourceType == "raw" && !name.contains('.') && item.publicId.contains('.')) name += "." + item.publicId.substringAfterLast('.')
        val to = newPublicId(parent(item.publicId), name, item.resourceType)
        val r = CloudHttp.postForm("$apiBase/${item.resourceType}/rename",
            signed(mapOf("from_public_id" to item.publicId, "to_public_id" to to, "timestamp" to now(), "invalidate" to "true")))
        val url = r.str("secure_url").ifBlank { item.url }
        if (isOwnFolder(item.publicId)) runCatching {
            server(context) {
                api.cloudRenamed(it, mapOf("from_public_id" to item.publicId, "to_public_id" to to, "resource_type" to item.resourceType,
                    "name" to name, "url" to url, "version" to r.str("version")))
            }
        }
        return item.copy(publicId = to, name = name, url = url)
    }

    override suspend fun createFolder(path: String) {
        withContext(Dispatchers.IO) { CloudHttp.adminPost(creds, "folders/$path") }
        if (isOwnFolder(path)) runCatching { server(context) { api.cloudFolderCreate(it, mapOf("path" to path)) } }
    }

    override suspend fun deleteFolder(path: String) {
        withContext(Dispatchers.IO) {
            runCatching { CloudHttp.adminDelete(creds, "folders/$path") }.onFailure { e ->
                // A folder only on the server's list (empty, made in the app) isn't at Cloudinary at all.
                if (!(isOwnFolder(path) && e.message.orEmpty().contains("Can't find", true))) throw e
            }
        }
        if (isOwnFolder(path)) runCatching { server(context) { api.cloudFolderDelete(it, mapOf("path" to path)) } }
    }

    /** Files of deleted accounts still at Cloudinary (the server can't delete them itself). */
    suspend fun pending(): CloudPending = server(context) { api.cloudPending(it) }

    /** Delete those files at Cloudinary and take them off the server's list. Returns how many went. */
    suspend fun clearPending(files: List<PendingFile>): Int {
        var done = 0
        for (f in files) {
            runCatching {
                CloudHttp.postForm("$apiBase/${f.resourceType}/destroy",
                    signed(mapOf("public_id" to f.publicId, "timestamp" to now(), "invalidate" to "true")))
            }.onSuccess { done++ }
        }
        server(context) {
            api.cloudDeleted(it, mapOf("files" to files.map { f -> mapOf("public_id" to f.publicId, "resource_type" to f.resourceType) }))
        }
        return done
    }
}

// =============================================================================================== session
/**
 * Opens the right [CloudSource] for this account. The Everything key is kept in memory only — never on the
 * phone's storage — and is forgotten on sign-out ([clear]).
 */
object CloudSession {
    @Volatile private var creds: CloudCreds? = null

    fun clear() {
        creds = null
    }

    suspend fun open(context: Context): Pair<CloudAccess, CloudSource> {
        val app = context.applicationContext
        val access = server(app) { ApiClient.service.cloudAccess(it) }
        if (!access.configured) throw CloudException("Cloud isn't available yet. Please try again later.")
        return when (access.access) {
            "all" -> {
                val c = creds ?: server(app) { ApiClient.service.cloudCredentials(it) }.also { creds = it }
                access to AdminSource(app, c).also { it.prepare() }
            }
            "own" -> {
                clear()
                access to OwnSource(app, access)
            }
            else -> {
                clear()
                throw CloudException("Cloud isn't switched on for your account.")
            }
        }
    }
}

// =============================================================================================== live
/**
 * Silent "cloud" pushes from the server (SyncUpMessagingService): [files] goes up when files changed in the
 * person's folder, [access] when the admin changed their Cloud access. The open Cloud page reloads / reopens,
 * and the main page refreshes the account (the Cloud tile).
 */
object CloudEvents {
    var files by mutableIntStateOf(0)
        private set
    var access by mutableIntStateOf(0)
        private set

    fun received(what: String?) {
        if (what == "access") access++ else files++
    }
}

// =============================================================================================== uploads
/** One file going up, shown under the folder while it uploads. */
class CloudUpload(val id: Long, val name: String, val folder: String, val size: Long) {
    var sent by mutableLongStateOf(0L)
    var state by mutableStateOf("waiting") // waiting | uploading | done | failed
    var error by mutableStateOf<String?>(null)
}

/**
 * Uploads, one at a time, kept going while the person moves around the app (not only on this screen).
 * [finished] goes up after each one, so the open folder can refresh.
 */
object CloudUploads {
    val list = mutableStateListOf<CloudUpload>()
    var finished by mutableIntStateOf(0)
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val oneAtATime = Mutex()
    private var nextId = 1L

    fun add(source: CloudSource, folder: String, files: List<UploadSource>) {
        for (f in files) {
            val job = CloudUpload(nextId++, f.name, folder, f.size)
            list += job
            scope.launch {
                oneAtATime.withLock {
                    job.state = "uploading"
                    runCatching { source.upload(folder, f) { sent -> job.sent = sent } }
                        .onSuccess { job.state = "done"; job.sent = job.size }
                        .onFailure { job.state = "failed"; job.error = it.message ?: "Couldn't upload." }
                    finished++
                }
            }
        }
    }

    /** Clear finished and failed rows. */
    fun clearDone() {
        list.removeAll { it.state == "done" || it.state == "failed" }
    }

    val busy: Boolean get() = list.any { it.state == "waiting" || it.state == "uploading" }
}
