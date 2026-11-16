package com.agani.syncup.browser

import android.content.Context
import android.net.Uri
import com.agani.syncup.data.UaRule
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-link "append to the User-Agent" rules. The admin puts an append value on a SyncUp link; the
 * server sends the switched-on links as {url, append} (the user's when signed in, the phone's own
 * when signed out). When a browser tab *starts* at a matching website, that value rides in the
 * tab's User-Agent for the whole tab (across redirects to other sites) until the tab is closed —
 * see [BrowserTab.uaToken] and [WebPlatform.userAgent]. Matching is by website (host without
 * "www."), the same "same-site" rule as [PageTokens].
 *
 * The value is NOT a secret: the User-Agent is sent to every site and third-party resource the tab
 * loads. It's a "this came from our app" marker, never used for auth (that stays on the signed
 * window.SyncUp.token). Never applied in Incognito.
 *
 * Kept on the phone so tabs restored at launch can re-check their saved token before the first sync.
 */
object UaTokens {
    private const val PREFS = "ua_tokens"
    private const val KEY_RULES = "rules"

    /** Website host (no "www.") → the value to append. */
    @Volatile private var byHost: Map<String, String> = emptyMap()
    private var loaded = false

    /** Load the saved rules (once). */
    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val raw = prefs(context).getString(KEY_RULES, null)
        byHost = runCatching {
            val a = JSONArray(raw ?: "[]")
            (0 until a.length()).associate { a.getJSONObject(it).let { o -> o.optString("host") to o.optString("append") } }
        }.getOrDefault(emptyMap()).filterKeys { it.isNotBlank() }.filterValues(::validAppend)
    }

    /** The server's latest list (null = the response didn't say; keep what we have). */
    fun update(context: Context, list: List<UaRule>?) {
        if (list == null) return
        val clean = list.mapNotNull { rule ->
            val host = hostOf(rule.url) ?: return@mapNotNull null
            val append = rule.append?.trim()?.takeIf(::validAppend) ?: return@mapNotNull null
            host to append
        }.toMap()
        if (clean == byHost) return
        byHost = clean
        val json = JSONArray(clean.map { (h, a) -> JSONObject().put("host", h).put("append", a) })
        prefs(context).edit().putString(KEY_RULES, json.toString()).apply()
    }

    /** Signed out: drop the user's rules at once (the phone's own come with the next check-in). */
    fun clear(context: Context) = update(context, emptyList())

    /** The value to append for a tab that starts at [url] (https only, same-site), or null. */
    fun appendFor(url: String?): String? {
        val host = hostOf(url) ?: return null
        return byHost[host]
    }

    /** True if [append] still belongs to a known rule — used to drop a restored tab's stale token. */
    fun hasAppend(append: String?): Boolean = append != null && byHost.containsValue(append)

    private fun hostOf(url: String?): String? {
        val uri = runCatching { Uri.parse(url ?: return null) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() != "https") return null
        return uri.host?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotBlank() }
    }

    /** Printable, single line, within the header length we allow (the server enforces this too). */
    private fun validAppend(value: String?): Boolean =
        !value.isNullOrBlank() && value.length <= 100 && value.all { it.code in 0x20..0x7E }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
