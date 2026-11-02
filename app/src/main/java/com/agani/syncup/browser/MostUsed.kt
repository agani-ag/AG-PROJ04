package com.agani.syncup.browser

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.TimeZone

/** A row of History: one page at one time. */
internal class PageVisit(val url: String, val title: String, val at: Long)

/** A site the user keeps coming back to. */
internal data class UsedSite(val key: String, val title: String, val url: String, val visits: Int, val lastAt: Long)

/**
 * Which sites this person really uses, worked out from History alone. Nothing here is sent anywhere, and
 * Incognito and SyncUp tabs can't appear because they're never in History. With no such sites yet (a new
 * user), there's simply nothing to recommend.
 */
internal object MostUsed {
    const val PLACES = 8
    private const val SITTING_MS = 30 * 60_000L
    private const val DAY_MS = 86_400_000L
    private const val MIN_VISITS = 3
    private const val MIN_DAYS = 2
    private const val MAX_NAME = 24

    /** The same site at another door: m.youtube.com and www.youtube.com are one YouTube. */
    private val SAME_SITE = Regex("""^(www\d*|m|mobile)\.""")
    private val GOOGLE = Regex("""^(www\.)?google\.[a-z.]+$""")
    private val LOCAL_ENDINGS = listOf(".local", ".lan", ".internal", ".home", ".localdomain")
    private val SIGN_IN_HOSTS = listOf("login.", "signin.", "auth.", "sso.", "oauth.", "accounts.")
    private val SIGN_IN_PATHS = setOf("login", "signin", "sign-in", "signup", "sign-up", "logout", "oauth", "oauth2", "auth", "sso")
    private val PASSING_THROUGH = setOf(
        "t.co", "bit.ly", "tinyurl.com", "goo.gl", "ow.ly", "rb.gy", "lnkd.in", "youtu.be", "wa.me", "l.facebook.com", "lm.facebook.com",
        "l.instagram.com", "out.reddit.com", "googleadservices.com", "www.googleadservices.com", "doubleclick.net", "appleid.apple.com",
    )
    private val TITLE_PARTS = Regex("""\s+[-–—·•:]\s+|\s*\|\s*""")

    /**
     * What the backend's catalogue says: its tiles with their own address within a site ("google.com/maps"
     * stays apart from Google), and what to call and open them.
     */
    internal class Known(catalogue: List<CatalogShortcut>) {
        val paths: Set<String>
        val names = HashMap<String, String>()
        val urls = HashMap<String, String>()

        init {
            val found = HashSet<String>()
            catalogue.forEach { item ->
                val u = item.url.toHttpUrlOrNull() ?: return@forEach
                val first = u.pathSegments.firstOrNull().orEmpty().lowercase()
                if (first.isNotEmpty() && '.' !in first && first != "search") found += "${bareHost(u)}/$first"
            }
            paths = found
            catalogue.forEach { item ->
                val key = keyOf(item.url, this) ?: return@forEach
                names.getOrPut(key) { item.title }
                urls.getOrPut(key) { item.url }
            }
        }
    }

    private fun bareHost(u: HttpUrl): String = u.host.lowercase().let { h ->
        val bare = h.replaceFirst(SAME_SITE, "")
        if ('.' in bare) bare else h
    }

    private fun keyOf(u: HttpUrl, paths: Set<String>): String {
        val host = bareHost(u)
        val first = u.pathSegments.firstOrNull().orEmpty().lowercase()
        return "$host/$first".takeIf { first.isNotEmpty() && it in paths } ?: host
    }

    /** What a shortcut or a page is counted under, or null when it isn't a web address. */
    fun keyOf(url: String, known: Known): String? = url.toHttpUrlOrNull()?.let { keyOf(it, known.paths) }

    private fun isLocal(host: String): Boolean =
        ':' in host || '.' !in host || host.split('.').all { p -> p.isNotEmpty() && p.all(Char::isDigit) } || LOCAL_ENDINGS.any(host::endsWith)

    private fun isSearch(host: String, first: String, u: HttpUrl): Boolean = when {
        GOOGLE.matches(host) -> first in setOf("search", "url", "imgres", "sorry")
        host == "bing.com" || host == "www.bing.com" -> first == "search" || first == "ck"
        host == "duckduckgo.com" -> u.queryParameter("q") != null || first == "l"
        host == "search.yahoo.com" || host == "search.brave.com" -> true
        host == "ecosia.org" || host == "www.ecosia.org" -> first == "search"
        host.startsWith("yandex.") || host.startsWith("www.yandex.") -> first == "search"
        else -> false
    }

    /** Pages nobody means to "use": searching doesn't make Google a used site, nor signing in make a login page one. */
    private fun counts(u: HttpUrl): Boolean {
        val host = u.host.lowercase()
        val first = u.pathSegments.firstOrNull().orEmpty().lowercase()
        return !isLocal(host) && host !in PASSING_THROUGH && !isSearch(host, first, u) &&
            SIGN_IN_HOSTS.none(host::startsWith) && first !in SIGN_IN_PATHS
    }

    private fun dayOf(at: Long, zone: TimeZone): Long = (at + zone.getOffset(at)) / DAY_MS

    /**
     * The sites used often enough to be worth recommending, most visits first and the most recent winning
     * a tie, so the order always matches the numbers on screen. A visit is a sitting, not a page: 20 pages
     * within 30 minutes count once. A site needs [MIN_VISITS] visits on [MIN_DAYS] different days, so one
     * opened from a link now and then stays out.
     */
    fun rank(visits: List<PageVisit>, catalogue: List<CatalogShortcut>, zone: TimeZone = TimeZone.getDefault()): List<UsedSite> {
        class Seen(val at: Long, val u: HttpUrl, val title: String)

        val known = Known(catalogue)
        val sites = HashMap<String, MutableList<Seen>>()
        visits.sortedBy { it.at }.forEach { v ->
            val u = v.url.toHttpUrlOrNull() ?: return@forEach
            if (counts(u)) sites.getOrPut(keyOf(u, known.paths)) { ArrayList() } += Seen(v.at, u, v.title)
        }
        return sites.mapNotNull { (key, seen) ->
            var sittings = 0
            var last = Long.MIN_VALUE / 2
            val days = HashSet<Long>()
            seen.forEach { s ->
                if (s.at - last > SITTING_MS) sittings++
                last = s.at
                days += dayOf(s.at, zone)
            }
            if (sittings < MIN_VISITS || days.size < MIN_DAYS) return@mapNotNull null
            val latest = seen.last().u
            UsedSite(
                key,
                known.names[key] ?: nameOf(latest.host.lowercase().replaceFirst(SAME_SITE, ""), seen.map { it.title }),
                known.urls[key] ?: "${latest.scheme}://${latest.host}/${key.substringAfter('/', "")}",
                sittings, last,
            )
        }.groupBy { it.title.lowercase() }.map { (_, same) ->
            // One name, one tile: google.com and google.co.in are one Google to the person using them.
            same.maxBy { it.visits }.copy(visits = same.sumOf { it.visits }, lastAt = same.maxOf { it.lastAt })
        }.sortedWith(compareByDescending<UsedSite> { it.visits }.thenByDescending { it.lastAt })
    }

    /**
     * A site's name from its page titles: a part that recurs and says the site's own name ("Cricbuzz"), else
     * the "Page - Brand" ending most pages share ("Gmail"), else its address. Never one page's headline.
     */
    internal fun nameOf(host: String, titles: List<String>): String {
        val site = UrlInput.registrableDomain(host)
        val label = site.substringBefore('.')
        val pages = titles.map { it.trim() }.filter { it.isNotEmpty() }
        val split = pages.map { t -> t.split(TITLE_PARTS).map { it.trim() }.filter { it.isNotEmpty() } }
        val seen = split.flatMap { it.distinct() }.groupingBy { it }.eachCount()
        val need = maxOf(2, (pages.size + 1) / 2)
        fun fits(part: String) = part.length in 2..MAX_NAME && '@' !in part && (seen[part] ?: 0) >= need
        fun squashed(part: String) = part.lowercase().filter(Char::isLetterOrDigit)

        seen.keys.filter { fits(it) && squashed(it).contains(label) }.minByOrNull { it.length }?.let { return it }
        split.filter { it.size > 1 }.groupingBy { it.last() }.eachCount().maxByOrNull { it.value }?.key?.takeIf(::fits)?.let { return it }
        if (host != site) return host
        // As the site itself writes it ("IRCTC", not "Irctc").
        return pages.firstNotNullOfOrNull { Regex(Regex.escape(label), RegexOption.IGNORE_CASE).find(it)?.value }
            ?: label.replaceFirstChar { it.uppercase() }
    }

    /**
     * The All page shows a site only once: one in Recommended leaves the cards below it. The name counts
     * too, since en.wikipedia.org in History and www.wikipedia.org in the catalogue are one Wikipedia.
     */
    fun withoutRecommended(categories: List<CatalogCategory>, recommended: List<UsedSite>): List<CatalogCategory> {
        if (recommended.isEmpty()) return categories
        val known = Known(categories.flatMap { it.items })
        val keys = recommended.mapTo(HashSet()) { it.key }
        val names = recommended.mapTo(HashSet()) { it.title.lowercase() }
        return categories.map { c -> c.copy(items = c.items.filterNot { keyOf(it.url, known) in keys || it.title.lowercase() in names }) }
            .filter { it.items.isNotEmpty() }
    }

    fun visitsText(n: Int): String = if (n == 1) "1 visit" else "$n visits"

    fun lastSeenText(at: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): String =
        when (val days = dayOf(now, zone) - dayOf(at, zone)) {
            0L -> "Today"
            1L -> "Yesterday"
            else -> "$days days ago"
        }
}
