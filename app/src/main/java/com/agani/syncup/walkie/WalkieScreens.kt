package com.agani.syncup.walkie

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DoorFront
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HighlightOff
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.PersonAddAlt1
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import com.agani.syncup.group.GroupElsewhere
import com.agani.syncup.group.GroupJoiningView
import com.agani.syncup.group.GroupLink
import com.agani.syncup.group.GroupMode
import com.agani.syncup.group.GroupPhase
import com.agani.syncup.group.GroupRoom
import com.agani.syncup.group.GroupRoomsCard
import com.agani.syncup.music.NoteBox
import com.agani.syncup.music.SectionHead
import com.agani.syncup.music.ToolTile
import com.agani.syncup.music.ToolTint
import com.agani.syncup.music.ToolTopBar
import com.agani.syncup.music.cardSlice
import com.agani.syncup.music.tintButton
import com.agani.syncup.party.Party
import com.agani.syncup.quickshare.OutlinePill
import com.agani.syncup.quickshare.QsPhase
import com.agani.syncup.quickshare.QuickShare
import com.agani.syncup.quickshare.Radios
import com.agani.syncup.quickshare.RadiosDialog
import com.agani.syncup.ui.theme.CardShape
import com.agani.syncup.ui.theme.PillTab
import com.agani.syncup.ui.theme.PillTabs
import com.agani.syncup.ui.theme.appCard
import com.agani.syncup.ui.theme.dialogSurface
import kotlinx.coroutines.delay

private val Teal = Color(0xFF0F766E)
private val TealBright = Color(0xFF14B8A6)
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
        ToolTopBar(if (Walkie.inChannel) Walkie.channelName else "Walkie Talkie", onBack = onBack) {
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
                GroupLink.phase == GroupPhase.JOINING -> GroupJoiningView(ToolTint.TEAL)
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
            containerColor = cs.dialogSurface,
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
            containerColor = cs.dialogSurface,
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
            confirmButton = { Button(onClick = { Walkie.acceptJoin(r) }, colors = tintButton(ToolTint.TEAL)) { Text("Accept") } },
            dismissButton = { OutlinedButton(onClick = { Walkie.declineJoin(r) }) { Text("Decline") } },
            containerColor = cs.dialogSurface,
        )
    }

    Walkie.ended?.let { message ->
        AlertDialog(
            onDismissRequest = { Walkie.dismissEnded() },
            title = { Text("Channel ended") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { Walkie.dismissEnded() }) { Text("OK") } },
            containerColor = cs.dialogSurface,
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
                }) { Text("End channel", color = cs.error) }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep going") } },
            containerColor = cs.dialogSurface,
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
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)) {
        item(key = "name") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().appCard(CardShape).padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            ) {
                ToolTile(Icons.Rounded.RecordVoiceOver, ToolTint.TEAL, size = 36.dp)
                Spacer(Modifier.width(12.dp))
                Text("Others see you as ", fontSize = 13.5.sp, color = cs.onSurfaceVariant)
                Text(Walkie.myName, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TextButton(onClick = onRename) { Text("Change", color = ToolTint.TEAL.icon) }
            }
        }
        item(key = "actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                ActionCard(Icons.Rounded.RecordVoiceOver, "Start a channel", "Friends nearby join you", ToolTint.TEAL, Modifier.weight(1f), onStart)
                ActionCard(Icons.AutoMirrored.Rounded.Login, "Join a channel", "Talk on a friend's channel", ToolTint.BLUE, Modifier.weight(1f), onLook)
            }
        }
        if (permissionNote) {
            item(key = "permission") {
                Text(
                    "Walkie Talkie needs the Nearby devices permission to find other phones. Turn it on in Settings → Apps → SyncUp → Permissions.",
                    fontSize = 13.sp, lineHeight = 18.sp, color = cs.onErrorContainer,
                    modifier = Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.errorContainer).padding(14.dp),
                )
            }
        }
        item(key = "tabs") {
            // Walkie Talkie channels, or SyncUp groups (a group can switch to Walkie talkie, music, a video, sharing).
            PillTabs(
                listOf(PillTab("Channels", Icons.Rounded.RecordVoiceOver), PillTab("Groups", Icons.Rounded.Groups)),
                selected = if (listGroups) 1 else 0, onSelect = { onListGroups(it == 1) },
                modifier = Modifier.padding(top = 18.dp),
            )
        }
        if (listGroups) {
            item(key = "groupsHead") { SectionHead("Groups nearby", count = GroupLink.rooms.size.takeIf { it > 0 }?.toString()) }
            if (GroupLink.rooms.isEmpty()) {
                item(key = "groupsEmpty") {
                    Looking(
                        canBrowse,
                        "Looking for groups… A group started in Radio → Party or Video player → Watch Party shows here — joining takes you to whatever it's doing.",
                        "Looking for groups… A group started in Radio → Party or Video player → Watch Party shows here — joining takes you to whatever it's doing.",
                    )
                }
            }
            if (GroupLink.rooms.isNotEmpty()) item(key = "groups") { GroupRoomsCard(GroupLink.rooms) { onJoinGroup(it) } }
            return@LazyColumn
        }
        item(key = "channelsHead") { SectionHead("Channels nearby", count = Walkie.channels.size.takeIf { it > 0 }?.toString()) }
        if (Walkie.channels.isEmpty()) {
            item(key = "channelsEmpty") {
                Looking(
                    canBrowse,
                    "Looking for channels… Ask your friend to start one in Tools → Walkie Talkie.",
                    "Tap Join a channel to look for channels nearby.",
                )
            }
        }
        val channels = Walkie.channels
        itemsIndexed(channels, key = { _, ch -> ch.endpointId }) { i, ch ->
            ChannelRow(ch, Modifier.cardSlice(first = i == 0, last = i == channels.lastIndex), onJoin = { onJoin(ch) })
        }
        item(key = "about") {
            NoteBox(
                Icons.Rounded.WifiTethering,
                "No internet needed. Works across a house, hall or campsite (about 50–100 m, more on the same Wi-Fi). " +
                    "Voices are live only — never recorded or saved.",
                Modifier.padding(top = 14.dp),
            )
        }
    }
}

/** Nothing found yet: a spinner while looking (else how to start looking), on a white card. */
@Composable
private fun Looking(canBrowse: Boolean, looking: String, idle: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().appCard(CardShape).padding(16.dp),
    ) {
        if (canBrowse) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, trackColor = cs.outlineVariant)
            Spacer(Modifier.width(14.dp))
        }
        Text(if (canBrowse) looking else idle, fontSize = 13.5.sp, lineHeight = 19.sp, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun ChannelRow(ch: WalkieChannel, modifier: Modifier, onJoin: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Letter(ch.name)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(ch.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${ch.host} · ${ch.people} ${if (ch.people == 1) "person" else "people"}", fontSize = 12.5.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 5.dp)) {
                ModeChip(ch.mode)
                SmallChip(
                    if (ch.join == JoinRule.ASK) Icons.Rounded.PersonAddAlt1 else Icons.Rounded.DoorFront,
                    if (ch.join == JoinRule.ASK) "Ask to join" else "Anyone joins",
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onJoin, colors = tintButton(ToolTint.TEAL), contentPadding = PaddingValues(horizontal = 16.dp), modifier = Modifier.height(36.dp)) {
            Text("Join", fontSize = 13.5.sp)
        }
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
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = cs.surfaceContainerLowest,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text("Start a channel", fontSize = 20.sp, fontWeight = FontWeight.Medium)
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(24) }, singleLine = true, label = { Text("Channel name") },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            )
            Text("How do people talk?", fontSize = 14.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp))
            PillTabs(
                listOf(PillTab(TalkMode.HOLD.label(), Icons.Rounded.PanTool), PillTab(TalkMode.OPEN.label(), Icons.Rounded.Mic)),
                selected = talk.ordinal, onSelect = { talk = TalkMode.entries[it] },
            )
            Text(
                if (talk == TalkMode.HOLD) "Hold the big button to talk, let go to listen. One person at a time — best for groups and noisy places."
                else "Hands-free: everyone can talk any time, like a group call. Best with earphones.",
                fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, start = 4.dp),
            )
            Text("Who can join?", fontSize = 14.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp))
            PillTabs(
                listOf(PillTab(JoinRule.ASK.label(), Icons.Rounded.PersonAddAlt1), PillTab(JoinRule.ANYONE.label(), Icons.Rounded.DoorFront)),
                selected = join.ordinal, onSelect = { join = JoinRule.entries[it] },
            )
            Text(
                if (join == JoinRule.ASK) "You see a code and tap Accept for each person. People you let in once come straight back after a drop."
                else "Anyone nearby with SyncUp comes straight in — no code.",
                fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, start = 4.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(ToolTint.TEAL.tile).padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Icon(if (talk == TalkMode.HOLD) Icons.Rounded.PanTool else Icons.Rounded.Mic, null, tint = ToolTint.TEAL.icon, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "${talk.label()} · ${join.label()} — you can change both later from ⋮ in the channel.",
                    fontSize = 13.sp, lineHeight = 18.sp, color = cs.onSurface,
                )
            }
            Button(
                onClick = {
                    onDismiss()
                    Walkie.start(name, talk, join)
                },
                colors = tintButton(ToolTint.TEAL), modifier = Modifier.padding(top = 14.dp).fillMaxWidth().height(48.dp),
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
        Box(Modifier.size(140.dp).clip(CircleShape).background(ToolTint.TEAL.tile), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.RecordVoiceOver, null, tint = ToolTint.TEAL.icon, modifier = Modifier.size(48.dp))
        }
        val ask = Walkie.joinRule == JoinRule.ASK
        Text(
            if (ask && Walkie.code.isNotBlank()) "Asking ${Walkie.hostName} to let you in…" else "Joining ${Walkie.channelName}…",
            fontSize = 19.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 20.dp),
        )
        if (ask && Walkie.code.isNotBlank()) {
            CodeBox(Walkie.code, Modifier.padding(top = 20.dp))
            Text("Check ${Walkie.hostName}'s phone shows the same code.", fontSize = 14.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
            Text("Next time you join ${Walkie.hostName}'s channel, you go straight in.", fontSize = 13.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp))
        }
        Spacer(Modifier.weight(1f))
        OutlinePill("Cancel", Modifier.fillMaxWidth()) { Walkie.cancelJoin() }
        Spacer(Modifier.height(16.dp))
    }
}

// ============================================================================ the channel
@Composable
private fun ChannelView(onAllowMic: () -> Unit, onLeave: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Walkie.banner?.let { b ->
            Note(Icons.Rounded.SwapHoriz, b, ToolTint.TEAL, onClose = { Walkie.dismissBanner() })
            LaunchedEffect(b) {
                delay(10_000)
                if (Walkie.banner == b) Walkie.dismissBanner()
            }
        }
        when {
            Walkie.phase == WalkiePhase.SWITCHING -> Note(Icons.Rounded.Sync, "Finding the channel again… it carries on by itself.", ToolTint.AMBER)
            Walkie.inPhoneCall -> Note(Icons.Rounded.PhoneAndroid, "Paused for your phone call — back after it.", ToolTint.AMBER)
        }
        Walkie.callFrom?.let { Note(Icons.Rounded.NotificationsActive, "$it is calling everyone", ToolTint.TEAL) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 14.dp)) {
            Icon(
                if (Walkie.isHub) Icons.Rounded.Star else Icons.Rounded.RecordVoiceOver, null,
                tint = if (Walkie.isHub) ToolTint.AMBER.icon else cs.onSurfaceVariant, modifier = Modifier.size(15.dp),
            )
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
            Note(Icons.Rounded.Headphones, "Phones close together can echo. Earphones work best on an open line.", ToolTint.AMBER)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)) {
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
            ToolButton(Icons.AutoMirrored.Rounded.Logout, "Leave", on = false, color = cs.error, modifier = Modifier.weight(1f), onClick = onLeave)
        }
    }
}

@Composable
private fun PeopleGrid() {
    val cs = MaterialTheme.colorScheme
    val people = Walkie.members.toList()
    val cols = if (people.size == 5) 5 else 4
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        people.chunked(cols).take(3).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { m ->
                    val me = Walkie.isMe(m)
                    val talking = (me && Walkie.onAir) || (!me && (m.id in Walkie.speaking || Walkie.floorId == m.id))
                    val onAir = me && Walkie.onAir
                    // Who's talking gets a ring: red for you on air, teal for the others.
                    val ring = when {
                        onAir -> Red
                        talking -> TealBright
                        else -> Color.Transparent
                    }
                    val tint = when {
                        onAir -> ToolTint.RED
                        talking || me -> ToolTint.TEAL
                        else -> null
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Box {
                            Box(
                                Modifier.size(56.dp)
                                    .then(if (tint != null) Modifier.clip(CircleShape).background(tint.tile) else Modifier.appCard(CircleShape))
                                    .border(3.dp, ring, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(m.name.take(1).uppercase(), fontSize = 18.sp, fontWeight = FontWeight.Medium, color = tint?.icon ?: cs.onSurface)
                            }
                            if (m.token == Walkie.hostToken) {
                                Box(
                                    Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp).size(20.dp).clip(CircleShape).background(cs.surface),
                                    contentAlignment = Alignment.Center,
                                ) { Icon(Icons.Rounded.Star, "Runs the channel", tint = ToolTint.AMBER.icon, modifier = Modifier.size(14.dp)) }
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
                                onAir -> cs.error
                                talking || me -> ToolTint.TEAL.icon
                                else -> cs.onSurface
                            },
                            fontWeight = if (talking || me) FontWeight.Medium else FontWeight.Normal,
                            modifier = Modifier.padding(top = 5.dp),
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
            Pill(Icons.Rounded.RecordVoiceOver, "${Walkie.floorName ?: "Someone"} is talking · ${clock(now - Walkie.floorSinceMs)}")
        }
        val usable = Walkie.phase == WalkiePhase.IN_CHANNEL && !Walkie.inPhoneCall
        // One flat colour: teal ready, red on air, grey while it can't be used.
        val (fill, title, sub) = when {
            !Walkie.micAllowed -> Triple(cs.surfaceContainerHighest, "Allow microphone", "to talk — you can listen now")
            !usable -> Triple(cs.surfaceContainerHighest, if (Walkie.inPhoneCall) "Paused" else "Reconnecting…", "")
            Walkie.onAir -> Triple(Red, "You're on air", "${clock(now - Walkie.talkStartedMs)} · let go to stop")
            Walkie.asking -> Triple(Color(0xFF115E59), "Connecting…", "keep holding")
            other != null -> Triple(cs.surfaceContainerHighest, "Wait…", "your turn when ${Walkie.floorName ?: "they"} stops")
            else -> Triple(Teal, "Hold to talk", "up to 1 minute")
        }
        val grey = fill == cs.surfaceContainerHighest
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
            } else if (!grey) {
                // The soft teal ring around the button.
                Box(Modifier.size(224.dp).clip(CircleShape).background(ToolTint.TEAL.tile))
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                modifier = Modifier.size(196.dp).clip(CircleShape).background(fill)
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
        if (Walkie.volumeKeyTalk) Pill(Icons.Rounded.PhoneAndroid, "Volume-key talk on")
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
            // A soft green ring while your voice is going out.
            if (on && Walkie.onAir) Box(Modifier.size(224.dp).clip(CircleShape).background(ToolTint.GREEN.tile))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                modifier = Modifier.size(196.dp).clip(CircleShape)
                    .background(if (on) Green else cs.surfaceContainerHighest)
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
            MenuItem(Icons.Rounded.HighlightOff, "End channel", "For everyone", color = cs.error) { onClose(); onEnd() }
        } else {
            MenuItem(Icons.AutoMirrored.Rounded.Logout, "Leave channel", null, color = cs.error) {
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
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = cs.surfaceContainerLowest,
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text("Leave ${Walkie.channelName}?", fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text("You run this channel. Keep it going for the others, or end it.", fontSize = 13.5.sp, lineHeight = 19.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(ToolTint.TEAL.tile.copy(alpha = .5f))
                    .border(2.dp, ToolTint.TEAL.icon, RoundedCornerShape(20.dp))
                    .clickable {
                        onDismiss()
                        next?.let { Walkie.handOver(it, leave = true) }
                    }.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            ) {
                ToolTile(Icons.Rounded.SwapHoriz, ToolTint.TEAL, size = 40.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Hand over & leave", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("${next?.name ?: "Someone"} will run the channel", fontSize = 12.5.sp, color = cs.onSurfaceVariant)
                }
                TextButton(onClick = onChange) { Text("Change", color = ToolTint.TEAL.icon) }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).border(1.dp, cs.outlineVariant, RoundedCornerShape(20.dp))
                    .clickable(onClick = onEnd).padding(14.dp),
            ) {
                ToolTile(Icons.Rounded.HighlightOff, ToolTint.RED, size = 40.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("End channel for everyone", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.error)
                    Text("Everyone's walkie-talkie stops", fontSize = 12.5.sp, color = cs.onSurfaceVariant)
                }
            }
            OutlinePill("Stay", Modifier.padding(top = 16.dp).fillMaxWidth(), onDismiss)
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
        containerColor = MaterialTheme.colorScheme.dialogSurface,
    )
}

// ============================================================================ small pieces
@Composable
private fun ModeChip(mode: TalkMode) = SmallChip(if (mode == TalkMode.HOLD) Icons.Rounded.PanTool else Icons.Rounded.Mic, mode.label(), teal = true)

@Composable
private fun SmallChip(icon: ImageVector, text: String, teal: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val bg = if (teal) ToolTint.TEAL.tile else cs.surfaceContainer
    val fg = if (teal) ToolTint.TEAL.icon else cs.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 11.5.sp, lineHeight = 14.sp, color = fg, maxLines = 1)
    }
}

/** A soft teal pill: who's talking, "Volume-key talk on". */
@Composable
private fun Pill(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(ToolTint.TEAL.tile).padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Icon(icon, null, tint = ToolTint.TEAL.icon, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = ToolTint.TEAL.icon)
    }
}

/** A note on a soft tile colour (teal news, amber warnings), with an optional ✕. */
@Composable
private fun Note(icon: ImageVector, text: String, tint: ToolTint, onClose: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tint.tile).padding(start = 14.dp, end = 4.dp).heightIn(min = 44.dp),
    ) {
        Icon(icon, null, tint = tint.icon, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 13.sp, lineHeight = 18.sp, color = cs.onSurface, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
        if (onClose != null) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, "Close", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        } else {
            Spacer(Modifier.width(10.dp))
        }
    }
}

/** One of the four buttons under the talk button: a white tile, soft teal while it's on. */
@Composable
private fun ToolButton(icon: ImageVector, label: String, on: Boolean, modifier: Modifier, enabled: Boolean = true, color: Color = Color.Unspecified, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val fg = when {
        color != Color.Unspecified -> color
        on -> ToolTint.TEAL.icon
        else -> cs.onSurfaceVariant
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        modifier = modifier.height(64.dp)
            .then(if (on) Modifier.clip(shape).background(ToolTint.TEAL.tile) else Modifier.appCard(shape))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
        Text(label, fontSize = 11.5.sp, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp))
    }
}

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

@Composable
private fun Letter(name: String) {
    Box(
        Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(ToolTint.TEAL.tile),
        contentAlignment = Alignment.Center,
    ) { Text(name.take(1).uppercase(), color = ToolTint.TEAL.icon, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
}

/** The code both phones show, on a white card. */
@Composable
private fun CodeBox(code: String, modifier: Modifier = Modifier) {
    Text(
        code, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 8.sp, color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.appCard(RoundedCornerShape(20.dp)).padding(start = 36.dp, end = 28.dp, top = 12.dp, bottom = 12.dp),
    )
}

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
