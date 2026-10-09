package com.agani.syncup.files

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.downloads.Downloads
import com.agani.syncup.downloads.Files

/** Tools → Files: the phone's room, folders you've opened once, recent files, and SyncUp's downloads. */
@Composable
fun FilesScreen(onOpenFolder: (Uri, String) -> Unit, onOpenDownloads: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var reload by remember { mutableStateOf(0) }
    val folders = remember(reload) { FilesStore.folders(context) }
    val recent = remember(reload) { FilesStore.recent(context) }
    val (total, used, free) = remember { FilesStore.storageStats() }
    val runningDownloads = Downloads.tasks.count { it.active }

    val openTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            FilesStore.addFolder(context, uri)
            reload++
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val doc = DocumentFile.fromSingleUri(context, uri)
            FilesStore.recordOpened(context, uri, doc?.name ?: uri.lastPathSegment ?: "File", doc?.length() ?: -1)
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, doc?.type ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { context.startActivity(intent) }
            reload++
        }
    }

    Box(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp)) {
                BarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
                Text("Files", fontSize = 22.sp, lineHeight = 28.sp, color = cs.onSurface, modifier = Modifier.weight(1f).padding(start = 4.dp))
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                if (total > 0) item { StorageCard(total, used, free) }
                item { SectionLabel("SYNCUP") }
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenDownloads).padding(horizontal = 20.dp, vertical = 10.dp),
                    ) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Download, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Downloads", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                            Text(if (runningDownloads == 0) "Files you save from pages" else "$runningDownloads running", fontSize = 12.5.sp, color = cs.onSurfaceVariant)
                        }
                    }
                }
                item { SectionLabel("YOUR FOLDERS") }
                items(folders, key = { it.uri.toString() }) { f ->
                    FolderRow(f.name, f.uri.lastPathSegment ?: "", onClick = { onOpenFolder(f.uri, f.name) }, onRemove = { FilesStore.removeFolder(context, f.uri); reload++ })
                }
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().height(48.dp)
                            .clip(RoundedCornerShape(24.dp)).clickable { openTree.launch(null) },
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Rounded.CreateNewFolder, null, tint = cs.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open a folder", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.primary)
                    }
                }
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp)).clickable { pickFile.launch(arrayOf("*/*")) },
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Rounded.InsertDriveFile, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open one file", fontSize = 13.sp, color = cs.onSurfaceVariant)
                    }
                }
                if (recent.isNotEmpty()) {
                    item { SectionLabel("RECENT") }
                    items(recent, key = { it.uri.toString() + it.openedAt }) { r ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                val intent = Intent(Intent.ACTION_VIEW).setData(r.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                runCatching { context.startActivity(intent) }
                            }.padding(horizontal = 20.dp, vertical = 10.dp),
                        ) {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.InsertDriveFile, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (r.size >= 0) Files.size(r.size) else "", fontSize = 12.5.sp, color = cs.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageCard(total: Long, used: Long, free: Long) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(cs.surfaceContainerLowest).padding(16.dp),
    ) {
        Text("Phone storage", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
        Text("${Files.size(used)} used of ${Files.size(total)} · ${Files.size(free)} free", fontSize = 12.5.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).padding(top = 10.dp).clip(RoundedCornerShape(4.dp)).background(cs.surfaceContainerHigh)) {
            val frac = if (total > 0) (used.toFloat() / total).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth(frac).fillMaxSize().background(cs.primary))
        }
    }
}

@Composable
private fun FolderRow(name: String, subtitle: String, onClick: () -> Unit, onRemove: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Folder, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, fontSize = 12.5.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        BarIcon(Icons.Rounded.Close, "Forget this folder", tint = cs.onSurfaceVariant, onClick = onRemove)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = .3.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp))
}
