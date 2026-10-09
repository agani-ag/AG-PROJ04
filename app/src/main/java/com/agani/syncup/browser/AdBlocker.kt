package com.agani.syncup.browser

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * SyncUp's ad blocker, inside its own browser only.
 *
 * Uses the public EasyList filter lists (EasyList, EasyPrivacy and EasyList India), downloaded on
 * the phone and refreshed weekly — until the first download, a small built-in list of ad domains.
 * Supported from the lists: blocking by domain (`||ads.example^`, with `$third-party`), address
 * patterns with `*` `^` `|` and `$domain=` / `$third-party` / resource-type options, exceptions
 * (`@@`), whole-site exceptions (`$document`, `$elemhide`), and element hiding for the site
 * (`site##selector`, `site#@#selector`) plus a short list of common ad boxes everywhere.
 * Never used: script injection rules, regex rules, or anything that would change a page's code.
 *
 * The page itself is never blocked — only what it loads. "Allow on site" turns it off per site.
 */
object AdBlocker {
    private val LISTS = listOf(
        "easylist" to "https://easylist.to/easylist/easylist.txt",
        "easyprivacy" to "https://easylist.to/easylist/easyprivacy.txt",
        "indianlist" to "https://easylist-downloads.adblockplus.org/indianlist.txt",
    )
    private const val WEEK_MS = 7L * 24 * 3600 * 1000

    private var prefs: SharedPreferences? = null
    private lateinit var dir: File
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var engine: Engine = Engine.builtIn()
    @Volatile private var allowedSnapshot: Set<String> = emptySet()

    /** When the filter lists were last downloaded (0 = never). */
    var lastUpdated by mutableLongStateOf(0L)
        private set
    var updating by mutableStateOf(false)
        private set
    /** Ads and trackers blocked this week. */
    var weekBlocked by mutableIntStateOf(0)
        private set
    /** Sites the user let show ads ("Allow on site"). */
    val allowedSites = mutableStateListOf<String>()

    private var pendingCount = 0
    private var countPosted = false

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("adblock", Context.MODE_PRIVATE)
        prefs = p
        dir = File(context.filesDir, "adblock").apply { mkdirs() }
        lastUpdated = p.getLong("updated", 0)
        allowedSites.addAll(p.getStringSet("allowed", emptySet())!!.sorted())
        allowedSnapshot = allowedSites.toSet()
        weekBlocked = if (p.getInt("week", 0) == currentWeek()) p.getInt("week_count", 0) else 0
        io.execute {
            load()
            if (System.currentTimeMillis() - lastUpdated > WEEK_MS) download()
        }
    }

    // ------------------------------------------------------------------ settings
    /** Does blocking apply to [tab]'s page right now? */
    fun activeFor(tab: BrowserTab): Boolean =
        BrowserSettings.blockAds && !isAllowed(tab.pageHost)

    fun isAllowed(host: String): Boolean {
        val site = UrlInput.registrableDomain(host)
        return site.isNotEmpty() && site in allowedSnapshot
    }

    /** "Allow on site" / "Block ads on this site" for [host]'s site. */
    fun setAllowed(host: String, allowed: Boolean) {
        val site = UrlInput.registrableDomain(host).ifEmpty { return }
        if (allowed && site !in allowedSites) allowedSites.add(site)
        if (!allowed) allowedSites.remove(site)
        allowedSnapshot = allowedSites.toSet()
        prefs?.edit()?.putStringSet("allowed", allowedSnapshot)?.apply()
    }

    fun clearAllowed() {
        allowedSites.clear()
        allowedSnapshot = emptySet()
        prefs?.edit()?.remove("allowed")?.apply()
    }

    /** Download the filter lists now (Settings → "Update now"). */
    fun updateNow() {
        if (updating) return
        io.execute { download() }
    }

    // ------------------------------------------------------------------ matching (WebView threads)
    /**
     * Should the page on [pageHost] be kept from loading [url]? [accept] is the request's Accept
     * header (helps tell images and stylesheets apart).
     */
    fun shouldBlock(url: String, pageHost: String, accept: String?): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http")) return false
        val host = hostOf(lower)
        if (host.isEmpty()) return false
        return engine.blocks(lower, host, pageHost, typeOf(lower, accept))
    }

    /** CSS that hides ad boxes on [host] (empty when there are none or the site is exempt). */
    fun cosmeticCss(host: String): String {
        val h = host.lowercase()
        val css = engine.css(h)
        return if (isYouTube(h)) css + YOUTUBE_HIDE.joinToString("") { "$it{display:none!important}" } else css
    }

    /** YouTube serves its video ads from the same servers as the videos, so it needs [YOUTUBE_JS]. */
    fun isYouTube(host: String): Boolean = YOUTUBE_HOSTS.any { host == it || host.endsWith(".$it") }

    /** The pages [YOUTUBE_JS] runs on. */
    val YOUTUBE_ORIGINS = setOf("https://www.youtube.com", "https://m.youtube.com", "https://youtube.com", "https://music.youtube.com")
    private val YOUTUBE_HOSTS = listOf("youtube.com", "youtube-nocookie.com")

    /** YouTube's ad slots, sponsored cards and promoted items (mobile and desktop pages). */
    private val YOUTUBE_HIDE = listOf(
        "ytm-companion-slot", "ytm-companion-ad-renderer", "ytm-promoted-sparkles-web-renderer",
        "ytm-promoted-sparkles-text-search-renderer", "ytm-promoted-video-renderer", "ad-slot-renderer", "ytm-ad-slot-renderer",
        "ytm-rich-item-renderer:has(ad-slot-renderer)", "ytm-item-section-renderer:has(ad-slot-renderer)",
        "ytm-statement-banner-renderer", ".ytp-ad-overlay-container", "#player-ads", "#masthead-ad",
        "ytd-ad-slot-renderer", "ytd-companion-slot-renderer", "ytd-promoted-sparkles-web-renderer", "ytd-banner-promo-renderer",
        "ytd-in-feed-ad-layout-renderer", "ytd-rich-item-renderer:has(ytd-ad-slot-renderer)", "ytd-statement-banner-renderer",
    )

    /**
     * YouTube video ads, the way browser ad blockers handle them: the ad slots are taken out of the
     * video's details before YouTube's player reads them (the first video arrives in the page, the
     * next ones from the player's own requests). An ad that still starts is muted, run to its end
     * and skipped, and the sound comes back for the video. Only runs while ads are blocked for the page.
     */
    val YOUTUBE_JS = """
        (function () {
          if (window.__syncupYt) return;
          window.__syncupYt = true;
          try { if (!window.AndroidAdBridge || !AndroidAdBridge.active(location.hostname)) return; } catch (e) { return; }
          var KEYS = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams'];
          function clean(o) {
            if (!o || typeof o !== 'object') return o;
            try {
              for (var i = 0; i < KEYS.length; i++) {
                if (KEYS[i] in o) delete o[KEYS[i]];
                if (o.playerResponse && typeof o.playerResponse === 'object' && KEYS[i] in o.playerResponse) delete o.playerResponse[KEYS[i]];
              }
              if (Array.isArray(o)) for (var j = 0; j < o.length; j++) if (o[j] && o[j].playerResponse) clean(o[j]);
            } catch (e) {}
            return o;
          }
          ['ytInitialPlayerResponse', 'playerResponse'].forEach(function (name) {
            var value = window[name];
            try {
              Object.defineProperty(window, name, {
                configurable: true,
                get: function () { return value; },
                set: function (v) { value = clean(v); }
              });
            } catch (e) {}
          });
          var parse = JSON.parse;
          JSON.parse = function () { return clean(parse.apply(this, arguments)); };
          var json = Response.prototype.json;
          Response.prototype.json = function () { return json.apply(this, arguments).then(clean); };
          var mutedHere = false;
          setInterval(function () {
            try {
              var player = document.querySelector('.html5-video-player');
              if (!player) return;
              var v = player.querySelector('video');
              var ad = player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting');
              if (ad && v) {
                if (!v.muted) { v.muted = true; mutedHere = true; }
                if (isFinite(v.duration) && v.duration > 0 && v.currentTime < v.duration - 0.2) v.currentTime = v.duration;
                var skip = player.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, button[class*="skip-ad"], button[class*="ad-skip"]');
                if (skip) skip.click();
              } else if (!ad && v && mutedHere) {
                v.muted = false;
                mutedHere = false;
              }
            } catch (e) {}
          }, 250);
        })();
    """.trimIndent()

    /** Count blocked requests for Settings ("1,284 this week"); batched onto the main thread. */
    internal fun counted() {
        synchronized(this) {
            pendingCount++
            if (countPosted) return
            countPosted = true
        }
        main.postDelayed({
            val n = synchronized(this) {
                countPosted = false
                pendingCount.also { pendingCount = 0 }
            }
            val week = currentWeek()
            val p = prefs ?: return@postDelayed
            val base = if (p.getInt("week", 0) == week) p.getInt("week_count", 0) else 0
            weekBlocked = base + n
            p.edit().putInt("week", week).putInt("week_count", weekBlocked).apply()
        }, 1_000)
    }

    // ------------------------------------------------------------------ lists
    private fun load() {
        val files = LISTS.map { File(dir, "${it.first}.txt") }.filter { it.exists() && it.length() > 0 }
        if (files.isEmpty()) return
        val e = Engine.builtIn()
        files.forEach { f -> runCatching { f.forEachLine { e.add(it) } } }
        engine = e
    }

    private fun download() {
        main.post { updating = true }
        val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        var any = false
        for ((name, url) in LISTS) {
            runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@use
                    val tmp = File(dir, "$name.tmp")
                    r.body?.byteStream()?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    if (tmp.length() > 1000 && tmp.renameTo(File(dir, "$name.txt").also { it.delete() })) any = true
                }
            }
        }
        if (any) load()
        val now = System.currentTimeMillis()
        main.post {
            updating = false
            if (any) {
                lastUpdated = now
                prefs?.edit()?.putLong("updated", now)?.apply()
            }
        }
    }

    private fun currentWeek(): Int = Calendar.getInstance().let { it.get(Calendar.YEAR) * 100 + it.get(Calendar.WEEK_OF_YEAR) }

    private fun hostOf(url: String): String {
        val start = url.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: return ""
        var end = start
        while (end < url.length && url[end] != '/' && url[end] != '?' && url[end] != '#' && url[end] != ':') end++
        return url.substring(start, end).substringAfterLast('@')
    }

    /** A rough resource type: image / stylesheet / script / media / font / subdocument, or null. */
    private fun typeOf(url: String, accept: String?): String? {
        val a = accept?.lowercase().orEmpty()
        if (a.startsWith("image/")) return "image"
        if (a.startsWith("text/css")) return "stylesheet"
        if (a.startsWith("text/html")) return "subdocument"
        val path = url.substringBefore('?').substringBefore('#')
        return when (path.substringAfterLast('.', "").substringAfterLast('/')) {
            "js", "mjs" -> "script"
            "css" -> "stylesheet"
            "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif", "bmp" -> "image"
            "mp4", "webm", "mp3", "m4a", "ogg", "wav", "m3u8", "ts" -> "media"
            "woff", "woff2", "ttf", "otf", "eot" -> "font"
            "html", "htm" -> "subdocument"
            else -> null
        }
    }

    // ------------------------------------------------------------------ the rule engine
    private class Rule(
        val pattern: String,
        /** 1 = third-party only, -1 = first-party only, 0 = either. */
        val party: Int,
        val include: Array<String>?,
        val exclude: Array<String>?,
        val types: Set<String>?,
    ) {
        @Volatile private var regex: Regex? = null

        fun matches(url: String, pageHost: String, thirdParty: Boolean, type: String?): Boolean {
            if (party == 1 && !thirdParty) return false
            if (party == -1 && thirdParty) return false
            if (types != null && (type == null || type !in types)) return false
            if (include != null && include.none { onDomain(pageHost, it) }) return false
            if (exclude != null && exclude.any { onDomain(pageHost, it) }) return false
            val r = regex ?: toRegex(pattern).also { regex = it } ?: return false
            return r.containsMatchIn(url)
        }

        companion object {
            /** ABP pattern → regex: `||` domain anchor, `|` ends, `*` anything, `^` separator. */
            fun toRegex(p: String): Regex? = runCatching {
                var s = p
                val sb = StringBuilder()
                when {
                    s.startsWith("||") -> {
                        sb.append("^[a-z][a-z0-9+.-]*://([^/?#]*\\.)?")
                        s = s.substring(2)
                    }
                    s.startsWith("|") -> {
                        sb.append('^')
                        s = s.substring(1)
                    }
                }
                val endAnchor = s.endsWith("|")
                if (endAnchor) s = s.dropLast(1)
                for (ch in s) {
                    when (ch) {
                        '*' -> sb.append(".*")
                        '^' -> sb.append("(?:[^a-z0-9_.%-]|$)")
                        in "\\.+?()[]{}$|" -> sb.append('\\').append(ch)
                        else -> sb.append(ch)
                    }
                }
                if (endAnchor) sb.append('$')
                Regex(sb.toString())
            }.getOrNull()
        }
    }

    private class Engine {
        val blockHosts = HashSet<String>()
        val blockHosts3p = HashSet<String>()
        val allowHosts = HashSet<String>()
        val block = HashMap<String, MutableList<Rule>>()
        val allow = HashMap<String, MutableList<Rule>>()
        val blockLoose = ArrayList<Rule>()
        val exemptSites = HashSet<String>()
        val noCosmeticSites = HashSet<String>()
        val siteHide = HashMap<String, MutableList<String>>()
        val siteUnhide = HashMap<String, MutableSet<String>>()

        fun blocks(url: String, host: String, pageHost: String, type: String?): Boolean {
            if (pageHost.isNotEmpty() && onAny(pageHost, exemptSites)) return false
            if (onAny(host, allowHosts)) return false
            val thirdParty = UrlInput.registrableDomain(host) != UrlInput.registrableDomain(pageHost)
            val tokens = tokens(url)
            val blocked = onAny(host, blockHosts) || (thirdParty && onAny(host, blockHosts3p)) ||
                tokens.any { t -> block[t]?.any { it.matches(url, pageHost, thirdParty, type) } == true } ||
                blockLoose.any { it.matches(url, pageHost, thirdParty, type) }
            if (!blocked) return false
            return tokens.none { t -> allow[t]?.any { it.matches(url, pageHost, thirdParty, type) } == true }
        }

        fun css(host: String): String {
            if (host.isEmpty() || onAny(host, exemptSites) || onAny(host, noCosmeticSites)) return ""
            val selectors = LinkedHashSet<String>(GENERIC_HIDE)
            val unhide = HashSet<String>()
            for (d in suffixes(host)) {
                siteHide[d]?.let { selectors.addAll(it) }
                siteUnhide[d]?.let { unhide.addAll(it) }
            }
            selectors.removeAll(unhide)
            // One rule per selector: a selector the browser doesn't understand only drops itself.
            return selectors.joinToString("") { "$it{display:none!important}" }
        }

        /** One line of a filter list. */
        fun add(raw: String) {
            val line = raw.trim()
            if (line.isEmpty() || line[0] == '!' || line[0] == '[') return
            if (line.contains("#@#")) {
                val sel = line.substringAfter("#@#")
                if (!simpleSelector(sel)) return
                line.substringBefore("#@#").split(',').filter { it.isNotBlank() && !it.startsWith("~") }
                    .forEach { siteUnhide.getOrPut(it.lowercase()) { HashSet() }.add(sel) }
                return
            }
            if (line.contains("#?#") || line.contains("#$#") || line.contains("#%#") || line.contains("#+js") || line.contains("##+js")) return
            val hide = line.indexOf("##")
            if (hide >= 0) {
                val domains = line.substring(0, hide)
                val sel = line.substring(hide + 2)
                // Generic hiding is the short built-in list; the lists' site-specific rules are used.
                if (domains.isEmpty() || !simpleSelector(sel)) return
                domains.split(',').filter { it.isNotBlank() && !it.startsWith("~") }
                    .forEach { siteHide.getOrPut(it.lowercase()) { ArrayList() }.add(sel) }
                return
            }
            network(line)
        }

        private fun network(line: String) {
            var l = line
            val exception = l.startsWith("@@")
            if (exception) l = l.substring(2)
            if (l.length > 2 && l.startsWith("/") && l.endsWith("/")) return // regex rules: not used
            var party = 0
            var include: Array<String>? = null
            var exclude: Array<String>? = null
            var types: MutableSet<String>? = null
            var document = false
            var elemhide = false
            val dollar = l.lastIndexOf('$')
            if (dollar >= 0 && !l.substring(dollar + 1).contains('/')) {
                for (opt in l.substring(dollar + 1).split(',')) {
                    val o = opt.trim().lowercase()
                    when {
                        o == "third-party" || o == "3p" -> party = 1
                        o == "~third-party" || o == "first-party" || o == "1p" -> party = -1
                        o.startsWith("domain=") -> {
                            val ds = o.substring(7).split('|').filter { it.isNotBlank() }
                            include = ds.filter { !it.startsWith("~") }.toTypedArray().takeIf { it.isNotEmpty() }
                            exclude = ds.filter { it.startsWith("~") }.map { it.substring(1) }.toTypedArray().takeIf { it.isNotEmpty() }
                        }
                        o == "document" || o == "doc" -> document = true
                        o == "elemhide" || o == "generichide" || o == "ehide" || o == "ghide" -> elemhide = true
                        o in TYPE_NAMES -> (types ?: HashSet<String>().also { types = it }).add(TYPE_NAMES.getValue(o))
                        o == "match-case" || o == "important" || o == "all" -> Unit
                        // Anything else (popup, csp, redirect, removeparam, ~types …): rule not used.
                        else -> return
                    }
                }
                l = l.substring(0, dollar)
            }
            if (l.isEmpty() || l == "|" || l == "||" || l == "*") return
            if (exception && (document || elemhide)) {
                val site = DOMAIN_ONLY.matchEntire(l)?.groupValues?.get(1) ?: return
                if (document) exemptSites.add(site) else noCosmeticSites.add(site)
                return
            }
            if (document || elemhide) return
            val pattern = l.lowercase()
            // The common case: a whole domain, no conditions → a set lookup.
            val domainOnly = DOMAIN_ONLY.matchEntire(pattern)?.groupValues?.get(1)
            if (domainOnly != null && include == null && exclude == null && types == null) {
                when {
                    exception && party == 0 -> allowHosts.add(domainOnly)
                    !exception && party == 0 -> blockHosts.add(domainOnly)
                    !exception && party == 1 -> blockHosts3p.add(domainOnly)
                    else -> addRule(exception, Rule(pattern, party, include, exclude, types))
                }
                return
            }
            addRule(exception, Rule(pattern, party, include, exclude, types))
        }

        private fun addRule(exception: Boolean, rule: Rule) {
            val token = bestToken(rule.pattern)
            when {
                token != null -> (if (exception) allow else block).getOrPut(token) { ArrayList(2) }.add(rule)
                !exception && blockLoose.size < 400 -> blockLoose.add(rule)
            }
        }

        companion object {
            private val DOMAIN_ONLY = Regex("""^\|\|([a-z0-9.-]+\.[a-z0-9-]+)\^?$""")
            private val TOKEN = Regex("[a-z0-9%]+")
            private val COMMON = setOf("http", "https", "www", "com", "net", "org", "html", "js")
            private val TYPE_NAMES = mapOf(
                "script" to "script", "image" to "image", "stylesheet" to "stylesheet", "css" to "stylesheet",
                "media" to "media", "font" to "font", "subdocument" to "subdocument", "frame" to "subdocument",
                "xmlhttprequest" to "xhr", "xhr" to "xhr", "object" to "object", "other" to "other",
                "websocket" to "websocket", "ping" to "ping",
            )

            /**
             * The longest whole word of a pattern, to look rules up by (like Adblock Plus). A word
             * at an unanchored start or end, or next to `*`, isn't whole: the address could have more
             * letters there ("ads" in "uploads").
             */
            fun bestToken(p: String): String? {
                var best: String? = null
                for (m in TOKEN.findAll(p)) {
                    val s = m.range.first
                    val e = m.range.last + 1
                    val left = s > 0 && p[s - 1] != '*'
                    val right = e < p.length && p[e] != '*'
                    if (!left || !right) continue
                    val t = m.value
                    if (t.length < 2 || t in COMMON) continue
                    if (best == null || t.length > best.length) best = t
                }
                return best
            }

            fun builtIn(): Engine = Engine().apply { blockHosts.addAll(BUILT_IN_HOSTS) }

        /** Major ad and tracking networks: blocked from the first start, before the lists arrive. */
        private val BUILT_IN_HOSTS = listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com", "googletagservices.com",
            "adnxs.com", "criteo.com", "criteo.net", "taboola.com", "outbrain.com", "pubmatic.com", "rubiconproject.com",
            "openx.net", "casalemedia.com", "amazon-adsystem.com", "adsrvr.org", "advertising.com", "moatads.com",
            "scorecardresearch.com", "quantserve.com", "zedo.com", "yieldmo.com", "sharethrough.com", "smartadserver.com",
            "adform.net", "teads.tv", "popads.net", "popcash.net", "propellerads.com", "exoclick.com", "juicyads.com",
            "mgid.com", "revcontent.com", "adcolony.com", "adskeeper.com", "adsterra.com", "hilltopads.net", "onclickads.net",
            "trafficjunky.net", "bidvertiser.com", "infolinks.com", "media.net", "33across.com", "adsafeprotected.com",
            "lijit.com", "sovrn.com", "contextweb.com", "spotxchange.com", "yieldlab.net", "bidswitch.net", "adroll.com",
            "inmobi.com", "vdo.ai", "izooto.com", "clickadu.com", "adcash.com",
        )
        }
    }

    private val URL_TOKEN = Regex("[a-z0-9%]+")

    /** Every word of an address, to find the rules that could match it. */
    private fun tokens(url: String): Set<String> = URL_TOKEN.findAll(url).mapTo(HashSet()) { it.value }

    private fun suffixes(host: String): List<String> {
        val out = ArrayList<String>()
        var h = host
        while (h.contains('.')) {
            out.add(h)
            h = h.substringAfter('.')
        }
        return out
    }

    private fun onAny(host: String, set: Set<String>): Boolean {
        if (set.isEmpty()) return false
        var h = host
        while (true) {
            if (h in set) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }

    private fun onDomain(host: String, domain: String): Boolean = host == domain || host.endsWith(".$domain")

    private fun simpleSelector(sel: String): Boolean =
        sel.isNotBlank() && sel.length < 400 && UNSUPPORTED.none { sel.contains(it) }

    private val UNSUPPORTED = listOf(
        ":-abp-", ":has-text", ":xpath", ":style", ":matches-", ":remove", ":upward", ":min-text", ":watch-", ":others",
        ":if(", ":if-not", ":nth-ancestor", ":contains", "{", "}",
    )

    /** Common ad boxes, hidden everywhere (the lists' own generic hiding is too big to inject). */
    private val GENERIC_HIDE = listOf(
        "ins.adsbygoogle", ".adsbygoogle", "[id^=\"div-gpt-ad\"]", "[id^=\"google_ads_iframe\"]", "[id^=\"google_ads_frame\"]",
        "iframe[src*=\"googlesyndication.com\"]", "iframe[src*=\"doubleclick.net\"]", "amp-ad", "amp-embed[type=\"taboola\"]",
        "[id^=\"taboola-\"]", ".trc_related_container", ".OUTBRAIN", "[data-widget-id^=\"outbrain\"]", ".ob-widget",
        "[data-ad-slot]", "[data-ad-client]", ".ad-slot", ".ad-banner", ".ad_banner", ".adBanner", ".adbanner", ".ads-banner",
        "#ad-banner", ".advertisement", "div[aria-label=\"Advertisement\"]", "div[aria-label=\"advertisement\"]",
        ".GoogleActiveViewElement", "[data-google-query-id]", ".mgid-widget", "[id^=\"mgid_\"]", "[id^=\"ScriptRoot\"]",
        ".sponsored-ad", ".ad-placeholder", "[class^=\"adunit\"]", "[id^=\"adunit\"]", ".dfp-ad", "[id^=\"dfp-ad\"]",
    )
}
