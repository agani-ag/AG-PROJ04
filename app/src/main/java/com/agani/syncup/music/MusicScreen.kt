package com.agani.syncup.music

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.browser.ui.EmptyState
import com.agani.syncup.browser.ui.PillButton
import com.agani.syncup.browser.ui.SyncPill
import com.agani.syncup.data.RadioChannel
import com.agani.syncup.data.RadioChannelsResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Music: every song on this phone, with shuffle / repeat. Radio (SyncUp's live stations) is its own
 * screen now — see [RadioScreen] — but both play through the same player, so the mini player, the
 * notification and the lock screen work the same whichever screen started playback.
 */
@Composable
fun MusicScreen(onBack: () -> Unit) {
    val player = LocalPlayer.current ?: return
    val cs = MaterialTheme.colorScheme
    var nowPlaying by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp)) {
            BarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
            Text("Music", fontSize = 22.sp, lineHeight = 28.sp, color = cs.onSurface, modifier = Modifier.weight(1f).padding(start = 4.dp))
        }
        Box(Modifier.weight(1f)) {
            SongsTab(player, onOpenPlayer = { nowPlaying = true })
        }
        AnimatedVisibility(
            visible = player.item != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            MiniPlayer(onOpen = { nowPlaying = true }, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp))
        }
    }

    if (nowPlaying && player.item != null) NowPlayingSheet(player, onDismiss = { nowPlaying = false })
}

/** Radio: SyncUp's live stations (admin on/off, signed-in users only — gated by the caller). */
@Composable
fun RadioScreen(loadStations: suspend () -> Result<RadioChannelsResponse>, onBack: () -> Unit) {
    val player = LocalPlayer.current ?: return
    val cs = MaterialTheme.colorScheme
    var nowPlaying by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp)) {
            BarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
            Text("Radio", fontSize = 22.sp, lineHeight = 28.sp, color = cs.onSurface, modifier = Modifier.weight(1f).padding(start = 4.dp))
        }
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
        ) {
            Icon(Icons.Rounded.Info, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(15.dp).padding(top = 2.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Private broadcasts via this organization's own AudioSync only — not public radio stations.",
                fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant,
            )
        }
        Box(Modifier.weight(1f)) {
            RadioTab(player, loadStations, onOpenPlayer = { nowPlaying = true })
        }
        AnimatedVisibility(
            visible = player.item != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            MiniPlayer(onOpen = { nowPlaying = true }, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp))
        }
    }

    if (nowPlaying && player.item != null) NowPlayingSheet(player, onDismiss = { nowPlaying = false })
}

// ============================================================================ songs
@Composable
private fun SongsTab(player: PlayerHandle, onOpenPlayer: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var granted by remember { mutableStateOf(LocalSongs.canRead(context)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        asked = true
    }
    var songs by remember { mutableStateOf<List<Song>?>(null) } // null while loading
    var query by rememberSaveable { mutableStateOf("") }

    // Re-read on every return to the app: picks up songs added meanwhile, and access given in Settings.
    var reload by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = LocalSongs.canRead(context)
                reload++
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(granted, reload) {
        if (granted) songs = withContext(Dispatchers.IO) { runCatching { LocalSongs.load(context) }.getOrDefault(emptyList()) }
    }

    if (!granted) {
        // Denied for good ("don't ask again"): the system won't show the prompt, so send them to Settings.
        val blocked = asked && context.findActivity()?.let { !ActivityCompat.shouldShowRequestPermissionRationale(it, LocalSongs.permission) } == true
        EmptyState(
            Icons.Rounded.LibraryMusic,
            "Play the songs on this phone",
            if (blocked) "Music access is off for SyncUp. Turn it on in Settings → Permissions."
            else "Allow access to your music. Your songs stay on this phone — nothing is uploaded.",
            action = if (blocked) "Open settings" else "Allow access",
            actionIcon = if (blocked) Icons.Rounded.Settings else null,
            onAction = { if (blocked) openAppSettings(context) else ask.launch(LocalSongs.permission) },
        )
        return
    }

    val all = songs
    when {
        all == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        all.isEmpty() -> EmptyState(Icons.Rounded.MusicOff, "No songs on this phone", "Songs you download or copy to this phone show up here")
        else -> {
            val q = query.trim()
            val list = remember(all, q) {
                if (q.isEmpty()) all
                else all.filter { it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true) }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
                item(key = "search") {
                    SyncPill(
                        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
                        height = 48.dp, focused = query.isNotEmpty(),
                    ) {
                        Icon(Icons.Rounded.Search, null, tint = cs.onSurfaceVariant)
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 15.sp, color = cs.onSurface),
                            cursorBrush = SolidColor(cs.primary),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (query.isEmpty()) Text("Search songs, artists, albums", color = cs.onSurfaceVariant, fontSize = 15.sp)
                                    inner()
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (query.isNotEmpty()) PillButton(Icons.Rounded.Close, "Clear", size = 40.dp) { query = "" }
                    }
                }
                item(key = "actions") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
                    ) {
                        Text(
                            "${list.size} song${if (list.size == 1) "" else "s"}",
                            fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.weight(1f),
                        )
                        if (list.isNotEmpty()) {
                            FilledTonalButton(onClick = { player.playSongs(list, 0, shuffled = true) }, contentPadding = PaddingValues(horizontal = 16.dp)) {
                                Icon(Icons.Rounded.Shuffle, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Shuffle")
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { player.playSongs(list, 0) }, contentPadding = PaddingValues(horizontal = 16.dp)) {
                                Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Play")
                            }
                        }
                    }
                }
                if (list.isEmpty()) {
                    item(key = "none") {
                        Text(
                            "No songs match “$q”", fontSize = 14.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                        )
                    }
                }
                itemsIndexed(list, key = { _, s -> s.id }) { index, song ->
                    val current = song.id == player.songId
                    SongRow(song, current, playing = current && player.isPlaying) {
                        if (current) onOpenPlayer() else player.playSongs(list, index)
                    }
                }
            }
        }
    }
}

@Composable
private fun SongRow(song: Song, current: Boolean, playing: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (current) Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(16.dp)).background(cs.primaryContainer.copy(alpha = .55f)) else Modifier)
            .clickable(onClick = onClick)
            .padding(start = if (current) 12.dp else 20.dp, end = if (current) 12.dp else 20.dp, top = 8.dp, bottom = 8.dp),
    ) {
        ArtworkTile(song.artUri, radio = false, modifier = Modifier.size(48.dp), radius = 12.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
                color = if (current) cs.primary else cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${song.artist} · ${formatTime(song.durationMs)}", fontSize = 12.sp, lineHeight = 16.sp,
                color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (current) {
            Spacer(Modifier.width(8.dp))
            if (playing) EqBars() else Icon(Icons.Rounded.Pause, "Paused", tint = cs.primary, modifier = Modifier.size(18.dp))
        }
    }
}

// ============================================================================ radio
@Composable
private fun RadioTab(player: PlayerHandle, loadStations: suspend () -> Result<RadioChannelsResponse>, onOpenPlayer: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var stations by remember { mutableStateOf<List<RadioChannel>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var listError by remember { mutableStateOf<String?>(null) }
    // Stations come and go (a broadcaster goes on / off air), so the list refreshes while it's open.
    LaunchedEffect(Unit) {
        while (true) {
            loadStations().fold(
                onSuccess = { stations = it.channels; listError = null },
                onFailure = { listError = it.message ?: "Couldn't load stations." },
            )
            loading = false
            delay(15_000)
        }
    }

    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        stations.isEmpty() -> EmptyState(
            Icons.Rounded.Radio,
            if (listError != null) "Couldn't load stations" else "No stations on air",
            listError?.let { "Check your connection. Trying again shortly." } ?: "Live stations show up here when they go on air",
        )
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
            item(key = "head") {
                Text(
                    "Live stations", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSurface,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
                )
            }
            if (player.isRadio && player.error != null) {
                item(key = "error") {
                    Text(
                        player.error.orEmpty(), fontSize = 13.sp, color = cs.error,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                    )
                }
            }
            itemsIndexed(stations, key = { _, s -> s.id }) { index, station ->
                val active = station.id == player.stationId
                StationRow(station, active, playing = active && player.isPlaying) {
                    if (active) onOpenPlayer() else player.playRadio(stations, index)
                }
            }
        }
    }
}

private val STATION_COLORS = listOf(Color(0xFFE11D48), Color(0xFFF59E0B), Color(0xFF7C3AED), Color(0xFF0891B2), Color(0xFF16A34A), Color(0xFF2563EB))

@Composable
private fun StationRow(station: RadioChannel, active: Boolean, playing: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val tint = STATION_COLORS[(station.name.hashCode() and 0x7fffffff) % STATION_COLORS.size]
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (active) Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(16.dp)).background(cs.primaryContainer.copy(alpha = .55f)) else Modifier)
            .clickable(onClick = onClick)
            .padding(start = if (active) 12.dp else 20.dp, end = if (active) 12.dp else 20.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(tint), contentAlignment = Alignment.Center) {
            Text(
                station.name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase(),
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White,
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                station.name, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
                color = if (active) cs.primary else cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(station.nowPlaying.ifBlank { "Live radio" })
                    if (station.listeners > 0) append(" · ${station.listeners} listening")
                },
                fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (playing) EqBars() else Icon(if (active) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (active) "Paused" else "Play", tint = cs.primary)
    }
}

// ============================================================================ now playing
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NowPlayingSheet(player: PlayerHandle, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        NowPlaying(player)
    }
}

@Composable
private fun NowPlaying(player: PlayerHandle) {
    val cs = MaterialTheme.colorScheme
    val radio = player.isRadio
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
    ) {
        Box(Modifier.fillMaxWidth(.78f).aspectRatio(1f)) {
            ArtworkTile(player.artUri, radio, Modifier.fillMaxSize(), radius = 28.dp, large = true)
            if (radio) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(14.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFDC2626))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White))
                    Spacer(Modifier.width(6.dp))
                    Text("LIVE", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = .6.sp, color = Color.White)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            player.title.ifBlank { if (radio) "Radio" else "Music" }, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium,
            color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            player.subtitle, fontSize = 14.sp, lineHeight = 20.sp, color = cs.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        if (radio) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(64.dp)) {
                Icon(Icons.Rounded.GraphicEq, null, tint = cs.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Live — no rewinding", fontSize = 13.sp, color = cs.onSurfaceVariant)
            }
        } else {
            SeekBar(player)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            if (!radio) {
                ModeButton(Icons.Rounded.Shuffle, if (player.shuffle) "Shuffle on" else "Shuffle off", player.shuffle) { player.updateShuffle(!player.shuffle) }
            }
            IconButton(onClick = { player.previous() }, enabled = !radio || player.hasPrevious, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.SkipPrevious, if (radio) "Previous station" else "Previous song", modifier = Modifier.size(36.dp))
            }
            Box(
                Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(cs.primary).clickable { player.toggle() },
                contentAlignment = Alignment.Center,
            ) {
                if (player.buffering) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp, color = cs.onPrimary)
                } else {
                    Icon(
                        if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (player.isPlaying) "Pause" else "Play",
                        tint = cs.onPrimary, modifier = Modifier.size(36.dp),
                    )
                }
            }
            IconButton(onClick = { player.next() }, enabled = player.hasNext, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.SkipNext, if (radio) "Next station" else "Next song", modifier = Modifier.size(36.dp))
            }
            if (!radio) {
                val repeat = player.repeat
                ModeButton(
                    if (repeat == androidx.media3.common.Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    when (repeat) {
                        androidx.media3.common.Player.REPEAT_MODE_ALL -> "Repeat all"
                        androidx.media3.common.Player.REPEAT_MODE_ONE -> "Repeat this song"
                        else -> "Repeat off"
                    },
                    repeat != androidx.media3.common.Player.REPEAT_MODE_OFF,
                ) { player.cycleRepeat() }
            }
        }
        player.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, fontSize = 13.sp, color = cs.error, textAlign = TextAlign.Center)
        }
    }
}

/** Shuffle / repeat: tinted with a dot underneath while on. */
@Composable
private fun ModeButton(icon: ImageVector, description: String, on: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, description, tint = if (on) cs.primary else cs.onSurfaceVariant)
            Spacer(Modifier.height(3.dp))
            Box(Modifier.size(4.dp).clip(CircleShape).background(if (on) cs.primary else Color.Transparent))
        }
    }
}

@Composable
private fun SeekBar(player: PlayerHandle) {
    val cs = MaterialTheme.colorScheme
    val duration = player.durationMs
    var position by remember { mutableLongStateOf(player.position) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(player.item, player.isPlaying) {
        while (true) {
            if (dragging == null) position = player.position
            if (!player.isPlaying) break
            delay(500)
        }
    }
    val fraction = dragging ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth().height(64.dp), verticalArrangement = Arrangement.Center) {
        Slider(
            value = fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let {
                    position = (it * duration).toLong()
                    player.seekTo(position)
                }
                dragging = null
            },
            enabled = duration > 0,
            colors = SliderDefaults.colors(inactiveTrackColor = cs.surfaceContainerHighest),
            modifier = Modifier.height(32.dp),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime((fraction * duration).toLong()), fontSize = 12.sp, color = cs.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(formatTime(duration), fontSize = 12.sp, color = cs.onSurfaceVariant)
        }
    }
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
