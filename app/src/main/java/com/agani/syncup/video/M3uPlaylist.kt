package com.agani.syncup.video

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One channel inside a pasted M3U playlist. [language] is "" when unknown. */
data class TvChannel(val id: String, val title: String, val logo: String, val url: String, val category: String, val language: String = "")

/** One group of channels from a pasted M3U playlist, by its `group-title` (or "Channels" if none). */
data class TvCategoryGroup(val id: String, val name: String, val channels: List<TvChannel>)

/** One M3U/M3U8 playlist link the user added — see [SavedPlaylists]. [channelCount] is from its last fetch. */
data class SavedPlaylistEntry(val url: String, val title: String, val channelCount: Int, val addedAt: Long)

/**
 * Every playlist link the user has added, managed entirely from Tools → Channels (never the Video
 * player's paste box — that's for direct video links only, see [VideoLibraryScreen]). Mobile-only,
 * nothing sent to or stored by SyncUp's backend. Keyed by URL: adding one already present just
 * refreshes its title/count instead of creating a duplicate row.
 */
object SavedPlaylists {
    private const val PREFS = "saved_playlists"
    private const val KEY = "items"

    fun all(context: Context): List<SavedPlaylistEntry> = runCatching {
        val a = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            SavedPlaylistEntry(o.getString("url"), o.optString("title"), o.optInt("count"), o.optLong("at"))
        }
    }.getOrDefault(emptyList())

    private fun writeAll(context: Context, list: List<SavedPlaylistEntry>) {
        val a = JSONArray()
        list.forEach { e -> a.put(JSONObject().put("url", e.url).put("title", e.title).put("count", e.channelCount).put("at", e.addedAt)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }

    /** Adds a new entry (appended at the end), or refreshes an existing one with the same URL in place. */
    fun upsert(context: Context, url: String, title: String, channelCount: Int) {
        val existing = all(context)
        val updated = if (existing.any { it.url == url }) {
            existing.map { if (it.url == url) it.copy(title = title, channelCount = channelCount) else it }
        } else {
            existing + SavedPlaylistEntry(url, title, channelCount, System.currentTimeMillis())
        }
        writeAll(context, updated)
    }

    /** "Update link": [oldUrl]'s entry becomes [newUrl] (same name, same position in the list). */
    fun replaceUrl(context: Context, oldUrl: String, newUrl: String, channelCount: Int) {
        writeAll(context, all(context).map { if (it.url == oldUrl) it.copy(url = newUrl, channelCount = channelCount) else it })
    }

    fun remove(context: Context, url: String) {
        writeAll(context, all(context).filterNot { it.url == url })
    }
}

/**
 * Starred channels, kept on the phone by stream URL — stable across re-pasting the same playlist
 * (iptv-org's own URLs don't change), and shared across any playlist that happens to reuse one.
 */
object ChannelFavorites {
    private const val PREFS = "channel_favorites"
    private const val KEY = "urls"

    fun all(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()).orEmpty()

    fun isFavorite(context: Context, url: String): Boolean = url in all(context)

    fun toggle(context: Context, url: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = (prefs.getStringSet(KEY, emptySet()) ?: emptySet()).toMutableSet()
        val nowFavorite = if (url in set) { set.remove(url); false } else { set.add(url); true }
        prefs.edit().putStringSet(KEY, set).apply()
        return nowFavorite
    }
}

sealed interface M3uResult {
    /** A multi-channel playlist (like iptv-org's index.m3u) — browse it, same as the old Live TV grid. */
    data class ChannelList(val categories: List<TvCategoryGroup>) : M3uResult
    /** Only one channel in the file — just play its actual stream URL, not the wrapper .m3u file. */
    data class SingleChannel(val url: String, val title: String) : M3uResult
    /** Not a channel list at all (a real HLS manifest, or not M3U) — play the pasted URL as given. */
    object NotAPlaylist : M3uResult
}

/**
 * Parses whatever link the user adds in Tools → Channels — entirely client-side, nothing sent to or
 * served by SyncUp's own backend. This is what replaced the backend-curated "Live TV" catalogue (see
 * tv-station-plan.md): SyncUp doesn't bundle or promote any channel list itself anymore, it just plays
 * what the user supplies, with the same browsing UI as before when that happens to be a channel list.
 *
 * Classification is by the file's own `group-title` (genre), same as before. When the playlist uses
 * iptv-org's `tvg-id` convention (`ChannelName.country@feed`, e.g. `10TV.in@SD`), each channel is also
 * tagged with its language — fetched from iptv-org's own `feeds.json`/`languages.json` (metadata only,
 * about channels the user already brought in by pasting the link; no stream list is sourced from
 * this). That fetch is sizeable (~8 MB) so it's skipped entirely for playlists that don't look like
 * iptv-org's — a generic M3U just won't get language tags, same as it wouldn't before.
 */
object M3uPlaylist {
    private val logoRegex = Regex("""tvg-logo="([^"]*)"""")
    private val groupRegex = Regex("""group-title="([^"]*)"""")
    private val tvgIdRegex = Regex("""tvg-id="([^"]*)"""")
    private val channelIdRegex = Regex("""^(.+?)(?:@.+)?$""")
    private val looksLikeIptvOrgId = Regex("""\.[a-zA-Z]{2}(?:@|$)""")

    /** Call off the main thread — does blocking network fetches. */
    fun inspect(url: String): M3uResult {
        val text = fetch(url, connectMs = 10_000, readMs = 15_000) ?: return M3uResult.NotAPlaylist
        if (!text.contains("#EXTM3U")) return M3uResult.NotAPlaylist
        // A real HLS manifest (single stream, possibly with quality variants) — not a channel list.
        if (text.contains("#EXT-X-STREAM-INF") || text.contains("#EXT-X-TARGETDURATION")) return M3uResult.NotAPlaylist

        data class Entry(val title: String, val logo: String, val group: String, val tvgId: String, val url: String)
        val entries = mutableListOf<Entry>()
        var title = ""
        var logo = ""
        var group = ""
        var tvgId = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    title = line.substringAfterLast(',').trim()
                    logo = logoRegex.find(line)?.groupValues?.get(1).orEmpty()
                    group = groupRegex.find(line)?.groupValues?.get(1)?.substringBefore(';')?.takeIf { it.isNotBlank() } ?: "Channels"
                    tvgId = tvgIdRegex.find(line)?.groupValues?.get(1).orEmpty()
                }
                line.isNotBlank() && !line.startsWith("#") -> {
                    if (title.isNotBlank()) entries += Entry(title, logo, group, tvgId, line)
                    title = ""; logo = ""; group = ""; tvgId = ""
                }
            }
        }

        if (entries.isEmpty()) return M3uResult.NotAPlaylist
        if (entries.size == 1) return M3uResult.SingleChannel(entries[0].url, entries[0].title)

        // Language tags only when this really looks like iptv-org's id convention — otherwise skip
        // the ~8 MB fetch entirely, it wouldn't match anything anyway.
        val looksLikeIptvOrg = entries.count { looksLikeIptvOrgId.containsMatchIn(it.tvgId) } >= entries.size * 2 / 5
        val languageByChannel = if (looksLikeIptvOrg) fetchLanguageMap() else emptyMap()

        val categories = entries.groupBy { it.group }
            .map { (group, items) ->
                TvCategoryGroup(
                    group, group,
                    items.mapIndexed { i, e ->
                        val channelId = channelIdRegex.find(e.tvgId)?.groupValues?.get(1).orEmpty()
                        val language = languageByChannel[channelId].orEmpty()
                        TvChannel("$group-$i", e.title, e.logo, e.url, group, language)
                    },
                )
            }
            .sortedByDescending { it.channels.size }
        return M3uResult.ChannelList(categories)
    }

    /** iptv-org channel id → its main feed's language name ("Tamil", "Hindi", …), or empty if unknown. */
    private fun fetchLanguageMap(): Map<String, String> {
        val languageNames = runCatching {
            val arr = JSONArray(fetch("https://iptv-org.github.io/api/languages.json", connectMs = 15_000, readMs = 30_000) ?: return emptyMap())
            (0 until arr.length()).associate { i ->
                val o = arr.getJSONObject(i)
                o.getString("code") to o.getString("name")
            }
        }.getOrNull() ?: return emptyMap()

        return runCatching {
            val arr = JSONArray(fetch("https://iptv-org.github.io/api/feeds.json", connectMs = 15_000, readMs = 60_000) ?: return emptyMap())
            val result = HashMap<String, String>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val channel = o.optString("channel").takeIf { it.isNotBlank() } ?: continue
                if (result.containsKey(channel) && !o.optBoolean("is_main")) continue // a later main feed can still overwrite a non-main one
                val langs = o.optJSONArray("languages") ?: continue
                if (langs.length() == 0) continue
                val name = languageNames[langs.getString(0)] ?: continue
                result[channel] = name
            }
            result
        }.getOrDefault(emptyMap())
    }

    private fun fetch(url: String, connectMs: Int, readMs: Int): String? = runCatching {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = connectMs
            readTimeout = readMs
            requestMethod = "GET"
            inputStream.bufferedReader().use { it.readText() }
        }
    }.getOrNull()
}
