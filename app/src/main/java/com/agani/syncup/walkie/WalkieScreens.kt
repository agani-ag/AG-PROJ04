package com.agani.syncup.walkie

import com.agani.syncup.group.GroupElsewhere
import com.agani.syncup.group.GroupJoiningView
import com.agani.syncup.group.GroupLink
import com.agani.syncup.group.GroupMode
import com.agani.syncup.group.GroupPhase
import com.agani.syncup.group.GroupRoom
import com.agani.syncup.group.GroupRoomCard
import android.Manifest
import android.app.Activity
import android.media.AudioManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DoorFront
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HighlightOff
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.PersonAddAlt1
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.party.Party
import com.agani.syncup.quickshare.QsPhase
import com.agani.syncup.quickshare.QuickShare
import com.agani.syncup.quickshare.Radios
import com.agani.syncup.quickshare.RadiosDialog
import com.agani.syncup.ui.theme.isLight
import kotlinx.coroutines.delay

private val Teal = Color(0xFF0F766E)
private val TealBright = Color(0xFF14B8A6)
private val TealLight = Color(0xFFCCFBF1)
private val TealOn = Color(0xFF134E4A)
private val Red = Color(0xFFDC2626)
private val Amber = Color(0xFFD97706)
private val Green = Color(0xFF15803D)

private fun TalkMode.label() = if (this == TalkMode.HOLD) "Hold to talk" else "Open line"
private fun JoinRule.label() = if (this == JoinRule.ASK) "Ask me first" else "Anyone nearby"

/** Tools → Walkie Talkie: start or join a channel nearby, then talk. See [Walkie]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalkieScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    remember { Walkie.init(context) }
    val cs = MaterialTheme.colorScheme
    var starting by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var leaveSheet by remember { mutableStateOf(false) }
    var pickTalk by remember { mutableStateOf(false) }
    var pickJoin by remember { mutableStateOf(false) }
    var pickHandOver by remember { mutableStateOf<Boolean?>(null) } // null = closed; true = then leave
    var confirmEnd by remember { mutableStateOf(false) }
    var waitingForRadios by remember { mutableStateOf<(() -> Unit)?>(null) }
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    var permissionNote by remember { mutableStateOf(false) }
    // Home: Walkie Talkie channels, or SyncUp groups nearby (one search at a time).
    var listGroups by remember { mutableStateOf(false) }
    // In a group doing something else (host): pick the talk style, then switch it here.
    var pickGroupTalk by remember { mutableStateOf(false) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val next = afterPermission
        afterPermission = null
        // Nearby is a must; the mic isn't (no mic = listen only).
        if (QuickShare.missingPermissions(receiving = false).isEmpty()) {
            permissionNote = false
            next?.invoke()
        } else {
            permissionNote = true
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        // Now Talk works from the lock screen too.
        if (ok && Walkie.inChannel) WalkieService.start(context)
    }

    /** Permissions (Nearby + mic), then Bluetooth / Wi-Fi on, then [action]. */
    fun ready(action: () -> Unit) {
        when {
            com.agani.syncup.group.GroupLink.active -> return Toast.makeText(context, "Leave the group first — or switch the group to Walkie talkie", Toast.LENGTH_LONG).show()
            QuickShare.phase != QsPhase.IDLE -> return Toast.makeText(context, "Finish SyncUp Share first", Toast.LENGTH_SHORT).show()
        }
        val next = { if (Radios.missing(context).isEmpty()) action() else waitingForRadios = action }
        val ask = QuickShare.missingPermissions(receiving = false) +
            listOf(Manifest.permission.RECORD_AUDIO).filter { ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (ask.isEmpty()) next() else {
            afterPermission = next
            permissions.launch(ask.toTypedArray())
        }
    }

    // Volume-key talk works only while this screen is on display.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        Walkie.screenVisible = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) Walkie.screenVisible = true
            if (e == Lifecycle.Event.ON_PAUSE) Walkie.screenVisible = false
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            Walkie.screenVisible = false
        }
    }
    // In a channel, the volume keys set the voices' volume.
    val activity = context as? Activity
    DisposableEffect(Walkie.inChannel) {
        if (Walkie.inChannel) activity?.volumeControlStream = AudioManager.STREAM_VOICE_CALL
        onDispose { activity?.volumeControlStream = AudioManager.USE_DEFAULT_STREAM_TYPE }
    }

    LaunchedEffect(Walkie.notice) {
        Walkie.notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            Walkie.notice = null
        }
    }

    // Look for channels (or groups) while this page is open and this phone isn't in one.
    val canBrowse = QuickShare.missingPermissions(receiving = false).isEmpty() && Radios.missing(context).isEmpty()
    var browsing by remember { mutableStateOf(false) }
    DisposableEffect(Walkie.phase, GroupLink.phase, canBrowse, browsing, listGroups) {
        val idle = Walkie.phase == WalkiePhase.IDLE && GroupLink.phase == GroupPhase.IDLE && (canBrowse || browsing) && QuickShare.phase == QsPhase.IDLE
        if (idle) {
            if (listGroups) GroupLink.startBrowsing() else Walkie.startBrowsing()
        }
        onDispose { if (listGroups) GroupLink.stopBrowsing() else Walkie.stopBrowsing() }
    }

    Column(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp)) {
            BarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
            Text(
                if (Walkie.inChannel) Walkie.channelName else "Walkie Talkie",
                fontSize = 22.sp, lineHeight = 28.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            if (Walkie.inChannel) {
                ModeChip(Walkie.mode)
                Box {
                    BarIcon(Icons.Rounded.MoreVert, "Channel menu") { menu = true }
                    ChannelMenu(
                        open = menu, onClose = { menu = false },
                        onTalkStyle = { pickTalk = true }, onJoinRule = { pickJoin = true },
                        onHandOver = { pickHandOver = false }, onEnd = { confirmEnd = true },
                    )
                }
            }
        }
        Box(Modifier.weight(1f)) {
            when {
                Walkie.phase == WalkiePhase.JOINING -> JoiningView()
                Walkie.inChannel -> ChannelView(
                    onAllowMic = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                    onLeave = {
                        when {
                            Walkie.isHub && Walkie.members.size > 1 -> leaveSheet = true
                            Walkie.isHub -> confirmEnd = true
                            else -> Walkie.leave()
                        }
                    },
                )
                GroupLink.phase == GroupPhase.JOINING -> GroupJoiningView(Teal, TealLight)
                // In a group: what it's doing; the host can switch it to Walkie talkie from here.
                GroupLink.inGroup -> GroupElsewhere(GroupMode.WALKIE, onSwitchHere = { pickGroupTalk = true })
                else -> HomeView(
                    canBrowse = canBrowse,
                    permissionNote = permissionNote,
                    listGroups = listGroups,
                    onListGroups = { listGroups = it },
                    onRename = { renaming = true },
                    onStart = { ready { starting = true } },
                    onLook = { ready { browsing = true; if (listGroups) GroupLink.startBrowsing() else Walkie.startBrowsing() } },
                    onJoin = { ch -> ready { Walkie.join(ch) } },
                    onJoinGroup = { room -> ready { GroupLink.join(room) } },
                )
            }
        }
    }

    if (starting) StartSheet(onDismiss = { starting = false })

    if (pickGroupTalk) {
        AlertDialog(
            onDismissRequest = { pickGroupTalk = false },
            title = { Text("Switch the group to Walkie talkie") },
            text = { Text("How does the group talk? Everyone in ${GroupLink.groupName} comes here.", fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = {
                    pickGroupTalk = false
                    GroupLink.switchTo(GroupMode.WALKIE, org.json.JSONObject().put("talk", TalkMode.HOLD.key))
                }) { Text("Hold to talk") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pickGroupTalk = false
                    GroupLink.switchTo(GroupMode.WALKIE, org.json.JSONObject().put("talk", TalkMode.OPEN.key))
                }) { Text("Open talk") }
            },
        )
    }

    if (renaming) {
        var name by remember { mutableStateOf(Walkie.myName) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Your name") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(24) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    QuickShare.rename(name)
                    renaming = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
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

    // The phone running the channel: someone asks to join.
    Walkie.joinRequests.firstOrNull()?.let { r ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("${r.name} wants to join") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("Let them in only if their phone shows this code.", fontSize = 14.sp, textAlign = TextAlign.Center)
                    CodeBox(r.code, Modifier.padding(top = 16.dp))
                }
            },
            confirmButton = { Button(onClick = { Walkie.acceptJoin(r) }, colors = tealButton()) { Text("Accept") } },
            dismissButton = { OutlinedButton(onClick = { Walkie.declineJoin(r) }) { Text("Decline") } },
        )
    }

    Walkie.ended?.let { message ->
        AlertDialog(
            onDismissRequest = { Walkie.dismissEnded() },
            title = { Text("Channel ended") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { Walkie.dismissEnded() }) { Text("OK") } },
        )
    }

    if (pickTalk) {
        ChoiceDialog(
            title = "How do people talk?",
            options = listOf(
                TalkMode.HOLD.label() to "One at a time — hold the button, let go to listen.",
                TalkMode.OPEN.label() to "Hands-free, like a group call. Everyone's mic starts off after the switch.",
            ),
            selected = Walkie.mode.ordinal,
            onPick = { Walkie.changeMode(TalkMode.entries[it]) },
            onClose = { pickTalk = false },
        )
    }
    if (pickJoin) {
        ChoiceDialog(
            title = "Who can join?",
            options = listOf(
                JoinRule.ASK.label() to "You see a code and tap Accept for each person.",
                JoinRule.ANYONE.label() to "Anyone nearby with SyncUp comes straight in.",
            ),
            selected = Walkie.joinRule.ordinal,
            onPick = { Walkie.changeJoinRule(JoinRule.entries[it]) },
            onClose = { pickJoin = false },
        )
    }
    pickHandOver?.let { thenLeave ->
        val others = Walkie.members.filter { !Walkie.isMe(it) }
        ChoiceDialog(
            title = if (thenLeave) "Who runs the channel after you?" else "Hand over to…",
            options = others.map { it.name to null },
            selected = 0,
            confirm = if (thenLeave) "Hand over & leave" else "Hand over",
            onPick = { i -> others.getOrNull(i)?.let { Walkie.handOver(it, leave = thenLeave) } },
            onClose = { pickHandOver = null },
        )
    }
    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End the channel?") },
            text = { Text("Everyone's walkie-talkie stops.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnd = false
                    Walkie.end()
                }) { Text("End channel", color = Red) }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep going") } },
        )
    }
    if (leaveSheet) {
        LeaveSheet(
            onDismiss = { leaveSheet = false },
            onChange = {
                leaveSheet = false
                pickHandOver = true
            },
            onEnd = {
                leaveSheet = false
                confirmEnd = true
            },
        )
    }
}

// ============================================================================ home
@Composable
private fun HomeView(
    canBrowse: Boolean,
    permissionNote: Boolean,
    listGroups: Boolean,
    onListGroups: (Boolean) -> Unit,
    onRename: () -> Unit,
    onStart: () -> Unit,
    onLook: () -> Unit,
    onJoin: (WalkieChannel) -> Unit,
    onJoinGroup: (GroupRoom) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val light = cs.isLight
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surfaceContainerLow).padding(start = 12.dp, end = 4.dp),
            ) {
                Icon(Icons.Rounded.RecordVoiceOver, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("Others see you as ", fontSize = 13.5.sp, color = cs.onSurfaceVariant)
                Text(Walkie.myName, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TextButton(onClick = onRename) { Text("Change", color = if (light) Teal else TealBright) }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                ActionCard(
                    Icons.Rounded.RecordVoiceOver, "Start a channel", "Friends nearby join you",
                    if (light) Teal else TealBright, if (light) TealLight else TealOn, Modifier.weight(1f), onStart,
                )
                ActionCard(
                    Icons.AutoMirrored.Rounded.Login, "Join a channel", "Talk on a friend's channel",
                    cs.primary, cs.primaryContainer, Modifier.weight(1f), onLook,
                )
            }
        }
        if (permissionNote) {
            item {
                Text(
                    "Walkie Talkie needs the Nearby devices permission to find other phones. Turn it on in Settings → Apps → SyncUp → Permissions.",
                    fontSize = 13.sp, lineHeight = 18.sp, color = cs.onErrorContainer,
                    modifier = Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.errorContainer).padding(14.dp),
                )
            }
        }
        item {
            // Walkie Talkie channels, or SyncUp groups (a group can switch to Walkie talkie, music, a video, sharing).
            Box(Modifier.padding(top = 18.dp)) {
                Segmented(
                    listOf(Icons.Rounded.RecordVoiceOver to "Channels", Icons.Rounded.Groups to "Groups"),
                    selected = if (listGroups) 1 else 0, onSelect = { onListGroups(it == 1) },
                )
            }
        }
        if (listGroups) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp)) {
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
                        }
                        Text(
                            "Looking for groups… A group started in Radio → Party or Video player → Watch Party shows here — joining takes you to whatever it's doing.",
                            fontSize = 13.5.sp, lineHeight = 19.sp, color = cs.onSurfaceVariant,
                        )
                    }
                }
            }
            items(GroupLink.rooms, key = { "g" + it.endpointId }) { room -> GroupRoomCard(room) { onJoinGroup(room) } }
            return@LazyColumn
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp)) {
                Text("Channels nearby", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                if (Walkie.channels.isNotEmpty()) Text("  · ${Walkie.channels.size}", fontSize = 14.sp, color = cs.onSurfaceVariant)
            }
        }
        if (Walkie.channels.isEmpty()) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainerLow).padding(16.dp),
                ) {
                    if (canBrowse) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(14.dp))
                        Text("Looking for channels… Ask your friend to start one in Tools → Walkie Talkie.", fontSize = 13.5.sp, lineHeight = 19.sp, color = cs.onSurfaceVariant)
                    } else {
                        Text("Tap Join a channel to look for channels nearby.", fontSize = 13.5.sp, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
        items(Walkie.channels, key = { it.endpointId }) { ch -> ChannelRow(ch, onJoin = { onJoin(ch) }) }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surfaceContainerLow).padding(12.dp),
            ) {
                Icon(Icons.Rounded.WifiTethering, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "No internet needed. Works across a house, hall or campsite (about 50–100 m, more on the same Wi-Fi). " +
                        "Voices are live only — never recorded or saved.",
                    fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChannelRow(ch: WalkieChannel, onJoin: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(cs.surfaceContainerLowest).border(1.dp, cs.outlineVariant, RoundedCornerShape(18.dp)).padding(12.dp),
    ) {
        Letter(ch.name)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(ch.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${ch.host} · ${ch.people} ${if (ch.people == 1) "person" else "people"}", fontSize = 12.5.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                ModeChip(ch.mode)
                SmallChip(
                    if (ch.join == JoinRule.ASK) Icons.Rounded.PersonAddAlt1 else Icons.Rounded.DoorFront,
                    if (ch.join == JoinRule.ASK) "Ask to join" else "Anyone joins",
                )
            }
        }
        Button(onClick = onJoin, colors = tealButton(), contentPadding = PaddingValues(horizontal = 18.dp)) { Text("Join") }
    }
}

// ============================================================================ start
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartSheet(onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var name by remember { mutableStateOf("${Walkie.myName}'s channel") }
    var talk by remember { mutableStateOf(TalkMode.HOLD) }
    var join by remember { mutableStateOf(JoinRule.ASK) }
    // Fully open (the Start button is at the bottom), scrolling on short screens.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text("Start a channel", fontSize = 20.sp, fontWeight = FontWeight.Medium)
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(24) }, singleLine = true, label = { Text("Channel name") },
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            )
            Text("How do people talk?", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
            Segmented(
                listOf(Icons.Rounded.PanTool to TalkMode.HOLD.label(), Icons.Rounded.Mic to TalkMode.OPEN.label()),
                selected = talk.ordinal, onSelect = { talk = TalkMode.entries[it] },
            )
            Text(
                if (talk == TalkMode.HOLD) "Hold the big button to talk, let go to listen. One person at a time — best for groups and noisy places."
                else "Hands-free: everyone can talk any time, like a group call. Best with earphones.",
                fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, start = 4.dp),
            )
            Text("Who can join?", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
            Segmented(
                listOf(Icons.Rounded.PersonAddAlt1 to JoinRule.ASK.label(), Icons.Rounded.DoorFront to JoinRule.ANYONE.label()),
                selected = join.ordinal, onSelect = { join = JoinRule.entries[it] },
            )
            Text(
                if (join == JoinRule.ASK) "You see a code and tap Accept for each person. People you let in once come straight back after a drop."
                else "Anyone nearby with SyncUp comes straight in — no code.",
                fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, start = 4.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(TealLight.copy(alpha = if (cs.isLight) 1f else .2f)).padding(12.dp),
            ) {
                Icon(if (talk == TalkMode.HOLD) Icons.Rounded.PanTool else Icons.Rounded.Mic, null, tint = if (cs.isLight) TealOn else TealLight, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "${talk.label()} · ${join.label()} — you can change both later from ⋮ in the channel.",
                    fontSize = 13.sp, lineHeight = 18.sp, color = if (cs.isLight) TealOn else TealLight,
                )
            }
            Button(
                onClick = {
                    onDismiss()
                    Walkie.start(name, talk, join)
                },
                colors = tealButton(), modifier = Modifier.padding(top = 14.dp).fillMaxWidth().height(48.dp),
            ) { Text("Start channel", fontSize = 15.sp) }
        }
    }
}

// ============================================================================ joining
@Composable
private fun JoiningView() {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(140.dp).clip(CircleShape).background(TealLight.copy(alpha = if (cs.isLight) 1f else .2f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.RecordVoiceOver, null, tint = Teal, modifier = Modifier.size(48.dp))
        }
        val ask = Walkie.joinRule == JoinRule.ASK
        Text(
            if (ask && Walkie.code.isNotBlank()) "Asking ${Walkie.hostName} to let you in…" else "Joining ${Walkie.channelName}…",
            fontSize = 19.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 20.dp),
        )
        if (ask && Walkie.code.isNotBlank()) {
            CodeBox(Walkie.code, Modifier.padding(top = 16.dp))
            Text("Check ${Walkie.hostName}'s phone shows the same code.", fontSize = 14.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            Text("Next time you join ${Walkie.hostName}'s channel, you go straight in.", fontSize = 13.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp))
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = { Walkie.cancelJoin() }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Cancel") }
        Spacer(Modifier.height(20.dp))
    }
}

// ============================================================================ the channel
@Composable
private fun ChannelView(onAllowMic: () -> Unit, onLeave: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Walkie.banner?.let { b ->
            Note(Icons.Rounded.SwapHoriz, b, TealLight, TealOn, onClose = { Walkie.dismissBanner() })
            LaunchedEffect(b) {
                delay(10_000)
                if (Walkie.banner == b) Walkie.dismissBanner()
            }
        }
        when {
            Walkie.phase == WalkiePhase.SWITCHING -> Note(Icons.Rounded.Sync, "Finding the channel again… it carries on by itself.", Color(0xFFFEF3C7), Color(0xFF78350F))
            Walkie.inPhoneCall -> Note(Icons.Rounded.PhoneAndroid, "Paused for your phone call — back after it.", Color(0xFFFEF3C7), Color(0xFF78350F))
        }
        Walkie.callFrom?.let { Note(Icons.Rounded.NotificationsActive, "$it is calling everyone", TealLight, TealOn) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)) {
            Icon(if (Walkie.isHub) Icons.Rounded.Star else Icons.Rounded.RecordVoiceOver, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            val n = Walkie.members.size
            Text(
                (if (Walkie.isHub) "You run this channel" else "${Walkie.hostName} runs this channel") +
                    " · $n ${if (n == 1) "person" else "people"}" + if (Walkie.phase == WalkiePhase.SWITCHING) " · reconnecting" else " · connected",
                fontSize = 13.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        PeopleGrid()
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (Walkie.mode == TalkMode.HOLD) HoldToTalk(onAllowMic) else OpenLine(onAllowMic)
        }
        if (Walkie.mode == TalkMode.OPEN) {
            Note(Icons.Rounded.Headphones, "Phones close together can echo. Earphones work best on an open line.", Color(0xFFFEF3C7), Color(0xFF78350F))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 16.dp)) {
            val headset = Walkie.headset
            ToolButton(
                when {
                    headset != null -> Icons.Rounded.Headphones
                    Walkie.loudspeaker -> Icons.AutoMirrored.Rounded.VolumeUp
                    else -> Icons.Rounded.PhoneAndroid
                },
                headset ?: if (Walkie.loudspeaker) "Loudspeaker" else "Earpiece",
                on = headset != null || Walkie.loudspeaker, enabled = headset == null, modifier = Modifier.weight(1f),
            ) { Walkie.toggleLoudspeaker() }
            ToolButton(Icons.Rounded.NotificationsActive, "Call all", on = false, modifier = Modifier.weight(1f)) { Walkie.callAll() }
            ToolButton(Icons.AutoMirrored.Rounded.VolumeOff, if (Walkie.othersMuted) "Muted" else "Mute others", on = Walkie.othersMuted, modifier = Modifier.weight(1f)) { Walkie.toggleOthersMuted() }
            ToolButton(Icons.AutoMirrored.Rounded.Logout, "Leave", on = false, color = Red, modifier = Modifier.weight(1f), onClick = onLeave)
        }
    }
}

@Composable
private fun PeopleGrid() {
    val cs = MaterialTheme.colorScheme
    val people = Walkie.members.toList()
    val cols = if (people.size == 5) 5 else 4
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        people.chunked(cols).take(3).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { m ->
                    val me = Walkie.isMe(m)
                    val talking = (me && Walkie.onAir) || (!me && (m.id in Walkie.speaking || Walkie.floorId == m.id))
                    val ring = when {
                        me && Walkie.onAir -> Red
                        talking -> TealBright
                        else -> Color.Transparent
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Box {
                            Box(
                                Modifier.size(56.dp).border(3.dp, ring, CircleShape).padding(5.dp).clip(CircleShape)
                                    .background(if (talking) (if (me) Color(0xFFFEE2E2) else TealLight) else cs.surfaceContainerHigh),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(m.name.take(1).uppercase(), fontSize = 18.sp, fontWeight = FontWeight.Medium, color = if (talking) (if (me) Red else Teal) else cs.onSurface)
                            }
                            if (m.token == Walkie.hostToken) {
                                Icon(
                                    Icons.Rounded.Star, "Runs the channel", tint = Color(0xFFB45309),
                                    modifier = Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp).size(18.dp).clip(CircleShape).background(cs.surface).padding(2.dp),
                                )
                            }
                        }
                        Text(
                            when {
                                talking -> "Talking"
                                me -> "You"
                                else -> m.name
                            },
                            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = when {
                                me && Walkie.onAir -> Red
                                talking || me -> if (cs.isLight) Teal else TealBright
                                else -> cs.onSurface
                            },
                            fontWeight = if (talking || me) FontWeight.Medium else FontWeight.Normal,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (people.size > cols * 3) Text("+ ${people.size - cols * 3} more", fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun HoldToTalk(onAllowMic: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = android.os.SystemClock.elapsedRealtime()
            delay(200)
        }
    }
    val other = Walkie.floorId?.takeIf { it != Walkie.myId }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (other != null && !Walkie.onAir) {
            Pill("${Walkie.floorName ?: "Someone"} is talking · ${clock(now - Walkie.floorSinceMs)}")
        }
        val usable = Walkie.phase == WalkiePhase.IN_CHANNEL && !Walkie.inPhoneCall
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(240.dp)) {
            if (Walkie.onAir) {
                val left = (Walkie.TALK_LIMIT_MS - (now - Walkie.talkStartedMs)).coerceAtLeast(0)
                val warn = left <= 10_000
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 8.dp.toPx()
                    drawArc(Color(0xFFFEE2E2), 0f, 360f, false, style = Stroke(stroke))
                    drawArc(
                        if (warn) Amber else Red, -90f, 360f * left / Walkie.TALK_LIMIT_MS, false,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
                Text(
                    "${(left + 999) / 1000} s left", fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = if (warn) Amber else Red,
                    modifier = Modifier.align(Alignment.BottomCenter).offset(y = 4.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFFEE2E2)).padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
            val (top, title, sub) = when {
                !Walkie.micAllowed -> Triple(listOf(cs.surfaceContainerHighest, cs.surfaceContainerHighest), "Allow microphone", "to talk — you can listen now")
                !usable -> Triple(listOf(cs.surfaceContainerHighest, cs.surfaceContainerHighest), if (Walkie.inPhoneCall) "Paused" else "Reconnecting…", "")
                Walkie.onAir -> Triple(listOf(Color(0xFFF87171), Red), "You're on air", "${clock(now - Walkie.talkStartedMs)} · let go to stop")
                Walkie.asking -> Triple(listOf(Color(0xFF0D9488), Color(0xFF115E59)), "Connecting…", "keep holding")
                other != null -> Triple(listOf(cs.surfaceContainerHighest, cs.surfaceContainerHighest), "Wait…", "your turn when ${Walkie.floorName ?: "they"} stops")
                else -> Triple(listOf(Color(0xFF2DD4BF), Teal), "Hold to talk", "up to 1 minute")
            }
            val grey = top.first() == cs.surfaceContainerHighest
            Column(
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                modifier = Modifier.size(196.dp).clip(CircleShape).background(Brush.radialGradient(top))
                    .semantics { contentDescription = title }
                    .then(
                        if (!Walkie.micAllowed) Modifier.clickable(onClick = onAllowMic)
                        else Modifier.pointerInput(Unit) {
                            detectTapGestures(onPress = {
                                Walkie.pressTalk()
                                tryAwaitRelease()
                                Walkie.releaseTalk()
                            })
                        },
                    ),
            ) {
                Icon(if (Walkie.micAllowed) Icons.Rounded.Mic else Icons.Rounded.MicOff, null, tint = if (grey) cs.onSurfaceVariant else Color.White, modifier = Modifier.size(54.dp))
                Text(title, color = if (grey) cs.onSurfaceVariant else Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
                if (sub.isNotBlank()) Text(sub, color = if (grey) cs.onSurfaceVariant else Color.White.copy(alpha = .9f), fontSize = 12.5.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 18.dp))
            }
        }
        if (Walkie.volumeKeyTalk) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(TealLight.copy(alpha = if (cs.isLight) 1f else .2f)).padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Rounded.PhoneAndroid, null, tint = if (cs.isLight) TealOn else TealLight, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Volume-key talk on", fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = if (cs.isLight) TealOn else TealLight)
            }
        }
        Text(
            when {
                Walkie.onAir -> {
                    val n = Walkie.members.size - 1
                    if (n <= 0) "Nobody else is here yet." else "$n ${if (n == 1) "person hears" else "people hear"} you now."
                }
                other != null -> "One person at a time — the button frees up as soon as ${Walkie.floorName ?: "they"} lets go."
                Walkie.volumeKeyTalk -> "Hold the button or a volume key to talk.\nA quick press still changes the volume."
                else -> "Hold the button and speak.\nLet go to listen."
            },
            fontSize = 13.5.sp, lineHeight = 19.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun OpenLine(onAllowMic: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val on = Walkie.micAllowed && !Walkie.micMuted && !Walkie.inPhoneCall
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(240.dp)) {
            if (on && Walkie.onAir) Box(Modifier.size(232.dp).clip(CircleShape).background(Green.copy(alpha = .14f)))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                modifier = Modifier.size(196.dp).clip(CircleShape)
                    .background(if (on) Brush.radialGradient(listOf(Color(0xFF4ADE80), Green)) else Brush.radialGradient(listOf(cs.surfaceContainerHighest, cs.surfaceContainerHighest)))
                    .clickable { if (Walkie.micAllowed) Walkie.toggleMic() else onAllowMic() },
            ) {
                Icon(if (on) Icons.Rounded.Mic else Icons.Rounded.MicOff, null, tint = if (on) Color.White else cs.onSurfaceVariant, modifier = Modifier.size(54.dp))
                Text(
                    when {
                        !Walkie.micAllowed -> "Allow microphone"
                        Walkie.inPhoneCall -> "Paused"
                        on -> "Mic on"
                        else -> "Muted"
                    },
                    color = if (on) Color.White else cs.onSurfaceVariant, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp),
                )
                Text(if (on) "tap to mute" else "tap to talk", color = if (on) Color.White.copy(alpha = .9f) else cs.onSurfaceVariant, fontSize = 12.5.sp)
            }
        }
        val talking = Walkie.members.filter { !Walkie.isMe(it) && it.id in Walkie.speaking }.joinToString { it.name }
        Text(
            (if (on) "Just talk — your voice goes out only while you speak." else "Your mic is off. Tap to talk.") +
                if (talking.isNotBlank()) "\n$talking talking." else "",
            fontSize = 13.5.sp, lineHeight = 19.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
}

// ============================================================================ menus & sheets
@Composable
private fun ChannelMenu(open: Boolean, onClose: () -> Unit, onTalkStyle: () -> Unit, onJoinRule: () -> Unit, onHandOver: () -> Unit, onEnd: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    DropdownMenu(expanded = open, onDismissRequest = onClose) {
        MenuLabel("Just for you · this channel")
        val holdMode = Walkie.mode == TalkMode.HOLD
        DropdownMenuItem(
            text = {
                Column {
                    Text("Volume-key talk")
                    Text(if (holdMode) "Hold a volume key to talk" else "Only in Hold to talk channels", fontSize = 12.sp, color = cs.onSurfaceVariant)
                }
            },
            leadingIcon = { Icon(Icons.Rounded.PhoneAndroid, null) },
            trailingIcon = { Switch(checked = Walkie.volumeKeyTalk && holdMode, onCheckedChange = { Walkie.changeVolumeKeyTalk(it) }, enabled = holdMode) },
            enabled = holdMode,
            onClick = { Walkie.changeVolumeKeyTalk(!Walkie.volumeKeyTalk) },
        )
        HorizontalDivider()
        if (Walkie.isHub) {
            MenuLabel("Channel · you run it")
            MenuItem(Icons.Rounded.PanTool, "Talk style", Walkie.mode.label()) { onClose(); onTalkStyle() }
            MenuItem(Icons.Rounded.PersonAddAlt1, "Who can join", Walkie.joinRule.label()) { onClose(); onJoinRule() }
            MenuItem(Icons.Rounded.SwapHoriz, "Hand over to…", "Let someone else run it", enabled = Walkie.members.size > 1) { onClose(); onHandOver() }
            HorizontalDivider()
            MenuItem(Icons.Rounded.HighlightOff, "End channel", "For everyone", color = Red) { onClose(); onEnd() }
        } else {
            MenuItem(Icons.AutoMirrored.Rounded.Logout, "Leave channel", null, color = Red) {
                onClose()
                Walkie.leave()
            }
        }
    }
}

@Composable
private fun MenuLabel(text: String) {
    Text(
        text.uppercase(), fontSize = 11.5.sp, fontWeight = FontWeight.Medium, letterSpacing = .4.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun MenuItem(icon: ImageVector, title: String, sub: String?, enabled: Boolean = true, color: Color = Color.Unspecified, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column {
                Text(title, color = color)
                if (sub != null) Text(sub, fontSize = 12.sp, color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color)
            }
        },
        leadingIcon = { Icon(icon, null, tint = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color) },
        enabled = enabled,
        onClick = onClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LeaveSheet(onDismiss: () -> Unit, onChange: () -> Unit, onEnd: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val next = Walkie.successor
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text("Leave ${Walkie.channelName}?", fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text("You run this channel. Keep it going for the others, or end it.", fontSize = 13.5.sp, lineHeight = 19.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).border(2.dp, Teal, RoundedCornerShape(18.dp))
                    .clickable {
                        onDismiss()
                        next?.let { Walkie.handOver(it, leave = true) }
                    }.padding(14.dp),
            ) {
                IconTile(Icons.Rounded.SwapHoriz, Teal, TealLight)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Hand over & leave", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("${next?.name ?: "Someone"} will run the channel", fontSize = 12.5.sp, color = cs.onSurfaceVariant)
                }
                TextButton(onClick = onChange) { Text("Change", color = if (cs.isLight) Teal else TealBright) }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).border(1.dp, cs.outlineVariant, RoundedCornerShape(18.dp))
                    .clickable(onClick = onEnd).padding(14.dp),
            ) {
                IconTile(Icons.Rounded.HighlightOff, Red, Color(0xFFFEE2E2))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("End channel for everyone", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Red)
                    Text("Everyone's walkie-talkie stops", fontSize = 12.5.sp, color = cs.onSurfaceVariant)
                }
            }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.padding(top = 16.dp).fillMaxWidth().height(48.dp)) { Text("Stay") }
        }
    }
}

/** A list of choices with radio buttons; [onPick] gets the chosen index on confirm. */
@Composable
private fun ChoiceDialog(
    title: String,
    options: List<Pair<String, String?>>,
    selected: Int,
    onPick: (Int) -> Unit,
    onClose: () -> Unit,
    confirm: String = "OK",
) {
    var pick by remember { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { i, (label, sub) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { pick = i }.padding(vertical = 6.dp),
                    ) {
                        RadioButton(selected = pick == i, onClick = { pick = i })
                        Column(Modifier.weight(1f)) {
                            Text(label, fontSize = 15.sp)
                            if (sub != null) Text(sub, fontSize = 12.5.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onClose()
                onPick(pick)
            }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

// ============================================================================ small pieces
@Composable
private fun ModeChip(mode: TalkMode) = SmallChip(if (mode == TalkMode.HOLD) Icons.Rounded.PanTool else Icons.Rounded.Mic, mode.label(), teal = true)

@Composable
private fun SmallChip(icon: ImageVector, text: String, teal: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val bg = if (teal) TealLight.copy(alpha = if (cs.isLight) 1f else .2f) else cs.surfaceContainerHigh
    val fg = if (teal) (if (cs.isLight) TealOn else TealLight) else cs.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 11.5.sp, lineHeight = 14.sp, color = fg, maxLines = 1)
    }
}

@Composable
private fun Pill(text: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(TealLight.copy(alpha = if (cs.isLight) 1f else .2f)).padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Icon(Icons.Rounded.RecordVoiceOver, null, tint = if (cs.isLight) TealOn else TealLight, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (cs.isLight) TealOn else TealLight)
    }
}

@Composable
private fun Note(icon: ImageVector, text: String, bg: Color, fg: Color, onClose: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).padding(start = 14.dp, end = 4.dp).heightIn(min = 44.dp),
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 13.sp, lineHeight = 18.sp, color = fg, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
        if (onClose != null) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, "Close", tint = fg, modifier = Modifier.size(18.dp))
            }
        } else {
            Spacer(Modifier.width(10.dp))
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, on: Boolean, modifier: Modifier, enabled: Boolean = true, color: Color = Color.Unspecified, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val fg = when {
        color != Color.Unspecified -> color
        on -> if (cs.isLight) TealOn else TealLight
        else -> cs.onSurfaceVariant
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        modifier = modifier.height(60.dp).clip(RoundedCornerShape(16.dp))
            .background(if (on) TealLight.copy(alpha = if (cs.isLight) 1f else .2f) else cs.surfaceContainerLow)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
        Text(label, fontSize = 11.5.sp, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp))
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

@Composable
private fun IconTile(icon: ImageVector, tint: Color, bg: Color) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** A pill row of choices with icons (Start sheet). */
@Composable
private fun Segmented(options: List<Pair<ImageVector, String>>, selected: Int, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(23.dp)).background(cs.surfaceContainer).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, (icon, label) ->
            val on = i == selected
            val fg = if (on) (if (cs.isLight) TealOn else TealLight) else cs.onSurfaceVariant
            Row(
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(19.dp))
                    .background(if (on) TealLight.copy(alpha = if (cs.isLight) 1f else .25f) else Color.Transparent)
                    .clickable { onSelect(i) },
            ) {
                Icon(icon, null, tint = fg, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = fg)
            }
        }
    }
}

@Composable
private fun Letter(name: String) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(TealLight.copy(alpha = if (cs.isLight) 1f else .2f)),
        contentAlignment = Alignment.Center,
    ) { Text(name.take(1).uppercase(), color = Teal, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun CodeBox(code: String, modifier: Modifier = Modifier) {
    Text(
        code.toCharArray().joinToString(" "),
        fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp,
        modifier = modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 22.dp, vertical = 10.dp),
    )
}

@Composable
private fun tealButton() = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Color.White)

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** For the Tools tile / menu: what Walkie Talkie is doing, or null. */
fun walkieStatus(): String? = when {
    !Walkie.active -> null
    Walkie.phase == WalkiePhase.JOINING -> "Joining ${Walkie.channelName}"
    else -> "On: ${Walkie.channelName} · ${Walkie.members.size} ${if (Walkie.members.size == 1) "person" else "people"}"
}
