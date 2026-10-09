package com.agani.syncup.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.downloads.Downloads
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Each website's own icon, fetched by the app from the site itself (no third-party icon service):
 * the page's declared icons (apple-touch-icon, the largest rel="icon"), else /favicon.ico. Kept on
 * the phone and checked again after a week; a site without a usable icon gets a coloured letter
 * (and is tried again after a few days). Icons load only for the tiles being shown, 3 at a time.
 */
object SiteIcons {
    private const val FRESH_MS = 7L * 24 * 3600 * 1000
    private const val RETRY_MS = 3L * 24 * 3600 * 1000
    private const val MAX_PX = 128

    private var dir: File? = null
    private val memory = mutableStateMapOf<String, ImageBitmap>()
    private val missing = mutableStateMapOf<String, Boolean>()
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun init(context: Context) {
        if (dir == null) dir = File(context.filesDir, "site-icons").apply { mkdirs() }
    }

    fun hostOf(url: String): String? = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * What an icon is kept under: the site — except a YouTube channel (youtube.com/@name), which gets
     * its own picture, so a row of channels doesn't show the same YouTube logo on every tile.
     */
    fun keyOf(url: String): String? {
        val host = hostOf(url) ?: return null
        val first = runCatching { Uri.parse(url).pathSegments.firstOrNull() }.getOrNull()
        return if (isYouTube(host) && first != null && first.startsWith("@")) "$host/${first.lowercase()}" else host
    }

    private fun isYouTube(host: String) = host == "youtube.com" || host.endsWith(".youtube.com")

    fun icon(key: String): ImageBitmap? = memory[key]

    fun isMissing(key: String): Boolean = missing[key] == true

    /** Make sure [url]'s icon is on its way: memory → the phone → the site. Main thread. */
    fun request(url: String) {
        val host = keyOf(url) ?: return
        val d = dir ?: return
        if (memory.containsKey(host) || missing.containsKey(host) || !inFlight.add(host)) return
        pool.execute {
            try {
                val key = key(host)
                val png = File(d, "$key.png")
                val none = File(d, "$key.none")
                val now = System.currentTimeMillis()
                var cached: Bitmap? = null
                if (png.exists()) cached = BitmapFactory.decodeFile(png.path)
                if (cached != null) show(host, cached)
                val stale = cached == null || now - png.lastModified() > FRESH_MS
                if (cached == null && none.exists() && now - none.lastModified() < RETRY_MS) {
                    main.post { missing[host] = true }
                    return@execute
                }
                if (!stale) return@execute
                val fresh = fetch(url, channel = host.contains('/'))
                when {
                    fresh != null -> {
                        runCatching { png.outputStream().use { fresh.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                        none.delete()
                        show(host, fresh)
                    }
                    cached != null -> png.setLastModified(now) // keep the old icon; look again next week
                    else -> {
                        runCatching { none.writeText("") }
                        none.setLastModified(now)
                        main.post { missing[host] = true }
                    }
                }
            } finally {
                inFlight.remove(host)
            }
        }
    }

    /**
     * The site's icon as the browser received it while the user was on the page (onReceivedIcon) —
     * for sites that turn away the app's own fetch. Kept only where there's no fetched icon yet.
     */
    fun offer(pageUrl: String, bmp: Bitmap) {
        val host = hostOf(pageUrl) ?: return
        val d = dir ?: return
        if (memory.containsKey(host) || bmp.width < 16) return
        val copy = bmp.copy(Bitmap.Config.ARGB_8888, false) ?: return
        pool.execute {
            val key = key(host)
            runCatching { File(d, "$key.png").outputStream().use { scale(copy).compress(Bitmap.CompressFormat.PNG, 100, it) } }
            File(d, "$key.none").delete()
            show(host, copy)
        }
    }

    private fun show(host: String, bmp: Bitmap) {
        val image = bmp.asImageBitmap()
        main.post {
            missing.remove(host)
            memory[host] = image
        }
    }

    private fun key(host: String): String =
        MessageDigest.getInstance("SHA-1").digest(host.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    // ------------------------------------------------------------------ fetching (worker threads)
    private class Candidate(val url: String, val score: Int)

    private val LINK = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("""([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")
    private val OG_IMAGE = Regex("""<meta\s+property="og:image"\s+content="([^"]+)"""", RegexOption.IGNORE_CASE)

    /**
     * The best icon the site offers, scaled to at most [MAX_PX], or null. For a [channel] page that's
     * the channel's own picture (its og:image, asked for at a small size).
     */
    private fun fetch(pageUrl: String, channel: Boolean = false): Bitmap? {
        val ua = Downloads.defaultUserAgent.ifBlank {
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
        }
        val candidates = ArrayList<Candidate>()
        var origin = pageUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: return null
        runCatching {
            val page = Request.Builder().url(pageUrl).header("User-Agent", ua)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-IN,en;q=0.9")
                .build()
            client.newCall(page).execute().use { r ->
                val base = r.request.url
                origin = "${base.scheme}://${base.host}"
                val html = r.body?.byteStream()?.let { readUpTo(it, 384 * 1024) }?.toString(Charsets.UTF_8).orEmpty()
                if (channel) {
                    OG_IMAGE.find(html)?.groupValues?.get(1)?.replace("&amp;", "&")?.let { picture ->
                        candidates.add(Candidate(picture.replace(Regex("=s\\d+-"), "=s176-"), 5000))
                    }
                }
                for (tag in LINK.findAll(html.substringBefore("</head>", html))) {
                    val attrs = ATTR.findAll(tag.value).associate { m ->
                        m.groupValues[1].lowercase() to (m.groupValues[2].ifEmpty { m.groupValues[3].ifEmpty { m.groupValues[4] } })
                    }
                    val rel = attrs["rel"]?.lowercase() ?: continue
                    val href = attrs["href"]?.trim() ?: continue
                    if (!rel.contains("icon") || rel.contains("mask-icon")) continue
                    if (href.lowercase().endsWith(".svg") || attrs["type"]?.contains("svg") == true) continue
                    val size = attrs["sizes"]?.lowercase()?.split(' ')?.mapNotNull { it.substringBefore('x').toIntOrNull() }?.maxOrNull()
                    val score = when {
                        rel.contains("apple-touch-icon") -> 1000 + (size ?: 180)
                        size != null -> size
                        else -> 32
                    }
                    val abs = if (href.startsWith("data:")) href else base.resolve(href)?.toString() ?: continue
                    candidates.add(Candidate(abs, score))
                }
            }
        }
        candidates.sortByDescending { it.score }
        candidates.add(Candidate("$origin/apple-touch-icon.png", 0))
        candidates.add(Candidate("$origin/favicon.ico", 0))
        for (c in candidates.distinctBy { it.url }.take(6)) {
            val bmp = runCatching { download(c.url, ua) }.getOrNull() ?: continue
            if (bmp.width >= 16 && bmp.height >= 16) return scale(bmp)
        }
        return null
    }

    private fun download(url: String, ua: String): Bitmap? {
        val bytes = if (url.startsWith("data:")) {
            if (!url.substringBefore(',').contains(";base64")) return null
            Base64.decode(url.substringAfter(','), Base64.DEFAULT)
        } else {
            val image = Request.Builder().url(url).header("User-Agent", ua)
                .header("Accept", "image/avif,image/webp,image/png,image/*,*/*;q=0.8")
                .header("Accept-Language", "en-IN,en;q=0.9")
                .build()
            client.newCall(image).execute().use { r ->
                if (!r.isSuccessful) return null
                r.body?.byteStream()?.let { readUpTo(it, 1024 * 1024) } ?: return null
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_PX && bounds.outHeight / (sample * 2) >= MAX_PX) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun scale(bmp: Bitmap): Bitmap {
        val big = maxOf(bmp.width, bmp.height)
        if (big <= MAX_PX) return bmp
        val f = MAX_PX.toFloat() / big
        return Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt().coerceAtLeast(1), (bmp.height * f).toInt().coerceAtLeast(1), true)
    }

    private fun readUpTo(input: InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

private val LETTER_COLORS = listOf(
    Color(0xFF1B4FD8), Color(0xFF0F766E), Color(0xFFDC2626), Color(0xFF7C3AED),
    Color(0xFFEA580C), Color(0xFF0891B2), Color(0xFFB45309), Color(0xFF374151),
)

/**
 * A site's icon for a shortcut tile ([circle] across, icon [iconSize]): the fetched icon, a coloured
 * letter when the site has none, or a faint letter while it loads.
 */
@Composable
fun SiteMark(url: String, label: String, circle: Dp = 56.dp, iconSize: Dp = 30.dp) {
    val host = remember(url) { SiteIcons.keyOf(url) }
    LaunchedEffect(host) { SiteIcons.request(url) }
    val icon = host?.let { SiteIcons.icon(it) }
    val letter = label.trim().take(1).uppercase().ifEmpty { "?" }
    when {
        icon != null -> Image(icon, null, Modifier.size(iconSize).clip(RoundedCornerShape(iconSize * .22f)))
        host != null && SiteIcons.isMissing(host) -> Box(
            Modifier.size(circle).clip(CircleShape).background(LETTER_COLORS[(host.hashCode() and 0x7fffffff) % LETTER_COLORS.size]),
            contentAlignment = Alignment.Center,
        ) {
            Text(letter, color = Color.White, fontSize = (circle.value * .36f).sp, fontWeight = FontWeight.Medium)
        }
        else -> Text(letter, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .45f), fontSize = (circle.value * .32f).sp, fontWeight = FontWeight.Medium)
    }
}
