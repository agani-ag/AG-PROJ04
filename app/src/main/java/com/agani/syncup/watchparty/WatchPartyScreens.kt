package com.agani.syncup.watchparty

import com.agani.syncup.group.GroupBar
import com.agani.syncup.group.GroupElsewhere
import com.agani.syncup.group.GroupJoiningView
import com.agani.syncup.group.GroupLink
import com.agani.syncup.group.GroupMode
import com.agani.syncup.group.GroupPhase
import com.agani.syncup.group.GroupRoom
import com.agani.syncup.group.GroupRoomCard
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Theaters
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.downloads.DlState
import com.agani.syncup.downloads.Downloads
import com.agani.syncup.downloads.FileKind
import com.agani.syncup.downloads.Files
import com.agani.syncup.party.Party
import com.agani.syncup.quickshare.QsPhase
import com.agani.syncup.quickshare.QuickShare
import com.agani.syncup.quickshare.Radios
import com.agani.syncup.quickshare.RadiosDialog
import com.agani.syncup.ui.theme.isLight
import com.agani.syncup.video.WatchHistory
import com.agani.syncup.walkie.Walkie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Rose = Color(0xFFBE123C)
private val RoseLight = Color(0xFFFFE4E6)
private val RoseOn = Color(0xFF4C0519)

/**
 * Video player → Watch Party tab: start a Watch Party with a video, join one nearby, or (while in
 * one) go back to the player. [startWith] opens the start sheet with that video picked ("Watch
 * together" from the player or a video's ⋮).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchPartyTab(startWith: WatchSource?, onStartWithUsed: () -> Unit) {
    val context = LocalContext.current
    remember { WatchParty.init(context) }
    val cs = MaterialTheme.colorScheme
    var starting by remember { mutableStateOf<WatchSource?>(null) }
    var startOpen by remember { mutableStateOf(false) }
    var waitingForRadios by remember { mutableStateOf<(() -> Unit)?>(null) }
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    var permissionNote by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val next = afterPermission
        afterPermission = null
        if (QuickShare.missingPermissions(receiving = false).isEmpty()) {
            permissionNote = false
            next?.invoke()
        } else {
            permissionNote = true
        }
    }

    /** Nearby permissions, then Bluetooth / Wi-Fi on, then [action]. One nearby feature at a time. */
    fun ready(action: () -> Unit) {
        val busy = when {
            QuickShare.phase != QsPhase.IDLE -> "Finish SyncUp Share first"
            Walkie.active -> "Leave Walkie Talkie first"
            else -> null
        }
        if (busy != null) return Toast.makeText(context, busy, Toast.LENGTH_SHORT).show()
        val next = { if (Radios.missing(context).isEmpty()) action() else waitingForRadios = action }
        val missing = QuickShare.missingPermissions(receiving = false)
        if (missing.isEmpty()) next() else {
            afterPermission = next
            permissions.launch(missing.toTypedArray())
        }
    }

    // "Watch together": straight to the start sheet with that video — or, hosting a group, straight to watching it.
    LaunchedEffect(startWith) {
        if (startWith != null) {
            onStartWithUsed()
            when {
                GroupLink.inGroup && GroupLink.isHost -> WatchParty.startHosting(GroupLink.groupName, startWith) { WatchPartyActivity.open(context) }
                GroupLink.inGroup -> Toast.makeText(context, "You're in ${GroupLink.groupName} — ${GroupLink.hostName} chooses what to watch.", Toast.LENGTH_LONG).show()
                else -> ready {
                    starting = startWith
                    startOpen = true
                }
            }
        }
    }
    // The group just came to Watch together: the player opens by itself.
    LaunchedEffect(WatchParty.wantsPlayer) {
        if (WatchParty.wantsPlayer) {
            WatchParty.wantsPlayer = false
            WatchPartyActivity.open(context)
        }
    }
    LaunchedEffect(WatchParty.notice) {
        WatchParty.notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            WatchParty.notice = null
        }
    }
    val canBrowse = QuickShare.missingPermissions(receiving = false).isEmpty() && Radios.missing(context).isEmpty()
    var browsing by remember { mutableStateOf(false) }
    DisposableEffect(GroupLink.phase, canBrowse, browsing) {
        if (GroupLink.phase == GroupPhase.IDLE && (canBrowse || browsing) && QuickShare.phase == QsPhase.IDLE && !Walkie.active) GroupLink.startBrowsing()
        onDispose { GroupLink.stopBrowsing() }
    }
    var pickToSwitch by remember { mutableStateOf(false) }

    when {
        GroupLink.phase == GroupPhase.JOINING -> GroupJoiningView(Rose, RoseLight)
        WatchParty.active -> InPartyView(onOpen = { WatchPartyActivity.open(context) }, onEnd = { confirmEnd = true })
        // In a group doing something else: back to the video from here (carry on, else pick one).
        GroupLink.inGroup -> GroupElsewhere(GroupMode.WATCH, onSwitchHere = {
            if (WatchParty.current != null) GroupLink.switchTo(GroupMode.WATCH) else pickToSwitch = true
        })
        else -> HomeView(
            canBrowse = canBrowse,
            permissionNote = permissionNote,
            onStart = { ready { starting = null; startOpen = true } },
            onLook = { ready { browsing = true; GroupLink.startBrowsing() } },
            onJoin = { room -> ready { GroupLink.join(room) } },
        )
    }
    if (pickToSwitch) {
        VideoPickerSheet(title = "Watch together", onPick = { src ->
            pickToSwitch = false
            WatchParty.startHosting(GroupLink.groupName, src) { WatchPartyActivity.open(context) }
        }, onDismiss = { pickToSwitch = false })
    }

    if (startOpen) {
        StartSheet(
            preset = starting,
            onDismiss = { startOpen = false },
            onStart = { name, source ->
                startOpen = false
                WatchParty.startHosting(name, source) { WatchPartyActivity.open(context) }
            },
        )
    }
    waitingForRadios?.let { pending ->
        RadiosDialog(receiving = false, onReady = { waitingForRadios = null; pending() }, onCancel = { waitingForRadios = null })
    }
    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text(if (WatchParty.isHost) "End the group?" else "Leave the group?") },
            text = { Text(if (WatchParty.isHost) "The video stops on every phone, and everyone leaves the group." else "The others keep watching.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnd = false
                    if (WatchParty.isHost) WatchParty.end() else WatchParty.leave()
                }) { Text(if (WatchParty.isHost) "End group" else "Leave", color = cs.error) }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Cancel") } },
        )
    }
}

// ============================================================================ home
@Composable
private fun HomeView(canBrowse: Boolean, permissionNote: Boolean, onStart: () -> Unit, onLook: () -> Unit, onJoin: (GroupRoom) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val light = cs.isLight
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                ActionCard(Icons.Rounded.Theaters, "Start a Watch Party", "Play a video on everyone's phone", if (light) Rose else Color(0xFFFDA4AF), if (light) RoseLight else Color(0xFF4C0519), Modifier.weight(1f), onStart)
                ActionCard(Icons.AutoMirrored.Rounded.Login, "Join a Watch Party", "Watch with friends nearby", cs.primary, cs.primaryContainer, Modifier.weight(1f), onLook)
            }
        }
        if (permissionNote) {
            item {
                Text(
                    "Watch Party needs the Nearby devices permission to find other phones. Turn it on in Settings → Apps → SyncUp → Permissions.",
                    fontSize = 13.sp, lineHeight = 18.sp, color = cs.onErrorContainer,
                    modifier = Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.errorContainer).padding(14.dp),
                )
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 22.dp, bottom = 8.dp)) {
                Text("Groups nearby", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                if (GroupLink.rooms.isNotEmpty()) Text("  · ${GroupLink.rooms.size}", fontSize = 14.sp, color = cs.onSurfaceVariant)
            }
        }
        if (GroupLink.rooms.isEmpty()) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainerLow).padding(16.dp),
                ) {
                    if (canBrowse) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(14.dp))
                        Text("Looking for groups… Ask your friend to start one in Video player → Watch Party.", fontSize = 13.5.sp, lineHeight = 19.sp, color = cs.onSurfaceVariant)
                    } else {
                        Text("Tap Join a Watch Party to look for parties nearby.", fontSize = 13.5.sp, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
        items(GroupLink.rooms, key = { it.endpointId }) { room -> GroupRoomCard(room) { onJoin(room) } }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surfaceContainerLow).padding(12.dp),
            ) {
                Icon(Icons.Rounded.WifiTethering, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "Videos from the host's phone stream over a fast direct Wi-Fi link — no internet needed, keep the phones in one room. " +
                        "Video links play from the internet on every phone. Everyone sets their own sound.",
                    fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

/** In a Watch Party with the player closed: back to it, or end / leave. */
@Composable
private fun InPartyView(onOpen: () -> Unit, onEnd: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        GroupBar(Modifier.padding(bottom = 12.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF4C0519), Color(0xFFBE123C), Color(0xFFFB7185)))).padding(18.dp),
        ) {
            Text(
                WatchParty.roomName + if (WatchParty.isHost) " · you're the host" else "",
                color = Color.White.copy(alpha = .85f), fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
            )
            Text(WatchParty.current?.title ?: "Nothing playing yet", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            Text(
                "${WatchParty.people.size} ${if (WatchParty.people.size == 1) "person" else "people"} · " + when (WatchParty.stage) {
                    WpStage.PLAYING -> "playing"
                    WpStage.PREPARING -> "getting ready"
                    WpStage.PAUSED -> "paused"
                },
                color = Color.White.copy(alpha = .85f), fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp),
            )
            Button(
                onClick = onOpen, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Rose),
                modifier = Modifier.padding(top = 16.dp).fillMaxWidth().height(46.dp),
            ) {
                Icon(Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(6.dp))
                Text("Open the player")
            }
        }
        Text(WatchParty.people.joinToString(", "), fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 14.dp))
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onEnd, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text(if (WatchParty.isHost) "End the group" else "Leave the group", color = cs.error)
        }
    }
}

// ============================================================================ start + picking a video
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartSheet(preset: WatchSource?, onDismiss: () -> Unit, onStart: (String, WatchSource) -> Unit) {
    var name by remember { mutableStateOf("${QuickShare.myName.ifBlank { "My" }}'s Watch Party") }
    var picked by remember { mutableStateOf(preset) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text("Start a Watch Party", fontSize = 20.sp, fontWeight = FontWeight.Medium)
            OutlinedTextField(value = name, onValueChange = { name = it.take(30) }, singleLine = true, label = { Text("Party name") }, modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
            Text("Pick the video", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            VideoChooser(selected = picked, onPick = { picked = it })
            if (picked?.startMs?.let { it > 0 } == true) {
                Text("Starts from ${fmt(picked!!.startMs)} (where you were).", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
            Text("Add more videos to “Up next” once the party starts.", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            Button(
                onClick = { picked?.let { onStart(name, it) } }, enabled = picked != null,
                colors = ButtonDefaults.buttonColors(containerColor = Rose, contentColor = Color.White),
                modifier = Modifier.padding(top = 14.dp).fillMaxWidth().height(48.dp),
            ) { Text("Start Watch Party", fontSize = 15.sp) }
        }
    }
}

/** The host adds a video to Up next. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoPickerSheet(title: String, onPick: (WatchSource) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            VideoChooser(selected = null, onPick = {
                onPick(it)
                onDismiss()
            })
        }
    }
}

/** The same sources as My videos: Continue watching, SyncUp downloads, a file on the phone, a link. */
@Composable
private fun VideoChooser(selected: WatchSource?, onPick: (WatchSource) -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val watching by produceState(emptyList<com.agani.syncup.video.Watched>()) {
        value = withContext(Dispatchers.IO) { WatchHistory.continueWatching(context) }
    }
    val downloads = Downloads.tasks.filter { it.state == DlState.DONE && it.kind == FileKind.VIDEO && it.contentUri != null }
    var link by remember { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && i >= 0) c.getString(i) else null
                }
            }.getOrNull() ?: uri.lastPathSegment ?: "Video"
            onPick(WatchSource(uri.toString(), name))
        }
    }
    selected?.takeIf { s -> watching.none { it.uri == s.uri } && downloads.none { it.contentUri == s.uri } }?.let { s ->
        Choice(Icons.Rounded.CheckCircle, s.title, if (s.isLink) "Video link" else "From this phone", on = true) { onPick(s) }
    }
    watching.take(6).forEach { w ->
        Choice(Icons.Rounded.History, w.title, "Continue watching", on = selected?.uri == w.uri) { onPick(WatchSource(w.uri, w.title, selected?.takeIf { it.uri == w.uri }?.startMs ?: 0)) }
    }
    downloads.take(10).forEach { t ->
        Choice(Icons.Rounded.Download, t.fileName, "SyncUp downloads · ${Files.size(t.total)}", on = selected?.uri == t.contentUri) {
            onPick(WatchSource(t.contentUri!!, t.fileName, selected?.takeIf { it.uri == t.contentUri }?.startMs ?: 0))
        }
    }
    Choice(Icons.Rounded.FolderOpen, "Pick a video from this phone", "Opens one file", on = false) { pick.launch(arrayOf("video/*")) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        OutlinedTextField(
            value = link, onValueChange = { link = it }, singleLine = true, placeholder = { Text("Paste a video link (mp4 / m3u8)") },
            leadingIcon = { Icon(Icons.Rounded.Link, null) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                val u = link.trim().let { if (it.isNotBlank() && "://" !in it) "https://$it" else it }
                if (u.startsWith("http")) onPick(WatchSource(u, Uri.parse(u).lastPathSegment ?: u))
            }),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = {
            val u = link.trim().let { if (it.isNotBlank() && "://" !in it) "https://$it" else it }
            if (u.startsWith("http")) onPick(WatchSource(u, Uri.parse(u).lastPathSegment ?: u))
        }, enabled = link.isNotBlank()) { Text("Use") }
    }
    Text("Links play from the internet on every phone.", fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
}

@Composable
private fun Choice(icon: ImageVector, title: String, sub: String, on: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (on) RoseLight.copy(alpha = if (cs.isLight) .6f else .15f) else cs.surfaceContainerLowest)
            .border(if (on) 2.dp else 1.dp, if (on) Rose else cs.outlineVariant, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(10.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (on) Rose else cs.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ActionCard(icon: ImageVector, title: String, subtitle: String, tint: Color, container: Color, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(22.dp)).background(cs.surfaceContainerLowest)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(22.dp)).clickable(onClick = onClick).padding(16.dp),
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(container), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.weight(1f))
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(subtitle, fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
    }
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** For the Tools tile: what the Watch Party is doing, or null. */
fun watchPartyStatus(): String? = WatchParty.statusLine()
