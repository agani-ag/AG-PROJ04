package com.agani.syncup.browser

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** History / Bookmarks / Downloads. History and bookmarks are Normal-section only; downloads are shared. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    page: LibraryPage,
    db: BrowserDb,
    onOpen: (url: String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) } // bump to reload the list
    var confirmClear by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var bookmarks by remember { mutableStateOf<List<Bookmark>>(emptyList()) }
    var downloads by remember { mutableStateOf<List<DownloadEntry>>(emptyList()) }

    LaunchedEffect(page, version) {
        withContext(Dispatchers.IO) {
            when (page) {
                LibraryPage.HISTORY -> history = db.history()
                LibraryPage.BOOKMARKS -> bookmarks = db.bookmarks()
                LibraryPage.DOWNLOADS -> downloads = db.downloads()
            }
        }
    }

    fun io(block: () -> Unit) = scope.launch {
        withContext(Dispatchers.IO) { block() }
        version++
    }

    val title = when (page) {
        LibraryPage.HISTORY -> "History"
        LibraryPage.BOOKMARKS -> "Bookmarks"
        LibraryPage.DOWNLOADS -> "Downloads"
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    if (page == LibraryPage.HISTORY && history.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) { Icon(Icons.Rounded.DeleteSweep, "Clear history") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        val empty = when (page) {
            LibraryPage.HISTORY -> history.isEmpty()
            LibraryPage.BOOKMARKS -> bookmarks.isEmpty()
            LibraryPage.DOWNLOADS -> downloads.isEmpty()
        }
        if (empty) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        when (page) {
                            LibraryPage.HISTORY -> Icons.Rounded.History
                            LibraryPage.BOOKMARKS -> Icons.Rounded.Star
                            LibraryPage.DOWNLOADS -> Icons.Rounded.Download
                        },
                        null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(44.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("No ${title.lowercase()} yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (page != LibraryPage.DOWNLOADS) {
                        Text("Only Normal browsing is saved here", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            when (page) {
                LibraryPage.HISTORY -> {
                    var lastDay = ""
                    history.forEach { h ->
                        val day = DateUtils.getRelativeTimeSpanString(
                            h.visitedAt, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS,
                        ).toString()
                        if (day != lastDay) {
                            lastDay = day
                            item(key = "d$day${h.id}") { DayHeader(day) }
                        }
                        item(key = h.id) {
                            LibraryRow(
                                letter = UrlInput.display(h.url).take(1).uppercase(),
                                title = h.title.ifBlank { UrlInput.display(h.url) },
                                subtitle = UrlInput.display(h.url).substringBefore('/') + " · " +
                                    DateUtils.formatDateTime(context, h.visitedAt, DateUtils.FORMAT_SHOW_TIME),
                                onClick = { onOpen(h.url) },
                                trailing = Icons.Rounded.Close,
                                onTrailing = { io { db.deleteHistory(h.id) } },
                            )
                        }
                    }
                }
                LibraryPage.BOOKMARKS -> items(bookmarks, key = { it.id }) { b ->
                    LibraryRow(
                        letter = UrlInput.display(b.url).take(1).uppercase(),
                        title = b.title,
                        subtitle = UrlInput.display(b.url),
                        onClick = { onOpen(b.url) },
                        trailing = Icons.Rounded.Close,
                        onTrailing = { io { db.deleteBookmark(b.id) } },
                    )
                }
                LibraryPage.DOWNLOADS -> items(downloads, key = { it.id }) { d ->
                    LibraryRow(
                        icon = Icons.Rounded.Description,
                        title = d.fileName,
                        subtitle = d.source,
                        workLabel = d.work,
                        onClick = { openDownload(context, d) },
                        trailing = Icons.Rounded.Close,
                        onTrailing = { io { db.deleteDownload(d.id) } },
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?", fontWeight = FontWeight.Bold) },
            text = { Text("This removes all your Normal browsing history from this device.") },
            confirmButton = {
                Button(onClick = {
                    confirmClear = false
                    io { db.clearHistory() }
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

private fun openDownload(context: Context, d: DownloadEntry) {
    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val uri = runCatching { dm.getUriForDownloadedFile(d.systemId) }.getOrNull()
    if (uri == null) {
        Toast.makeText(context, "File not found — it may still be downloading or was deleted", Toast.LENGTH_SHORT).show()
        return
    }
    val type = runCatching { dm.getMimeTypeForDownloadedFile(d.systemId) }.getOrNull() ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (runCatching { context.startActivity(intent) }.isFailure) {
        Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun DayHeader(text: String) {
    Text(
        text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .6.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun LibraryRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: ImageVector,
    onTrailing: () -> Unit,
    letter: String? = null,
    icon: ImageVector? = null,
    workLabel: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
            if (icon != null) Icon(icon, null, tint = cs.primary) else Text(letter ?: "•", color = cs.primary, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (workLabel) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFCCFBF1)).padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        Icon(Icons.Rounded.Work, null, tint = Color(0xFF0F766E), modifier = Modifier.size(11.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("Work", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F766E))
                    }
                }
                Text(subtitle, fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onTrailing) { Icon(trailing, "Remove", tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
    }
}
