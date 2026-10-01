package com.agani.syncup.music

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.agani.syncup.MainActivity
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
        // A song that won't play (damaged / removed file) is skipped rather than ending the queue.
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val song = player.currentMediaItem?.mediaId?.startsWith(SONG_ID) == true
                if (song && player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }
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
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

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
