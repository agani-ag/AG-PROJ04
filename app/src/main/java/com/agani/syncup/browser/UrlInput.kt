package com.agani.syncup.browser

import android.net.Uri

/** Address-bar helpers: turn what the user typed into a URL, and URLs into short display text. */
object UrlInput {

    /** Typed text → URL. A URL or bare domain loads directly; anything else becomes a search. */
    fun toUrl(input: String): String {
        val text = input.trim()
        if (text.isEmpty()) return ""
        val lower = text.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return text
        val looksLikeAddress = !text.contains(' ') &&
            (text.contains('.') || lower.startsWith("localhost")) &&
            !text.startsWith(".") && !text.endsWith(".")
        return if (looksLikeAddress) "https://$text" else BrowserSettings.searchEngine.searchUrl + Uri.encode(text)
    }

    /** "https://www.example.com/a/b/" → "example.com/a/b". Search URLs show the query. */
    fun display(url: String): String {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url
        val host = uri.host?.removePrefix("www.") ?: return url
        if (isSearchPage(url)) uri.getQueryParameter("q")?.let { return it }
        val path = uri.path.orEmpty().trimEnd('/')
        return host + path
    }

    /** A results page of one of the supported search engines. */
    fun isSearchPage(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = uri.host?.removePrefix("www.")?.lowercase() ?: return false
        return (host.startsWith("google.") && uri.path == "/search") ||
            (host == "bing.com" && uri.path == "/search") ||
            (host == "duckduckgo.com" && uri.getQueryParameter("q") != null)
    }

    /** Split a URL for the address bar: host (dark) and path (light). */
    fun hostAndPath(url: String): Pair<String, String> {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url to ""
        val host = uri.host?.removePrefix("www.") ?: return url to ""
        if (isSearchPage(url)) return (uri.getQueryParameter("q") ?: host) to ""
        return host to uri.path.orEmpty().trimEnd('/')
    }

    fun isSecure(url: String): Boolean = url.startsWith("https://", ignoreCase = true)

    /**
     * Approximate registrable domain: "mail.google.com" → "google.com",
     * "app.gstsync.co.in" → "gstsync.co.in". Good enough to decide "same site as the work link".
     */
    fun registrableDomain(host: String): String {
        val labels = host.lowercase().removeSuffix(".").split('.').filter { it.isNotEmpty() }
        if (labels.size <= 2) return labels.joinToString(".")
        val secondLevel = labels[labels.size - 2]
        val countryTld = labels.last().length == 2
        val takeThree = countryTld && secondLevel in SECOND_LEVEL
        return labels.takeLast(if (takeThree) 3 else 2).joinToString(".")
    }

    fun hostMatches(host: String, rootDomain: String): Boolean {
        val h = host.lowercase()
        val r = rootDomain.lowercase()
        return h == r || h.endsWith(".$r")
    }

    fun rootDomainOf(url: String): String? =
        runCatching { Uri.parse(url).host }.getOrNull()?.takeIf { it.isNotBlank() }?.let(::registrableDomain)

    private val SECOND_LEVEL = setOf("co", "com", "net", "org", "gov", "ac", "edu", "nic", "res", "ind", "gen", "firm")
}
