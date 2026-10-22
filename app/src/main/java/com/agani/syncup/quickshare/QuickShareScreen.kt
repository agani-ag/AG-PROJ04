package com.agani.syncup.quickshare

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.SwipeDown
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.agani.syncup.browser.ui.SheetHeader
import com.agani.syncup.downloads.FileKind
import com.agani.syncup.downloads.Files
import com.agani.syncup.music.CardDivider
import com.agani.syncup.music.SectionHead
import com.agani.syncup.music.ToolTile
import com.agani.syncup.music.ToolTint
import com.agani.syncup.music.ToolTopBar
import com.agani.syncup.music.cardSlice
import com.agani.syncup.ui.theme.CardShape
import com.agani.syncup.ui.theme.appCard
import com.agani.syncup.ui.theme.dialogSurface
import com.agani.syncup.ui.theme.success
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tools → SyncUp Share: send files or text to / receive them from a nearby SyncUp phone. See [QuickShare]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickShareScreen(onBack: () -> Unit, onOpenDownloads: () -> Unit, onOpenLink: (String) -> Unit = {}) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    remember { QuickShare.init(context) }
    var pickSheet by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var permissionNote by remember { mutableStateOf(false) }
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    // Send → Text or link: the "Send text" screen, and what's typed there (kept while it's being sent).
    var composing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var draftSecret by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(Unit) {
        QuickShare.screenVisible = true
        onDispose { QuickShare.onScreenLeft() }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val action = afterPermission
        afterPermission = null
        if (result.values.all { it }) {
            permissionNote = false
            action?.invoke()
        } else {
            permissionNote = true
        }
    }

    // After the permissions: Bluetooth and Wi-Fi (and Location on Android 12 and older) must be on.
    // While something's off, the "Turn on" dialog holds the send / receive and runs it once all are on.
    var waitingForRadios by remember { mutableStateOf<(() -> Unit)?>(null) }
    var radiosReceiving by remember { mutableStateOf(false) }

    fun withRadios(receiving: Boolean, action: () -> Unit) {
        if (Radios.missing(context).isEmpty()) {
            action()
        } else {
            radiosReceiving = receiving
            waitingForRadios = action
        }
    }

    /** Permissions, then the switches, then [action]. Every send / receive starts here. */
    fun withPermissions(receiving: Boolean, action: () -> Unit) {
        // Both use Nearby: one at a time.
        if (com.agani.syncup.group.GroupLink.active) {
            Toast.makeText(context, "Leave the group first — or send to the group with its Share files", Toast.LENGTH_LONG).show()
            return
        }
        if (com.agani.syncup.walkie.Walkie.active) {
            Toast.makeText(context, "Leave Walkie Talkie first (Tools → Walkie Talkie)", Toast.LENGTH_LONG).show()
            return
        }
        val next = { withRadios(receiving, action) }
        val missing = QuickShare.missingPermissions(receiving)
        if (missing.isEmpty()) {
            permissionNote = false
            next()
        } else {
            afterPermission = next
            permissions.launch(missing.toTypedArray())
        }
    }

    fun send(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            val list = withContext(Dispatchers.IO) { QuickShare.describe(uris) }
            if (list.isEmpty()) {
                Toast.makeText(context, "Couldn't read those files", Toast.LENGTH_SHORT).show()
                return@launch
            }
            QuickShare.setPicked(list)
            withPermissions(receiving = false) { QuickShare.startSending() }
        }
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { send(it) }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { send(it) }

    // "Share → SyncUp Share" from another app: start sending those straight away.
    val sharedIn = QuickShare.sharedIn
    LaunchedEffect(sharedIn) {
        if (sharedIn.isNotEmpty()) {
            QuickShare.sharedIn = emptyList()
            if (!QuickShare.busy) send(sharedIn)
        }
    }

    // Text shared from another app: Send text, filled in.
    val sharedText = QuickShare.sharedText
    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            QuickShare.sharedText = null
            if (!QuickShare.busy) {
                QuickShare.reset()
                draft = sharedText.take(QuickShare.MAX_TEXT)
                draftSecret = false
                composing = true
            }
        }
    }

    fun writeText() {
        if (!QuickShare.busy) QuickShare.reset()
        composing = true
    }

    fun sendText() {
        if (draft.isBlank()) return
        QuickShare.setText(draft, draftSecret)
        // Back on the page: a missing permission shows its note there; the draft stays for "Change".
        composing = false
        withPermissions(receiving = false) { QuickShare.startSending() }
    }

    // The Quick Settings tile: straight to Receive (asking for the permission first if needed).
    val receiveRequested = QuickShare.receiveRequested
    LaunchedEffect(receiveRequested) {
        if (receiveRequested) {
            QuickShare.receiveRequested = false
            if (!QuickShare.busy) withPermissions(receiving = true) { QuickShare.startReceiving() }
        }
    }

    // "Show in Quick Settings" switch: on = ask Android to add the tile, off = take it out again.
    var tileHelp by remember { mutableStateOf(false) }
    fun setTile(on: Boolean) {
        if (!on) {
            SyncUpShareTileService.turnOff(context)
            Toast.makeText(context, "SyncUp Share removed from Quick Settings", Toast.LENGTH_SHORT).show()
            return
        }
        SyncUpShareTileService.turnOn(context) { added ->
            if (added) {
                Toast.makeText(context, "SyncUp Share is in your Quick Settings now", Toast.LENGTH_SHORT).show()
            } else if (Build.VERSION.SDK_INT < 33) {
                tileHelp = true // no "Add tile?" prompt before Android 13 — explain how to add it by hand
            }
        }
    }

    fun back() {
        when {
            QuickShare.busy -> confirmStop = true
            QuickShare.phase != QsPhase.IDLE -> QuickShare.reset()
            composing -> composing = false
            else -> onBack()
        }
    }
    BackHandler(enabled = QuickShare.phase != QsPhase.IDLE || composing) { back() }
    val writing = composing && QuickShare.phase == QsPhase.IDLE

    Box(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            ToolTopBar(if (writing) "Send text" else "SyncUp Share", onBack = ::back)
            when (QuickShare.phase) {
                QsPhase.IDLE -> if (writing) TextComposer(
                    value = draft,
                    onValue = { draft = it },
                    secret = draftSecret,
                    onSecret = { draftSecret = it },
                    onChoosePhone = ::sendText,
                ) else Home(
                    permissionNote = permissionNote,
                    onSend = { pickSheet = true },
                    onReceive = { withPermissions(receiving = true) { QuickShare.startReceiving() } },
                    onRename = { renaming = true },
                    onOpenSettings = { openAppSettings(context) },
                    tileOn = QuickShare.tileAdded,
                    onTileChange = ::setTile,
                )
                QsPhase.SEARCHING -> Searching(onChange = { if (QuickShare.textMode) writeText() else pickSheet = true }, onCancel = { QuickShare.reset() })
                QsPhase.VISIBLE -> Visible(onRename = { renaming = true }, onStop = { QuickShare.reset() })
                QsPhase.CONFIRM -> Confirm(onAccept = { QuickShare.accept() }, onDecline = { QuickShare.decline() })
                QsPhase.CONNECTING -> Connecting(onCancel = { QuickShare.cancel() })
                QsPhase.TRANSFERRING -> Transferring(onCancel = { confirmStop = true })
                QsPhase.DONE -> if (QuickShare.textMode) TextFinished(
                    onOpenLink = onOpenLink,
                    onAgain = { if (QuickShare.role == QsRole.SEND) pickSheet = true else withPermissions(true) { QuickShare.startReceiving() } },
                    onDone = { QuickShare.reset() },
                ) else Finished(
                    onOpen = { openFile(context, it) },
                    onDownloads = onOpenDownloads,
                    onAgain = { if (QuickShare.role == QsRole.SEND) pickSheet = true else withPermissions(true) { QuickShare.startReceiving() } },
                    onDone = { QuickShare.reset() },
                )
                QsPhase.FAILED -> Failed(
                    onRetry = {
                        if (QuickShare.role == QsRole.SEND) withPermissions(false) { QuickShare.retrySend() }
                        else withPermissions(true) { QuickShare.startReceiving() }
                    },
                    onDone = { QuickShare.reset() },
                )
            }
        }
    }

    if (pickSheet) {
        ModalBottomSheet(onDismissRequest = { pickSheet = false }, containerColor = cs.surfaceContainerLowest) {
            SheetHeader("Send to a nearby phone", "Pick what to send — nothing is uploaded")
            SheetOption(Icons.Rounded.PhotoLibrary, "Photos & videos") {
                pickSheet = false
                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }
            SheetOption(Icons.Rounded.FolderOpen, "Files") {
                pickSheet = false
                pickFiles.launch(arrayOf("*/*"))
            }
            SheetOption(Icons.Rounded.TextFields, "Text or link") {
                pickSheet = false
                writeText()
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    waitingForRadios?.let { pending ->
        RadiosDialog(
            receiving = radiosReceiving,
            onReady = {
                waitingForRadios = null
                pending()
            },
            onCancel = { waitingForRadios = null },
        )
    }

    if (renaming) {
        var name by remember { mutableStateOf(QuickShare.myName) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Your name on SyncUp Share") },
            text = {
                Column {
                    Text("Nearby phones see this when you send or receive.", fontSize = 13.sp, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    QuickShare.rename(name)
                    renaming = false
                    // Already visible: show up under the new name.
                    if (QuickShare.phase == QsPhase.VISIBLE) QuickShare.startReceiving()
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
            containerColor = cs.dialogSurface,
        )
    }

    if (tileHelp) {
        AlertDialog(
            onDismissRequest = { tileHelp = false },
            title = { Text("Add to Quick Settings") },
            text = {
                Text(
                    "If SyncUp Share isn't in your swipe-down panel yet: swipe down twice from the top of the screen, tap Edit (the pencil — on Samsung, ⋮ → Edit buttons), " +
                        "then drag SyncUp Share into your buttons.",
                )
            },
            confirmButton = { TextButton(onClick = { tileHelp = false }) { Text("OK") } },
            containerColor = cs.dialogSurface,
        )
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Stop the transfer?") },
            text = { Text("Files that haven't finished won't arrive.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    QuickShare.cancel()
                }) { Text("Stop", color = cs.error) }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Keep going") } },
            containerColor = cs.dialogSurface,
        )
    }
}

// ============================================================================ home
@Composable
private fun Home(
    permissionNote: Boolean,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onRename: () -> Unit,
    onOpenSettings: () -> Unit,
    /** The SyncUp Share tile is in Quick Settings. */
    tileOn: Boolean,
    onTileChange: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val history = QuickShare.history
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)) {
        item(key = "actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                ActionCard(Icons.Rounded.ArrowUpward, "Send", "Files, photos or text for a nearby phone", ToolTint.BLUE, Modifier.weight(1f), onSend)
                ActionCard(Icons.Rounded.ArrowDownward, "Receive", "Let a nearby phone send to you", ToolTint.GREEN, Modifier.weight(1f), onReceive)
            }
        }
        if (permissionNote) {
            item(key = "permission") {
                Column(
                    Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(cs.errorContainer).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
                ) {
                    Text(
                        "SyncUp Share needs the Nearby devices permission to find the other phone. It isn't used for anything else.",
                        fontSize = 13.sp, lineHeight = 18.sp, color = cs.onErrorContainer,
                    )
                    TextButton(onClick = onOpenSettings, contentPadding = PaddingValues(0.dp)) { Text("Open settings", color = cs.onErrorContainer, fontWeight = FontWeight.Medium) }
                }
            }
        }
        item(key = "me") {
            Column(Modifier.padding(top = 12.dp).fillMaxWidth().appCard(CardShape)) {
                ShareRow(
                    title = QuickShare.myName,
                    subtitle = "Your name on SyncUp Share",
                    leading = { ToolTile(Icons.Rounded.Smartphone, ToolTint.SLATE) },
                    trailing = { Icon(Icons.Rounded.Edit, "Change name", tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp)) },
                    onClick = onRename,
                )
                CardDivider()
                ShareRow(
                    title = "Show in Quick Settings",
                    subtitle = if (tileOn) "SyncUp Share is in your swipe-down panel" else "Add a SyncUp Share button to your swipe-down panel",
                    leading = { ToolTile(Icons.Rounded.SwipeDown, ToolTint.SLATE) },
                    trailing = { Switch(checked = tileOn, onCheckedChange = onTileChange) },
                    onClick = { onTileChange(!tileOn) },
                )
            }
        }
        item(key = "info") {
            Text(
                "Both phones need SyncUp, with Bluetooth and Wi-Fi on. Files go straight from phone to phone — nothing is uploaded and no mobile data is used.",
                fontSize = 12.5.sp, lineHeight = 18.sp, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 12.dp),
            )
        }
        if (history.isNotEmpty()) {
            item(key = "recent") { SectionHead("Recent", action = { HeadAction("Clear") { QuickShare.clearHistory() } }) }
            itemsIndexed(history, key = { _, r -> r.at }) { i, r ->
                val slice = Modifier.cardSlice(first = i == 0, last = i == history.lastIndex)
                if (r.isText) {
                    TextRecentRow(r, ago(r.at), slice)
                    return@itemsIndexed
                }
                ShareRow(
                    title = if (r.incoming) "Received from ${r.peer}" else "Sent to ${r.peer}",
                    subtitle = buildString {
                        append(if (r.count == 1) "1 file" else "${r.count} files")
                        if (r.bytes > 0) append(" · ${Files.size(r.bytes)}")
                        append(" · ${ago(r.at)}")
                        if (!r.ok) append(" · didn't finish")
                    },
                    modifier = slice,
                    leading = {
                        ToolTile(
                            if (r.incoming) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
                            when {
                                !r.ok -> ToolTint.RED
                                r.incoming -> ToolTint.GREEN
                                else -> ToolTint.BLUE
                            },
                        )
                    },
                )
            }
        }
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
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
        Text(subtitle, fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
    }
}

/** One choice in the Send sheet: the icon on a soft blue tile, then the label. */
@Composable
private fun SheetOption(icon: ImageVector, title: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 60.dp).padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        ToolTile(icon, ToolTint.BLUE, size = 40.dp)
        Spacer(Modifier.width(16.dp))
        Text(title, fontSize = 15.sp, color = cs.onSurface)
    }
}

// ============================================================================ send: choose a phone
@Composable
private fun Searching(onChange: () -> Unit, onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val picked = QuickShare.picked
    val nearby = QuickShare.nearby
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)) {
            val text = QuickShare.outText
            if (QuickShare.textMode && text != null) {
                item(key = "what") { TextOfferCard(text, QuickShare.outSecret, onChange) }
            } else item(key = "what") {
                Column(Modifier.fillMaxWidth().appCard(CardShape).padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${if (picked.size == 1) "1 file" else "${picked.size} files"} · ${Files.size(picked.sumOf { it.size.coerceAtLeast(0) })}",
                            fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onChange) { Text("Change") }
                    }
                    picked.take(3).forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp, top = 5.dp, bottom = 5.dp)) {
                            Icon(kindIcon(p.name, p.mime), null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(p.name, fontSize = 13.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (p.size >= 0) Text(Files.size(p.size), fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    if (picked.size > 3) {
                        Text("and ${picked.size - 3} more", fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 28.dp, top = 2.dp))
                    }
                }
            }
            item(key = "head") { SectionHead("Nearby phones") }
            itemsIndexed(nearby, key = { _, phone -> "p" + phone.endpointId }) { i, phone ->
                ShareRow(
                    title = phone.name,
                    subtitle = "Tap to send",
                    modifier = Modifier.cardSlice(first = i == 0, last = false),
                    leading = { Initial(phone.name) },
                    trailing = { Icon(Icons.Rounded.ChevronRight, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp)) },
                    onClick = { QuickShare.sendTo(phone) },
                )
            }
            item(key = "looking") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.cardSlice(first = nearby.isEmpty(), last = true).fillMaxWidth().heightIn(min = 60.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.5.dp, trackColor = cs.outlineVariant)
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(
                        if (nearby.isEmpty()) "Looking for phones… On the other phone, open Tools → SyncUp Share → Receive."
                        else "Still looking for more phones…",
                        fontSize = 13.sp, lineHeight = 18.sp, color = cs.onSurfaceVariant,
                    )
                }
            }
        }
        BottomButtons { OutlinePill("Cancel", Modifier.fillMaxWidth(), onCancel) }
    }
}

// ============================================================================ receive: waiting
@Composable
private fun Visible(onRename: () -> Unit, onStop: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Two soft green rings: this phone is out there, ready.
            Box(Modifier.size(132.dp).clip(CircleShape).background(ToolTint.GREEN.tile.copy(alpha = .55f)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(96.dp).clip(CircleShape).background(ToolTint.GREEN.tile), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.WifiTethering, null, tint = ToolTint.GREEN.icon, modifier = Modifier.size(44.dp))
                }
            }
            Spacer(Modifier.height(28.dp))
            Text("Ready to receive", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
            Spacer(Modifier.height(6.dp))
            Text("Nearby phones see you as", fontSize = 14.sp, color = cs.onSurfaceVariant)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(50)).clickable(onClick = onRename).padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(QuickShare.myName, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = cs.primary)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Rounded.Edit, "Change name", tint = cs.primary, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "On the other phone: Tools → SyncUp Share → Send, then pick this phone.",
                fontSize = 13.sp, lineHeight = 19.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
        BottomButtons { OutlinePill("Stop receiving", Modifier.fillMaxWidth(), onStop) }
    }
}

// ============================================================================ receive: accept?
@Composable
private fun Confirm(onAccept: () -> Unit, onDecline: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Initial(QuickShare.peerName, size = 76, soft = true)
            Spacer(Modifier.height(20.dp))
            Text(QuickShare.peerName, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, textAlign = TextAlign.Center)
            Text(
                when {
                    QuickShare.textMode && QuickShare.secret -> "wants to send you a secret"
                    QuickShare.textMode -> "wants to send you text · ${"%,d".format(QuickShare.offerChars)} character${if (QuickShare.offerChars == 1) "" else "s"}"
                    QuickShare.offerCount == 1 -> "wants to send you 1 file · ${Files.size(QuickShare.offerBytes)}"
                    QuickShare.offerCount > 1 -> "wants to send you ${QuickShare.offerCount} files · ${Files.size(QuickShare.offerBytes)}"
                    else -> "wants to send you files"
                },
                fontSize = 14.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(28.dp))
            CodeBox(QuickShare.code)
            Spacer(Modifier.height(12.dp))
            Text("Check the other phone shows the same code.", fontSize = 13.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        BottomButtons {
            OutlinePill("Decline", Modifier.weight(1f), onDecline)
            Button(onClick = onAccept, modifier = Modifier.weight(1f).height(48.dp)) { Text("Accept", fontSize = 15.sp) }
        }
    }
}

// ============================================================================ connecting
@Composable
private fun Connecting(onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val sending = QuickShare.role == QsRole.SEND
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(48.dp), strokeWidth = 3.dp, trackColor = cs.outlineVariant)
            Spacer(Modifier.height(24.dp))
            Text(
                if (sending) "Waiting for ${QuickShare.peerName} to accept" else "Connecting to ${QuickShare.peerName}…",
                fontSize = 18.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, textAlign = TextAlign.Center,
            )
            if (QuickShare.code.isNotBlank()) {
                Spacer(Modifier.height(24.dp))
                CodeBox(QuickShare.code)
                Spacer(Modifier.height(12.dp))
                Text("Check the other phone shows the same code.", fontSize = 13.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        BottomButtons { OutlinePill("Cancel", Modifier.fillMaxWidth(), onCancel) }
    }
}

// ============================================================================ transferring
@Composable
private fun Transferring(onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val sending = QuickShare.role == QsRole.SEND
    val total = QuickShare.totalBytes
    val done = QuickShare.doneBytes
    val speed = QuickShare.speed
    if (QuickShare.textMode) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(48.dp), strokeWidth = 3.dp, trackColor = cs.outlineVariant)
                Spacer(Modifier.height(24.dp))
                Text(
                    if (sending) "Sending text to ${QuickShare.peerName}…" else "Receiving text from ${QuickShare.peerName}…",
                    fontSize = 18.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, textAlign = TextAlign.Center,
                )
            }
            BottomButtons { OutlinePill("Cancel", Modifier.fillMaxWidth(), onCancel) }
        }
        return
    }
    val files = QuickShare.files
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)) {
            item(key = "progress") {
                Column(Modifier.fillMaxWidth().appCard(CardShape).padding(16.dp)) {
                    Text(
                        if (sending) "Sending to ${QuickShare.peerName}" else "Receiving from ${QuickShare.peerName}",
                        fontSize = 17.sp, fontWeight = FontWeight.Medium, color = cs.onSurface,
                    )
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f },
                        strokeCap = StrokeCap.Round, trackColor = cs.outlineVariant,
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        buildString {
                            append(if (total > 0) "${Files.size(done)} of ${Files.size(total)}" else "Starting…")
                            if (speed > 0) {
                                append(" · ${Files.size(speed)}/s")
                                if (total > done) append(" · about ${Files.duration((total - done) / speed)} left")
                            }
                        },
                        fontSize = 13.sp, color = cs.onSurfaceVariant,
                    )
                }
            }
            item(key = "gap") { Spacer(Modifier.height(12.dp)) }
            itemsIndexed(files, key = { _, f -> f.payloadId }) { i, f ->
                FileRow(f, Modifier.cardSlice(first = i == 0, last = i == files.lastIndex))
            }
        }
        BottomButtons { OutlinePill("Cancel", Modifier.fillMaxWidth(), onCancel) }
    }
}

@Composable
private fun FileRow(f: QsFile, modifier: Modifier, onOpen: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        ToolTile(kindIcon(f.name, f.mime), ToolTint.SLATE, size = 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when (f.state) {
                QsFileState.MOVING -> {
                    LinearProgressIndicator(
                        progress = { if (f.size > 0) (f.done.toFloat() / f.size).coerceIn(0f, 1f) else 0f },
                        strokeCap = StrokeCap.Round, trackColor = cs.outlineVariant,
                        modifier = Modifier.padding(top = 7.dp).fillMaxWidth().height(4.dp),
                    )
                }
                else -> Text(
                    when (f.state) {
                        QsFileState.WAITING -> "Waiting · ${Files.size(f.size)}"
                        QsFileState.SAVING -> "Saving…"
                        QsFileState.DONE -> if (onOpen != null) "${Files.size(f.size)} · tap to open" else Files.size(f.size)
                        QsFileState.FAILED -> "Didn't arrive"
                        else -> ""
                    },
                    fontSize = 12.5.sp, color = if (f.state == QsFileState.FAILED) cs.error else cs.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        when (f.state) {
            QsFileState.DONE -> Icon(Icons.Rounded.CheckCircle, "Done", tint = cs.success, modifier = Modifier.size(20.dp))
            QsFileState.FAILED -> Icon(Icons.Rounded.Close, "Failed", tint = cs.error, modifier = Modifier.size(20.dp))
            QsFileState.SAVING -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            else -> Unit
        }
    }
}

// ============================================================================ finished
@Composable
private fun Finished(onOpen: (QsFile) -> Unit, onDownloads: () -> Unit, onAgain: () -> Unit, onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val sending = QuickShare.role == QsRole.SEND
    val list = QuickShare.files
    val n = list.size
    val files = if (n == 1) "1 file" else "$n files"
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            item(key = "done") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    DoneMark()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (sending) "Sent $files to ${QuickShare.peerName}" else "Received $files from ${QuickShare.peerName}",
                        fontSize = 18.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, textAlign = TextAlign.Center,
                    )
                    if (!sending) {
                        Text("Saved in Download / SyncUp", fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            itemsIndexed(list, key = { _, f -> f.payloadId }) { i, f ->
                FileRow(
                    f, Modifier.cardSlice(first = i == 0, last = i == list.lastIndex),
                    onOpen = if (!sending && f.savedUri != null) ({ onOpen(f) }) else null,
                )
            }
        }
        BottomButtons {
            if (sending) {
                OutlinePill("Send more", Modifier.weight(1f), onAgain)
            } else {
                OutlinePill("Downloads", Modifier.weight(1f), onDownloads)
            }
            Button(onClick = onDone, modifier = Modifier.weight(1f).height(48.dp)) { Text("Done", fontSize = 15.sp) }
        }
    }
}

@Composable
private fun Failed(onRetry: () -> Unit, onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val sending = QuickShare.role == QsRole.SEND
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(76.dp).clip(CircleShape).background(ToolTint.RED.tile), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = ToolTint.RED.icon, modifier = Modifier.size(38.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                QuickShare.error ?: "Something went wrong.",
                fontSize = 16.sp, lineHeight = 22.sp, color = cs.onSurface, textAlign = TextAlign.Center,
            )
        }
        BottomButtons {
            OutlinePill(if (sending) "Try again" else "Receive again", Modifier.weight(1f), onRetry)
            Button(onClick = onDone, modifier = Modifier.weight(1f).height(48.dp)) { Text("Done", fontSize = 15.sp) }
        }
    }
}

// ============================================================================ bits
/**
 * A row inside a card (Recent, nearby phones): tile, title and one line, then whatever sits on the
 * right. Put it in a [cardSlice] or an appCard.
 */
@Composable
internal fun ShareRow(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    endPadding: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 64.dp).padding(start = 16.dp, end = endPadding, top = 10.dp, bottom = 10.dp),
    ) {
        leading()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle, fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
    }
}

/** A small text action at the end of a section heading ("Clear"). */
@Composable
private fun HeadAction(label: String, onClick: () -> Unit) {
    Text(
        label, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** The page's buttons, at the foot of the screen. */
@Composable
private fun BottomButtons(content: @Composable RowScope.() -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
        content = content,
    )
}

/** An outlined pill on the card colour (Cancel, Decline, Send more). */
@Composable
internal fun OutlinePill(label: String, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(1.dp, cs.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = cs.surfaceContainerLowest, contentColor = cs.onSurface),
        modifier = modifier.height(48.dp),
    ) { Text(label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/** A tick on a soft green circle: it went through. */
@Composable
internal fun DoneMark() {
    Box(Modifier.size(76.dp).clip(CircleShape).background(ToolTint.GREEN.tile), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Check, null, tint = ToolTint.GREEN.icon, modifier = Modifier.size(40.dp))
    }
}

/** The code both phones show, on a white card. */
@Composable
private fun CodeBox(code: String) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.appCard(RoundedCornerShape(20.dp)).padding(start = 36.dp, end = 28.dp, top = 12.dp, bottom = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(code, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 8.sp, color = cs.onSurface)
    }
}

/** A round first-letter avatar: blue, or soft blue for the big one on the accept screen. */
@Composable
private fun Initial(name: String, size: Int = 36, soft: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(if (soft) ToolTint.BLUE.tile else cs.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).uppercase().ifBlank { "?" },
            fontSize = (size * 0.4f).sp, fontWeight = FontWeight.Medium, color = if (soft) ToolTint.BLUE.icon else cs.onPrimary,
        )
    }
}

private fun kindIcon(name: String, mime: String): ImageVector = when (FileKind.of(name, mime)) {
    FileKind.VIDEO -> Icons.Rounded.Movie
    FileKind.MUSIC -> Icons.Rounded.MusicNote
    FileKind.IMAGE -> Icons.Rounded.Image
    FileKind.DOCUMENT -> Icons.Rounded.Description
    FileKind.APP -> Icons.Rounded.Android
    FileKind.OTHER -> Icons.Rounded.InsertDriveFile
}

private fun openFile(context: android.content.Context, f: QsFile) {
    val saved = f.savedUri ?: return
    val uri = if (saved.startsWith("content://")) {
        Uri.parse(saved)
    } else {
        runCatching { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(saved)) }.getOrNull() ?: return
    }
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, Files.mimeFor(f.name, f.mime))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { Toast.makeText(context, "No app here can open this file", Toast.LENGTH_SHORT).show() }
}

private fun openAppSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun ago(at: Long): String {
    val minutes = (System.currentTimeMillis() - at) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(at))
    }
}
