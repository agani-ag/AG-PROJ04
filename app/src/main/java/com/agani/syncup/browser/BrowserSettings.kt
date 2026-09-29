package com.agani.syncup.browser

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

enum class SearchEngine(val label: String, val searchUrl: String) {
    GOOGLE("Google", "https://www.google.com/search?q="),
    BING("Bing", "https://www.bing.com/search?q="),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q="),
}

/** A new-tab shortcut the user added themselves (the built-in ones are fixed). */
data class UserShortcut(val name: String, val url: String)

/**
 * Browser preferences (device-local, observable by Compose). Call [init] once from the Activity.
 */
object BrowserSettings {
    private var prefs: SharedPreferences? = null

    var searchEngine by mutableStateOf(SearchEngine.GOOGLE)
        private set
    var blockPopups by mutableStateOf(true)
        private set
    var addressBarBottom by mutableStateOf(false)
        private set
    val shortcuts = mutableStateListOf<UserShortcut>()
    /** Built-in shortcuts the user removed from the new-tab page (by URL). */
    val hiddenBuiltins = mutableStateListOf<String>()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("browser_settings", Context.MODE_PRIVATE)
        prefs = p
        searchEngine = runCatching { SearchEngine.valueOf(p.getString("engine", SearchEngine.GOOGLE.name)!!) }
            .getOrDefault(SearchEngine.GOOGLE)
        blockPopups = p.getBoolean("block_popups", true)
        addressBarBottom = p.getBoolean("bar_bottom", false)
        val arr = runCatching { JSONArray(p.getString("shortcuts", "[]")) }.getOrNull() ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            shortcuts.add(UserShortcut(o.optString("name"), o.optString("url")))
        }
        p.getStringSet("hidden_builtins", emptySet())?.let { hiddenBuiltins.addAll(it) }
    }

    fun updateSearchEngine(e: SearchEngine) {
        searchEngine = e
        prefs?.edit()?.putString("engine", e.name)?.apply()
    }

    fun updateBlockPopups(on: Boolean) {
        blockPopups = on
        prefs?.edit()?.putBoolean("block_popups", on)?.apply()
    }

    fun updateAddressBarBottom(on: Boolean) {
        addressBarBottom = on
        prefs?.edit()?.putBoolean("bar_bottom", on)?.apply()
    }

    fun addShortcut(name: String, url: String) {
        shortcuts.add(UserShortcut(name, url))
        saveShortcuts()
    }

    fun removeShortcut(s: UserShortcut) {
        shortcuts.remove(s)
        saveShortcuts()
    }

    /** Undo for [removeShortcut]. */
    fun restoreShortcut(s: UserShortcut, index: Int) {
        shortcuts.add(index.coerceIn(0, shortcuts.size), s)
        saveShortcuts()
    }

    fun hideBuiltin(url: String) {
        if (url !in hiddenBuiltins) hiddenBuiltins.add(url)
        prefs?.edit()?.putStringSet("hidden_builtins", hiddenBuiltins.toSet())?.apply()
    }

    fun unhideBuiltin(url: String) {
        hiddenBuiltins.remove(url)
        prefs?.edit()?.putStringSet("hidden_builtins", hiddenBuiltins.toSet())?.apply()
    }

    private fun saveShortcuts() {
        val arr = JSONArray()
        shortcuts.forEach { arr.put(JSONObject().put("name", it.name).put("url", it.url)) }
        prefs?.edit()?.putString("shortcuts", arr.toString())?.apply()
    }
}
