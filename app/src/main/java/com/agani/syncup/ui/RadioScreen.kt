package com.agani.syncup.ui

import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.background
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
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("Radio") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
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

            Text(
                "Stations",
                fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = .1.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
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
                else -> LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
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
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(cs.primaryContainer)
            .padding(20.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.7f)
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF3B82F6), Color(0xFF1D4ED8), Color(0xFF1E3A8A)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = Color.White, modifier = Modifier.size(56.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFDC2626))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White))
                Spacer(Modifier.width(6.dp))
                Text("LIVE", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = .6.sp, color = Color.White)
            }
            if (channel.listeners > 0) {
                Text(
                    "${channel.listeners} listening", fontSize = 11.sp, color = Color.White.copy(alpha = .9f),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            channel.name, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium,
            color = cs.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            channel.nowPlaying.ifBlank { "Live radio" }, fontSize = 14.sp, color = cs.onPrimaryContainer.copy(alpha = .8f),
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
            modifier = Modifier.fillMaxWidth(),
        ) {
            IconButton(onClick = onPrev, enabled = canPrev) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous station", tint = cs.onPrimaryContainer.copy(alpha = if (canPrev) 1f else .38f), modifier = Modifier.size(32.dp))
            }
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(22.dp)).background(cs.primary).clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                if (buffering) {
                    CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.dp, color = cs.onPrimary)
                } else {
                    Icon(
                        if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = cs.onPrimary,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
            IconButton(onClick = onNext, enabled = canNext) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Next station", tint = cs.onPrimaryContainer.copy(alpha = if (canNext) 1f else .38f), modifier = Modifier.size(32.dp))
            }
        }
    }
}

private val STATION_COLORS = listOf(Color(0xFFE11D48), Color(0xFFF59E0B), Color(0xFF7C3AED), Color(0xFF0891B2), Color(0xFF16A34A), Color(0xFF2563EB))

@Composable
private fun ChannelRow(
    ch: RadioChannel,
    active: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val tint = STATION_COLORS[(ch.name.hashCode() and 0x7fffffff) % STATION_COLORS.size]
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (active) Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(20.dp)).background(cs.primaryContainer) else Modifier)
            .clickable(onClick = onClick)
            .padding(start = if (active) 12.dp else 20.dp, end = if (active) 12.dp else 20.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tint), contentAlignment = Alignment.Center) {
            Text(
                ch.name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase(),
                fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White,
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                ch.name, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (active) cs.onPrimaryContainer else cs.onSurface,
            )
            Text(
                if (playing) "Playing" + (if (ch.nowPlaying.isNotBlank()) " · ${ch.nowPlaying}" else "") else ch.nowPlaying.ifBlank { "Live radio" },
                fontSize = 12.sp, lineHeight = 16.sp,
                color = if (active) cs.onPrimaryContainer.copy(alpha = .8f) else cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            if (playing) Icons.Rounded.GraphicEq else Icons.Rounded.PlayArrow,
            contentDescription = if (playing) "Playing" else "Play",
            tint = if (active) cs.onPrimaryContainer else cs.primary,
        )
    }
}
