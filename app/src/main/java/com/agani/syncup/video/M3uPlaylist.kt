package com.agani.syncup.video

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import com.agani.syncup.data.SyncChange
import com.agani.syncup.sync.ItemSync
import com.agani.syncup.sync.SyncType
import com.google.gson.JsonParser
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** "1 channel", "12 channels". */
internal fun channelCountText(count: Int) = if (count == 1) "1 channel" else "$count channels"

/** One channel inside a pasted M3U playlist. [language] is "" when unknown. */
data class TvChannel(val id: String, val title: String, val logo: String, val url: String, val category: String, val language: String = "") {
    /** What "Continue watching" says it came from. */
    val playerSource get() = "Live TV · $category"
}

/** One group of channels from a pasted M3U playlist, by its `group-title` (or "Channels" if none). */
data class TvCategoryGroup(val id: String, val name: String, val channels: List<TvChannel>)

/** One M3U/M3U8 playlist link the user added — see [SavedPlaylists]. [channelCount] is from its last fetch. */
data class SavedPlaylistEntry(val url: String, val title: String, val channelCount: Int, val addedAt: Long)

/**
 * Every playlist link the user has added, managed entirely from Tools → Channels (never the Video
 * player's paste box — that's for direct video links only, see [VideoLibraryScreen]). Kept on the phone
 * (works signed out); signed in, [ChannelListSync] keeps them with the account, so they come back after
 * signing out and in, or on a new phone. The latest change wins, deletions travel as tombstones, what was
 * saved signed out joins the account at the next sign-in, and a different account's lists leave the phone.
 * Keyed by URL: adding one already present just refreshes its title/count instead of creating a duplicate.
 */
object SavedPlaylists {
    private const val PREFS = "saved_playlists"
    private const val KEY = "items"
    private const val TOMBSTONE_DAYS = 60L
    internal const val KIND = "chanlist"

    /** As stored. [deleted]: its deletion still has to reach the account. [local]: not yet with any account. */
    private data class Stored(
        val key: String, val url: String, val title: String, val count: Int, val at: Long,
        val updatedMs: Long, val dirty: Boolean, val deleted: Boolean, val local: Boolean,
    )

    private lateinit var app: Context
    private val lock = Any()

    /** Bumped on every change, so an open Channels page follows what sync brings in. */
    var version by mutableLongStateOf(0L)
        private set

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        ChannelListSync.attach(app)
    }

    private fun keyOf(url: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun load(context: Context): List<Stored> = runCatching {
        val a = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val url = o.getString("url")
            val at = o.optLong("at")
            // Lists saved before sync came in have only url, title, count and at: they're new to the account.
            Stored(
                o.optString("key").ifBlank { keyOf(url) }, url, o.optString("title"), o.optInt("count"), at,
                o.optLong("ms", at), o.optBoolean("dirty", true), o.optBoolean("deleted"), o.optBoolean("local", true),
            )
        }
    }.getOrDefault(emptyList())

    // commit(), not apply(): a delete the user just confirmed must be on disk before this returns —
    // apply()'s write is queued and can be lost if the process is killed right after (e.g. force-stop
    // during testing, or the OS reclaiming memory), which would make a "deleted" entry reappear.
    private fun save(context: Context, list: List<Stored>) {
        val keepFrom = System.currentTimeMillis() - TOMBSTONE_DAYS * 86_400_000
        val a = JSONArray()
        list.filterNot { it.deleted && !it.dirty && it.updatedMs < keepFrom }.forEach { e ->
            a.put(
                JSONObject().put("key", e.key).put("url", e.url).put("title", e.title).put("count", e.count).put("at", e.at)
                    .put("ms", e.updatedMs).put("dirty", e.dirty).put("deleted", e.deleted).put("local", e.local),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).commit()
        version++
    }

    private fun change(context: Context, sync: Boolean = true, block: (List<Stored>) -> List<Stored>) {
        synchronized(lock) { save(context, block(load(context))) }
        if (sync) ChannelListSync.requestSync()
    }

    fun all(context: Context): List<SavedPlaylistEntry> = synchronized(lock) {
        load(context).filterNot { it.deleted }.sortedBy { it.at }.map { SavedPlaylistEntry(it.url, it.title, it.count, it.at) }
    }

    /** Adds a new entry (at the end), or refreshes an existing one with the same URL in place. */
    fun upsert(context: Context, url: String, title: String, channelCount: Int) {
        val key = keyOf(url)
        val now = System.currentTimeMillis()
        change(context) { list ->
            val old = list.firstOrNull { it.key == key }
            when {
                old == null -> list + Stored(key, url, title, channelCount, now, now, dirty = true, deleted = false, local = true)
                old.deleted -> list.map { if (it.key == key) it.copy(url = url, title = title, count = channelCount, at = now, updatedMs = now, dirty = true, deleted = false) else it }
                // Its name is what travels; the count is only what this phone last saw in it.
                old.title != title -> list.map { if (it.key == key) it.copy(title = title, count = channelCount, updatedMs = now, dirty = true) else it }
                else -> list.map { if (it.key == key) it.copy(count = channelCount) else it }
            }
        }
    }

    /** "Update link": [oldUrl]'s entry becomes [newUrl] (same name, same position in the list). */
    fun replaceUrl(context: Context, oldUrl: String, newUrl: String, channelCount: Int) {
        val oldKey = keyOf(oldUrl)
        val newKey = keyOf(newUrl)
        val now = System.currentTimeMillis()
        change(context) { list ->
            val old = list.firstOrNull { it.key == oldKey } ?: return@change list
            val moved = old.copy(key = newKey, url = newUrl, count = channelCount, updatedMs = now, dirty = true, deleted = false, local = true)
            list.filterNot { it.key == newKey }.map { if (it.key == oldKey) it.copy(updatedMs = now, dirty = true, deleted = true) else it } + moved
        }
    }

    fun remove(context: Context, url: String) {
        val key = keyOf(url)
        val now = System.currentTimeMillis()
        change(context) { list -> list.map { if (it.key == key) it.copy(updatedMs = now, dirty = true, deleted = true) else it } }
    }

    // ------------------------------------------------------------------ sync (see ChannelListSync)
    internal fun pendingChanges(limit: Int): List<SyncChange> = synchronized(lock) {
        load(app).filter { it.dirty }.take(limit).map {
            val data = if (it.deleted) null else JsonParser.parseString(JSONObject().put("url", it.url).put("title", it.title).put("count", it.count).put("at", it.at).toString()).asJsonObject
            SyncChange(KIND, it.key, data, it.updatedMs, it.deleted)
        }
    }

    internal fun markUploaded(sent: List<SyncChange>) {
        if (sent.isEmpty()) return
        val done = sent.associate { it.key to it.updatedMs }
        change(app, sync = false) { list -> list.map { if (done[it.key] == it.updatedMs) it.copy(dirty = false, local = false) else it } }
    }

    internal fun applyRemote(changes: List<SyncChange>) {
        if (changes.isEmpty()) return
        change(app, sync = false) { list ->
            val byKey = list.associateByTo(LinkedHashMap()) { it.key }
            for (c in changes) {
                val old = byKey[c.key]
                if (old != null && old.updatedMs >= c.updatedMs) continue
                val data = c.data?.let { runCatching { JSONObject(it.toString()) }.getOrNull() } ?: JSONObject()
                val url = data.optString("url").ifBlank { old?.url.orEmpty() }
                if (url.isBlank()) continue
                byKey[c.key] = Stored(
                    c.key, url, data.optString("title").ifBlank { old?.title.orEmpty() }, data.optInt("count", old?.count ?: 0),
                    data.optLong("at", old?.at ?: c.updatedMs), c.updatedMs, dirty = false, deleted = c.deleted, local = false,
                )
            }
            byKey.values.toList()
        }
    }

    /** An account signed in that isn't the last one here: what was saved signed out joins it; a previous person's lists leave. */
    internal fun accountChanged(previous: String?) {
        change(app, sync = false) { list ->
            val kept = if (previous != null) list.filter { it.local } else list
            kept.map { if (it.local) it.copy(local = false, dirty = true) else it }
        }
    }

    /** Everything goes up again (sync switched back on). */
    internal fun markAllDirty() {
        change(app, sync = false) { list -> list.map { it.copy(dirty = true) } }
    }
}

/** Saved channel lists through the account's sync, under Settings → Sync → Channel lists. */
internal object ChannelListSync : ItemSync("channels", SyncType.CHANNELS, listOf(SavedPlaylists.KIND)) {
    override suspend fun pending(limit: Int) = SavedPlaylists.pendingChanges(limit)
    override suspend fun uploaded(sent: List<SyncChange>) = SavedPlaylists.markUploaded(sent)
    override suspend fun applyRemote(changes: List<SyncChange>) = SavedPlaylists.applyRemote(changes)
    override suspend fun markAllDirty() = SavedPlaylists.markAllDirty()
    override suspend fun accountChanged(previous: String?, current: String) = SavedPlaylists.accountChanged(previous)
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
        prefs.edit().putStringSet(KEY, set).commit() // see SavedPlaylists.writeAll — same apply()-loss risk
        return nowFavorite
    }
}

/**
 * What Next, Previous and the sideways swipes go through in the player: the channels on screen when one was
 * tapped, in that order. Kept in memory, because a whole list (11,000+ channels) is too big for an Intent.
 */
internal object ChannelQueue {
    private var channels: List<TvChannel> = emptyList()
    private var at = 0

    val canStep get() = channels.size > 1
    val position get() = "${at + 1} of ${channels.size}"

    fun start(list: List<TvChannel>, current: TvChannel) {
        channels = list
        at = list.indexOf(current).coerceAtLeast(0)
    }

    /** The channel [by] steps along, going round at either end; null with nowhere else to go. */
    fun step(by: Int): TvChannel? {
        if (!canStep) return null
        at = (at + by).mod(channels.size)
        return channels[at]
    }
}

sealed interface M3uResult {
    /** A multi-channel playlist (like iptv-org's index.m3u) — browse it, same as the old Live TV grid. */
    data class ChannelList(val categories: List<TvCategoryGroup>) : M3uResult
    /** Only one channel in the file — just play its actual stream URL, not the wrapper .m3u file. */
    data class SingleChannel(val url: String, val title: String) : M3uResult
    /** One stream at the link itself: a real HLS manifest. Channels keeps it as a list of one. */
    data class SingleStream(val url: String) : M3uResult
    /** Not a channel list at all (couldn't be read, or not M3U). */
    object NotAPlaylist : M3uResult
}

/**
 * Parses whatever link the user adds in Tools → Channels, entirely on the phone. This is what replaced
 * the backend-curated "Live TV" catalogue (see tv-station-plan.md): the app is never fed a channel list. It
 * plays what the person saves, whether they typed the link or an admin pushed it to them (see [isChannelLink]).
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

    /** A pushed link that belongs in Channels, by its address (with or without a query). */
    fun isChannelLink(url: String): Boolean =
        url.substringBefore('#').substringBefore('?').trim().lowercase().let { it.endsWith(".m3u") || it.endsWith(".m3u8") }

    /** What a saved link opens as: a list as it is; one stream or a one-channel file as a list of one. Null = not a list. */
    fun channelGroups(result: M3uResult, name: String): List<TvCategoryGroup>? = when (result) {
        is M3uResult.ChannelList -> result.categories
        is M3uResult.SingleChannel -> listOfOne(result.url, result.title)
        is M3uResult.SingleStream -> listOfOne(result.url, name)
        M3uResult.NotAPlaylist -> null
    }

    private fun listOfOne(url: String, title: String) = listOf(
        TvCategoryGroup("Channels", "Channels", listOf(TvChannel("Channels-0", title.ifBlank { "Channel" }, "", url, "Channels"))),
    )

    /** Call off the main thread — does blocking network fetches. */
    fun inspect(url: String): M3uResult {
        val text = fetch(url, connectMs = 10_000, readMs = 15_000) ?: return M3uResult.NotAPlaylist
        if (!text.contains("#EXTM3U")) return M3uResult.NotAPlaylist
        // A real HLS manifest (single stream, possibly with quality variants) — not a channel list.
        if (text.contains("#EXT-X-STREAM-INF") || text.contains("#EXT-X-TARGETDURATION")) return M3uResult.SingleStream(url)

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
