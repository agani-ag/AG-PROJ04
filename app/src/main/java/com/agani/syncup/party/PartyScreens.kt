package com.agani.syncup.party

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.EmptyState
import com.agani.syncup.browser.ui.StepSlider
import com.agani.syncup.group.GroupBar
import com.agani.syncup.group.GroupElsewhere
import com.agani.syncup.group.GroupJoiningView
import com.agani.syncup.group.GroupLink
import com.agani.syncup.group.GroupMode
import com.agani.syncup.group.GroupPhase
import com.agani.syncup.group.GroupRoom
import com.agani.syncup.group.GroupRoomsCard
import com.agani.syncup.music.LocalPlayer
import com.agani.syncup.music.LocalSongs
import com.agani.syncup.music.NoteBox
import com.agani.syncup.music.SectionHead
import com.agani.syncup.music.Song
import com.agani.syncup.music.ToolTile
import com.agani.syncup.music.ToolTint
import com.agani.syncup.music.cardSlice
import com.agani.syncup.music.tintButton
import com.agani.syncup.quickshare.QsPhase
import com.agani.syncup.quickshare.QuickShare
import com.agani.syncup.quickshare.Radios
import com.agani.syncup.quickshare.RadiosDialog
import com.agani.syncup.ui.theme.CardShape
import com.agani.syncup.ui.theme.PillTabs
import com.agani.syncup.ui.theme.appCard
import com.agani.syncup.ui.theme.dialogSurface
import com.agani.syncup.ui.theme.isLight
import com.google.android.gms.nearby.connection.BandwidthInfo
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Party's colour: pink tiles and buttons; the now-playing card is flat Party pink with white on it. */
private val PartyTint = ToolTint.PINK

@Composable
private fun partyPink(): Color = if (MaterialTheme.colorScheme.isLight) Color(0xFFBE185D) else Color(0xFF9D174D)

/**
 * Tools → Radio → Party: start a party or join one nearby; then the host's DJ screen or the
 * guest's listening screen. See [Party].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartyTab() {
    val context = LocalContext.current
    remember { Party.init(context) }
    val musicPlayer = LocalPlayer.current
    var naming by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var waitingForRadios by remember { mutableStateOf<(() -> Unit)?>(null) }
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    var permissionNote by remember { mutableStateOf(false) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val next = afterPermission
        afterPermission = null
        if (result.values.all { it }) {
            permissionNote = false
            next?.invoke()
        } else {
            permissionNote = true
        }
    }

    /** Nearby permissions, then Bluetooth / Wi-Fi switched on, then [action] — like SyncUp Share. */
    fun ready(action: () -> Unit) {
        if (QuickShare.phase != QsPhase.IDLE) {
            Toast.makeText(context, "Finish SyncUp Share first", Toast.LENGTH_SHORT).show()
            return
        }
        if (com.agani.syncup.walkie.Walkie.active) {
            Toast.makeText(context, "Leave Walkie Talkie first", Toast.LENGTH_SHORT).show()
            return
        }
        val next = { if (Radios.missing(context).isEmpty()) action() else waitingForRadios = action }
        val missing = QuickShare.missingPermissions(receiving = false)
        if (missing.isEmpty()) next() else {
            afterPermission = next
            permissions.launch(missing.toTypedArray())
        }
    }

    // One-off messages from the party (declined, a song added…).
    LaunchedEffect(Party.notice) {
        Party.notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            Party.notice = null
        }
    }

    // Look for groups while this page is open and this phone isn't in one.
    val canBrowse = QuickShare.missingPermissions(receiving = false).isEmpty() && Radios.missing(context).isEmpty()
    var browsing by remember { mutableStateOf(false) }
    DisposableEffect(GroupLink.phase, canBrowse, browsing) {
        if (GroupLink.phase == GroupPhase.IDLE && (canBrowse || browsing) && QuickShare.phase == QsPhase.IDLE) GroupLink.startBrowsing()
        onDispose { GroupLink.stopBrowsing() }
    }
    var switchHere by remember { mutableStateOf(false) }

    when {
        Party.role == PartyRole.HOST -> HostScreen(onAddSongs = { picking = true })
        GroupLink.phase == GroupPhase.JOINING -> GroupJoiningView(ToolTint.PINK)
        Party.role == PartyRole.GUEST -> GuestScreen(onAddSong = { picking = true })
        // In a group doing something else: back to music from here (the queue carries on, else pick songs).
        GroupLink.inGroup -> GroupElsewhere(GroupMode.MUSIC, onSwitchHere = {
            if (Party.queue.isNotEmpty()) GroupLink.switchTo(GroupMode.MUSIC) else switchHere = true
        })
        else -> PartyHome(
            canBrowse = canBrowse,
            permissionNote = permissionNote,
            onStart = { ready { naming = true } },
            onLook = { ready { browsing = true; GroupLink.startBrowsing() } },
            onJoin = { room ->
                ready {
                    if (room.mode == GroupMode.MUSIC) musicPlayer?.stop()
                    GroupLink.join(room)
                }
            },
        )
    }
    if (switchHere) {
        SongPicker(multiple = true, onDismiss = { switchHere = false }, onPicked = { songs ->
            switchHere = false
            musicPlayer?.stop()
            GroupLink.switchTo(GroupMode.MUSIC)
            Party.addSongs(songs)
        })
    }

    if (naming) {
        var name by remember { mutableStateOf("${QuickShare.myName}'s Party") }
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.dialogSurface,
            onDismissRequest = { naming = false },
            title = { Text("Start a party") },
            text = {
                Column {
                    Text("Phones nearby see this name.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = name, onValueChange = { name = it.take(30) }, singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    naming = false
                    musicPlayer?.stop()
                    Party.startHosting(name)
                    picking = true
                }) { Text("Start") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }

    waitingForRadios?.let { pending ->
        RadiosDialog(
            receiving = false,
            onReady = {
                waitingForRadios = null
                pending()
            },
            onCancel = { waitingForRadios = null },
        )
    }

    if (picking) {
        SongPicker(
            multiple = Party.role == PartyRole.HOST,
            onDismiss = { picking = false },
            onPicked = { songs ->
                picking = false
                if (Party.role == PartyRole.HOST) Party.addSongs(songs) else songs.firstOrNull()?.let(Party::requestAdd)
            },
        )
    }

    // Host: a guest wants to add a song ("Ask me").
    Party.addRequests.firstOrNull()?.let { r ->
        ModalBottomSheet(onDismissRequest = { Party.declineAdd(r) }, containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Letter(r.guest)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${r.guest} wants to add a song", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text("“${r.title}” · ${formatMs(r.durationMs)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 18.dp)) {
                    OutlinedButton(onClick = { Party.declineAdd(r) }, modifier = Modifier.weight(1f).height(46.dp)) { Text("No") }
                    Button(onClick = { Party.acceptAdd(r) }, colors = tintButton(PartyTint), modifier = Modifier.weight(1f).height(46.dp)) { Text("Add to queue") }
                }
            }
        }
    }
}

// ============================================================================ home
@Composable
private fun PartyHome(
    canBrowse: Boolean,
    permissionNote: Boolean,
    onStart: () -> Unit,
    onLook: () -> Unit,
    onJoin: (GroupRoom) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp)) {
        item(key = "actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                ActionCard(Icons.Rounded.MusicNote, "Start a party", "Play your songs on everyone's phone", PartyTint, Modifier.weight(1f), onStart)
                ActionCard(Icons.AutoMirrored.Rounded.Login, "Join a party", "Listen with friends nearby", ToolTint.BLUE, Modifier.weight(1f), onLook)
            }
        }
        if (permissionNote) {
            item(key = "permission") {
                Text(
                    "Party needs the Nearby devices permission to find other phones. Turn it on in Settings → Apps → SyncUp → Permissions.",
                    fontSize = 13.sp, lineHeight = 18.sp, color = cs.onErrorContainer,
                    modifier = Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.errorContainer).padding(14.dp),
                )
            }
        }
        item(key = "roomsHead") { SectionHead("Groups nearby", count = GroupLink.rooms.size.takeIf { it > 0 }?.toString()) }
        if (GroupLink.rooms.isEmpty()) {
            item(key = "roomsNone") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().appCard(CardShape).padding(16.dp),
                ) {
                    if (canBrowse) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = PartyTint.icon, trackColor = cs.outlineVariant)
                        Spacer(Modifier.width(14.dp))
                        Text("Looking for groups… Ask your friend to start a party in Radio → Party.", fontSize = 13.5.sp, lineHeight = 19.sp, color = cs.onSurfaceVariant)
                    } else {
                        Text("Tap Join a party to look for parties nearby.", fontSize = 13.5.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                    }
                }
            }
        } else {
            item(key = "rooms") { GroupRoomsCard(GroupLink.rooms) { onJoin(it) } }
        }
        item(key = "about") {
            NoteBox(
                Icons.Rounded.WifiTethering,
                "Every phone plays the same moment — a room of phones sounds like one speaker. No internet needed. " +
                    "Up to 8 phones in a group — the host can switch everyone to a video, Walkie talkie or sharing files.",
                Modifier.padding(top = 14.dp),
            )
        }
    }
}

// ============================================================================ host
@Composable
private fun HostScreen(onAddSongs: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var confirmEnd by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp)) {
        item(key = "group") { GroupBar(Modifier.padding(bottom = 12.dp)) }
        if (Party.localPaused) item(key = "playHere") { PlayHereRow() }
        item(key = "now") { NowPlayingCard(host = true) }
        item(key = "people") { PeopleRow() }
        item(key = "volume") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 8.dp)) {
                Icon(Icons.Rounded.VolumeUp, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("Everyone sets their own volume.", fontSize = 12.5.sp, color = cs.onSurfaceVariant)
            }
        }
        item(key = "nextHead") {
            SectionHead("Up next", action = {
                FilledTonalButton(onClick = onAddSongs, contentPadding = PaddingValues(horizontal = 16.dp), modifier = Modifier.height(36.dp)) {
                    Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add songs", fontSize = 13.5.sp)
                }
            })
        }
        val next = Party.upNext
        if (next.isEmpty()) {
            item(key = "nextNone") { Text("Nothing queued. Add songs from this phone.", fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp)) }
        }
        itemsIndexed(next, key = { _, t -> t.id }) { i, t ->
            QueueRow(t, i + 1, Modifier.cardSlice(first = i == 0, last = i == next.lastIndex), onRemove = { Party.removeFromQueue(t.id) })
        }
        item(key = "guestAdds") {
            SubLabel("Guests can add songs")
            PillTabs(
                listOf("Off", "Ask me", "On"),
                selected = Party.guestAdds.ordinal,
                onSelect = { Party.changeGuestAdds(GuestAdds.entries[it]) },
            )
        }
        item(key = "end") {
            OutlinedButton(
                onClick = { confirmEnd = true },
                modifier = Modifier.padding(top = 26.dp).fillMaxWidth().height(48.dp),
            ) { Text("End the group", color = cs.error) }
        }
    }
    if (confirmEnd) {
        AlertDialog(
            containerColor = cs.dialogSurface,
            onDismissRequest = { confirmEnd = false },
            title = { Text("End the group?") },
            text = { Text("The music stops on every phone, and everyone leaves the group.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnd = false
                    Party.end()
                }) { Text("End group", color = cs.error) }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep going") } },
        )
    }
}

// ============================================================================ guest
@Composable
private fun GuestScreen(onAddSong: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp)) {
        item(key = "group") { GroupBar(Modifier.padding(bottom = 12.dp)) }
        if (Party.phase == PartyPhase.RECONNECTING) {
            item(key = "reconnecting") {
                Banner(Icons.Rounded.Sync, "Reconnecting to ${Party.hostName}…", "The music keeps playing from what this phone already has.")
            }
        }
        if (Party.localPaused) item(key = "playHere") { PlayHereRow() }
        item(key = "now") { NowPlayingCard(host = false) }
        item(key = "people") { PeopleRow() }
        item(key = "volume") { VolumeRow(context) }
        item(key = "sync") { SyncAdjustRow(context) }
        val next = Party.upNext
        item(key = "nextHead") { SectionHead("Up next") }
        if (next.isEmpty()) {
            item(key = "nextNone") { Text("Nothing queued yet.", fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp)) }
        }
        itemsIndexed(next, key = { _, t -> t.id }) { i, t ->
            QueueRow(t, i + 1, Modifier.cardSlice(first = i == 0, last = i == next.lastIndex), onRemove = null)
        }
        item(key = "buttons") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 24.dp)) {
                if (Party.canAdd) {
                    FilledTonalButton(onClick = onAddSong, modifier = Modifier.weight(1f).height(48.dp)) {
                        Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add a song")
                    }
                }
                OutlinedButton(onClick = { Party.leave() }, modifier = Modifier.weight(1f).height(48.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.Logout, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Leave")
                }
            }
        }
    }
}

/** This phone's sound stopped (another app took over); the party went on. */
@Composable
private fun PlayHereRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 12.dp).fillMaxWidth().appCard(CardShape)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Text("The party's sound stopped on this phone.", fontSize = 13.5.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = { Party.resumeHere() }) { Text("Play here", color = PartyTint.icon) }
    }
}

/** A small label over a control ("My volume", "Guests can add songs"), with an optional icon and grey detail. */
@Composable
private fun SubLabel(text: String, icon: ImageVector? = null, detail: String? = null) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 18.dp, bottom = 6.dp)) {
        if (icon != null) {
            Icon(icon, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
        if (detail != null) Text(detail, fontSize = 13.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun VolumeRow(context: Context) {
    val cs = MaterialTheme.colorScheme
    val am = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val max = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableFloatStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }
    // The volume keys change it too: follow them.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            volume = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
        }
    }
    Column {
        SubLabel("My volume", Icons.Rounded.VolumeUp)
        StepSlider(
            value = volume, range = 0f..max.toFloat(), step = 1f, steps = (max - 1).coerceAtLeast(0),
            label = "volume",
            onChange = {
                volume = it
                am.setStreamVolume(AudioManager.STREAM_MUSIC, it.toInt(), 0)
            },
            colors = SliderDefaults.colors(inactiveTrackColor = cs.outlineVariant),
        )
    }
}

@Composable
private fun SyncAdjustRow(context: Context) {
    val cs = MaterialTheme.colorScheme
    val bluetooth = remember {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
        }
    }
    var adjust by remember { mutableFloatStateOf(Party.syncAdjustMs.toFloat()) }
    Column {
        SubLabel(
            "Sync adjust", Icons.Rounded.Bluetooth,
            (if (bluetooth) " · Bluetooth speaker" else "") + " · ${if (adjust > 0) "+" else ""}${adjust.toInt()} ms",
        )
        StepSlider(
            value = adjust, range = -400f..400f, step = 10f,
            label = "sync adjust",
            // Whole 10 ms while sliding; saved when the finger lifts (or on each tap of − / +).
            onChange = { adjust = (it / 10f).roundToInt() * 10f },
            onChangeFinished = { Party.setSyncAdjust(adjust.toInt()) },
            colors = SliderDefaults.colors(inactiveTrackColor = cs.outlineVariant),
        )
        Text(
            "Bluetooth speakers and earbuds play a little late. If this phone sounds behind the others, tap + ; ahead, tap −.",
            fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp),
        )
    }
}

// ============================================================================ shared pieces
@Composable
private fun NowPlayingCard(host: Boolean) {
    val track = Party.current
    var position by remember { mutableLongStateOf(Party.positionMs()) }
    LaunchedEffect(track?.id, Party.paused) {
        while (true) {
            position = Party.positionMs()
            delay(500)
        }
    }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val pink = partyPink()
    Column(
        Modifier.fillMaxWidth().clip(CardShape).background(pink).padding(18.dp),
    ) {
        Text(
            Party.roomName + if (host) " · you're the host" else "",
            color = Color.White.copy(alpha = .85f), fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 12.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(58.dp).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = .18f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MusicNote, null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(track?.title ?: "Nothing playing", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val who = when {
                    track == null -> if (host) "Add songs to start" else "Waiting for ${Party.hostName}"
                    track.addedBy == Party.hostName -> if (host) "from this phone" else "${Party.hostName} is playing"
                    else -> "added by ${track.addedBy}"
                }
                Text(
                    listOfNotNull(track?.artist?.takeIf { it.isNotBlank() }, who).joinToString(" · "),
                    color = Color.White.copy(alpha = .88f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (track != null) {
            if (host) {
                Slider(
                    value = dragging ?: position.toFloat(),
                    valueRange = 0f..track.durationMs.coerceAtLeast(1).toFloat(),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { Party.seekTo(it.toLong()) }
                        dragging = null
                    },
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = .3f)),
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                Box(Modifier.padding(top = 18.dp, bottom = 8.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = .3f))) {
                    Box(Modifier.fillMaxWidth((position.toFloat() / track.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color.White))
                }
            }
            Row {
                Text(formatMs(position), color = Color.White.copy(alpha = .85f), fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(formatMs(track.durationMs), color = Color.White.copy(alpha = .85f), fontSize = 12.sp)
            }
        }
        if (host) {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                IconButton(onClick = { Party.previous() }, enabled = track != null) {
                    Icon(Icons.Rounded.SkipPrevious, "Previous", tint = Color.White.copy(alpha = if (track != null) 1f else .5f), modifier = Modifier.size(30.dp))
                }
                Box(
                    Modifier.size(58.dp).clip(CircleShape).background(Color.White).clickable(enabled = track != null) { Party.togglePause() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (Party.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (Party.paused) "Play" else "Pause", tint = pink, modifier = Modifier.size(32.dp))
                }
                IconButton(onClick = { Party.next() }, enabled = Party.upNext.isNotEmpty()) {
                    Icon(Icons.Rounded.SkipNext, "Next", tint = Color.White.copy(alpha = if (Party.upNext.isNotEmpty()) 1f else .5f), modifier = Modifier.size(30.dp))
                }
            }
        }
        SyncLine(host)
    }
}

@Composable
private fun SyncLine(host: Boolean) {
    val others = (Party.people.size - 1).coerceAtLeast(0)
    val err = Party.syncErrorMs
    // A guest not playing yet: how much of the song is here (it ticks up while it arrives).
    var percent by remember { mutableIntStateOf(0) }
    var timed by remember { mutableStateOf(false) }
    val waiting = !host && err == null && Party.current != null && !Party.paused
    LaunchedEffect(waiting, Party.current?.id) {
        while (waiting) {
            percent = Party.currentSongPercent()
            timed = Party.clockReady
            delay(1_000)
        }
    }
    val slow = Party.linkQuality == BandwidthInfo.Quality.LOW
    val (dot, text) = when {
        Party.current == null -> Color.White.copy(alpha = .5f) to (if (host) "Add songs to start" else "Waiting for ${Party.hostName} to play")
        Party.paused -> Color.White.copy(alpha = .5f) to "Paused"
        Party.phase == PartyPhase.RECONNECTING -> Color(0xFFFCD34D) to "Playing from what's on this phone"
        host -> Color(0xFF86EFAC) to if (others == 0) "Waiting for friends to join" else "Playing on ${others + 1} phones"
        err == null && !timed -> Color(0xFFFCD34D) to ("Setting the time with ${Party.hostName}'s phone…" + if (slow) " · slow link" else "")
        err == null -> Color(0xFFFCD34D) to ("Getting the song… $percent%" + if (slow) " · slow link" else "")
        abs(err) <= 15 -> Color(0xFF86EFAC) to "In sync with $others other phone${if (others == 1) "" else "s"}"
        else -> Color(0xFFFCD34D) to "Lining up… (${err.toInt()} ms)"
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(7.dp))
        Text(text, color = Color.White.copy(alpha = .92f), fontSize = 12.5.sp)
    }
    if (waiting && slow) {
        // Bluetooth only: songs crawl in. Wi-Fi (the same network, or just on and close by) is much faster.
        Text(
            "The phones are on a slow Bluetooth link. Put both phones on the same Wi-Fi, or keep Wi-Fi on and the phones close, for a faster link.",
            color = Color.White.copy(alpha = .8f), fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Who's at the party: overlapping round letters, then their names. */
@Composable
private fun PeopleRow() {
    val cs = MaterialTheme.colorScheme
    val shown = Party.people.take(5)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 14.dp)) {
        shown.forEachIndexed { i, name ->
            Box(
                Modifier.offset(x = (-9 * i).dp).size(32.dp).clip(CircleShape).background(cs.surface).padding(2.dp).clip(CircleShape).background(cs.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Text(name.take(1).uppercase(), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onPrimaryContainer) }
        }
        Text(
            Party.people.joinToString(", "),
            fontSize = 13.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.offset(x = (-9 * (shown.size - 1).coerceAtLeast(0)).dp + 8.dp),
        )
    }
}

/** A song in Up next: its place, title, who added it, and ✕ for the host. One slice of the queue's card. */
@Composable
private fun QueueRow(track: PartyTrack, n: Int, modifier: Modifier, onRemove: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().heightIn(min = 58.dp).padding(start = 16.dp, end = if (onRemove != null) 4.dp else 16.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Text("$n", fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.width(22.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(track.artist.takeIf { it.isNotBlank() }, "added by ${track.addedBy}").joinToString(" · "),
                fontSize = 12.5.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (onRemove != null) IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, "Remove", tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
    }
}

/** Start / Join: a square white card with the tool's tile on top and the words at the bottom. */
@Composable
private fun ActionCard(icon: ImageVector, title: String, subtitle: String, tint: ToolTint, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.heightIn(min = 150.dp).appCard(CardShape).clickable(onClick = onClick).padding(14.dp),
    ) {
        ToolTile(icon, tint)
        Spacer(Modifier.weight(1f).heightIn(min = 16.dp))
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(subtitle, fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
    }
}

/** Soft amber note (reconnecting). */
@Composable
private fun Banner(icon: ImageVector, title: String, text: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(ToolTint.AMBER.tile).padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Icon(icon, null, tint = ToolTint.AMBER.icon, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
            Text(text, fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun Letter(name: String) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(15.dp)).background(PartyTint.tile),
        contentAlignment = Alignment.Center,
    ) { Text(name.take(1).uppercase(), color = PartyTint.icon, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
}

private fun formatMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

// ============================================================================ song picker
/** Songs on this phone: the host picks several, a guest one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SongPicker(multiple: Boolean, onDismiss: () -> Unit, onPicked: (List<Song>) -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var granted by remember { mutableStateOf(LocalSongs.canRead(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var songs by remember { mutableStateOf<List<Song>?>(null) }
    var query by remember { mutableStateOf("") }
    val chosen = remember { mutableStateListOf<Song>() }
    LaunchedEffect(granted) {
        if (granted) songs = withContext(Dispatchers.IO) { runCatching { LocalSongs.load(context) }.getOrDefault(emptyList()) }
        else ask.launch(LocalSongs.permission)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = cs.surfaceContainerLowest) {
        Column(Modifier.fillMaxWidth().heightIn(min = 300.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp, end = 16.dp, bottom = 10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(if (multiple) "Add songs to the party" else "Add a song", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Text("From this phone", fontSize = 13.sp, color = cs.onSurfaceVariant)
                }
                if (multiple) {
                    Button(onClick = { onPicked(chosen.toList()) }, enabled = chosen.isNotEmpty(), colors = tintButton(PartyTint), modifier = Modifier.height(40.dp)) {
                        Text(if (chosen.isEmpty()) "Add" else "Add ${chosen.size}")
                    }
                }
            }
            val all = songs
            when {
                !granted -> EmptyState(
                    Icons.Rounded.LibraryMusic, "Allow access to your music",
                    "Party plays the songs on this phone. They're only sent to the phones in the party, for the party.",
                    action = "Allow access", onAction = { ask.launch(LocalSongs.permission) },
                    modifier = Modifier.heightIn(max = 420.dp),
                )
                all == null -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                all.isEmpty() -> EmptyState(Icons.Rounded.MusicNote, "No songs on this phone", "Songs you download or copy to this phone show up here", modifier = Modifier.heightIn(max = 360.dp))
                else -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(50)).background(cs.surfaceContainer).padding(horizontal = 16.dp),
                    ) {
                        Icon(Icons.Rounded.Search, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        BasicTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            textStyle = TextStyle(fontSize = 15.sp, color = cs.onSurface), cursorBrush = SolidColor(cs.primary),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (query.isEmpty()) Text("Search songs", color = cs.onSurfaceVariant, fontSize = 15.sp)
                                    inner()
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    val q = query.trim()
                    val list = remember(all, q) { if (q.isEmpty()) all else all.filter { it.title.contains(q, true) || it.artist.contains(q, true) } }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(list, key = { it.id }) { song ->
                            val on = song in chosen
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    if (!multiple) onPicked(listOf(song)) else if (on) chosen.remove(song) else chosen.add(song)
                                }.padding(horizontal = 20.dp, vertical = 8.dp),
                            ) {
                                if (multiple) {
                                    Checkbox(
                                        checked = on, onCheckedChange = { if (it) chosen.add(song) else chosen.remove(song) },
                                        colors = CheckboxDefaults.colors(checkedColor = PartyTint.icon, checkmarkColor = PartyTint.onSolid),
                                    )
                                } else {
                                    Icon(Icons.Rounded.MusicNote, null, tint = cs.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(song.title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOfNotNull(song.artist.takeIf { it.isNotBlank() && it != "<unknown>" }, formatMs(song.durationMs)).joinToString(" · "),
                                        fontSize = 12.5.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (on) Icon(Icons.Rounded.CheckCircle, null, tint = PartyTint.icon, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** For the Tools tile / menu: what the party is doing, or null. */
fun partyStatus(): String? = when {
    GroupLink.phase == GroupPhase.JOINING && GroupLink.mode == GroupMode.MUSIC -> "Joining ${GroupLink.groupName}"
    !Party.active -> null
    else -> "Party: ${Party.roomName} · ${Party.people.size} ${if (Party.people.size == 1) "person" else "people"}"
}

/** Icon for the Party tab. */
val PartyIcon: ImageVector get() = Icons.Rounded.Groups
