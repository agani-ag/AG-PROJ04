package com.agani.syncup.music

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.BitmapLoader
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.ExoPlayer
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

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
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
            // A song that won't play (damaged / removed file) is skipped rather than ending the queue.
            override fun onPlayerError(error: PlaybackException) {
                if (player.isSong() && player.hasNextMediaItem()) {
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
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refreshModeButtons()
            override fun onTimelineChanged(timeline: Timeline, reason: Int) = refreshModeButtons()
        })
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
        session?.run {
            player.release()
            release()
        }
        session = null
        artIo.shutdown()
        super.onDestroy()
    }
}

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
