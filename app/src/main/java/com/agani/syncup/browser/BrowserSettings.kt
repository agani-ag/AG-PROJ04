package com.agani.syncup.browser

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class SearchEngine(val label: String, val searchUrl: String) {
    GOOGLE("Google", "https://www.google.com/search?q="),
    BING("Bing", "https://www.bing.com/search?q="),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q="),
}

/**
 * Browser preferences (observable by Compose). Call [init] once from the Activity.
 *
 * Search engine and pop-up blocking sync to the user's account (see BrowserSync); the address-bar
 * position, website darkening, text size, the sites allowed to open pop-ups, the ad blocker and the
 * media detector stay per phone. Every synced change is reported through [onChange] as (kind, key):
 * ("setting", "search_engine" | "block_popups").
 */
object BrowserSettings {
    private var prefs: SharedPreferences? = null

    @Volatile var onChange: ((kind: String, key: String) -> Unit)? = null

    var searchEngine by mutableStateOf(SearchEngine.GOOGLE)
        private set
    var blockPopups by mutableStateOf(true)
        private set
    var addressBarBottom by mutableStateOf(false)
        private set
    /** Darken websites that have no dark theme of their own while the app is dark (off, like Chrome). */
    var darkenWebsites by mutableStateOf(false)
        private set
    /** Text size for web pages, in percent. */
    var textZoom by mutableIntStateOf(100)
        private set
    /** Block ads and trackers everywhere in the app (SyncUp's ad blocker). */
    var blockAds by mutableStateOf(true)
        private set
    /** Show the ⬇ button when a page has videos, music or files to download. */
    var mediaDetector by mutableStateOf(true)
        private set
    /** Sites (registrable domains) the user let open pop-ups while blocking is on. */
    val popupSites = mutableStateListOf<String>()
    /** The same for Incognito: forgotten when the app closes. */
    private val incognitoPopupSites = HashSet<String>()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("browser_settings", Context.MODE_PRIVATE)
        prefs = p
        searchEngine = runCatching { SearchEngine.valueOf(p.getString("engine", SearchEngine.GOOGLE.name)!!) }
            .getOrDefault(SearchEngine.GOOGLE)
        blockPopups = p.getBoolean("block_popups", true)
        addressBarBottom = p.getBoolean("bar_bottom", false)
        darkenWebsites = p.getBoolean("darken_websites", false)
        textZoom = p.getInt("text_zoom", 100)
        blockAds = p.getBoolean("block_ads", true)
        mediaDetector = p.getBoolean("media_detector", true)
        p.getStringSet("popup_sites", emptySet())?.let { popupSites.addAll(it.sorted()) }
    }

    fun updateSearchEngine(e: SearchEngine) {
        searchEngine = e
        prefs?.edit()?.putString("engine", e.name)?.apply()
        onChange?.invoke("setting", "search_engine")
    }

    fun updateBlockPopups(on: Boolean) {
        blockPopups = on
        prefs?.edit()?.putBoolean("block_popups", on)?.apply()
        onChange?.invoke("setting", "block_popups")
    }

    fun updateAddressBarBottom(on: Boolean) {
        addressBarBottom = on
        prefs?.edit()?.putBoolean("bar_bottom", on)?.apply()
    }

    fun updateDarkenWebsites(on: Boolean) {
        darkenWebsites = on
        prefs?.edit()?.putBoolean("darken_websites", on)?.apply()
    }

    fun updateTextZoom(percent: Int) {
        textZoom = percent
        prefs?.edit()?.putInt("text_zoom", percent)?.apply()
    }

    fun updateBlockAds(on: Boolean) {
        blockAds = on
        prefs?.edit()?.putBoolean("block_ads", on)?.apply()
    }

    fun updateMediaDetector(on: Boolean) {
        mediaDetector = on
        prefs?.edit()?.putBoolean("media_detector", on)?.apply()
    }

    /** May a page on [site] open pop-ups without a tap? */
    fun popupsAllowed(site: String, incognito: Boolean): Boolean =
        !blockPopups || site in popupSites || (incognito && site in incognitoPopupSites)

    /** "Always allow pop-ups" for [site]. Incognito's choice lasts until the app closes. */
    fun allowPopups(site: String, incognito: Boolean) {
        if (incognito) {
            incognitoPopupSites.add(site)
            return
        }
        if (site !in popupSites) popupSites.add(site)
        prefs?.edit()?.putStringSet("popup_sites", popupSites.toSet())?.apply()
    }

    fun removePopupSite(site: String) {
        popupSites.remove(site)
        prefs?.edit()?.putStringSet("popup_sites", popupSites.toSet())?.apply()
    }

    fun clearPopupSites() {
        popupSites.clear()
        incognitoPopupSites.clear()
        prefs?.edit()?.remove("popup_sites")?.apply()
    }

    fun applySyncedSearchEngine(name: String) {
        val e = runCatching { SearchEngine.valueOf(name) }.getOrNull() ?: return
        searchEngine = e
        prefs?.edit()?.putString("engine", e.name)?.apply()
    }

    fun applySyncedBlockPopups(on: Boolean) {
        blockPopups = on
        prefs?.edit()?.putBoolean("block_popups", on)?.apply()
    }

}
