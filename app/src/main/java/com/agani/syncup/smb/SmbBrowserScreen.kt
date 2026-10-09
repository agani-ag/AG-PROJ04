package com.agani.syncup.smb

import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.agani.syncup.downloads.FileKind
import com.agani.syncup.downloads.Files
import com.agani.syncup.files.ClipMode
import com.agani.syncup.files.FileClipboard
import com.agani.syncup.files.PasteBar
import com.agani.syncup.files.SelectionBar
import com.agani.syncup.files.ActionButton
import com.agani.syncup.files.pasteInto
import com.agani.syncup.video.VideoPlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private sealed interface LoadState {
    data object Loading : LoadState
    data class Ready(val session: SmbSession) : LoadState
    data class Error(val message: String) : LoadState
}

/** Browse one share: connects, lists folders/files, Share/Copy/Move/Rename/Delete, Download/Upload, Paste. */
@Composable
fun SmbBrowserScreen(server: SmbServer, onBack: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var state by remember(server.id) { mutableStateOf<LoadState>(LoadState.Loading) }
    var path by remember(server.id) { mutableStateOf("") }
    var crumbs by remember(server.id) { mutableStateOf(listOf(server.name)) }
    var refresh by remember { mutableStateOf(0) }
    var entries by remember { mutableStateOf<List<SmbEntry>>(emptyList()) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var renaming by remember { mutableStateOf<SmbEntry?>(null) }
    var deleting by remember { mutableStateOf<List<SmbEntry>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    DisposableEffect(server.id) {
        val s = runCatching { SmbSession.open(server) }
        state = s.fold({ LoadState.Ready(it) }, { LoadState.Error(it.message ?: "Couldn't connect") })
        onDispose { (state as? LoadState.Ready)?.session?.close() }
    }
    val ready = state as? LoadState.Ready
    androidx.compose.runtime.LaunchedEffect(ready, path, refresh) {
        val session = ready?.session ?: return@LaunchedEffect
        entries = withContext(Dispatchers.IO) { runCatching { session.list(path) }.getOrDefault(emptyList()) }
    }

    fun openFolder(entry: SmbEntry) {
        path = entry.path
        crumbs = crumbs + entry.name
    }
    fun goBack(): Boolean {
        if (crumbs.size <= 1) return false
        crumbs = crumbs.dropLast(1)
        path = path.substringBeforeLast('\\', "")
        return true
    }

    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val session = ready?.session ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val name = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) c.getString(i) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "file"
        busy = "Uploading $name…"
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { ins -> session.openWrite(SmbPasteTarget.join(path, name)).use { out -> ins.copyTo(out) } }
                }.isSuccess
            }
            busy = null
            refresh++
            Toast.makeText(context, if (ok) "Uploaded $name" else "Couldn't upload $name", Toast.LENGTH_SHORT).show()
        }
    }

    fun downloadAndMaybePlay(entry: SmbEntry, play: Boolean) {
        val session = ready?.session ?: return
        busy = if (play) "Opening ${entry.name}…" else "Downloading ${entry.name}…"
        scope.launch {
            val uri = withContext(Dispatchers.IO) { runCatching { saveToDownloads(context, session, entry) }.getOrNull() }
            busy = null
            if (uri == null) {
                Toast.makeText(context, "Couldn't download ${entry.name}", Toast.LENGTH_SHORT).show()
            } else if (play) {
                context.startActivity(VideoPlayerActivity.intent(context, uri.toString(), entry.name, server.name))
            } else {
                Toast.makeText(context, "Saved ${entry.name} to Downloads", Toast.LENGTH_SHORT).show()
            }
        }
    }

    androidx.activity.compose.BackHandler(enabled = selected.isNotEmpty() || crumbs.size > 1) {
        if (selected.isNotEmpty()) selected = emptySet() else goBack()
    }

    Box(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            if (selected.isNotEmpty()) {
                SelectionBar(selected.size, onClose = { selected = emptySet() }, onSelectAll = { selected = entries.map { it.path }.toSet() })
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp)) {
                    BarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { if (!goBack()) onBack() }
                    Text(crumbs.lastOrNull() ?: server.name, fontSize = 22.sp, lineHeight = 28.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 4.dp))
                    if (ready != null) BarIcon(Icons.Rounded.Upload, "Upload a file") { upload.launch(arrayOf("*/*")) }
                }
                if (crumbs.size > 1) {
                    Text(crumbs.joinToString(" › "), fontSize = 12.5.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp).fillMaxWidth())
                }
            }
            busy?.let { msg ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(msg, fontSize = 13.sp, color = cs.onSurfaceVariant)
                }
            }
            if (!FileClipboard.isEmpty && selected.isEmpty() && ready != null) {
                PasteBar(
                    count = FileClipboard.items.size, move = FileClipboard.mode == ClipMode.MOVE, busy = busy != null,
                    onPaste = {
                        val session = ready.session
                        val items = FileClipboard.items.toList()
                        val move = FileClipboard.mode == ClipMode.MOVE
                        busy = "Pasting…"
                        scope.launch {
                            val (ok, fail) = withContext(Dispatchers.IO) { pasteInto(context, SmbPasteTarget(session, path), items, move) }
                            busy = null
                            if (move) FileClipboard.clear()
                            refresh++
                            Toast.makeText(context, if (fail == 0) "Pasted $ok item${if (ok == 1) "" else "s"}" else "Pasted $ok, $fail failed", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onCancel = { FileClipboard.clear() },
                )
            }
            when (val s = state) {
                is LoadState.Loading -> Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is LoadState.Error -> EmptyState(Icons.Rounded.AttachFile, "Couldn't connect", s.message, modifier = Modifier.weight(1f))
                is LoadState.Ready -> if (entries.isEmpty()) {
                    EmptyState(Icons.Rounded.Folder, "This folder is empty", modifier = Modifier.weight(1f))
                } else {
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(entries, key = { it.path }) { e ->
                            EntryRow(
                                e, selected = e.path in selected, selecting = selected.isNotEmpty(),
                                onClick = {
                                    when {
                                        selected.isNotEmpty() -> selected = if (e.path in selected) selected - e.path else selected + e.path
                                        e.isDirectory -> openFolder(e)
                                        e.name.substringAfterLast('.', "").lowercase() in VIDEO_EXT -> downloadAndMaybePlay(e, play = true)
                                        else -> downloadAndMaybePlay(e, play = false)
                                    }
                                },
                                onLongClick = { if (selected.isEmpty()) selected = setOf(e.path) },
                            )
                        }
                    }
                }
            }
            if (selected.isNotEmpty() && ready != null) {
                val picked = entries.filter { it.path in selected }
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceAround, modifier = Modifier.fillMaxWidth().height(72.dp).background(cs.surfaceContainer)) {
                    ActionButton(Icons.Rounded.Download, "Download", onClick = {
                        picked.forEach { downloadAndMaybePlay(it, play = false) }
                        selected = emptySet()
                    })
                    ActionButton(Icons.Rounded.ContentCopy, "Copy", onClick = { startClipCopy(context, scope, ready.session, picked, ClipMode.COPY) { selected = emptySet() } })
                    ActionButton(Icons.Rounded.ContentCut, "Move", onClick = { startClipCopy(context, scope, ready.session, picked, ClipMode.MOVE) { selected = emptySet() } })
                    if (picked.size == 1) ActionButton(Icons.Rounded.Edit, "Rename", onClick = { renaming = picked.first() })
                    ActionButton(Icons.Rounded.Delete, "Delete", onClick = { deleting = picked }, danger = true)
                }
            }
        }
    }

    renaming?.let { entry ->
        var text by remember(entry) { mutableStateOf(entry.name) }
        val session = ready?.session
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val s = session
                    if (s != null && text.isNotBlank()) {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { runCatching { s.rename(entry, text.trim()) }.isSuccess }
                            if (!ok) Toast.makeText(context, "Couldn't rename", Toast.LENGTH_SHORT).show()
                            renaming = null; selected = emptySet(); refresh++
                        }
                    } else renaming = null
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    deleting?.let { picks ->
        val session = ready?.session
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${picks.size} item${if (picks.size == 1) "" else "s"}?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    val s = session
                    if (s != null) {
                        scope.launch {
                            withContext(Dispatchers.IO) { picks.forEach { runCatching { s.delete(it) } } }
                            deleting = null; selected = emptySet(); refresh++
                        }
                    } else deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

private fun startClipCopy(context: android.content.Context, scope: kotlinx.coroutines.CoroutineScope, session: SmbSession, picked: List<SmbEntry>, mode: ClipMode, onDone: () -> Unit) {
    scope.launch {
        val items = withContext(Dispatchers.IO) { picked.map { cacheForClipboard(context, session, it) } }
        FileClipboard.set(items, mode)
        onDone()
        Toast.makeText(context, "${items.size} item${if (items.size == 1) "" else "s"} ${if (mode == ClipMode.MOVE) "to move" else "copied"}", Toast.LENGTH_SHORT).show()
    }
}

/** Downloads [entry] into the phone's public Downloads collection and returns its content:// URI. */
private fun saveToDownloads(context: android.content.Context, session: SmbSession, entry: SmbEntry): android.net.Uri? {
    val resolver = context.contentResolver
    val values = android.content.ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, Files.cleanName(entry.name))
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val uri = resolver.insert(collection, values) ?: return null
    resolver.openOutputStream(uri)?.use { out -> session.openRead(entry.path).use { ins -> ins.copyTo(out) } }
        ?: run { resolver.delete(uri, null, null); return null }
    values.clear()
    values.put(MediaStore.Downloads.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    return uri
}

private val VIDEO_EXT = setOf("mp4", "m4v", "webm", "mkv", "mov", "3gp", "avi", "wmv", "flv", "mpeg", "mpg", "ts")

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(entry: SmbEntry, selected: Boolean, selecting: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .then(if (selected) Modifier.background(cs.primaryContainer.copy(alpha = .4f)) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        if (selecting) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
            Spacer(Modifier.width(4.dp))
        }
        val (icon, tint, bg) = iconFor(entry)
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(bg), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = if (entry.isDirectory) "Folder" else "${Files.size(entry.size)} · ${dateOf(entry.lastModified)}"
            Text(subtitle, fontSize = 12.5.sp, color = cs.onSurfaceVariant, maxLines = 1)
        }
        if (!entry.isDirectory && entry.name.substringAfterLast('.', "").lowercase() in VIDEO_EXT) {
            Icon(Icons.Rounded.PlayArrow, "Play", tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun iconFor(entry: SmbEntry): Triple<ImageVector, Color, Color> {
    val cs = MaterialTheme.colorScheme
    if (entry.isDirectory) return Triple(Icons.Rounded.Folder, Color(0xFFB45309), Color(0xFFFEF3C7))
    return when (FileKind.of(entry.name, "")) {
        FileKind.IMAGE -> Triple(Icons.Rounded.Image, Color(0xFFB45309), Color(0xFFFEF3C7))
        FileKind.VIDEO -> Triple(Icons.Rounded.VideoFile, Color(0xFFBE123C), Color(0xFFFFE4E6))
        FileKind.MUSIC -> Triple(Icons.Rounded.MusicNote, Color(0xFF7C3AED), Color(0xFFF3E8FF))
        FileKind.DOCUMENT -> Triple(Icons.Rounded.Description, Color(0xFF1D4ED8), Color(0xFFDBEAFE))
        FileKind.APP -> Triple(Icons.Rounded.Android, cs.onSurfaceVariant, cs.surfaceContainerHigh)
        FileKind.OTHER -> Triple(Icons.Rounded.AttachFile, cs.onSurfaceVariant, cs.surfaceContainerHigh)
    }
}

private fun dateOf(ms: Long): String = if (ms <= 0) "" else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
