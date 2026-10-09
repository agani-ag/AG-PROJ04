package com.agani.syncup.downloads

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.browser.ui.EmptyState
import com.agani.syncup.ui.theme.dialogSurface
import kotlinx.coroutines.delay
import java.util.Calendar

// ============================================================================ the Downloads screen
/**
 * The Download Manager: running downloads with their parts, pause / resume / retry / cancel, and
 * finished files by day with open / share / rename / delete. "Add link" downloads any link.
 * [link] = a link shared to SyncUp from another app: opens "Add link" with it.
 */
@Composable
fun DownloadsScreen(link: String?, onLinkUsed: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var filter by remember { mutableStateOf<FileKind?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var addLink by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(link) {
        if (link != null) {
            addLink = link
            adding = true
            onLinkUsed()
        }
    }

    val all = Downloads.tasks
    val shown = all.filter { t ->
        (filter == null || t.kind == filter) && (query.isBlank() || t.fileName.contains(query, ignoreCase = true))
    }
    val active = shown.filter { it.state != DlState.DONE }
    val done = shown.filter { it.state == DlState.DONE }

    Box(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp)) {
                BarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
                if (searching) {
                    TextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("Search downloads") },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    BarIcon(Icons.Rounded.Close, "Close search", tint = cs.onSurfaceVariant) {
                        query = ""
                        searching = false
                    }
                } else {
                    Text("Downloads", fontSize = 22.sp, lineHeight = 28.sp, color = cs.onSurface, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    BarIcon(Icons.Rounded.Search, "Search downloads") { searching = true }
                    BarIcon(Icons.Rounded.Tune, "Download settings") { showSettings = true }
                }
            }
            if (all.isNotEmpty()) {
                val counts = all.groupingBy { it.kind }.eachCount()
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    item { FilterChipX("All ${all.size}", filter == null) { filter = null } }
                    items(FileKind.entries.filter { (counts[it] ?: 0) > 0 }) { k ->
                        FilterChipX("${k.label} ${counts[k]}", filter == k) { filter = if (filter == k) null else k }
                    }
                }
            }
            if (shown.isEmpty()) {
                EmptyState(
                    Icons.Rounded.Download,
                    if (all.isEmpty()) "No downloads yet" else "Nothing here",
                    if (all.isEmpty()) "Files you download in any section show up here. Tap Add link to download any link." else null,
                )
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
                    if (active.isNotEmpty()) {
                        item(key = "h-active") {
                            val running = active.count { it.state == DlState.RUNNING }
                            ListHeader(
                                "Downloading · $running of ${active.size}",
                                action = if (active.any { it.state == DlState.RUNNING || it.state == DlState.QUEUED }) "Pause all" else "Resume all",
                            ) {
                                if (active.any { it.state == DlState.RUNNING || it.state == DlState.QUEUED }) Downloads.pauseAll()
                                else active.forEach { Downloads.resume(it) }
                            }
                        }
                        items(active, key = { "a${it.id}" }) { t -> ActiveCard(t) }
                    }
                    var lastDay = ""
                    done.forEach { t ->
                        val day = dayLabel(t.finishedAt.takeIf { it > 0 } ?: t.createdAt)
                        if (day != lastDay) {
                            lastDay = day
                            item(key = "d$day${t.id}") { ListHeader(day) }
                        }
                        item(key = "f${t.id}") { DoneRow(t) }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = {
                addLink = null
                adding = true
            },
            icon = { Icon(Icons.Rounded.Add, null) },
            text = { Text("Add link") },
            containerColor = cs.primaryContainer,
            contentColor = cs.onPrimaryContainer,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        )
    }

    if (adding) {
        AddLinkDialog(
            initial = addLink,
            onDismiss = { adding = false },
            onDownload = { req, name, info ->
                adding = false
                Downloads.start(req, name, info)
                Toast.makeText(context, "Downloading ${name.ifBlank { req.guessName() }}", Toast.LENGTH_SHORT).show()
            },
        )
    }
    if (showSettings) DownloadSettingsSheet { showSettings = false }
}

@Composable
private fun FilterChipX(text: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.height(32.dp).clip(RoundedCornerShape(16.dp))
            .background(if (selected) cs.secondaryContainer else cs.surfaceContainerHigh)
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (selected) cs.onSecondaryContainer else cs.onSurface)
    }
}

@Composable
private fun ListHeader(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(
                action, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.primary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** The kind's coloured tile (video red, music violet, docs blue …). */
@Composable
fun KindTile(kind: FileKind, size: androidx.compose.ui.unit.Dp = 44.dp) {
    val (icon, tint) = kindLook(kind)
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun kindLook(kind: FileKind): Pair<ImageVector, Color> = when (kind) {
    FileKind.VIDEO -> Icons.Rounded.Movie to Color(0xFFDC2626)
    FileKind.MUSIC -> Icons.Rounded.MusicNote to Color(0xFF7C3AED)
    FileKind.IMAGE -> Icons.Rounded.Image to Color(0xFFD97706)
    FileKind.DOCUMENT -> Icons.Rounded.Description to Color(0xFF2563EB)
    FileKind.APP -> Icons.Rounded.Android to Color(0xFF16A34A)
    FileKind.OTHER -> Icons.Rounded.FolderZip to MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun ActiveCard(t: DownloadTask) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerLow)
            .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KindTile(t.kind)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.fileName, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sizeText = if (t.total > 0) "${Files.size(t.downloaded)} of ${Files.size(t.total)}" else Files.size(t.downloaded)
                val parts = t.parts.size.takeIf { it > 1 }?.let { " · $it parts" }.orEmpty()
                Text("$sizeText$parts", fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 1)
            }
            when (t.state) {
                DlState.PAUSED -> RoundAction(Icons.Rounded.PlayArrow, "Resume") { Downloads.resume(t) }
                DlState.FAILED -> RoundAction(Icons.Rounded.Refresh, "Retry") { Downloads.resume(t) }
                else -> RoundAction(Icons.Rounded.Pause, "Pause") { Downloads.pause(t) }
            }
            BarIcon(Icons.Rounded.Close, "Cancel download", tint = cs.onSurfaceVariant) { Downloads.cancel(t) }
        }
        val bars = t.partProgress.ifEmpty {
            t.parts.map { p -> if (p.length > 0) (p.done.get().toFloat() / p.length).coerceIn(0f, 1f) else 0f }
        }
        Spacer(Modifier.height(10.dp))
        if (t.total <= 0 && t.state == DlState.RUNNING) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 6.dp).height(6.dp).clip(RoundedCornerShape(3.dp)))
        } else {
            val fill = if (t.state == DlState.RUNNING) cs.primary else cs.outline
            Row(Modifier.fillMaxWidth().padding(end = 6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                (bars.ifEmpty { listOf(if (t.total > 0) t.downloaded.toFloat() / t.total else 0f) }).forEach { f ->
                    Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(cs.surfaceContainerHighest)) {
                        Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(fill))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.padding(end = 6.dp)) {
            when (t.state) {
                DlState.RUNNING -> {
                    Text(if (t.speed > 0) "${Files.size(t.speed)}/s" else "Connecting…", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.primary, modifier = Modifier.weight(1f))
                    val left = Downloads.secondsLeft(t)
                    if (left >= 0) Text("${Files.duration(left)} left", fontSize = 12.sp, color = cs.onSurfaceVariant)
                }
                DlState.QUEUED -> StatusText("Waiting · ${Downloads.atOnce} download${if (Downloads.atOnce == 1) "" else "s"} at a time")
                DlState.WAITING_NETWORK -> StatusText("Waiting for network — resumes on its own")
                DlState.WAITING_WIFI -> StatusText("Waiting for Wi-Fi (Wi-Fi only is on)")
                DlState.PAUSED -> StatusText(if (t.resumable || t.parts.isEmpty()) "Paused" else "Paused — this site can't resume, it will start again")
                DlState.FAILED -> Text(t.error ?: "Failed", fontSize = 12.sp, color = cs.error)
                DlState.DONE -> Unit
            }
        }
    }
}

@Composable
private fun StatusText(text: String) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RoundAction(icon: ImageVector, desc: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(cs.surfaceContainerHighest).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = cs.onSurface, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun DoneRow(t: DownloadTask) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { open(context, t) }.padding(vertical = 8.dp, horizontal = 4.dp),
    ) {
        KindTile(t.kind)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(t.fileName, fontSize = 15.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val time = DateUtils.formatDateTime(context, t.finishedAt.takeIf { it > 0 } ?: t.createdAt, DateUtils.FORMAT_SHOW_TIME)
            val source = when {
                t.incognito -> "Incognito"
                t.work -> "${t.source} (SyncUp)"
                else -> t.source
            }
            Text(
                listOf(if (t.total > 0) Files.size(t.total) else "", source, time).filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 12.sp, color = if (t.work) MaterialTheme.colorScheme.primary.copy(alpha = .9f) else cs.onSurfaceVariant, maxLines = 1,
            )
        }
        Box {
            BarIcon(Icons.Rounded.MoreVert, "More", tint = cs.onSurfaceVariant) { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; open(context, t) })
                DropdownMenuItem(text = { Text("Share") }, onClick = { menu = false; share(context, t) })
                if (t.legacyId == 0L) DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                if (t.url.isNotBlank()) {
                    DropdownMenuItem(text = { Text("Download again") }, onClick = {
                        menu = false
                        Downloads.start(DownloadRequest(url = t.url, userAgent = t.userAgent, referer = t.referer, source = t.source, work = t.work, incognito = t.incognito), t.fileName)
                    })
                }
                // A SyncUp link's address stays hidden.
                if (t.url.isNotBlank() && !t.work) {
                    DropdownMenuItem(text = { Text("Copy link") }, onClick = {
                        menu = false
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText("Link", t.url))
                        Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                    })
                }
                DropdownMenuItem(text = { Text("Delete file", color = cs.error) }, onClick = { menu = false; deleting = true })
                DropdownMenuItem(text = { Text("Remove from list") }, onClick = { menu = false; Downloads.remove(t, deleteFile = false) })
            }
        }
    }
    if (renaming) {
        var name by remember { mutableStateOf(t.fileName) }
        AlertDialog(
            containerColor = cs.dialogSurface,
            onDismissRequest = { renaming = false },
            title = { Text("Rename") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("File name") }) },
            confirmButton = {
                TextButton(onClick = {
                    renaming = false
                    if (name.isNotBlank() && name != t.fileName && !Downloads.rename(t, name)) {
                        Toast.makeText(context, "Couldn't rename this file", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (deleting) {
        AlertDialog(
            containerColor = cs.dialogSurface,
            onDismissRequest = { deleting = false },
            title = { Text("Delete file?") },
            text = { Text("${t.fileName} will be deleted from your phone.", color = cs.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    Downloads.remove(t, deleteFile = true)
                }) { Text("Delete", color = cs.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

private fun open(context: Context, t: DownloadTask) {
    val uri = Downloads.openUri(t)
    if (uri == null) {
        Toast.makeText(context, "File not found — it may have been moved or deleted", Toast.LENGTH_SHORT).show()
        return
    }
    // Videos play in SyncUp's own player (Tools → Video player), and remember where they stopped.
    if (t.kind == FileKind.VIDEO) {
        context.startActivity(com.agani.syncup.video.VideoPlayerActivity.intent(context, uri.toString(), t.fileName, "SyncUp downloads"))
        return
    }
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, t.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (runCatching { context.startActivity(intent) }.isFailure) {
        Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
    }
}

private fun share(context: Context, t: DownloadTask) {
    val uri = Downloads.openUri(t)
    if (uri == null) {
        Toast.makeText(context, "File not found — it may have been moved or deleted", Toast.LENGTH_SHORT).show()
        return
    }
    val send = Intent(Intent.ACTION_SEND).setType(t.mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(send, "Share")) }
}

private fun dayLabel(millis: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val dayDiff = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
    return when {
        sameYear && dayDiff == 0 -> "Today"
        sameYear && dayDiff == 1 -> "Yesterday"
        else -> java.text.SimpleDateFormat(if (sameYear) "EEEE, d MMMM" else "d MMMM yyyy", java.util.Locale.getDefault()).format(then.time)
    }
}

// ============================================================================ checking a link
/** The facts about a link: type, size, resumable, parts (or "Checking…" / the problem). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LinkFacts(name: String, info: LinkInfo?, checking: Boolean, problem: String?) {
    val cs = MaterialTheme.colorScheme
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            checking -> Fact("Checking the link…")
            problem != null -> Text(problem, fontSize = 13.sp, color = cs.error)
            info != null -> {
                Fact(FileKind.describe(name, info.mime))
                Fact(Files.size(info.size))
                if (info.resumable) Fact("Resumable", Icons.Rounded.Check, good = true) else Fact("Can't resume")
                val parts = if (info.resumable && info.size > 0) Downloads.partsPerFile.toLong().coerceAtMost((info.size shr 20).coerceAtLeast(1)) else 1
                if (parts > 1) Fact("$parts parts", Icons.Rounded.Layers, accent = true)
            }
        }
    }
}

@Composable
private fun Fact(text: String, icon: ImageVector? = null, good: Boolean = false, accent: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when {
        good -> Color(0xFF16A34A).copy(alpha = .16f) to Color(0xFF15803D)
        accent -> cs.primaryContainer to cs.onPrimaryContainer
        else -> cs.surfaceContainerHigh to cs.onSurface
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(30.dp).clip(RoundedCornerShape(15.dp)).background(bg).padding(horizontal = 12.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontSize = 13.sp, color = fg)
    }
}

/** "Add download": paste a link (or the one shared to SyncUp), check it, download it. */
@Composable
private fun AddLinkDialog(initial: String?, onDismiss: () -> Unit, onDownload: (DownloadRequest, String, LinkInfo?) -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var fromClipboard by remember { mutableStateOf(false) }
    var text by remember {
        mutableStateOf(
            initial ?: run {
                // Offer a link that was just copied in another app.
                val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                val copied = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                firstLink(copied)?.also { fromClipboard = true } ?: ""
            },
        )
    }
    var name by remember { mutableStateOf("") }
    var nameEdited by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<LinkInfo?>(null) }
    var checking by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val url = firstLink(text)

    LaunchedEffect(url) {
        info = null
        problem = null
        if (url == null) return@LaunchedEffect
        delay(400)
        checking = true
        runCatching { Downloads.probe(url, Downloads.defaultUserAgent) }
            .onSuccess {
                info = it
                if (!nameEdited) name = it.fileName
            }
            .onFailure { problem = "Couldn't check this link (${it.message ?: "no answer"}). You can still try to download it." }
        checking = false
    }

    AlertDialog(
        containerColor = cs.dialogSurface,
        onDismissRequest = onDismiss,
        title = { Text("Add download") },
        text = {
            Column {
                Text("Paste a link, or share one to SyncUp from any app.", fontSize = 14.sp, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it; fromClipboard = false }, label = { Text("Link") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                if (fromClipboard) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Icon(Icons.Rounded.ContentPaste, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pasted from clipboard", fontSize = 12.sp, color = cs.onSurfaceVariant)
                    }
                }
                if (url != null) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = name, onValueChange = { name = it; nameEdited = true }, label = { Text("File name") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    LinkFacts(name, info, checking, problem)
                }
            }
        },
        confirmButton = {
            Button(enabled = url != null && !checking, onClick = {
                val u = url ?: return@Button
                onDownload(DownloadRequest(url = u, userAgent = Downloads.defaultUserAgent, source = hostOf(u)), name, info)
            }) { Text("Download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val LINK = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

/** The first web link in [text]. */
fun firstLink(text: String?): String? = text?.let { LINK.find(it)?.value?.trimEnd('.', ',', ')', ']') }

private fun hostOf(url: String) = runCatching { android.net.Uri.parse(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")

// ============================================================================ the browser's "Download file" sheet
/** Shown when a page starts a download and "Ask before each download" is on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadPromptSheet(req: DownloadRequest, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var name by remember(req) { mutableStateOf(req.guessName()) }
    var nameEdited by remember(req) { mutableStateOf(false) }
    var info by remember(req) { mutableStateOf<LinkInfo?>(null) }
    var checking by remember(req) { mutableStateOf(true) }
    LaunchedEffect(req) {
        runCatching { Downloads.probe(req.url, req.userAgent, req.referer, req.cookies) }.onSuccess {
            info = it
            if (!nameEdited && req.suggestedName.isNullOrBlank() && req.contentDisposition.isNullOrBlank()) name = it.fileName
        }
        checking = false
    }
    val kind = FileKind.of(name, info?.mime ?: req.mime.orEmpty())
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindTile(kind)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Download file", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                    if (req.source.isNotBlank()) Text("from ${req.source}", fontSize = 13.sp, color = cs.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = name, onValueChange = { name = it; nameEdited = true }, label = { Text("File name") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            // Without a check, the browser's own facts are all there is.
            LinkFacts(
                name,
                info ?: if (!checking) LinkInfo(req.url, name, req.mime.orEmpty(), req.contentLength, false, null, null) else null,
                checking, null,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
                Icon(Icons.Rounded.Folder, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Save to", fontSize = 14.sp, color = cs.onSurface)
                    Text(Downloads.folderLabel(kind), fontSize = 12.sp, color = cs.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    Downloads.start(req, name, info)
                    Toast.makeText(context, "Downloading ${Files.cleanName(name)}", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }) { Text("Download") }
            }
        }
    }
}

// ============================================================================ settings
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSettingsSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text("Download settings", fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
            DownloadSettingsRows()
        }
    }
}

/** Parts per file, downloads at once, Wi-Fi only, ask before each download (also in Settings). */
@Composable
fun DownloadSettingsRows() {
    val cs = MaterialTheme.colorScheme
    ChoiceRow("Parts per file", "More parts = faster on most servers", listOf(1, 2, 4, 8, 16), Downloads.partsPerFile) { Downloads.updatePartsPerFile(it) }
    ChoiceRow("Downloads at once", "The others wait in line", listOf(1, 2, 3, 4, 5), Downloads.atOnce) { Downloads.updateAtOnce(it) }
    SwitchLine("Wi-Fi only", "Wait for Wi-Fi to download", Downloads.wifiOnly) { Downloads.updateWifiOnly(it) }
    SwitchLine("Ask before each download", "Show the name, size and folder first", Downloads.askFirst) { Downloads.updateAskFirst(it) }
    Text(
        "Files are saved in Download / SyncUp, sorted by type.",
        fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp),
    )
}

@Composable
private fun ChoiceRow(title: String, subtitle: String, options: List<Int>, value: Int, onPick: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = cs.onSurface)
            Text(subtitle, fontSize = 13.sp, color = cs.onSurfaceVariant)
        }
        Box {
            Text("$value", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.primary)
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { o ->
                    DropdownMenuItem(text = { Text("$o") }, onClick = {
                        open = false
                        onPick(o)
                    })
                }
            }
        }
    }
}

@Composable
private fun SwitchLine(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 24.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = cs.onSurface)
            Text(subtitle, fontSize = 13.sp, color = cs.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
