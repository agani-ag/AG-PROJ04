package com.agani.syncup.browser

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.agani.syncup.data.ApiClient
import com.agani.syncup.data.ShortcutCatalogResponse
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One shortcut from the backend catalogue. */
data class CatalogShortcut(val id: Long, val title: String, val url: String, val category: String)

/** One category of the catalogue ("News", "Shopping"), in the admin's order. */
data class CatalogCategory(val id: Long, val name: String, val items: List<CatalogShortcut>)

/**
 * The home page's shortcuts, set by the admin on the backend (Admin → Home shortcuts) for everyone,
 * signed in or not. A copy is kept on the phone, so the home page works offline and opens instantly;
 * it's refreshed when the app starts and when the home page shows (at most every few hours), and the
 * backend answers "unchanged" (304) when nothing changed. Before the first fetch: the built-in few.
 */
object ShortcutCatalog {
    private const val REFRESH_MS = 3L * 3600 * 1000

    /**
     * What the home page shows before it has ever reached the backend — the same as the backend's
     * ready-made "Popular" (seed_home_shortcuts). Free, and all work without signing in.
     */
    private val DEFAULTS = listOf(
        CatalogCategory(
            -1, "Popular",
            listOf(
                "Google" to "https://www.google.com",
                "YouTube" to "https://m.youtube.com",
                "Wikipedia" to "https://www.wikipedia.org",
                "Google Maps" to "https://www.google.com/maps",
                "Google News" to "https://news.google.com",
                "Translate" to "https://translate.google.com",
                "Cricbuzz" to "https://www.cricbuzz.com",
                "ChatGPT" to "https://chatgpt.com",
            ).mapIndexed { i, (t, u) -> CatalogShortcut(-(i + 1L), t, u, "Popular") },
        ),
    )

    private var file: File? = null
    private var version: String = ""
    private var fetchedAt = 0L
    private var loading = false

    var categories by mutableStateOf(DEFAULTS)
        private set

    /** The chip chosen on the home page (null = All); kept while the app is open. */
    var chosen by mutableStateOf<Long?>(null)

    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "shortcut_catalog.json")
        file = f
        runCatching {
            if (f.exists()) {
                val r = Gson().fromJson(f.readText(), ShortcutCatalogResponse::class.java)
                version = r.version
                toCategories(r)?.let { categories = it }
            }
        }
    }

    /** Fetch the latest catalogue if the copy is older than a few hours (or [force]). Main thread. */
    suspend fun refresh(force: Boolean = false) {
        if (loading || (!force && System.currentTimeMillis() - fetchedAt < REFRESH_MS)) return
        loading = true
        try {
            val resp = withContext(Dispatchers.IO) { runCatching { ApiClient.service.shortcuts(version.ifBlank { null }) }.getOrNull() } ?: return
            // Only a real answer counts as fresh; a failure is tried again next time.
            if (resp.code() == 304) {
                fetchedAt = System.currentTimeMillis()
                return
            }
            val body = resp.body() ?: return
            fetchedAt = System.currentTimeMillis()
            version = body.version
            categories = toCategories(body) ?: DEFAULTS
            withContext(Dispatchers.IO) { runCatching { file?.writeText(Gson().toJson(body)) } }
            if (chosen != null && categories.none { it.id == chosen }) chosen = null
        } finally {
            loading = false
        }
    }

    /** Shortcuts whose title or site matches [query] (address-bar suggestions). */
    fun search(query: String, limit: Int = 3): List<CatalogShortcut> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        return categories.asSequence().flatMap { it.items.asSequence() }
            .filter { it.title.lowercase().contains(q) || UrlInput.display(it.url).lowercase().contains(q) }
            .distinctBy { it.url }
            .sortedBy { if (it.title.lowercase().startsWith(q)) 0 else 1 }
            .take(limit).toList()
    }

    /** null when the backend has nothing to show: the built-in few stay. */
    private fun toCategories(r: ShortcutCatalogResponse): List<CatalogCategory>? =
        r.categories.mapNotNull { c ->
            val items = c.shortcuts.filter { it.url.startsWith("http") }.map { CatalogShortcut(it.id, it.title, it.url, c.name) }
            if (items.isEmpty()) null else CatalogCategory(c.id, c.name, items)
        }.takeIf { it.isNotEmpty() }
}
