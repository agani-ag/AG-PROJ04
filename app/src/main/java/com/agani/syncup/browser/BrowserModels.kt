package com.agani.syncup.browser

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The browser is split into three separate sections, each with its own tabs and its own
 * cookie/login store (a WebView profile):
 *  - NORMAL: personal browsing, available to everyone (no login needed).
 *  - WORK: only links from the SyncUp backend (signed-in users) and whatever they lead to. The
 *    set link's own site is shown by name, never by URL.
 *  - INCOGNITO: private browsing; nothing is saved and its data is wiped when the last tab closes.
 */
enum class Section { NORMAL, WORK, INCOGNITO }

/** One browser tab. Compose-observable so the chrome (address bar, progress, back/forward) updates live. */
class BrowserTab(
    val id: Long,
    val section: Section,
    initialUrl: String = "",
    /** Work tabs: the link's name, shown instead of its URL. */
    val workName: String? = null,
    /** Work tabs: the backend link id they were opened from. */
    val workLinkId: String? = null,
    /** Work tabs: registrable domain(s) of the set URL — pages on them are masked. */
    workRoots: List<String> = emptyList(),
    /** Work tabs: per-link partner token exposed as window.SyncUp.token (empty = none). */
    val notifyToken: String = "",
) {
    /**
     * Work tabs: every site the set link resolved to on its first load (its own domain plus any
     * redirect landing site). Pages on these are shown by the link's name, never by URL.
     */
    val maskedRoots = mutableStateListOf<String>().apply { addAll(workRoots) }

    /** Work tabs: false until the first page has finished loading (redirects still being collected). */
    var rootsSettled = false

    /** Current page URL; "" means the tab shows its section's home page (no WebView yet). */
    var url by mutableStateOf(initialUrl)
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    /** Non-null when the main page failed to load — the tab shows an error card with Retry. */
    var error by mutableStateOf<String?>(null)

    /** The page's <meta name="theme-color"> as ARGB, or null when it sets none (bars use the app theme). */
    var themeColor by mutableStateOf<Int?>(null)

    val isHome: Boolean get() = url.isBlank()
    val isWork: Boolean get() = section == Section.WORK

    /** True while a work tab is on the set link's own site — its address must not be shown. */
    val isMasked: Boolean
        get() {
            if (!isWork) return false
            if (maskedRoots.isEmpty()) return true
            val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return true
            return maskedRoots.any { UrlInput.hostMatches(host, it) }
        }

    /** What the tab is called in the tab switcher / history. Masked work pages use the link name. */
    val label: String
        get() = when {
            isHome -> when (section) {
                Section.WORK -> "Work"
                Section.INCOGNITO -> "Incognito tab"
                Section.NORMAL -> "New tab"
            }
            isMasked -> workName ?: "Work link"
            title.isNotBlank() -> title
            else -> UrlInput.display(url)
        }
}
