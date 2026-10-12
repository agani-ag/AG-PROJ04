package com.agani.syncup.quickshare

import android.content.Intent
import android.os.Build
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
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
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.browser.ui.IconTile
import com.agani.syncup.browser.ui.MenuRow
import com.agani.syncup.browser.ui.SectionHeader
import com.agani.syncup.browser.ui.SheetHeader
import com.agani.syncup.browser.ui.TonalRow
import com.agani.syncup.downloads.FileKind
import com.agani.syncup.downloads.Files
import com.agani.syncup.ui.theme.isLight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Tools → SyncUp Share: send files to / receive files from a nearby SyncUp phone. See [QuickShare]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickShareScreen(onBack: () -> Unit, onOpenDownloads: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    remember { QuickShare.init(context) }
    var pickSheet by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var permissionNote by remember { mutableStateOf(false) }
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }

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

    fun withPermissions(receiving: Boolean, action: () -> Unit) {
        val missing = QuickShare.missingPermissions(receiving)
        if (missing.isEmpty()) {
            permissionNote = false
            action()
        } else {
            afterPermission = action
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
            else -> onBack()
        }
    }
    BackHandler(enabled = QuickShare.phase != QsPhase.IDLE) { back() }

    Box(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp)) {
                BarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = ::back)
                Text("SyncUp Share", fontSize = 22.sp, lineHeight = 28.sp, color = cs.onSurface, modifier = Modifier.weight(1f).padding(start = 4.dp))
            }
            when (QuickShare.phase) {
                QsPhase.IDLE -> Home(
                    permissionNote = permissionNote,
                    onSend = { pickSheet = true },
                    onReceive = { withPermissions(receiving = true) { QuickShare.startReceiving() } },
                    onRename = { renaming = true },
                    onOpenSettings = { openAppSettings(context) },
                    tileOn = QuickShare.tileAdded,
                    onTileChange = ::setTile,
                )
                QsPhase.SEARCHING -> Searching(onChange = { pickSheet = true }, onCancel = { QuickShare.reset() })
                QsPhase.VISIBLE -> Visible(onRename = { renaming = true }, onStop = { QuickShare.reset() })
                QsPhase.CONFIRM -> Confirm(onAccept = { QuickShare.accept() }, onDecline = { QuickShare.decline() })
                QsPhase.CONNECTING -> Connecting(onCancel = { QuickShare.cancel() })
                QsPhase.TRANSFERRING -> Transferring(onCancel = { confirmStop = true })
                QsPhase.DONE -> Finished(
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
        ModalBottomSheet(onDismissRequest = { pickSheet = false }) {
            SheetHeader("Send to a nearby phone", "Pick what to send — nothing is uploaded")
            MenuRow(Icons.Rounded.PhotoLibrary, "Photos & videos") {
                pickSheet = false
                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }
            MenuRow(Icons.Rounded.FolderOpen, "Files") {
                pickSheet = false
                pickFiles.launch(arrayOf("*/*"))
            }
            Spacer(Modifier.height(16.dp))
        }
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
    val light = cs.isLight
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) {
                ActionCard(
                    Icons.Rounded.ArrowUpward, "Send", "Pick files for a nearby phone",
                    Color(if (light) 0xFF1D4ED8 else 0xFF93C5FD), Color(if (light) 0xFFDBEAFE else 0xFF1E3A8A),
                    Modifier.weight(1f), onSend,
                )
                ActionCard(
                    Icons.Rounded.ArrowDownward, "Receive", "Let a nearby phone send to you",
                    Color(if (light) 0xFF15803D else 0xFF86EFAC), Color(if (light) 0xFFDCFCE7 else 0xFF14532D),
                    Modifier.weight(1f), onReceive,
                )
            }
        }
        if (permissionNote) {
            item {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(cs.errorContainer).padding(16.dp),
                ) {
                    Text(
                        "SyncUp Share needs the Nearby devices permission to find the other phone. It isn't used for anything else.",
                        fontSize = 13.sp, lineHeight = 18.sp, color = cs.onErrorContainer,
                    )
                    TextButton(onClick = onOpenSettings, contentPadding = PaddingValues(0.dp)) { Text("Open settings", color = cs.onErrorContainer, fontWeight = FontWeight.Medium) }
                }
            }
        }
        item {
            TonalRow(
                title = QuickShare.myName,
                subtitle = "Your name on SyncUp Share",
                leading = { IconTile(Icons.Rounded.Smartphone, container = cs.surfaceContainerHigh, content = cs.onSurfaceVariant) },
                trailing = { Icon(Icons.Rounded.Edit, "Change name", tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp)) },
                onClick = onRename,
            )
        }
        item {
            TonalRow(
                title = "Show in Quick Settings",
                subtitle = if (tileOn) "SyncUp Share is in your swipe-down panel" else "Add a SyncUp Share button to your swipe-down panel",
                leading = { IconTile(Icons.Rounded.SwipeDown, container = cs.surfaceContainerHigh, content = cs.onSurfaceVariant) },
                trailing = { Switch(checked = tileOn, onCheckedChange = onTileChange) },
                onClick = { onTileChange(!tileOn) },
            )
        }
        item {
            Text(
                "Both phones need SyncUp, with Bluetooth and Wi-Fi on. Files go straight from phone to phone — nothing is uploaded and no mobile data is used.",
                fontSize = 12.5.sp, lineHeight = 18.sp, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        if (QuickShare.history.isNotEmpty()) {
            item { SectionHeader("Recent", Modifier.padding(start = 20.dp, end = 12.dp, top = 12.dp), action = "Clear", onAction = { QuickShare.clearHistory() }) }
            items(QuickShare.history, key = { it.at }) { r ->
                TonalRow(
                    title = if (r.incoming) "Received from ${r.peer}" else "Sent to ${r.peer}",
                    subtitle = buildString {
                        append(if (r.count == 1) "1 file" else "${r.count} files")
                        if (r.bytes > 0) append(" · ${Files.size(r.bytes)}")
                        append(" · ${ago(r.at)}")
                        if (!r.ok) append(" · didn't finish")
                    },
                    leading = {
                        IconTile(
                            if (r.incoming) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
                            container = if (r.ok) cs.secondaryContainer else cs.errorContainer,
                            content = if (r.ok) cs.onSecondaryContainer else cs.onErrorContainer,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ActionCard(icon: ImageVector, title: String, subtitle: String, color: Color, container: Color, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.heightIn(min = 150.dp).clip(shape).background(cs.surfaceContainerLowest)
            .border(1.dp, cs.outlineVariant, shape).clickable(onClick = onClick).padding(16.dp),
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(container), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = color, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.weight(1f).heightIn(min = 16.dp))
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
        Text(subtitle, fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
    }
}

// ============================================================================ send: choose a phone
@Composable
private fun Searching(onChange: () -> Unit, onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val picked = QuickShare.picked
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                        .background(cs.surfaceContainerLow).padding(vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 8.dp)) {
                        Text(
                            "${if (picked.size == 1) "1 file" else "${picked.size} files"} · ${Files.size(picked.sumOf { it.size.coerceAtLeast(0) })}",
                            fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onChange) { Text("Change") }
                    }
                    picked.take(3).forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Icon(kindIcon(p.name, p.mime), null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(p.name, fontSize = 13.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (p.size >= 0) Text(Files.size(p.size), fontSize = 12.sp, color = cs.onSurfaceVariant)
                        }
                    }
                    if (picked.size > 3) {
                        Text("and ${picked.size - 3} more", fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 44.dp, vertical = 2.dp))
                    }
                }
            }
            item { SectionHeader("Nearby phones", Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) }
            items(QuickShare.nearby, key = { it.endpointId }) { phone ->
                TonalRow(
                    title = phone.name,
                    subtitle = "Tap to send",
                    leading = { Initial(phone.name) },
                    onClick = { QuickShare.sendTo(phone) },
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(
                        if (QuickShare.nearby.isEmpty()) "Looking for phones… On the other phone, open Tools → SyncUp Share → Receive."
                        else "Still looking for more phones…",
                        fontSize = 13.sp, lineHeight = 18.sp, color = cs.onSurfaceVariant,
                    )
                }
            }
        }
        BottomButtons { OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") } }
    }
}

// ============================================================================ receive: waiting
@Composable
private fun Visible(onRename: () -> Unit, onStop: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(1f, 1.12f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "scale")
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(132.dp).scale(scale).clip(CircleShape).background(cs.primaryContainer.copy(alpha = .5f)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(96.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.WifiTethering, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(44.dp))
                }
            }
            Spacer(Modifier.height(28.dp))
            Text("Ready to receive", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
            Spacer(Modifier.height(6.dp))
            Text("Nearby phones see you as", fontSize = 14.sp, color = cs.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onRename).padding(6.dp)) {
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
        BottomButtons { OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop receiving") } }
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
            Initial(QuickShare.peerName, size = 72)
            Spacer(Modifier.height(20.dp))
            Text(QuickShare.peerName, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, textAlign = TextAlign.Center)
            Text(
                when {
                    QuickShare.offerCount == 1 -> "wants to send you 1 file · ${Files.size(QuickShare.offerBytes)}"
                    QuickShare.offerCount > 1 -> "wants to send you ${QuickShare.offerCount} files · ${Files.size(QuickShare.offerBytes)}"
                    else -> "wants to send you files"
                },
                fontSize = 14.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(28.dp))
            CodeBox(QuickShare.code)
            Spacer(Modifier.height(10.dp))
            Text("Check the other phone shows the same code.", fontSize = 13.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        BottomButtons {
            OutlinedButton(onClick = onDecline, modifier = Modifier.weight(1f)) { Text("Decline") }
            Button(onClick = onAccept, modifier = Modifier.weight(1f)) { Text("Accept") }
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
            CircularProgressIndicator(modifier = Modifier.size(48.dp), strokeWidth = 3.dp)
            Spacer(Modifier.height(24.dp))
            Text(
                if (sending) "Waiting for ${QuickShare.peerName} to accept" else "Connecting to ${QuickShare.peerName}…",
                fontSize = 18.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, textAlign = TextAlign.Center,
            )
            if (QuickShare.code.isNotBlank()) {
                Spacer(Modifier.height(24.dp))
                CodeBox(QuickShare.code)
                Spacer(Modifier.height(10.dp))
                Text("Check the other phone shows the same code.", fontSize = 13.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        BottomButtons { OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") } }
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
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(
                        if (sending) "Sending to ${QuickShare.peerName}" else "Receiving from ${QuickShare.peerName}",
                        fontSize = 18.sp, fontWeight = FontWeight.Medium, color = cs.onSurface,
                    )
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f },
                        strokeCap = StrokeCap.Round,
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                    )
                    Spacer(Modifier.height(8.dp))
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
            items(QuickShare.files, key = { it.payloadId }) { f -> FileRow(f) }
        }
        BottomButtons { OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") } }
    }
}

@Composable
private fun FileRow(f: QsFile, onOpen: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        IconTile(kindIcon(f.name, f.mime), container = cs.surfaceContainerHigh, content = cs.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when (f.state) {
                QsFileState.MOVING -> {
                    LinearProgressIndicator(
                        progress = { if (f.size > 0) (f.done.toFloat() / f.size).coerceIn(0f, 1f) else 0f },
                        strokeCap = StrokeCap.Round,
                        modifier = Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp),
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
                    fontSize = 12.sp, color = if (f.state == QsFileState.FAILED) cs.error else cs.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        when (f.state) {
            QsFileState.DONE -> Icon(Icons.Rounded.CheckCircle, "Done", tint = cs.primary, modifier = Modifier.size(20.dp))
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
    val n = QuickShare.files.size
    val files = if (n == 1) "1 file" else "$n files"
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = cs.primary, modifier = Modifier.size(72.dp))
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
            items(QuickShare.files, key = { it.payloadId }) { f ->
                FileRow(f, onOpen = if (!sending && f.savedUri != null) ({ onOpen(f) }) else null)
            }
        }
        BottomButtons {
            if (sending) {
                OutlinedButton(onClick = onAgain, modifier = Modifier.weight(1f)) { Text("Send more") }
            } else {
                OutlinedButton(onClick = onDownloads, modifier = Modifier.weight(1f)) { Text("Downloads") }
            }
            Button(onClick = onDone, modifier = Modifier.weight(1f)) { Text("Done") }
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
            Icon(Icons.Rounded.ErrorOutline, null, tint = cs.error, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(16.dp))
            Text(
                QuickShare.error ?: "Something went wrong.",
                fontSize = 16.sp, lineHeight = 22.sp, color = cs.onSurface, textAlign = TextAlign.Center,
            )
        }
        BottomButtons {
            OutlinedButton(onClick = onRetry, modifier = Modifier.weight(1f)) { Text(if (sending) "Try again" else "Receive again") }
            Button(onClick = onDone, modifier = Modifier.weight(1f)) { Text("Done") }
        }
    }
}

// ============================================================================ bits
@Composable
private fun BottomButtons(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        content = content,
    )
}

@Composable
private fun CodeBox(code: String) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHigh).padding(horizontal = 28.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(code.toCharArray().joinToString(" "), fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, color = cs.onSurface)
    }
}

@Composable
private fun Initial(name: String, size: Int = 40) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.size(size.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
        Text(
            name.trim().take(1).uppercase().ifBlank { "?" },
            fontSize = (size * 0.42f).sp, fontWeight = FontWeight.Medium, color = cs.onPrimaryContainer,
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
