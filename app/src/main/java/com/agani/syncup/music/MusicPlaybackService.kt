package com.agani.syncup.music

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.BitmapLoader
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import com.agani.syncup.stationfiles.Stations
import com.agani.syncup.stationfiles.stationDataSourceFactory
import com.agani.syncup.stationfiles.stationFileRetryDelay
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.agani.syncup.MainActivity
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import java.io.IOException
import java.util.concurrent.Executors

/**
 * The Music player: the phone's songs and SyncUp's live Radio, through one ExoPlayer + MediaSession.
 * As a [MediaSessionService] it keeps playing with the screen off / app backgrounded, with the
 * status-bar + lock-screen controls. The UI drives it through a MediaController ([PlayerHandle]).
 *
 * Songs are seekable and can shuffle / repeat; live stations are unseekable — play / stop and
 * previous / next station only, like a radio.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MusicPlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private val artIo: ListeningExecutorService = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
    private val main = Handler(Looper.getMainLooper())
    private val stationRetries = StationRetries { main.post { session?.player?.let { unstick(it) } } }
    private val boostEffect = BoostEffect()
    private var closedSince = 0L
    private var lastClosedAt = 0L
    private var quickCloses = 0
    private val reconnect = Runnable {
        val p = session?.player ?: return@Runnable
        if (p.playWhenReady && (p.playbackState == Player.STATE_ENDED || p.playbackState == Player.STATE_BUFFERING)) {
            p.stop()
            p.seekToDefaultPosition() // from the old position it would open a connection, seek, then open another
            p.prepare()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // WavesRenderersFactory: Now playing's waves read the sound on its way out (SoundWaves).
        // Station files (Radio → Files) are reached by the station's key: looked up again on every connection.
        val sources = stationDataSourceFactory(DefaultDataSource.Factory(this))
        val player = ExoPlayer.Builder(this, WavesRenderersFactory(this))
            .setMediaSourceFactory(DefaultMediaSourceFactory(sources).setLoadErrorHandlingPolicy(stationRetries))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true, // pause on calls / duck for notifications
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .build()
        player.addListener(object : Player.Listener {
            // A song that won't play (damaged / removed file) is skipped rather than ending the queue. A station's
            // song that lost its station waits paused instead: the next one would fail the same way.
            override fun onPlayerError(error: PlaybackException) {
                val stationGone = player.isStationFile() && error.errorCode in 2000..2999
                if (player.isSong() && !stationGone && player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }

            // Shuffle / repeat changed — from the app, the lock screen or the notification. Kept for
            // next time (songs only: a station always has them off) and the buttons redrawn.
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = modesChanged(player)
            override fun onRepeatModeChanged(repeatMode: Int) = modesChanged(player)

            // Songs ⇄ a station: the shuffle / repeat buttons come and go with it.
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                refreshModeButtons()
                followStation(player)
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) = refreshModeButtons()

            // A station's live socket stays open while it plays, and only then.
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                followStation(player)
                if (playWhenReady) {
                    unstick(player)
                } else {
                    main.removeCallbacks(reconnect)
                    closedSince = 0L
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                followStation(player)
                if (playbackState == Player.STATE_ENDED) stationClosed(player)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    main.removeCallbacks(reconnect)
                    closedSince = 0L
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) = boostEffect.apply(audioSessionId, MusicBoost.boost)
        })
        // Now playing's Boost (100 / 200 / 300%).
        MusicBoost.init(this)
        boostEffect.apply(player.audioSessionId, MusicBoost.boost)
        MusicBoost.onChange = { boostEffect.apply(player.audioSessionId, MusicBoost.boost) }
        // Tapping the media notification opens the in-app Music player.
        val openMusic = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN_MUSIC, true)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openMusic)
            .setBitmapLoader(SongArtLoader(this, DataSourceBitmapLoader(this), artIo))
            .setCallback(SessionCallback())
            .build()
        refreshModeButtons()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * A live stream doesn't end: its connection was closed, cleanly. Connect again straight away; if it keeps
     * closing, wait 3, 6, 12, then 15 s between tries, until [RECONNECT_WINDOW_MS] has passed with nothing playing.
     */
    private fun stationClosed(p: Player) {
        if (!p.playWhenReady || p.currentMediaItem?.mediaId?.startsWith(RADIO_ID) != true) return
        val time = SystemClock.elapsedRealtime()
        if (closedSince == 0L) closedSince = time
        if (time - closedSince > RECONNECT_WINDOW_MS) {
            p.pause() // gone for good: Play tries again
            return
        }
        quickCloses = if (time - lastClosedAt < RECONNECT_QUICK_MS) quickCloses + 1 else 0
        lastClosedAt = time
        val wait = if (quickCloses == 0) 0L else minOf(3_000L shl (quickCloses - 1).coerceAtMost(3), 15_000L)
        main.removeCallbacks(reconnect)
        main.postDelayed(reconnect, wait)
    }

    /**
     * ExoPlayer retries a broken live stream only after playing what it holds, and after a stall it won't play
     * until it holds 5 s (2.5 s at the start). A connection lost with less than that would leave both waiting
     * for ever, so connect again.
     */
    private fun unstick(p: Player) {
        if (p.playbackState == Player.STATE_BUFFERING && p.bufferedPosition > p.currentPosition) stationClosed(p)
    }

    /** Keep the live socket on the station being played; closed for songs, when stopped or after an error. */
    private fun followStation(p: Player) {
        val item = p.currentMediaItem
        val id = item?.mediaId?.takeIf { it.startsWith(RADIO_ID) }?.removePrefix(RADIO_ID)
        stationRetries.stationOn = id != null
        stationRetries.fileOn = p.isStationFile()
        if (id == null) main.removeCallbacks(reconnect)
        val playing = id != null && p.playWhenReady && p.playbackState != Player.STATE_IDLE && p.playbackState != Player.STATE_ENDED
        val url = if (playing) {
            liveSocketUrl(item?.localConfiguration?.uri?.toString().orEmpty(), item?.mediaMetadata?.extras?.getString(EXTRA_WS_URL))
        } else {
            null
        }
        RadioLive.follow(id, url) { live -> showSong(p, id.orEmpty(), live) }
    }

    /**
     * The station's new song on the lock screen, the notification and in the app. Only the item's details
     * change (same address), so the player carries on without touching the sound.
     */
    private fun showSong(p: Player, id: String, live: StationLive) {
        val item = p.currentMediaItem ?: return
        if (item.mediaId != RADIO_ID + id) return
        val song = live.title.ifBlank { "Live radio" }
        if (item.mediaMetadata.artist?.toString() == song) return
        p.replaceMediaItem(
            p.currentMediaItemIndex,
            item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setArtist(song).build()).build(),
        )
    }

    /** Shuffle + repeat on the lock screen and the notification — only while songs play. */
    private fun modeButtons(p: Player): List<CommandButton> {
        if (!p.isSong()) return emptyList()
        val shuffle = CommandButton.Builder(if (p.shuffleModeEnabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
            .setDisplayName(if (p.shuffleModeEnabled) "Shuffle on" else "Shuffle off")
            .setSessionCommand(SHUFFLE)
            .build()
        val repeat = CommandButton.Builder(
            when (p.repeatMode) {
                Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
                Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
                else -> CommandButton.ICON_REPEAT_OFF
            },
        )
            .setDisplayName(
                when (p.repeatMode) {
                    Player.REPEAT_MODE_ONE -> "Repeat this song"
                    Player.REPEAT_MODE_ALL -> "Repeat all"
                    else -> "Repeat off"
                },
            )
            .setSessionCommand(REPEAT)
            .build()
        return listOf(shuffle, repeat)
    }

    private fun refreshModeButtons() {
        val s = session ?: return
        s.setCustomLayout(modeButtons(s.player))
    }

    private fun modesChanged(p: Player) {
        if (p.isSong()) {
            getSharedPreferences(MUSIC_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(PREF_SHUFFLE, p.shuffleModeEnabled)
                .putInt(PREF_REPEAT, p.repeatMode)
                .apply()
        }
        refreshModeButtons()
    }

    /** Lets the system media controls (lock screen, notification) use the shuffle / repeat buttons. */
    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(SHUFFLE).add(REPEAT).build(),
                )
                .setCustomLayout(modeButtons(session.player))
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val p = session.player
            when (customCommand.customAction) {
                SHUFFLE.customAction -> p.shuffleModeEnabled = !p.shuffleModeEnabled
                REPEAT.customAction -> p.repeatMode = nextRepeatMode(p.repeatMode)
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private companion object {
        val SHUFFLE = SessionCommand("com.agani.syncup.music.SHUFFLE", Bundle.EMPTY)
        val REPEAT = SessionCommand("com.agani.syncup.music.REPEAT", Bundle.EMPTY)
    }

    // If the user swipes the app away and nothing is playing, stop the service (no lingering notif).
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(reconnect)
        RadioLive.stop()
        MusicBoost.onChange = null
        boostEffect.release()
        session?.run {
            player.release()
            release()
        }
        session = null
        SoundWaves.clear()
        artIo.shutdown()
        super.onDestroy()
    }
}

/**
 * While a station plays, a dropped stream keeps retrying (1, 2, 4, 8, then every 15 s, about 5 minutes in
 * all). The player stays in "connecting" meanwhile, so the service stays in the foreground with the
 * screen off and the sound comes back by itself. Songs fail as usual.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class StationRetries(private val onLoadError: () -> Unit) : DefaultLoadErrorHandlingPolicy() {
    @Volatile var stationOn = false
    /** A song from a station's files: waits about 3 minutes for its station (see stationFileRetryDelay). */
    @Volatile var fileOn = false

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        stationFileRetryDelay(loadErrorInfo)?.let { return it }
        if (!stationOn) return super.getRetryDelayMsFor(loadErrorInfo)
        Stations.forgetDirect() // it may have been on the station's Wi-Fi: check that again, else the public address
        onLoadError()
        val tries = loadErrorInfo.errorCount
        if (tries > STATION_RETRIES) return C.TIME_UNSET
        return minOf(1_000L shl (tries - 1).coerceIn(0, 4), 15_000L)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int =
        if (stationOn || fileOn) Int.MAX_VALUE else super.getMinimumLoadableRetryCount(dataType)
}

private const val STATION_RETRIES = 23
private const val RECONNECT_WINDOW_MS = 300_000L
private const val RECONNECT_QUICK_MS = 30_000L

/** Notification / lock-screen cover art: songs' art from MediaStore, anything else the default way. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class SongArtLoader(
    private val context: Context,
    private val fallback: BitmapLoader,
    private val io: ListeningExecutorService,
) : BitmapLoader by fallback {
    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        if (Artwork.isLocal(uri)) io.submit<Bitmap> { Artwork.load(context, uri, Artwork.LARGE) ?: throw IOException("No cover art") }
        else fallback.loadBitmap(uri)
}
