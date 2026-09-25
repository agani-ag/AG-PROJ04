package com.agani.syncup.ui

import android.content.ComponentName
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.agani.syncup.auth.AuthViewModel
import com.agani.syncup.data.RadioChannel
import com.agani.syncup.radio.RadioPlaybackService
import kotlinx.coroutines.delay

/**
 * Live Radio player. Backed by the native Media3 player (RadioPlaybackService) so a station keeps
 * playing with the screen off / app backgrounded, with a status-bar + lock-screen notification.
 * All live channels are loaded as one playlist, so Prev/Next switch stations both here and in the
 * notification. Live streams are unseekable (no scrub bar) — play/pause + skip only.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RadioScreen(
    viewModel: AuthViewModel,
    onClose: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, RadioPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            { runCatching { controller = future.get() } },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            // Release only the controller — the player keeps running in the service (background play).
            MediaController.releaseFuture(future)
            controller?.release()
            controller = null
        }
    }

    var isPlaying by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var currentId by remember { mutableStateOf<String?>(null) }
    var currentIndex by remember { mutableStateOf(0) }
    var mediaCount by remember { mutableStateOf(0) }
    var playbackError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(controller) {
        val c = controller ?: return@DisposableEffect onDispose {}
        fun sync() {
            isPlaying = c.isPlaying
            buffering = c.playbackState == Player.STATE_BUFFERING
            currentId = c.currentMediaItem?.mediaId?.takeIf { it.isNotEmpty() }
            currentIndex = c.currentMediaItemIndex
            mediaCount = c.mediaItemCount
        }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) { sync() }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) { sync() }
            override fun onPlayerError(error: PlaybackException) {
                playbackError = "This station went off air."
            }
        }
        c.addListener(listener)
        sync()
        onDispose { c.removeListener(listener) }
    }

    var channels by remember { mutableStateOf<List<RadioChannel>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var listError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.radioChannels().fold(
                onSuccess = { channels = it.channels; listError = null },
                onFailure = { listError = it.message ?: "Couldn't load stations." },
            )
            loading = false
            delay(15_000)
        }
    }

    // Load ALL channels as the player's playlist and start at the tapped one, so Prev/Next (here and
    // in the notification) switch stations.
    fun playChannel(target: RadioChannel) {
        val c = controller ?: return
        playbackError = null
        val list = channels
        val startIndex = list.indexOfFirst { it.id == target.id }.coerceAtLeast(0)
        val items = list.map { ch ->
            MediaItem.Builder()
                .setUri(ch.streamUrl)
                .setMediaId(ch.id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setStation(ch.name)
                        .setTitle(ch.name)
                        .setArtist(ch.nowPlaying.ifBlank { "Live radio" })
                        .build(),
                )
                .build()
        }
        c.setMediaItems(items, startIndex, C.TIME_UNSET)
        c.prepare()
        c.play()
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    val current = channels.firstOrNull { it.id == currentId }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Radio", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
        ) {
            // ---- Now-playing player ----
            if (current != null) {
                NowPlaying(
                    channel = current,
                    isPlaying = isPlaying,
                    buffering = buffering,
                    canPrev = currentIndex > 0,
                    canNext = currentIndex < mediaCount - 1,
                    onToggle = ::togglePlay,
                    onPrev = { controller?.seekToPreviousMediaItem() },
                    onNext = { controller?.seekToNextMediaItem() },
                )
            }
            playbackError?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Stations",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                channels.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        listError ?: "No stations on air right now.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp),
                ) {
                    items(channels, key = { it.id }) { ch ->
                        val active = ch.id == currentId
                        ChannelRow(
                            ch = ch,
                            active = active,
                            playing = active && isPlaying,
                            onClick = { if (active) togglePlay() else playChannel(ch) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NowPlaying(
    channel: RadioChannel,
    isPlaying: Boolean,
    buffering: Boolean,
    canPrev: Boolean,
    canNext: Boolean,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(20.dp),
        ) {
            Box(
                modifier = Modifier.size(72.dp).clip(CircleShape)
                    .padding(0.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Radio,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(56.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                channel.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                channel.nowPlaying.ifBlank { "Live radio" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (channel.listeners > 0) {
                Text(
                    "${channel.listeners} listening",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                IconButton(onClick = onPrev, enabled = canPrev) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous station", modifier = Modifier.size(36.dp))
                }
                FilledIconButton(
                    onClick = onToggle,
                    modifier = Modifier.size(64.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    if (buffering) {
                        CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(
                            if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }
                IconButton(onClick = onNext, enabled = canNext) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next station", modifier = Modifier.size(36.dp))
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    ch: RadioChannel,
    active: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(14.dp),
        ) {
            Icon(
                if (playing) Icons.Rounded.GraphicEq else Icons.Rounded.Radio,
                contentDescription = null,
                tint = if (active) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    ch.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (ch.nowPlaying.isNotBlank()) {
                    Text(
                        ch.nowPlaying,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                tint = if (active) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.primary,
            )
        }
    }
}
