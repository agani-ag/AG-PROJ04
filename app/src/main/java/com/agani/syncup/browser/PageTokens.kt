package com.agani.syncup.browser

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.agani.syncup.data.TokenSite
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The SyncUp page token — window.SyncUp.token — only on websites the admin switched on ("Give
 * SyncUp token" on a SyncUp link or a home shortcut). The server sends the list, with a different
 * token for every website: the user's when signed in (GET /sync), this phone's own when signed out
 * (device/hello). WebPlatform puts each token on its website's own pages only — not on other sites,
 * ads or frames from elsewhere inside them, and never in Incognito.
 *
 * Kept on the phone so tabs restored at launch have their tokens before the first sync.
 */
object PageTokens {
    private const val PREFS = "page_tokens"
    private const val KEY_SITES = "sites"

    @Volatile var sites: List<TokenSite> = emptyList()
        private set

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var loaded = false

    /** Load the saved list (once). */
    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SITES, null)
        sites = runCatching {
            val a = JSONArray(raw ?: "[]")
            (0 until a.length()).map { a.getJSONObject(it) }.map { TokenSite(it.optString("host"), it.optString("token")) }
        }.getOrDefault(emptyList()).filter(::valid)
    }

    /** The server's latest list (null = the response didn't say; keep what we have). */
    fun update(context: Context, list: List<TokenSite>?) {
        if (list == null) return
        val clean = list.filter(::valid).distinctBy { it.host }.sortedBy { it.host }
        if (clean == sites) return
        sites = clean
        val json = JSONArray(clean.map { JSONObject().put("host", it.host).put("token", it.token) })
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SITES, json.toString()).apply()
        main.post { listeners.forEach { it() } }
    }

    /** Signed out: drop the user's tokens at once (the phone's own come with the next check-in). */
    fun clear(context: Context) = update(context, emptyList())

    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    /** The pages a website's token goes to: its own https address, with and without "www.". */
    fun origins(site: TokenSite): Set<String> = setOf("https://${site.host}", "https://www.${site.host}")

    /** The switched-on website a page belongs to (https only), or null. */
    fun forUrl(url: String?): TokenSite? {
        val uri = runCatching { Uri.parse(url ?: return null) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() != "https") return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        return sites.firstOrNull { it.host == host }
    }

    /** Sets window.SyncUp.token on a page. */
    fun script(site: TokenSite): String =
        "window.SyncUp = Object.assign(window.SyncUp || {}, { token: ${JSONObject.quote(site.token)} });"

    private fun valid(site: TokenSite) = !site.host.isNullOrBlank() && !site.token.isNullOrBlank() &&
        site.host.all { it.isLetterOrDigit() || it == '.' || it == '-' }
}
