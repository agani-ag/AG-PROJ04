package com.agani.syncup.party

import android.app.PendingIntent
import android.content.Intent
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.agani.syncup.MainActivity
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * The party in the status bar and on the lock screen, like the Music player — and what keeps it
 * playing with the screen off. The sound itself comes from [PartySyncPlayer]; this session only
 * mirrors the party: the host gets play / pause / next, a guest just sees what's playing.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PartyService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).setNotificationId(NOTIFICATION_ID).build())
        val player = PartyPlayer(Looper.getMainLooper())
        Party.attachPlayer { player.refresh() }
        val open = PendingIntent.getActivity(
            this, 4300,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PARTY, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setId("syncup-party").setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        Party.detachPlayer()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private companion object {
        const val NOTIFICATION_ID = 4301
    }
}

/** The party as a Media3 player, for the session: state from [Party], commands back to it (host). */
@UnstableApi
private class PartyPlayer(looper: Looper) : SimpleBasePlayer(looper) {
    fun refresh() = invalidateState()

    override fun getState(): State {
        val track = Party.current
        val host = Party.role == PartyRole.HOST
        val commands = Player.Commands.Builder()
            .addAll(Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA, Player.COMMAND_GET_TIMELINE, Player.COMMAND_RELEASE)
            .apply {
                if (host && track != null) {
                    add(Player.COMMAND_PLAY_PAUSE)
                    if (Party.upNext.isNotEmpty()) addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                }
            }
            .build()
        val playing = Party.active && track != null && !Party.paused
        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        if (track == null || !Party.active) {
            return builder.setPlaybackState(Player.STATE_IDLE).build()
        }
        val item = MediaItem.Builder()
            .setMediaId("party-${track.id}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(listOfNotNull(track.artist.takeIf { it.isNotBlank() }, Party.roomName).joinToString(" · "))
                    .setIsPlayable(true)
                    .build(),
            )
            .build()
        return builder
            .setPlaylist(
                listOf(
                    MediaItemData.Builder(track.id)
                        .setMediaItem(item)
                        .setDurationUs(track.durationMs * 1000)
                        .setIsSeekable(false)
                        .build(),
                ),
            )
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(Player.STATE_READY)
            .setContentPositionMs(PositionSupplier { Party.positionMs() })
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (Party.role == PartyRole.HOST && playWhenReady == Party.paused) Party.togglePause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (Party.role == PartyRole.HOST && (seekCommand == Player.COMMAND_SEEK_TO_NEXT || seekCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)) {
            Party.next()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> = Futures.immediateVoidFuture()
}
