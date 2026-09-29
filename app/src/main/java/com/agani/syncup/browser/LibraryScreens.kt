package com.agani.syncup.browser

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.browser.ui.EmptyState
import com.agani.syncup.browser.ui.IconTile
import com.agani.syncup.browser.ui.SectionTheme
import com.agani.syncup.browser.ui.StatusChip
import com.agani.syncup.browser.ui.TonalRow
import com.agani.syncup.ui.theme.dialogSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

private val FAVICON_COLORS = listOf(
    Color(0xFF1B4FD8), Color(0xFF16A085), Color(0xFFDC2626), Color(0xFF7C3AED),
    Color(0xFFEA580C), Color(0xFF0891B2), Color(0xFF4285F4), Color(0xFF374151),
)

/** A letter "favicon" in a stable per-site colour. */
@Composable
private fun Favicon(url: String) {
    val host = UrlInput.hostAndPath(url).first
    val color = FAVICON_COLORS[(host.hashCode() and 0x7fffffff) % FAVICON_COLORS.size]
    Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(color), contentAlignment = Alignment.Center) {
        Text(host.take(1).uppercase(), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
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

/** History / Bookmarks / Downloads. History and bookmarks are Normal-section only; downloads are shared. */
@Composable
fun LibraryScreen(
    page: LibraryPage,
    db: BrowserDb,
    onOpen: (url: String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) } // bump to reload the list
    var confirmClear by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var bookmarks by remember { mutableStateOf<List<Bookmark>>(emptyList()) }
    var downloads by remember { mutableStateOf<List<DownloadEntry>>(emptyList()) }

    // Also reload when browser sync brings changes from the user's other devices.
    LaunchedEffect(page, version, com.agani.syncup.sync.BrowserSync.dataVersion) {
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
    val empty = when (page) {
        LibraryPage.HISTORY -> history.isEmpty()
        LibraryPage.BOOKMARKS -> bookmarks.isEmpty()
        LibraryPage.DOWNLOADS -> downloads.isEmpty()
    }

    Column(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp)) {
            BarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
            Text(title, fontSize = 22.sp, lineHeight = 28.sp, color = cs.onSurface, modifier = Modifier.weight(1f).padding(start = 4.dp))
            if (page == LibraryPage.HISTORY && history.isNotEmpty()) {
                BarIcon(Icons.Rounded.DeleteSweep, "Clear history", tint = cs.onSurfaceVariant) { confirmClear = true }
            }
        }
        if (page != LibraryPage.DOWNLOADS) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(start = 20.dp, end = 20.dp, bottom = 4.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(cs.surfaceContainerLow)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Icon(Icons.Rounded.Info, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                Text("Only Normal browsing is saved here. Work and Incognito aren't.", fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant)
            }
        }

        if (empty) {
            EmptyState(
                when (page) {
                    LibraryPage.HISTORY -> Icons.Rounded.History
                    LibraryPage.BOOKMARKS -> Icons.Rounded.StarBorder
                    LibraryPage.DOWNLOADS -> Icons.Rounded.Download
                },
                "No ${title.lowercase()} yet",
                when (page) {
                    LibraryPage.HISTORY -> "Pages you visit in Normal tabs show up here"
                    LibraryPage.BOOKMARKS -> "Tap the star in the address bar to save a page"
                    LibraryPage.DOWNLOADS -> "Files you download from any section show up here"
                },
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                when (page) {
                    LibraryPage.HISTORY -> {
                        var lastDay = ""
                        history.forEach { h ->
                            val day = dayLabel(h.visitedAt)
                            if (day != lastDay) {
                                lastDay = day
                                item(key = "d$day${h.id}") { DayHeader(day) }
                            }
                            item(key = h.id) {
                                TonalRow(
                                    title = h.title.ifBlank { UrlInput.display(h.url) },
                                    subtitle = UrlInput.hostAndPath(h.url).first,
                                    leading = { Favicon(h.url) },
                                    trailing = {
                                        Text(DateUtils.formatDateTime(context, h.visitedAt, DateUtils.FORMAT_SHOW_TIME), fontSize = 12.sp, color = cs.onSurfaceVariant)
                                        BarIcon(Icons.Rounded.Close, "Remove from history", tint = cs.onSurfaceVariant) { io { db.deleteHistory(h.id) } }
                                    },
                                    minHeight = 60.dp,
                                    onClick = { onOpen(h.url) },
                                )
                            }
                        }
                    }
                    LibraryPage.BOOKMARKS -> items(bookmarks, key = { it.id }) { b ->
                        TonalRow(
                            title = b.title,
                            subtitle = UrlInput.hostAndPath(b.url).first,
                            leading = { Favicon(b.url) },
                            trailing = { BarIcon(Icons.Rounded.Close, "Remove bookmark", tint = cs.onSurfaceVariant) { io { db.deleteBookmark(b.id) } } },
                            minHeight = 60.dp,
                            onClick = { onOpen(b.url) },
                        )
                    }
                    LibraryPage.DOWNLOADS -> items(downloads, key = { it.id }) { d ->
                        TonalRow(
                            title = d.fileName,
                            subtitle = d.source,
                            leading = { IconTile(fileIcon(d.fileName), container = cs.surfaceContainerHigh, content = cs.onSurfaceVariant) },
                            trailing = {
                                if (d.work) SectionTheme(Section.WORK) { StatusChip("Work", Icons.Rounded.Work) }
                                BarIcon(Icons.Rounded.Close, "Remove from list", tint = cs.onSurfaceVariant) { io { db.deleteDownload(d.id) } }
                            },
                            minHeight = 60.dp,
                            onClick = { openDownload(context, d) },
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = cs.dialogSurface,
            title = { Text("Clear history?") },
            text = { Text("This removes all your Normal browsing history from this device.", color = cs.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    io { db.clearHistory() }
                }) { Text("Clear", color = cs.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

private fun fileIcon(name: String): ImageVector = when (name.substringAfterLast('.', "").lowercase()) {
    "pdf" -> Icons.Rounded.PictureAsPdf
    "png", "jpg", "jpeg", "gif", "webp", "heic" -> Icons.Rounded.Image
    "xls", "xlsx", "csv" -> Icons.Rounded.TableChart
    else -> Icons.Rounded.Description
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
        text, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = .1.sp,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}
