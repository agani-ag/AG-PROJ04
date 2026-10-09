package com.agani.syncup.files

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.browser.ui.EmptyState
import com.agani.syncup.downloads.FileKind
import com.agani.syncup.downloads.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private enum class SortBy(val label: String) { NAME("Name"), NEWEST("Newest"), LARGEST("Largest") }

/** Browse a folder the user opened once (SAF): list, select, Share/Copy/Move/Rename/Delete, Paste. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FolderBrowserScreen(rootUri: Uri, rootName: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val root = remember(rootUri) { DocumentFile.fromTreeUri(context, rootUri) }
    var stack by remember { mutableStateOf(listOfNotNull(root?.let { it to rootName })) }
    var refresh by remember { mutableStateOf(0) }
    var sort by remember { mutableStateOf(SortBy.NAME) }
    var sortMenu by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Uri>()) }
    var renaming by remember { mutableStateOf<DocumentFile?>(null) }
    var deleting by remember { mutableStateOf<List<DocumentFile>?>(null) }
    var pasting by remember { mutableStateOf(false) }

    val current = stack.lastOrNull()?.first
    val children = remember(current, refresh, sort) {
        val list = current?.listFiles()?.toList().orEmpty()
        when (sort) {
            SortBy.NAME -> list.sortedWith(compareByDescending<DocumentFile> { it.isDirectory }.thenBy { it.name?.lowercase() })
            SortBy.NEWEST -> list.sortedWith(compareByDescending<DocumentFile> { it.isDirectory }.thenByDescending { it.lastModified() })
            SortBy.LARGEST -> list.sortedWith(compareByDescending<DocumentFile> { it.isDirectory }.thenByDescending { it.length() })
        }
    }

    fun toggle(uri: Uri) { selected = if (uri in selected) selected - uri else selected + uri }
    fun open(doc: DocumentFile) {
        if (doc.isDirectory) {
            stack = stack + (doc to (doc.name ?: "Folder"))
        } else {
            FilesStore.recordOpened(context, doc.uri, doc.name ?: "file", doc.length())
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(doc.uri, doc.type ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (runCatching { context.startActivity(intent) }.isFailure) {
                Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    androidx.activity.compose.BackHandler(enabled = selected.isNotEmpty() || stack.size > 1) {
        if (selected.isNotEmpty()) selected = emptySet() else stack = stack.dropLast(1)
    }

    Box(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            if (selected.isNotEmpty()) {
                SelectionBar(
                    count = selected.size,
                    onClose = { selected = emptySet() },
                    onSelectAll = { selected = children.map { it.uri }.toSet() },
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp)) {
                    BarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { if (stack.size > 1) stack = stack.dropLast(1) else onBack() }
                    Text(stack.lastOrNull()?.second ?: rootName, fontSize = 22.sp, lineHeight = 28.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 4.dp))
                    Box {
                        BarIcon(Icons.Rounded.Sort, "Sort") { sortMenu = true }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            SortBy.entries.forEach { s ->
                                DropdownMenuItem(text = { Text(s.label) }, onClick = { sort = s; sortMenu = false })
                            }
                        }
                    }
                }
            }
            if (stack.size > 1 && selected.isEmpty()) {
                Text(
                    stack.joinToString(" › ") { it.second }, fontSize = 12.5.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp).fillMaxWidth(),
                )
            }
            if (!FileClipboard.isEmpty && selected.isEmpty()) {
                PasteBar(
                    count = FileClipboard.items.size,
                    move = FileClipboard.mode == ClipMode.MOVE,
                    busy = pasting,
                    onPaste = {
                        val dest = current ?: return@PasteBar
                        val items = FileClipboard.items.toList()
                        val move = FileClipboard.mode == ClipMode.MOVE
                        pasting = true
                        scope.launch {
                            val (ok, fail) = withContext(Dispatchers.IO) { pasteInto(context, LocalPasteTarget(dest), items, move) }
                            pasting = false
                            if (move) FileClipboard.clear()
                            refresh++
                            Toast.makeText(context, if (fail == 0) "Pasted $ok item${if (ok == 1) "" else "s"}" else "Pasted $ok, $fail failed", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onCancel = { FileClipboard.clear() },
                )
            }
            if (children.isEmpty()) {
                EmptyState(Icons.Rounded.Folder, "This folder is empty", modifier = Modifier.weight(1f))
            } else {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(children, key = { it.uri.toString() }) { doc ->
                        FileRow(
                            doc = doc, selected = doc.uri in selected, selecting = selected.isNotEmpty(),
                            onClick = { if (selected.isNotEmpty()) toggle(doc.uri) else open(doc) },
                            onLongClick = { if (selected.isEmpty()) selected = setOf(doc.uri) },
                        )
                    }
                }
            }
            if (selected.isNotEmpty()) {
                val docs = children.filter { it.uri in selected }
                SelectionActions(
                    canRename = docs.size == 1,
                    onShare = {
                        val uris = ArrayList(docs.map { it.uri })
                        val intent = if (uris.size == 1) {
                            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(docs[0].type ?: "*/*")
                        } else {
                            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).setType("*/*")
                        }
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(intent, "Share"))
                    },
                    onCopy = { FileClipboard.set(docs.map { LocalClipItem(it) }, ClipMode.COPY); selected = emptySet() },
                    onMove = { FileClipboard.set(docs.map { LocalClipItem(it) }, ClipMode.MOVE); selected = emptySet() },
                    onRename = { renaming = docs.firstOrNull() },
                    onDelete = { deleting = docs },
                )
            }
        }
    }

    renaming?.let { doc ->
        var text by remember(doc) { mutableStateOf(doc.name ?: "") }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val ok = text.isNotBlank() && runCatching { doc.renameTo(text.trim()) }.getOrDefault(false)
                    if (!ok) Toast.makeText(context, "Couldn't rename", Toast.LENGTH_SHORT).show()
                    renaming = null
                    selected = emptySet()
                    refresh++
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    deleting?.let { docs ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${docs.size} item${if (docs.size == 1) "" else "s"}?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { docs.forEach { runCatching { it.delete() } } }
                        deleting = null
                        selected = emptySet()
                        refresh++
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun SelectionBar(count: Int, onClose: () -> Unit, onSelectAll: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(64.dp).background(cs.primaryContainer).padding(start = 4.dp, end = 16.dp),
    ) {
        BarIcon(Icons.Rounded.Close, "Close selection", tint = cs.onPrimaryContainer, onClick = onClose)
        Text("$count selected", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = cs.onPrimaryContainer, modifier = Modifier.weight(1f))
        Text("All", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onPrimaryContainer, modifier = Modifier.clickable(onClick = onSelectAll).padding(8.dp))
    }
}

@Composable
internal fun PasteBar(count: Int, move: Boolean, busy: Boolean, onPaste: () -> Unit, onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(cs.secondaryContainer).clickable(enabled = !busy, onClick = onPaste).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(if (move) Icons.Rounded.ContentCut else Icons.Rounded.ContentCopy, null, tint = cs.onSecondaryContainer, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            if (busy) "Pasting…" else "$count item${if (count == 1) "" else "s"} ${if (move) "to move" else "copied"} · Paste here",
            fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSecondaryContainer, modifier = Modifier.weight(1f),
        )
        if (!busy) Text("Cancel", fontSize = 13.sp, color = cs.onSecondaryContainer, modifier = Modifier.clickable(onClick = onCancel).padding(6.dp))
    }
}

@Composable
private fun SelectionActions(canRename: Boolean, onShare: () -> Unit, onCopy: () -> Unit, onMove: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        horizontalArrangement = Arrangement.SpaceAround,
        modifier = Modifier.fillMaxWidth().height(72.dp).background(cs.surfaceContainer),
    ) {
        ActionButton(Icons.Rounded.Share, "Share", onShare)
        ActionButton(Icons.Rounded.ContentCopy, "Copy", onCopy)
        ActionButton(Icons.Rounded.ContentCut, "Move", onMove)
        if (canRename) ActionButton(Icons.Rounded.Edit, "Rename", onRename)
        ActionButton(Icons.Rounded.Delete, "Delete", onDelete, danger = true)
    }
}

@Composable
internal fun ActionButton(icon: ImageVector, label: String, onClick: () -> Unit, danger: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick).padding(8.dp)) {
        Icon(icon, null, tint = if (danger) cs.error else cs.onSurface, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.5.sp, color = if (danger) cs.error else cs.onSurface)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FileRow(doc: DocumentFile, selected: Boolean, selecting: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val name = doc.name ?: "(unnamed)"
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
        val (icon, tint, bg) = iconFor(doc)
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(bg), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = if (doc.isDirectory) "Folder" else "${Files.size(doc.length())} · ${dateOf(doc.lastModified())}"
            Text(subtitle, fontSize = 12.5.sp, color = cs.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun iconFor(doc: DocumentFile): Triple<ImageVector, Color, Color> {
    val cs = MaterialTheme.colorScheme
    if (doc.isDirectory) return Triple(Icons.Rounded.Folder, Color(0xFFB45309), Color(0xFFFEF3C7))
    val kind = FileKind.of(doc.name.orEmpty(), doc.type ?: "")
    return when (kind) {
        FileKind.IMAGE -> Triple(Icons.Rounded.Image, Color(0xFFB45309), Color(0xFFFEF3C7))
        FileKind.VIDEO -> Triple(Icons.Rounded.VideoFile, Color(0xFFBE123C), Color(0xFFFFE4E6))
        FileKind.MUSIC -> Triple(Icons.Rounded.MusicNote, Color(0xFF7C3AED), Color(0xFFF3E8FF))
        FileKind.DOCUMENT -> Triple(Icons.Rounded.Description, Color(0xFF1D4ED8), Color(0xFFDBEAFE))
        FileKind.APP -> Triple(Icons.Rounded.Android, cs.onSurfaceVariant, cs.surfaceContainerHigh)
        FileKind.OTHER -> Triple(Icons.Rounded.AttachFile, cs.onSurfaceVariant, cs.surfaceContainerHigh)
    }
}

private fun dateOf(ms: Long): String = if (ms <= 0) "" else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
