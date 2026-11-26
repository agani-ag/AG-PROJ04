package com.agani.syncup.cloud

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.browser.ui.EmptyState
import com.agani.syncup.music.ToolTint
import com.agani.syncup.music.ToolTopBar
import com.agani.syncup.music.cardSlice
import com.agani.syncup.stationfiles.FolderRef
import com.agani.syncup.stationfiles.PinSheet
import com.agani.syncup.stationfiles.ROOT
import com.agani.syncup.stationfiles.StationFolder
import com.agani.syncup.stationfiles.StationUpload
import com.agani.syncup.stationfiles.StationUploads
import com.agani.syncup.stationfiles.Stations
import com.agani.syncup.ui.theme.AlertDialog
import com.agani.syncup.ui.theme.ModalBottomSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// =============================================================================================== the bell
/**
 * Copies, moves and uploads, out of the way (user, 26 Nov 2026: not on the page itself): a bell in the top bar
 * with how many there are — nothing at all at 0, red when one failed. Tapping it opens the details, with Clear all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersBell() {
    val count = CloudTransfers.list.size + CloudUploads.list.size
    var open by remember { mutableStateOf(false) }
    if (count == 0) {
        open = false
        return
    }
    val cs = MaterialTheme.colorScheme
    val failed = CloudTransfers.list.any { it.state == Transfer.FAILED || StationUploads.items.any { u -> u.id == it.uploadId && u.state == StationUpload.FAILED } } ||
        CloudUploads.list.any { it.state == "failed" }
    val busy = CloudTransfers.busy || CloudUploads.busy
    Box(contentAlignment = Alignment.Center) {
        BarIcon(if (busy) Icons.Rounded.NotificationsActive else Icons.Rounded.Notifications, "Copies and uploads: $count") { open = true }
        Badge(
            containerColor = if (failed) cs.error else cs.primary,
            contentColor = if (failed) cs.onError else cs.onPrimary,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 4.dp),
        ) { Text(if (count > 99) "99+" else "$count") }
    }
    if (open) TransfersSheet(onDismiss = { open = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransfersSheet(onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val jobs = CloudTransfers.list
    val ups = CloudUploads.list
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = cs.surfaceContainerLowest) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 24.dp, end = 12.dp, bottom = 4.dp)) {
            val left = jobs.count { it.active } + ups.count { it.state == "waiting" || it.state == "uploading" }
            Column(Modifier.weight(1f)) {
                Text("Copies & uploads", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                val failed = jobs.count { it.state == Transfer.FAILED } + ups.count { it.state == "failed" }
                Text(
                    when {
                        left > 0 -> "$left still going"
                        failed > 0 -> "$failed didn't finish — Retry, or Clear all"
                        else -> "All finished"
                    },
                    fontSize = 12.5.sp, color = cs.onSurfaceVariant,
                )
            }
            // Finished and failed ones go; any still going carry on (their × stops them).
            TextButton(onClick = {
                CloudTransfers.clearDone()
                CloudUploads.clearDone()
                if (CloudTransfers.list.isEmpty() && CloudUploads.list.isEmpty()) onDismiss()
            }) { Text("Clear all") }
        }
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 24.dp, end = 12.dp, bottom = 28.dp)) {
            val sorted = jobs.sortedBy { if (it.active || it.state == Transfer.FAILED) 0 else 1 }
            itemsIndexed(sorted, key = { _, t -> "t" + t.id }) { _, t -> TransferRow(t) }
            itemsIndexed(ups.reversed(), key = { _, u -> "u" + u.id }) { _, u -> UploadRow(u) }
        }
    }
}

/** A file going up from this phone to Cloud. */
@Composable
private fun UploadRow(j: CloudUpload) {
    val cs = MaterialTheme.colorScheme
    val failed = j.state == "failed"
    Column(Modifier.padding(top = 10.dp, end = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(j.name, fontSize = 13.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(
                when (j.state) {
                    "done" -> "Uploaded"
                    "failed" -> "Failed"
                    "waiting" -> "Waiting"
                    else -> if (j.size > 0) "Uploading · ${(j.sent * 100 / j.size).coerceIn(0, 100)}%" else "Uploading"
                },
                fontSize = 12.sp, color = if (failed) cs.error else cs.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text("Upload to " + j.folder.substringAfterLast('/').ifBlank { "Cloud" }, fontSize = 11.5.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (j.state == "uploading") {
            if (j.size > 0) LinearProgressIndicator(progress = { (j.sent.toFloat() / j.size).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        j.error?.let { Text(it, fontSize = 11.5.sp, color = cs.error, maxLines = 3) }
    }
}

@Composable
private fun TransferRow(t: Transfer) {
    val cs = MaterialTheme.colorScheme
    // Cloud → Shared, once it's on the phone: the station upload says how far it is.
    val up = if (t.uploadId != 0L) StationUploads.items.firstOrNull { it.id == t.uploadId } else null
    val upFailed = up?.state == StationUpload.FAILED
    val sent = if (up != null && t.state == Transfer.SENDING) StationUploads.sent[up.id] ?: up.received else t.done
    val pct = if (t.size > 0) (sent * 100 / t.size).coerceIn(0, 100) else null
    val failed = t.state == Transfer.FAILED || upFailed
    val status = when {
        failed -> "Failed"
        t.state == Transfer.WAITING -> "Waiting"
        t.state == Transfer.GETTING -> if (t.toCloud) "Reading" else "From Cloud" + (pct?.let { " · $it%" } ?: "")
        t.state == Transfer.SENDING -> up?.note ?: ("Sending" + (pct?.let { " · $it%" } ?: ""))
        t.state == Transfer.REMOVING -> "Removing from Cloud"
        t.state == Transfer.DONE -> if (t.move) "Moved" else "Copied"
        else -> ""
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.name, fontSize = 13.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(
                    status, fontSize = 12.sp, color = if (failed) cs.error else cs.onSurfaceVariant, maxLines = 1,
                    // No Retry / Cancel beside a finished one: keep it off the card's edge.
                    modifier = Modifier.padding(start = 8.dp, end = if (t.active || failed) 0.dp else 10.dp),
                )
            }
            Text(
                (if (t.move) "Move to " else "Copy to ") + t.where,
                fontSize = 11.5.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (t.active && !upFailed) {
                val showBar = t.state == Transfer.GETTING || t.state == Transfer.SENDING
                if (showBar && pct != null) {
                    LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
            (t.error ?: up?.error?.takeIf { upFailed })?.let { Text(it, fontSize = 11.5.sp, color = cs.error, maxLines = 3) }
        }
        if (failed) BarIcon(Icons.Rounded.Refresh, "Try ${t.name} again") { CloudTransfers.retry(t) }
        if (t.active || failed) BarIcon(Icons.Rounded.Close, "Cancel ${t.name}") { CloudTransfers.cancel(t) }
    }
}

// =============================================================================================== pickers
@Composable
private fun PickerFrame(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onClose: () -> Unit,
    bottom: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // The screen behind knows the real bars; a dialog window may report none at the bottom (its button then sat
    // under the gesture bar, emulator 26 Nov 2026), so the larger of the two is kept clear.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val outerBottom = WindowInsets.navigationBars.getBottom(density)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // No dimming: the status bar above shows the page behind, the same colour.
        val view = androidx.compose.ui.platform.LocalView.current
        val lightBars = cs.background.luminance() > 0.5f
        androidx.compose.runtime.SideEffect {
            val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window ?: return@SideEffect
            window.setDimAmount(0f)
            // Dark icons on a light page, light on a dark one — as the page behind has them.
            androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
        }
        val innerBottom = WindowInsets.safeDrawing.getBottom(density)
        val extra = with(density) { (outerBottom - innerBottom).coerceAtLeast(0).toDp() }
        Column(Modifier.fillMaxSize().background(cs.background).windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = extra)) {
            ToolTopBar(title, onBack, subtitle = subtitle) {
                BarIcon(Icons.Rounded.Close, "Close", onClick = onClose)
            }
            Box(Modifier.weight(1f)) { content() }
            Column(Modifier.fillMaxWidth().background(cs.surface).padding(16.dp)) { bottom() }
        }
    }
}

@Composable
private fun PickRow(icon: ImageVector, tint: ToolTint, title: String, sub: String?, first: Boolean, last: Boolean, trailing: ImageVector? = Icons.Rounded.ChevronRight, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().cardSlice(first, last).clickable(onClick = onClick)
            .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(tint.tile), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint.icon, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 1)
        }
        if (trailing != null) Icon(trailing, null, tint = cs.onSurfaceVariant)
    }
}

@Composable
private fun PickNote(text: String) {
    Text(text, fontSize = 12.5.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
}

/**
 * Choose a folder in Cloud: starts at the person's own folder (or the whole cloud for Everything); New folder
 * makes one on the way. [onPick] gets the source, the folder's path and a label for people ("My folder › Trips").
 */
@Composable
fun CloudFolderPicker(title: String, confirm: String, onDismiss: () -> Unit, onPick: (CloudSource, String, String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<CloudSource?>(null) }
    var openError by remember { mutableStateOf<String?>(null) }
    var opening by remember { mutableIntStateOf(0) }
    var path by remember { mutableStateOf<String?>(null) }
    var folders by remember { mutableStateOf<List<CloudFolderItem>?>(null) }
    var listError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var naming by remember { mutableStateOf(false) }

    LaunchedEffect(opening) {
        openError = null
        runCatching { CloudSession.open(context) }
            .onSuccess { (_, s) -> source = s; path = s.root }
            .onFailure { openError = it.message ?: "Couldn't open Cloud." }
    }
    val src = source
    val here = path
    LaunchedEffect(src, here, reload) {
        if (src == null || here == null) return@LaunchedEffect
        folders = null
        listError = null
        runCatching { src.folders(here) }.onSuccess { folders = it }.onFailure { listError = it.message ?: "Couldn't open this folder." }
    }
    val rootLabel = if (src?.everything == true) "Cloud" else "My folder"
    val label = if (src == null || here == null) "Cloud" else
        (listOf(rootLabel) + here.removePrefix(src.root).trim('/').split('/').filter { it.isNotEmpty() }).joinToString(" › ")
    fun up() {
        val s = src
        val p = here
        if (s == null || p == null || p == s.root || p.isEmpty()) onDismiss() else path = p.substringBeforeLast('/', "").let { if (it.length < s.root.length) s.root else it }
    }
    androidx.activity.compose.BackHandler { up() }

    PickerFrame(
        title, label, onBack = { up() }, onClose = onDismiss,
        bottom = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { naming = true }, enabled = src != null && folders != null, modifier = Modifier.heightIn(min = 50.dp)) {
                    Icon(Icons.Rounded.CreateNewFolder, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New folder")
                }
                Button(
                    onClick = { if (src != null && here != null) onPick(src, here, label) },
                    enabled = src != null && here != null && openError == null,
                    modifier = Modifier.weight(1f).heightIn(min = 50.dp),
                ) { Text(confirm, maxLines = 1) }
            }
        },
    ) {
        val list = folders
        when {
            openError != null -> EmptyState(Icons.Rounded.CloudOff, "Can't open Cloud", openError, action = "Try again", onAction = { opening++ })
            listError != null -> EmptyState(Icons.Rounded.CloudOff, "Can't open this folder", listError, action = "Try again", onAction = { reload++ })
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> EmptyState(Icons.Rounded.FolderOpen, "No folders in here", "It goes straight into “${label.substringAfterLast(" › ")}”, or make a new folder.")
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                itemsIndexed(list, key = { _, f -> f.path }) { i, f ->
                    PickRow(Icons.Rounded.Folder, ToolTint.SKY, f.label, null, i == 0, i == list.lastIndex) { path = f.path }
                }
            }
        }
    }
    if (naming) {
        var name by remember { mutableStateOf("") }
        val ok = name.isNotBlank() && !name.contains('/') && name.trim() !in setOf(".", "..")
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("New folder") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, singleLine = true, label = { Text("Folder name") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(enabled = ok, onClick = {
                    val s = src ?: return@TextButton
                    val p = here ?: return@TextButton
                    val target = if (p.isEmpty()) name.trim() else "$p/${name.trim()}"
                    naming = false
                    scope.launch {
                        runCatching { s.createFolder(target) }
                            .onSuccess { path = target }
                            .onFailure { listError = it.message ?: "Couldn't make the folder." }
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Choose a folder on a station sharing files: stations first, then their folders. Only a folder the station takes
 * files into can be chosen (AudioSync's "Allow uploads"; off by default). [onPick] gets the folder and its
 * upload_folder.
 */
@Composable
fun SharedFolderPicker(title: String, confirm: String, onDismiss: () -> Unit, onPick: (FolderRef, String) -> Unit) {
    var stack by remember { mutableStateOf(emptyList<FolderRef>()) }
    var folders by remember { mutableStateOf<List<StationFolder>?>(null) }
    var uploadFolder by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var pinProblem by remember { mutableStateOf(false) }
    var showPin by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val ref = stack.lastOrNull()

    LaunchedEffect(Unit) { Stations.checkTakesFiles() }
    LaunchedEffect(ref, reload) {
        folders = null
        uploadFolder = null
        problem = null
        pinProblem = false
        if (ref == null) return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { Stations.list(ref.key, ref.listUrl, "", 0, 500) } }
            .onSuccess { l ->
                folders = l.shares ?: l.folders
                uploadFolder = l.uploadFolder
            }
            .onFailure { e ->
                problem = when (e) {
                    is Stations.PinNeeded -> "${ref.station}'s files need its PIN.".also { pinProblem = true; showPin = true }
                    is Stations.Gone -> "${ref.station} is offline right now."
                    else -> "Couldn't open this folder."
                }
            }
    }
    fun up() { if (stack.isEmpty()) onDismiss() else stack = stack.dropLast(1) }
    androidx.activity.compose.BackHandler { up() }

    val label = when {
        ref == null -> "Choose a station"
        ref.isRoot -> ref.station
        else -> ref.station + " › " + (ref.trail.ifBlank { ref.name })
    }
    PickerFrame(
        title, label, onBack = { up() }, onClose = onDismiss,
        bottom = {
            when {
                ref == null -> PickNote("Stations share folders from their PC. Pick one, then the folder to put it in.")
                ref.isRoot -> PickNote("Open a folder to put it in.")
                folders != null && uploadFolder == null ->
                    PickNote("This folder doesn't take new files. Its owner can switch on “Allow uploads” in AudioSync.")
            }
            Button(
                onClick = { val r = ref; val u = uploadFolder; if (r != null && u != null) onPick(r, u) },
                enabled = ref != null && !ref.isRoot && uploadFolder != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
            ) { Text(confirm, maxLines = 1) }
        },
    ) {
        if (ref == null) {
            // Only stations that take files now (others couldn't take it anyway).
            val sharing = Stations.sharing.filter { it.key in Stations.takesFiles }
            when {
                !Stations.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                sharing.isEmpty() -> EmptyState(Icons.Rounded.FolderOpen, "No station takes files right now", "A station's owner can switch on “Allow uploads” in AudioSync.")
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                    itemsIndexed(sharing, key = { _, s -> s.key }) { i, s ->
                        PickRow(
                            Icons.Rounded.FolderOpen, ToolTint.ORANGE, s.name, if (s.filesProtected) "Needs a PIN" else null,
                            i == 0, i == sharing.lastIndex, trailing = if (s.filesProtected) Icons.Rounded.Lock else Icons.Rounded.ChevronRight,
                        ) { stack = listOf(FolderRef(s.key, s.name, ROOT, s.name, "")) }
                    }
                }
            }
        } else {
            val list = folders
            when {
                problem != null -> EmptyState(
                    if (pinProblem) Icons.Rounded.Lock else Icons.Rounded.CloudOff, problem!!, null,
                    action = if (pinProblem) "Enter the PIN" else "Try again",
                    actionIcon = if (pinProblem) Icons.Rounded.LockOpen else Icons.Rounded.Refresh,
                    onAction = { if (pinProblem) showPin = true else reload++ },
                )
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> EmptyState(Icons.Rounded.FolderOpen, if (ref.isRoot) "Nothing shared" else "No folders in here", if (ref.isRoot) null else "It goes straight into “${ref.name}”.")
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                    itemsIndexed(list, key = { _, f -> f.listUrl }) { i, f ->
                        PickRow(Icons.Rounded.Folder, ToolTint.AMBER, f.name, null, i == 0, i == list.lastIndex) {
                            stack = stack + FolderRef(
                                ref.key, ref.station, f.listUrl, f.name,
                                if (ref.isRoot) f.name else (if (ref.trail.isBlank()) ref.name else ref.trail) + " › " + f.name,
                            )
                        }
                    }
                }
            }
        }
    }
    if (showPin && ref != null) {
        PinSheet(ref.key, ref.station, onDone = {
            showPin = false
            reload++
            StationUploads.pinEntered(ref.key)
        }, onDismiss = { showPin = false })
    }
}
