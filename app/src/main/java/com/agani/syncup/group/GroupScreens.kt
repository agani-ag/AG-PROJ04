package com.agani.syncup.group

import android.Manifest
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Theaters
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.agani.syncup.downloads.Files
import com.agani.syncup.music.CardDivider
import com.agani.syncup.music.NoteBox
import com.agani.syncup.music.SectionHead
import com.agani.syncup.music.ToolTile
import com.agani.syncup.music.ToolTint
import com.agani.syncup.music.ToolTopBar
import com.agani.syncup.music.cardSlice
import com.agani.syncup.music.tintButton
import com.agani.syncup.party.Party
import com.agani.syncup.party.SongPicker
import com.agani.syncup.ui.theme.CardShape
import com.agani.syncup.ui.theme.appCard
import com.agani.syncup.ui.theme.dialogSurface
import com.agani.syncup.ui.theme.success
import com.agani.syncup.walkie.TalkMode
import com.agani.syncup.watchparty.VideoPickerSheet
import com.agani.syncup.watchparty.WatchParty
import com.google.android.gms.nearby.connection.BandwidthInfo
import kotlinx.coroutines.delay
import org.json.JSONObject

// ============================================================================ colours
/** The group's own colour (its Tools tile, the group bar, Switch). */
internal val GroupTint = ToolTint.INDIGO

/** The talk button's teal (the same in Light and Dark, with white on it). */
private val WalkieTeal = Color(0xFF0F766E)

/** Each mode's icon, and its tool's colour: Music party pink, Watch together rose, Walkie talkie teal, Share files blue. */
internal fun GroupMode.icon(): ImageVector = when (this) {
    GroupMode.MUSIC -> Icons.Rounded.MusicNote
    GroupMode.WATCH -> Icons.Rounded.Theaters
    GroupMode.WALKIE -> Icons.Rounded.RecordVoiceOver
    GroupMode.SHARE -> Icons.Rounded.SwapHoriz
}

internal fun GroupMode.tint(): ToolTint = when (this) {
    GroupMode.MUSIC -> ToolTint.PINK
    GroupMode.WATCH -> ToolTint.ROSE
    GroupMode.WALKIE -> ToolTint.TEAL
    GroupMode.SHARE -> ToolTint.BLUE
}

private fun GroupMode.blurb(): String = when (this) {
    GroupMode.MUSIC -> "Your songs on every phone"
    GroupMode.WATCH -> "A video on every phone, in sync"
    GroupMode.WALKIE -> "Talk to everyone in the group"
    GroupMode.SHARE -> "Send photos & files to the group"
}

@Composable
private fun ModeTile(mode: GroupMode, size: Int = 42) = ToolTile(mode.icon(), mode.tint(), size.dp)

// ============================================================================ the group bar
/**
 * On top of every mode while in a group: the group, how many phones and how fast their links are,
 * and — for the host — Switch. Tap it for the people. Holds its own sheets.
 */
@Composable
fun GroupBar(modifier: Modifier = Modifier) {
    if (!GroupLink.inGroup) return
    val sheets = rememberGroupSheets()
    val n = GroupLink.people.size
    val slow = GroupLink.slowPeople
    val line = buildString {
        append(if (GroupLink.isHost) "You're the host · " else "${GroupLink.hostName} hosts · ")
        append("$n phone${if (n == 1) "" else "s"}")
        when {
            GroupLink.phase == GroupPhase.RECONNECTING -> append(" · reconnecting…")
            !GroupLink.isHost && GroupLink.linkQuality == BandwidthInfo.Quality.LOW -> append(" · you're on slow Bluetooth")
            slow.isNotEmpty() -> append(" · ${slow.size} on slow Bluetooth")
            n > 1 -> append(" · all on Wi-Fi")
        }
    }
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(22.dp)).background(GroupTint.tile)
            .clickable { sheets.people = true }.padding(start = 14.dp, end = 9.dp, top = 9.dp, bottom = 9.dp),
    ) {
        Icon(Icons.Rounded.Groups, null, tint = GroupTint.icon, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(GroupLink.groupName, color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (GroupLink.isHost) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp).height(34.dp).clip(RoundedCornerShape(50)).background(GroupTint.icon)
                    .clickable(onClickLabel = "Switch the group") { sheets.switching = true }.padding(horizontal = 14.dp),
            ) {
                Icon(Icons.Rounded.SwapHoriz, null, tint = GroupTint.onSolid, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Switch", color = GroupTint.onSolid, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
    GroupSheets(sheets)
}

/** Which of the group's sheets are open: the people, Switch, and picking songs / a video for a switch. */
class GroupSheetState {
    var people by mutableStateOf(false)
    var switching by mutableStateOf(false)
    var songs by mutableStateOf(false)
    var video by mutableStateOf(false)
}

@Composable
fun rememberGroupSheets(): GroupSheetState = remember { GroupSheetState() }

/** The group's sheets (open them through [state]) — on the group bar, and the Watch Party player's chip. */
@Composable
fun GroupSheets(state: GroupSheetState) {
    if (state.people) GroupPeopleSheet(onDismiss = { state.people = false }, onSwitch = { state.people = false; state.switching = true })
    if (state.switching) {
        GroupSwitchSheet(
            onDismiss = { state.switching = false },
            onPickSongs = { state.switching = false; state.songs = true },
            onPickVideo = { state.switching = false; state.video = true },
        )
    }
    if (state.songs) {
        SongPicker(multiple = true, onDismiss = { state.songs = false }, onPicked = { songs ->
            state.songs = false
            GroupLink.switchTo(GroupMode.MUSIC)
            Party.addSongs(songs)
        })
    }
    if (state.video) {
        VideoPickerSheet(title = "Watch together", onPick = { src ->
            state.video = false
            // The group moves to the video; this phone's player opens (from the Watch Party tab).
            WatchParty.startHosting(GroupLink.groupName, src) { WatchParty.wantsPlayer = true }
        }, onDismiss = { state.video = false })
    }
}

// ============================================================================ people
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupPeopleSheet(onDismiss: () -> Unit, onSwitch: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var confirmEnd by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = cs.surfaceContainerLowest,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text(GroupLink.groupName, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Text(
                "Now: ${GroupLink.mode.label} · ${GroupLink.people.size} of ${GroupLink.MAX_PHONES} phones",
                fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            GroupLink.people.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(GroupTint.tile), contentAlignment = Alignment.Center) {
                        Text(p.name.take(1).uppercase(), color = GroupTint.icon, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            p.name + when {
                                p.host && p.name == GroupLink.myName -> " (you, host)"
                                p.host -> " (host)"
                                p.name == GroupLink.myName -> " (you)"
                                else -> ""
                            },
                            fontSize = 15.sp, fontWeight = FontWeight.Medium,
                        )
                        if (!p.host) LinkChip(p.quality)
                    }
                    if (GroupLink.isHost && !p.host) TextButton(onClick = { GroupLink.remove(p.name) }) { Text("Remove", color = cs.error) }
                }
            }
            NoteBox(
                Icons.Rounded.Groups, "New phones join with the code + Accept. They land in whatever the group is doing now.",
                Modifier.padding(top = 8.dp),
            )
            if (GroupLink.isHost) {
                Button(
                    onClick = onSwitch, colors = tintButton(GroupTint),
                    modifier = Modifier.padding(top = 16.dp).fillMaxWidth().height(48.dp),
                ) {
                    Icon(Icons.Rounded.SwapHoriz, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Switch the group")
                }
                OutlinedButton(onClick = { confirmEnd = true }, modifier = Modifier.padding(top = 10.dp).fillMaxWidth().height(48.dp)) {
                    Text("End the group for everyone", color = cs.error)
                }
            } else {
                OutlinedButton(onClick = { onDismiss(); GroupLink.leave() }, modifier = Modifier.padding(top = 16.dp).fillMaxWidth().height(48.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.Logout, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Leave group")
                }
            }
        }
    }
    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End the group?") },
            text = { Text("Everything stops on every phone, and everyone leaves the group.") },
            confirmButton = { TextButton(onClick = { confirmEnd = false; onDismiss(); GroupLink.end() }) { Text("End group", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep going") } },
            containerColor = cs.dialogSurface,
        )
    }
}

@Composable
private fun LinkChip(quality: Int) {
    val (icon, text, tint) = when (quality) {
        BandwidthInfo.Quality.LOW -> Triple(Icons.Rounded.Bluetooth, "Bluetooth · slow", ToolTint.AMBER)
        BandwidthInfo.Quality.MEDIUM, BandwidthInfo.Quality.HIGH -> Triple(Icons.Rounded.Wifi, "Wi-Fi", ToolTint.GREEN)
        else -> Triple(Icons.Rounded.Wifi, "Connecting…", ToolTint.SLATE)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 3.dp).clip(RoundedCornerShape(50)).background(tint.tile).padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Icon(icon, null, tint = tint.icon, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = tint.icon, fontSize = 11.5.sp)
    }
}

// ============================================================================ switching
/** Host: "Switch the group to…" — the four modes, then what the new one needs. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GroupSwitchSheet(onDismiss: () -> Unit, onPickSongs: () -> Unit, onPickVideo: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var step by remember { mutableStateOf<GroupMode?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = cs.surfaceContainerLowest,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            when (val s = step) {
                null -> {
                    Text("Switch the group to…", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Text("Every phone in the group changes too. Nobody has to join again.", fontSize = 13.sp, lineHeight = 18.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                    val modes = GroupMode.entries
                    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        modes.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                pair.forEach { m ->
                                    val now = m == GroupLink.mode
                                    val shape = RoundedCornerShape(22.dp)
                                    Column(
                                        Modifier.weight(1f).height(132.dp).clip(shape)
                                            .background(if (now) GroupTint.tile else cs.surfaceContainerLowest)
                                            .border(if (now) 2.dp else 1.dp, if (now) GroupTint.icon else cs.outlineVariant, shape)
                                            .clickable {
                                                when {
                                                    now -> onDismiss()
                                                    m == GroupMode.SHARE -> {
                                                        GroupLink.switchTo(GroupMode.SHARE)
                                                        onDismiss()
                                                    }
                                                    else -> step = m
                                                }
                                            }
                                            .padding(if (now) 13.dp else 14.dp),
                                    ) {
                                        Row(verticalAlignment = Alignment.Top) {
                                            ModeTile(m)
                                            Spacer(Modifier.weight(1f))
                                            if (now) {
                                                Text(
                                                    "Now", color = GroupTint.onSolid, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                                                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(GroupTint.icon).padding(horizontal = 9.dp, vertical = 3.dp),
                                                )
                                            }
                                        }
                                        Spacer(Modifier.weight(1f))
                                        Text(m.label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                        Text(m.blurb(), fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant, maxLines = 2)
                                    }
                                }
                            }
                        }
                    }
                    resumeLine()?.let { Note(GroupLink.mode.icon(), it) }
                }
                GroupMode.MUSIC -> {
                    SetupTitle(s, "Pick what the group hears.")
                    val songs = Party.queue.size
                    if (songs > 0) {
                        Option(Icons.Rounded.MusicNote, "Carry on the music", "${Party.current?.title ?: "The queue"} · $songs song${if (songs == 1) "" else "s"}", s) {
                            GroupLink.switchTo(GroupMode.MUSIC)
                            onDismiss()
                        }
                    }
                    Option(Icons.Rounded.Add, "Pick songs from this phone", "They play on every phone, in sync", s) { onPickSongs() }
                    BackRow { step = null }
                }
                GroupMode.WATCH -> {
                    SetupTitle(s, "Pick what the group watches.")
                    WatchParty.current?.let { v ->
                        Option(Icons.Rounded.Theaters, "Carry on “${v.title}”", "From ${formatMs(WatchParty.posMs)}", s) {
                            GroupLink.switchTo(GroupMode.WATCH)
                            onDismiss()
                        }
                    }
                    Option(Icons.Rounded.Add, "Pick a video", "From this phone, or a link", s) { onPickVideo() }
                    if (GroupLink.slowPeople.isNotEmpty()) {
                        Note(
                            Icons.Rounded.Bluetooth,
                            "${GroupLink.slowPeople.joinToString(", ")} ${if (GroupLink.slowPeople.size == 1) "is" else "are"} on a slow Bluetooth link — a video from this phone may stutter there. A video link works better.",
                            warn = true,
                        )
                    }
                    BackRow { step = null }
                }
                GroupMode.WALKIE -> {
                    SetupTitle(s, "How does the group talk?")
                    Option(Icons.Rounded.PanTool, "Hold to talk", "One at a time — hold the button, up to a minute", s) {
                        GroupLink.switchTo(GroupMode.WALKIE, JSONObject().put("talk", TalkMode.HOLD.key))
                        onDismiss()
                    }
                    Option(Icons.Rounded.Mic, "Open talk", "Hands-free — everyone's voice goes out when they speak", s) {
                        GroupLink.switchTo(GroupMode.WALKIE, JSONObject().put("talk", TalkMode.OPEN.key))
                        onDismiss()
                    }
                    BackRow { step = null }
                }
                GroupMode.SHARE -> Unit
            }
        }
    }
}

/** What switching away keeps ("the music queue is kept…"). */
@Composable
private fun resumeLine(): String? = when (GroupLink.mode) {
    GroupMode.MUSIC -> Party.current?.let { "The music is kept. Switch back to Music party later and it carries on from “${it.title}”." }
    GroupMode.WATCH -> WatchParty.current?.let { "The video is kept. Switch back to Watch together later and it carries on from ${formatMs(WatchParty.positionMs())}." }
    else -> null
}

@Composable
private fun SetupTitle(mode: GroupMode, sub: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ModeTile(mode)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(mode.label, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Text(sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(8.dp))
}

/** One choice after picking a mode: an outlined card with the mode's soft tile. */
@Composable
private fun Option(icon: ImageVector, title: String, sub: String, mode: GroupMode, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(shape).border(1.dp, cs.outlineVariant, shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        ToolTile(icon, mode.tint(), 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 2)
        }
        Icon(Icons.Rounded.ArrowForward, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

/** The grey note (what's kept), or a soft amber warning. */
@Composable
private fun Note(icon: ImageVector, text: String, warn: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(if (warn) ToolTint.AMBER.tile else cs.surfaceContainer).padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Icon(icon, null, tint = if (warn) ToolTint.AMBER.icon else cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 12.5.sp, lineHeight = 17.sp, color = if (warn) cs.onSurface else cs.onSurfaceVariant)
    }
}

@Composable
private fun BackRow(onBack: () -> Unit) {
    TextButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) { Text("Back to the modes") }
}

private fun formatMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

// ============================================================================ joining
/** The groups nearby, inside one card: each with what it's doing now, and Join in its colour. */
@Composable
fun GroupRoomsCard(rooms: List<GroupRoom>, modifier: Modifier = Modifier, onJoin: (GroupRoom) -> Unit) {
    Column(modifier.fillMaxWidth().appCard(CardShape)) {
        rooms.forEachIndexed { i, room ->
            key(room.endpointId) {
                if (i > 0) CardDivider()
                GroupRoomRow(room) { onJoin(room) }
            }
        }
    }
}

@Composable
private fun GroupRoomRow(room: GroupRoom, onJoin: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
    ) {
        ModeTile(room.mode)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(room.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(room.mode.label, room.title.takeIf { it.isNotBlank() }?.let { "“$it”" }, "${room.people} phone${if (room.people == 1) "" else "s"}").joinToString(" · "),
                fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onJoin, colors = tintButton(room.mode.tint()), contentPadding = PaddingValues(horizontal = 16.dp), modifier = Modifier.height(36.dp)) {
            Text(if (room.people >= GroupLink.MAX_PHONES) "Full" else "Join", fontSize = 13.5.sp)
        }
    }
}

/** Guest: asking to join — the code both phones show. */
@Composable
fun GroupJoiningView(tint: ToolTint) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(140.dp).clip(CircleShape).background(tint.tile), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Groups, null, tint = tint.icon, modifier = Modifier.size(48.dp))
        }
        Text(
            if (GroupLink.joinCode.isBlank()) "Connecting to ${GroupLink.groupName}…" else "Asking ${GroupLink.hostName} to let you in…",
            fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 20.dp),
        )
        if (GroupLink.joinCode.isNotBlank()) {
            CodeCard(GroupLink.joinCode, Modifier.padding(top = 28.dp))
            Text("Check ${GroupLink.hostName}'s phone shows the same code.", fontSize = 13.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = { GroupLink.cancelJoin() }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Cancel") }
        Spacer(Modifier.height(20.dp))
    }
}

/**
 * In a group that's doing something else right now (this page is [here]): the group bar, what it's
 * doing, Open — and for the host, a quick switch to this mode.
 */
@Composable
fun GroupElsewhere(here: GroupMode, onSwitchHere: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        GroupBar(Modifier.padding(top = 4.dp))
        Column(
            Modifier.padding(top = 12.dp).fillMaxWidth().appCard(CardShape).padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModeTile(GroupLink.mode)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("The group is in ${GroupLink.mode.label}", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    GroupLink.modeStatus()?.let { Text(it, fontSize = 13.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            Button(
                onClick = { GroupLink.requestOpen() }, colors = tintButton(GroupLink.mode.tint()),
                modifier = Modifier.padding(top = 14.dp).fillMaxWidth().height(46.dp),
            ) { Text("Open ${GroupLink.mode.label}") }
            if (GroupLink.isHost) {
                OutlinedButton(onClick = onSwitchHere, modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(46.dp)) {
                    Icon(Icons.Rounded.SwapHoriz, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Switch the group to ${here.label}")
                }
            } else {
                Text(
                    "${GroupLink.hostName} chooses what the group does. You'll come here when they switch to ${here.label}.",
                    fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

// ============================================================================ anywhere in the app
/**
 * Shown from the app's root, whatever screen is open: someone wants to join (host), the group ended,
 * "Ravi switched the group to…" (guests), and one-off notes.
 */
@Composable
fun GroupOverlays() {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    LaunchedEffect(GroupLink.notice) {
        GroupLink.notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            GroupLink.notice = null
        }
    }
    LaunchedEffect(GroupShare.notice) {
        GroupShare.notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            GroupShare.notice = null
        }
    }
    LaunchedEffect(GroupWalkie.notice) {
        GroupWalkie.notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            GroupWalkie.notice = null
        }
    }
    GroupJoinRequestDialog()
    // The notification's Switch (host): the Switch sheet, whatever screen is open.
    val sheets = rememberGroupSheets()
    LaunchedEffect(GroupLink.switchWanted) {
        if (GroupLink.switchWanted) {
            GroupLink.switchWanted = false
            if (GroupLink.isHost && GroupLink.inGroup) sheets.switching = true
        }
    }
    GroupSheets(sheets)
    GroupLink.ended?.let { message ->
        AlertDialog(
            onDismissRequest = { GroupLink.dismissEnded() },
            title = { Text("Group over") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { GroupLink.dismissEnded() }) { Text("OK") } },
            containerColor = cs.dialogSurface,
        )
    }
    GroupLink.switched?.let { (from, to) ->
        Dialog(onDismissRequest = {}) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clip(RoundedCornerShape(28.dp)).background(cs.dialogSurface).padding(28.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ModeTile(from, 60)
                    Icon(Icons.Rounded.ArrowForward, null, tint = GroupTint.icon, modifier = Modifier.padding(horizontal = 14.dp).size(28.dp))
                    ModeTile(to, 60)
                }
                Text("${GroupLink.hostName} switched the group to ${to.label}", fontSize = 19.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 20.dp))
                Text("You're still in ${GroupLink.groupName} — nothing to join.", fontSize = 13.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                LinearProgressIndicator(
                    color = GroupTint.icon, trackColor = cs.outlineVariant,
                    modifier = Modifier.padding(top = 18.dp).width(200.dp).clip(RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}

/** Host: someone wants to join (both phones show the code). */
@Composable
fun GroupJoinRequestDialog() {
    GroupLink.joinRequests.firstOrNull()?.let { r ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("${r.name} wants to join") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("Let them in only if their phone shows this code.", fontSize = 14.sp, textAlign = TextAlign.Center)
                    CodeCard(r.code, Modifier.padding(top = 16.dp), onCard = false)
                }
            },
            confirmButton = { Button(onClick = { GroupLink.acceptJoin(r) }, colors = tintButton(GroupTint)) { Text("Accept") } },
            dismissButton = { OutlinedButton(onClick = { GroupLink.declineJoin(r) }) { Text("Decline") } },
            containerColor = MaterialTheme.colorScheme.dialogSurface,
        )
    }
}

/**
 * The code both phones show: 30 sp, spaced out, on a white card — or on the field grey when it
 * already sits on a white dialog ([onCard] false).
 */
@Composable
private fun CodeCard(code: String, modifier: Modifier = Modifier, onCard: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Text(
        code, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 8.sp, color = cs.onSurface,
        modifier = modifier.then(if (onCard) Modifier.appCard(shape) else Modifier.clip(shape).background(cs.surfaceContainer))
            .padding(start = 36.dp, end = 28.dp, top = 12.dp, bottom = 12.dp),
    )
}

// ============================================================================ Walkie talkie & Share files
/** Tools → the group's Walkie talkie / Share files (Music party and Watch together have their own pages). */
@Composable
fun GroupModeScreen(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        ToolTopBar(if (GroupLink.inGroup) GroupLink.mode.label else "Group", onBack)
        when {
            !GroupLink.inGroup -> NoteBox(
                Icons.Rounded.Groups,
                "You're not in a group. Start one from Radio → Party or Video player → Watch Party, or join one there.",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            GroupLink.mode == GroupMode.WALKIE -> WalkieView()
            GroupLink.mode == GroupMode.SHARE -> ShareView()
            else -> GroupElsewhere(GroupLink.mode, onSwitchHere = {})
        }
    }
}

@Composable
private fun WalkieView() {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val tint = GroupMode.WALKIE.tint()
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) GroupWalkie.micGranted() else GroupWalkie.notice = "Walkie talkie needs the microphone to send your voice. You can still listen."
    }
    LaunchedEffect(GroupWalkie.needsMic) {
        if (GroupWalkie.needsMic) {
            GroupWalkie.needsMic = false
            askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    // SyncUp is open: the microphone can join the group's service now (talking on with the screen off).
    LaunchedEffect(Unit) { if (GroupWalkie.hasMic()) GroupService.useMic(context, true) }
    // Volume-key talk works only while this screen is on display; the volume keys set the voices' volume.
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        GroupWalkie.screenVisible = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) GroupWalkie.screenVisible = true
            if (e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) GroupWalkie.screenVisible = false
        }
        lifecycle.addObserver(observer)
        val activity = context as? android.app.Activity
        activity?.volumeControlStream = android.media.AudioManager.STREAM_VOICE_CALL
        onDispose {
            lifecycle.removeObserver(observer)
            GroupWalkie.screenVisible = false
            activity?.volumeControlStream = android.media.AudioManager.USE_DEFAULT_STREAM_TYPE
        }
    }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(GroupWalkie.floor) {
        while (GroupWalkie.floor != null) {
            now = SystemClock.elapsedRealtime()
            delay(200)
        }
    }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        item { GroupBar(Modifier.padding(top = 4.dp)) }
        item {
            val hold = GroupWalkie.talkMode == TalkMode.HOLD
            val floor = GroupWalkie.floor
            val mine = floor == GroupLink.myName
            val busy = floor != null && !mine
            val label = when {
                hold && mine -> "Talking…"
                hold && busy -> "$floor is talking"
                hold -> "Hold to talk"
                GroupWalkie.micOn && GroupWalkie.talking -> "Sending your voice"
                GroupWalkie.micOn -> "Mic on — just speak"
                else -> "Mic off"
            }
            val off = busy || (!hold && !GroupWalkie.micOn)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 22.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(228.dp)) {
                    if (hold && floor != null) {
                        val left = 1f - ((now - GroupWalkie.floorSince).toFloat() / GroupWalkie.TALK_LIMIT_MS).coerceIn(0f, 1f)
                        CircularProgressIndicator(
                            progress = { left }, color = if (mine) WalkieTeal else cs.outline, trackColor = cs.outlineVariant,
                            strokeWidth = 6.dp, modifier = Modifier.size(220.dp),
                        )
                    } else if (!off) {
                        // The soft teal ring around the button.
                        Box(Modifier.size(224.dp).clip(CircleShape).background(tint.tile))
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(196.dp).clip(CircleShape)
                            .background(if (off) cs.surfaceContainerHighest else WalkieTeal)
                            .then(
                                if (hold) Modifier.pointerInput(Unit) {
                                    detectTapGestures(onPress = {
                                        GroupWalkie.pressTalk()
                                        tryAwaitRelease()
                                        GroupWalkie.releaseTalk()
                                    })
                                } else Modifier.clickable { GroupWalkie.toggleMic() },
                            ),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                if (hold) Icons.Rounded.PanTool else if (GroupWalkie.micOn) Icons.Rounded.Mic else Icons.Rounded.MicOff, null,
                                tint = if (off) cs.onSurfaceVariant else Color.White, modifier = Modifier.size(50.dp),
                            )
                            Text(label, color = if (off) cs.onSurfaceVariant else Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
                Text(
                    when {
                        hold && GroupWalkie.volumeKeyTalk -> "Hold the button or a volume key to talk · a quick press still changes the volume"
                        hold -> "One at a time · up to a minute each"
                        else -> "Open talk · tap to turn your mic ${if (GroupWalkie.micOn) "off" else "on"}"
                    },
                    fontSize = 13.5.sp, lineHeight = 19.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, start = 12.dp, end = 12.dp),
                )
                // Loudspeaker (soft teal) or earpiece.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 12.dp).clip(RoundedCornerShape(50))
                        .background(if (GroupWalkie.loud) tint.tile else cs.surfaceContainer)
                        .clickable { GroupWalkie.toggleSpeaker() }.padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    val fg = if (GroupWalkie.loud) tint.icon else cs.onSurface
                    Icon(if (GroupWalkie.loud) Icons.AutoMirrored.Rounded.VolumeUp else Icons.Rounded.Phone, null, tint = fg, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (GroupWalkie.loud) "Loudspeaker" else "Earpiece", fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = fg)
                }
            }
        }
        if (GroupWalkie.talkMode == TalkMode.HOLD) {
            item {
                // Just for this person, this group: hold a volume key to talk.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 20.dp).fillMaxWidth().appCard(CardShape)
                        .clickable { GroupWalkie.changeVolumeKeyTalk(!GroupWalkie.volumeKeyTalk) }.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                ) {
                    ToolTile(Icons.Rounded.PhoneAndroid, tint)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Volume-key talk", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text("Hold a volume key to talk · just for you", fontSize = 12.5.sp, color = cs.onSurfaceVariant)
                    }
                    androidx.compose.material3.Switch(checked = GroupWalkie.volumeKeyTalk, onCheckedChange = { GroupWalkie.changeVolumeKeyTalk(it) })
                }
            }
        }
        item { SectionHead("In the group", count = GroupLink.people.size.toString()) }
        val people = GroupLink.people
        itemsIndexed(people, key = { _, p -> p.token }) { i, p ->
            val talking = p.name in GroupWalkie.speaking || p.name == GroupWalkie.floor
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.cardSlice(first = i == 0, last = i == people.lastIndex).fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(if (talking) WalkieTeal else tint.tile),
                    contentAlignment = Alignment.Center,
                ) { Text(p.name.take(1).uppercase(), color = if (talking) Color.White else tint.icon, fontWeight = FontWeight.Medium) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name + if (p.name == GroupLink.myName) " (you)" else "", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(if (talking) "talking" else if (p.host) "host" else "listening", fontSize = 12.5.sp, color = if (talking) tint.icon else cs.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShareView() {
    val cs = MaterialTheme.colorScheme
    val tint = GroupMode.SHARE.tint()
    val chosen = remember { mutableStateListOf<String>() } // empty = everyone
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) GroupShare.send(uris, chosen.toList())
    }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        item { GroupBar(Modifier.padding(top = 4.dp)) }
        item {
            Button(
                onClick = { pick.launch(arrayOf("*/*")) }, colors = tintButton(tint),
                modifier = Modifier.padding(top = 14.dp).fillMaxWidth().height(50.dp),
            ) {
                Icon(Icons.Rounded.Add, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (chosen.isEmpty()) "Send files to the group" else "Send files to ${chosen.joinToString(", ")}")
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                ToChip("Everyone", chosen.isEmpty()) { chosen.clear() }
                GroupShare.others().forEach { name ->
                    ToChip(name, name in chosen) { if (name in chosen) chosen.remove(name) else chosen.add(name) }
                }
            }
        }
        val received = GroupShare.received
        if (received.isNotEmpty()) {
            item {
                SectionHead(
                    "Received",
                    action = if (received.any { it.state == GroupFileState.ARRIVED }) {
                        { TextButton(onClick = { GroupShare.saveAll() }, contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.height(32.dp)) { Text("Save all") } }
                    } else null,
                )
            }
            itemsIndexed(received, key = { _, f -> "r${f.key}" }) { i, f -> FileRow(f, Modifier.cardSlice(first = i == 0, last = i == received.lastIndex)) }
        }
        val sent = GroupShare.sent
        if (sent.isNotEmpty()) {
            item { SectionHead("Sent") }
            itemsIndexed(sent, key = { _, f -> "s${f.key}" }) { i, f -> FileRow(f, Modifier.cardSlice(first = i == 0, last = i == sent.lastIndex)) }
        }
        if (sent.isEmpty() && received.isEmpty()) {
            item {
                NoteBox(
                    Icons.Rounded.InsertDriveFile,
                    "Files you send go to everyone in the group (or just the people you pick). Files sent to you wait here until you tap Save — then they're in Download / SyncUp.",
                    Modifier.padding(top = 18.dp),
                )
            }
        }
    }
}

@Composable
private fun ToChip(label: String, on: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(34.dp).clip(shape)
            .then(if (on) Modifier.background(cs.primaryContainer) else Modifier.background(cs.surfaceContainerLowest).border(1.dp, cs.outlineVariant, shape))
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
    ) {
        if (on) {
            Icon(Icons.Rounded.Check, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, fontSize = 13.5.sp, color = if (on) cs.onPrimaryContainer else cs.onSurface)
    }
}

@Composable
private fun FileRow(f: GroupFile, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val tint = GroupMode.SHARE.tint()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
    ) {
        ToolTile(Icons.Rounded.InsertDriveFile, tint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val size = if (f.size > 0) Files.size(f.size) else ""
            val line = if (f.incoming) {
                listOf("from ${f.from}", size).filter { it.isNotBlank() }.joinToString(" · ")
            } else {
                listOf("to ${f.to}", size, if (f.expected > 1) "${f.gotIt} of ${f.expected} got it" else "").filter { it.isNotBlank() }.joinToString(" · ")
            }
            Text(line, fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (f.state == GroupFileState.MOVING && f.size > 0) {
                LinearProgressIndicator(
                    progress = { (f.done.toFloat() / f.size).coerceIn(0f, 1f) }, color = tint.icon, trackColor = cs.outlineVariant,
                    modifier = Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        when (f.state) {
            GroupFileState.ARRIVED -> TextButton(onClick = { GroupShare.save(f) }) { Text("Save", color = tint.icon) }
            GroupFileState.SAVING -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            GroupFileState.SAVED, GroupFileState.SENT -> Icon(Icons.Rounded.CheckCircle, if (f.state == GroupFileState.SAVED) "Saved" else "Sent", tint = cs.success, modifier = Modifier.size(22.dp))
            GroupFileState.FAILED -> Text("Failed", fontSize = 12.sp, color = cs.error)
            GroupFileState.MOVING -> Unit
        }
    }
}
