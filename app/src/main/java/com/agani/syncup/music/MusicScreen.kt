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
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Groups
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
import androidx.compose.material3.ButtonDefaults
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
import com.agani.syncup.browser.ui.EmptyState
import com.agani.syncup.browser.ui.PillButton
import com.agani.syncup.data.RadioChannel
import com.agani.syncup.data.RadioChannelsResponse
import com.agani.syncup.party.Party
import com.agani.syncup.party.PartyRole
import com.agani.syncup.party.rememberPartyStarter
import com.agani.syncup.ui.theme.PillTabs
import com.agani.syncup.ui.theme.appCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Party's soft pink (the Party button, the "in a party" bar). */
private val PartyTint = ToolTint.PINK

/**
 * Music: every song on this phone, with shuffle / repeat. Radio (SyncUp's live stations) is its own
 * screen now — see [RadioScreen] — but both play through the same player, so the mini player, the
 * notification and the lock screen work the same whichever screen started playback.
 */
@Composable
fun MusicScreen(onBack: () -> Unit, onOpenParty: () -> Unit = {}) {
    val player = LocalPlayer.current ?: return
    val cs = MaterialTheme.colorScheme
    var nowPlaying by rememberSaveable { mutableStateOf(false) }
    // Music → Party: start a Radio → Party with these songs, then go to the host screen.
    val startParty = rememberPartyStarter(onStarted = {
        nowPlaying = false
        onOpenParty()
    })

    Column(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        ToolTopBar("Music", onBack)
        Box(Modifier.weight(1f)) {
            SongsTab(player, onOpenPlayer = { nowPlaying = true }, onParty = { songs -> startParty(songs, 0L) }, onOpenParty = onOpenParty)
        }
        AnimatedVisibility(
            visible = player.item != null,
            enter = slideInVertically(tween(220)) { it } + fadeIn(tween(220)),
            exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(180)),
        ) {
            MiniPlayer(onOpen = { nowPlaying = true }, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp))
        }
    }

    if (nowPlaying && player.item != null) {
        NowPlayingSheet(
            player, onDismiss = { nowPlaying = false },
            onPlayAtParty = if (Party.active) null else ({ startParty(player.currentAndNext(), player.position) }),
        )
    }
}

/**
 * Radio, for everyone, signed in or not: **Party** (play songs in sync on phones nearby) and **Live
 * stations** (SyncUp's AudioSync broadcasts, while the admin has them on; the tab is hidden when the
 * admin has them off).
 */
@Composable
fun RadioScreen(
    liveStations: Boolean,
    partyTab: Boolean,
    onTab: (party: Boolean) -> Unit,
    loadStations: suspend () -> Result<RadioChannelsResponse>,
    onBack: () -> Unit,
) {
    val player = LocalPlayer.current ?: return
    val cs = MaterialTheme.colorScheme
    var nowPlaying by rememberSaveable { mutableStateOf(false) }
    val onParty = partyTab || !liveStations

    Column(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        ToolTopBar("Radio", onBack)
        if (liveStations) {
            PillTabs(
                listOf("Party", "Live stations"),
                selected = if (onParty) 0 else 1,
                onSelect = { onTab(it == 0) },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp),
            )
        }
        Box(Modifier.weight(1f)) {
            if (onParty) {
                com.agani.syncup.party.PartyTab()
            } else {
                Column {
                    NoteBox(
                        Icons.Rounded.Info,
                        "Private broadcasts via this organization's own AudioSync only — not public radio stations.",
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                    )
                    RadioTab(player, loadStations, onOpenPlayer = { nowPlaying = true })
                }
            }
        }
        AnimatedVisibility(
            visible = player.item != null,
            enter = slideInVertically(tween(220)) { it } + fadeIn(tween(220)),
            exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(180)),
        ) {
            MiniPlayer(onOpen = { nowPlaying = true }, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp))
        }
    }

    if (nowPlaying && player.item != null) NowPlayingSheet(player, onDismiss = { nowPlaying = false })
}

// ============================================================================ songs
@Composable
private fun SongsTab(player: PlayerHandle, onOpenPlayer: () -> Unit, onParty: (List<Song>) -> Unit, onOpenParty: () -> Unit) {
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
            // While this phone hosts a party, Music adds to it instead of playing on its own.
            val hosting = Party.role == PartyRole.HOST
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) {
                if (Party.active) {
                    item(key = "party") {
                        // In a party: a soft pink bar back to it.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(PartyTint.tile)
                                .clickable(onClick = onOpenParty).padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        ) {
                            Icon(Icons.Rounded.Groups, null, tint = PartyTint.icon, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                (if (hosting) "You're hosting " else "You're in ") + Party.roomName + " · ${Party.people.size} ${if (Party.people.size == 1) "person" else "people"}",
                                color = cs.onSurface, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            Text(
                                "Open", color = PartyTint.onSolid, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(50)).background(PartyTint.icon).padding(horizontal = 14.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
                item(key = "search") {
                    val pill = RoundedCornerShape(50)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth().height(50.dp).appCard(pill)
                            .then(if (query.isNotEmpty()) Modifier.border(2.dp, cs.primary, pill) else Modifier)
                            .padding(start = 16.dp, end = 4.dp),
                    ) {
                        Icon(Icons.Rounded.Search, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
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
                if (list.isNotEmpty() && (hosting || !Party.active)) {
                    item(key = "actions") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                            val small = PaddingValues(horizontal = 10.dp)
                            if (hosting) {
                                Button(
                                    onClick = { Party.addSongs(list.filterNot { Party.isQueued(it.uri) }) },
                                    colors = ButtonDefaults.buttonColors(containerColor = PartyTint.tile, contentColor = PartyTint.icon),
                                    contentPadding = small, modifier = Modifier.weight(1f).height(40.dp),
                                ) { ButtonLabel(Icons.Rounded.Add, "Add all to party") }
                            } else {
                                Button(onClick = { player.playSongs(list, 0) }, contentPadding = small, modifier = Modifier.weight(1f).height(40.dp)) {
                                    ButtonLabel(Icons.Rounded.PlayArrow, "Play")
                                }
                                FilledTonalButton(onClick = { player.playSongs(list, 0, shuffled = true) }, contentPadding = small, modifier = Modifier.weight(1f).height(40.dp)) {
                                    ButtonLabel(Icons.Rounded.Shuffle, "Shuffle")
                                }
                                // Play the listed songs at a party: everyone nearby hears them in sync.
                                Button(
                                    onClick = { onParty(list) },
                                    colors = ButtonDefaults.buttonColors(containerColor = PartyTint.tile, contentColor = PartyTint.icon),
                                    contentPadding = small, modifier = Modifier.weight(1f).height(40.dp),
                                ) { ButtonLabel(Icons.Rounded.Groups, "Party") }
                            }
                        }
                    }
                }
                item(key = "count") { SectionHead("${list.size} song${if (list.size == 1) "" else "s"}") }
                if (list.isEmpty()) {
                    item(key = "none") {
                        Text(
                            "No songs match “$q”", fontSize = 14.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                        )
                    }
                }
                itemsIndexed(list, key = { _, s -> s.id }) { index, song ->
                    val slice = Modifier.cardSlice(first = index == 0, last = index == list.lastIndex)
                    if (hosting) {
                        val queued = Party.isQueued(song.uri)
                        SongRow(song, slice, current = false, playing = false, partyAdded = queued) { if (!queued) Party.addSongs(listOf(song)) }
                    } else {
                        val current = song.id == player.songId
                        SongRow(song, slice, current, playing = current && player.isPlaying) {
                            if (current) onOpenPlayer() else player.playSongs(list, index)
                        }
                    }
                }
            }
        }
    }
}

/** A pill button's icon + label, sized for the three-across row. */
@Composable
private fun ButtonLabel(icon: ImageVector, text: String) {
    Icon(icon, null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(6.dp))
    Text(text, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** A song: one slice of the list's card ([slice]); the playing one on soft blue. */
@Composable
private fun SongRow(song: Song, slice: Modifier, current: Boolean, playing: Boolean, partyAdded: Boolean? = null, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = slice
            .fillMaxWidth()
            .then(if (current) Modifier.background(cs.primaryContainer) else Modifier)
            .clickable(onClick = onClick)
            .heightIn(min = 66.dp)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        ArtworkTile(song.artUri, radio = false, modifier = Modifier.size(48.dp), radius = 14.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
                color = if (current) cs.primary else cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${song.artist} · ${formatTime(song.durationMs)}", fontSize = 12.5.sp, lineHeight = 17.sp,
                color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (current) {
            Spacer(Modifier.width(8.dp))
            if (playing) EqBars() else Icon(Icons.Rounded.Pause, "Paused", tint = cs.primary, modifier = Modifier.size(18.dp))
        }
        // Hosting a party: + adds the song to it; ✓ = already in the party.
        if (partyAdded != null) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(if (partyAdded) PartyTint.icon else PartyTint.tile),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (partyAdded) Icons.Rounded.CheckCircle else Icons.Rounded.Add, if (partyAdded) "In the party" else "Add to party",
                    tint = if (partyAdded) PartyTint.onSolid else PartyTint.icon, modifier = Modifier.size(20.dp),
                )
            }
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
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
            item(key = "head") { SectionHead("Live stations", count = stations.size.toString()) }
            if (player.isRadio && player.error != null) {
                item(key = "error") {
                    Text(
                        player.error.orEmpty(), fontSize = 13.sp, color = cs.error,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                    )
                }
            }
            itemsIndexed(stations, key = { _, s -> s.id }) { index, station ->
                val active = station.id == player.stationId
                StationRow(station, Modifier.cardSlice(first = index == 0, last = index == stations.lastIndex), active, playing = active && player.isPlaying) {
                    if (active) onOpenPlayer() else player.playRadio(stations, index)
                }
            }
        }
    }
}

/** Stations' letter tiles, each in a soft colour picked from its name. */
private val STATION_TINTS = listOf(ToolTint.ORANGE, ToolTint.ROSE, ToolTint.VIOLET, ToolTint.CYAN, ToolTint.GREEN, ToolTint.BLUE, ToolTint.AMBER)

@Composable
private fun StationRow(station: RadioChannel, slice: Modifier, active: Boolean, playing: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val tint = STATION_TINTS[(station.name.hashCode() and 0x7fffffff) % STATION_TINTS.size]
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = slice
            .fillMaxWidth()
            .then(if (active) Modifier.background(cs.primaryContainer) else Modifier)
            .clickable(onClick = onClick)
            .heightIn(min = 66.dp)
            .padding(start = 14.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(tint.tile), contentAlignment = Alignment.Center) {
            Text(
                station.name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase(),
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = tint.icon,
            )
        }
        Spacer(Modifier.width(12.dp))
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
                fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (playing) EqBars() else Icon(if (active) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (active) "Paused" else "Play", tint = cs.primary)
    }
}

// ============================================================================ now playing
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NowPlayingSheet(player: PlayerHandle, onDismiss: () -> Unit, onPlayAtParty: (() -> Unit)? = null) {
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        NowPlaying(player, onPlayAtParty)
    }
}

@Composable
private fun NowPlaying(player: PlayerHandle, onPlayAtParty: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    val radio = player.isRadio
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
    ) {
        // The cover; a tap shows the song's waves (Waves.kt).
        CoverOrWaves(player, Modifier.fillMaxWidth(.78f).aspectRatio(1f)) {
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
        Spacer(Modifier.height(20.dp))
        Text(
            player.title.ifBlank { if (radio) "Radio" else "Music" }, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium,
            color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            player.subtitle, fontSize = 14.sp, lineHeight = 20.sp, color = cs.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        if (radio) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(64.dp)) {
                Icon(Icons.Rounded.GraphicEq, null, tint = cs.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Live — no rewinding", fontSize = 13.sp, color = cs.onSurfaceVariant)
            }
        } else {
            SeekBar(player)
        }
        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (radio) Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally) else Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (!radio) {
                ModeButton(Icons.Rounded.Shuffle, if (player.shuffle) "Shuffle on" else "Shuffle off", player.shuffle) { player.updateShuffle(!player.shuffle) }
            }
            IconButton(onClick = { player.previous() }, enabled = !radio || player.hasPrevious, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.SkipPrevious, if (radio) "Previous station" else "Previous song", modifier = Modifier.size(36.dp))
            }
            Box(
                Modifier.size(74.dp).clip(RoundedCornerShape(26.dp)).background(cs.primary).clickable { player.toggle() },
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
        if (!radio && onPlayAtParty != null) {
            // Carry on as a party: this song from where it is, the rest of the list next.
            var at by remember { mutableLongStateOf(player.position) }
            LaunchedEffect(player.item, player.isPlaying) {
                while (true) {
                    at = player.position
                    if (!player.isPlaying) break
                    delay(1_000)
                }
            }
            // In the song's own colours, from its cover.
            val colors = rememberCoverColors(player.artUri, player.isRadio)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp))
                    .background(colors.tile).clickable(onClick = onPlayAtParty).padding(14.dp),
            ) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(colors.solid), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Groups, null, tint = colors.onSolid)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Play at a party", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                    Text("Everyone nearby hears this, from ${formatTime(at)}", fontSize = 12.5.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                }
            }
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
            Spacer(Modifier.height(4.dp))
            Box(Modifier.size(5.dp).clip(CircleShape).background(if (on) cs.primary else Color.Transparent))
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
            colors = SliderDefaults.colors(inactiveTrackColor = cs.outlineVariant),
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
