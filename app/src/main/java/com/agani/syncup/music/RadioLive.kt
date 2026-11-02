package com.agani.syncup.music

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.agani.syncup.BuildConfig
import com.agani.syncup.data.StationLiveMessage
import com.google.gson.Gson
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** What a station's live socket last said. */
internal data class StationLive(val title: String, val listeners: Int, val playing: Boolean)

/**
 * The address to open for a station's live updates, or null to do without: only wss:// on the same
 * host as its audio (the list is open, so a listing can't point phones anywhere else).
 * A debug build made for a test server ([testBuild]) also takes ws:// on this phone's own 127.0.0.1.
 */
internal fun liveSocketUrl(
    streamUrl: String,
    wsUrl: String?,
    testBuild: Boolean = BuildConfig.DEBUG_API_BASE_URL.isNotBlank(),
): String? {
    val ws = wsUrl?.trim().orEmpty()
    val wsHost = runCatching { URI(ws).host }.getOrNull() ?: return null
    val secure = ws.startsWith("wss://", ignoreCase = true)
    if (!secure && !(testBuild && ws.startsWith("ws://", ignoreCase = true) && wsHost == "127.0.0.1")) return null
    val streamHost = runCatching { URI(streamUrl.trim()).host }.getOrNull()
    return ws.takeIf { wsHost.equals(streamHost, ignoreCase = true) }
}

/** One socket message, or null when it isn't a now-playing update. */
internal fun parseStationLive(text: String): StationLive? {
    val m = runCatching { Gson().fromJson(text, StationLiveMessage::class.java) }.getOrNull() ?: return null
    if (m.type != "now_playing") return null
    return StationLive(m.title.orEmpty().take(MAX_TITLE), (m.listeners ?: 0).coerceAtLeast(0), m.playing ?: true)
}

/**
 * The live socket of the station being played, and only that one: MusicPlaybackService opens it while
 * the station plays (screen off too) and closes it when playback stops, so just looking at the list
 * connects to nobody. Its song reaches the lock screen and notification; audio stays on stream_url.
 *
 * AudioSync's socket only writes: it never answers a ping or a close. So the client sends no pings
 * (they'd look like a dead connection), drops sockets with cancel(), and reopens every few minutes in
 * case one died quietly. All of this runs on the main thread.
 */
internal object RadioLive {
    /** The station whose socket is open and speaking now (the player's WS mark), else null. */
    var stationId by mutableStateOf<String?>(null)
        private set

    /** What that station's socket last said. */
    var now by mutableStateOf<StationLive?>(null)
        private set

    /** The station stopped or its socket dropped: worth re-reading the station list now. */
    val changed = Channel<Unit>(Channel.CONFLATED)

    private class Target(val id: String, val url: String, val onUpdate: (StationLive) -> Unit)

    private var target: Target? = null
    private var socket: WebSocket? = null
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val reopen = Runnable { open() }
    private val client by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).build() }

    /** Follow this station's socket; null closes it. The same station and address carries on as it is. */
    fun follow(id: String?, url: String?, onUpdate: (StationLive) -> Unit = {}) {
        if (id == null || url == null) return stop()
        if (target?.id == id && target?.url == url) return
        stop()
        target = Target(id, url, onUpdate)
        open()
    }

    fun stop() {
        main.removeCallbacks(reopen)
        target = null
        socket?.cancel()
        socket = null
        stationId = null
        now = null
    }

    private fun open() {
        main.removeCallbacks(reopen)
        val t = target ?: return
        val request = runCatching { Request.Builder().url(t.url).build() }.getOrNull() ?: return
        socket?.cancel() // what it last said stays until the new one speaks
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val live = parseStationLive(text) ?: return
                main.post {
                    if (socket !== webSocket) return@post
                    stationId = t.id
                    now = live
                    t.onUpdate(live)
                    if (!live.playing) changed.trySend(Unit)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.cancel()
                lost(webSocket)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket)
        })
        main.postDelayed(reopen, REOPEN_MS)
    }

    private fun lost(webSocket: WebSocket) {
        main.post {
            if (socket !== webSocket) return@post
            socket = null
            stationId = null
            now = null
            changed.trySend(Unit)
            main.removeCallbacks(reopen)
            main.postDelayed(reopen, RETRY_MS)
        }
    }
}

private const val RETRY_MS = 30_000L
private const val REOPEN_MS = 180_000L
private const val MAX_TITLE = 300
