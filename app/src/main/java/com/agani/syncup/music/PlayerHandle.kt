package com.agani.syncup.music

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.agani.syncup.data.RadioChannel
import kotlin.random.Random

/** Media ids tell songs from radio stations. */
internal const val SONG_ID = "song:"
internal const val RADIO_ID = "radio:"

/** The app's one [PlayerHandle], provided by MainActivity. */
val LocalPlayer = staticCompositionLocalOf<PlayerHandle?> { null }

/**
 * The app's view of the shared player in [MusicPlaybackService]: what's loaded, whether it's
 * playing, shuffle / repeat, and the commands. Compose state, so screens redraw as it changes.
 */
@Stable
class PlayerHandle internal constructor(private val prefs: SharedPreferences) {
    internal var controller: MediaController? by mutableStateOf(null)

    /** The loaded song or station, or null when nothing is. */
    var item by mutableStateOf<MediaItem?>(null); private set
    private var meta by mutableStateOf(MediaMetadata.EMPTY)
    var isPlaying by mutableStateOf(false); private set
    var buffering by mutableStateOf(false); private set
    var hasNext by mutableStateOf(false); private set
    var hasPrevious by mutableStateOf(false); private set
    var durationMs by mutableLongStateOf(0L); private set
    var error by mutableStateOf<String?>(null); private set

    /** Saved choices. They apply to songs; Radio never shuffles or repeats. */
    var shuffle by mutableStateOf(prefs.getBoolean("shuffle", false)); private set
    var repeat by mutableIntStateOf(prefs.getInt("repeat", Player.REPEAT_MODE_OFF)); private set

    val isRadio: Boolean get() = item?.mediaId?.startsWith(RADIO_ID) == true
    val songId: Long? get() = item?.mediaId?.takeIf { it.startsWith(SONG_ID) }?.removePrefix(SONG_ID)?.toLongOrNull()
    val stationId: String? get() = item?.mediaId?.takeIf { it.startsWith(RADIO_ID) }?.removePrefix(RADIO_ID)
    val artUri: Uri? get() = item?.mediaMetadata?.artworkUri

    /** Song title, or the station's name. */
    val title: String
        get() = (if (isRadio) meta.station ?: meta.title else meta.title)?.toString().orEmpty()

    /** Artist, or what the station is playing now (its stream title when it sends one). */
    val subtitle: String
        get() = if (isRadio) {
            val streamTitle = meta.title?.toString()?.takeIf { it.isNotBlank() && it != meta.station?.toString() }
            streamTitle ?: meta.artist?.toString()?.takeIf { it.isNotBlank() } ?: "Live radio"
        } else {
            meta.artist?.toString().orEmpty()
        }

    /** Read on demand (it changes every frame while playing). */
    val position: Long get() = controller?.currentPosition ?: 0L

    internal fun sync(p: Player) {
        item = if (p.mediaItemCount > 0) p.currentMediaItem else null
        meta = p.mediaMetadata
        isPlaying = p.isPlaying
        buffering = p.playbackState == Player.STATE_BUFFERING
        hasNext = p.hasNextMediaItem()
        hasPrevious = p.hasPreviousMediaItem()
        durationMs = p.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
        if (p.isPlaying) error = null
    }

    internal fun failed(e: PlaybackException) {
        error = if (isRadio) "This station went off air." else "Couldn't play this song."
    }

    /** Play [songs] from [start]; [shuffled] turns shuffle on and starts anywhere. */
    fun playSongs(songs: List<Song>, start: Int, shuffled: Boolean = false) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        error = null
        if (shuffled) updateShuffle(true)
        c.setMediaItems(songs.map { it.toMediaItem() }, if (shuffled) Random.nextInt(songs.size) else start, 0L)
        c.shuffleModeEnabled = shuffle
        c.repeatMode = repeat
        c.prepare()
        c.play()
    }

    /** All live [stations] as one list, starting at [start], so previous / next switch stations. */
    fun playRadio(stations: List<RadioChannel>, start: Int) {
        val c = controller ?: return
        if (stations.isEmpty()) return
        error = null
        c.setMediaItems(stations.map { it.toMediaItem() }, start, C.TIME_UNSET)
        c.shuffleModeEnabled = false
        c.repeatMode = Player.REPEAT_MODE_OFF
        c.prepare()
        c.play()
    }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
            return
        }
        when (c.playbackState) {
            Player.STATE_IDLE -> c.prepare()                     // after an error: try again
            Player.STATE_ENDED -> c.seekToDefaultPosition(0)     // the end of the list: from the top
        }
        c.play()
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    /** Songs: back to the start, or the previous song near the start. Radio: the previous station. */
    fun previous() {
        val c = controller ?: return
        if (isRadio) c.seekToPreviousMediaItem() else c.seekToPrevious()
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
    }

    fun stop() {
        controller?.run {
            stop()
            clearMediaItems()
        }
    }

    fun updateShuffle(on: Boolean) {
        shuffle = on
        prefs.edit().putBoolean("shuffle", on).apply()
        if (!isRadio) controller?.shuffleModeEnabled = on
    }

    /** Off → all → one → off. */
    fun cycleRepeat() {
        repeat = when (repeat) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        prefs.edit().putInt("repeat", repeat).apply()
        if (!isRadio) controller?.repeatMode = repeat
    }
}

/** Connects to the player service for as long as the caller is on screen. */
@Composable
fun rememberPlayerHandle(): PlayerHandle {
    val context = LocalContext.current.applicationContext
    val handle = remember { PlayerHandle(context.getSharedPreferences("music", Context.MODE_PRIVATE)) }
    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, MusicPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = handle.sync(player)
            override fun onPlayerError(error: PlaybackException) = handle.failed(error)
        }
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            c.addListener(listener)
            handle.controller = c
            handle.sync(c)
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            handle.controller?.removeListener(listener)
            handle.controller = null
            MediaController.releaseFuture(future) // the player keeps running in its service
        }
    }
    return handle
}

internal fun Song.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(SONG_ID + id)
    .setUri(uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(artUri)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build(),
    )
    .build()

internal fun RadioChannel.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(RADIO_ID + id)
    .setUri(streamUrl)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setStation(name)
            .setTitle(name)
            .setArtist(nowPlaying.ifBlank { "Live radio" })
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .build(),
    )
    .build()

/** 3:07 · 1:02:45 */
internal fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
