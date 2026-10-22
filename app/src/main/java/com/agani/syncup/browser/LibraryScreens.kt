package com.agani.syncup.browser

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.StarBorder
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.browser.ui.EmptyState
import com.agani.syncup.ui.theme.dialogSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

internal fun dayLabel(millis: Long): String {
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

/**
 * History / Bookmarks / Downloads. History and bookmarks are Normal-section only; downloads are shared.
 * Rows sit in white cards (History: one card per day) and History has a search box that filters by
 * title or address as you type.
 */
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
    var query by remember { mutableStateOf("") }
    // Whether there's any history at all (the search box and the bin stay while a search finds nothing).
    var anyHistory by remember { mutableStateOf(false) }

    // Also reload when browser sync brings changes from the user's other devices.
    LaunchedEffect(page, version, com.agani.syncup.sync.BrowserSync.dataVersion, query) {
        val q = query.trim()
        when (page) {
            LibraryPage.HISTORY -> {
                if (q.isNotEmpty()) delay(150) // a newer keystroke cancels this one
                history = withContext(Dispatchers.IO) { db.history(q) }
                if (q.isEmpty()) anyHistory = history.isNotEmpty()
                else if (history.isNotEmpty()) anyHistory = true
            }
            LibraryPage.BOOKMARKS -> bookmarks = withContext(Dispatchers.IO) { db.bookmarks() }
            LibraryPage.DOWNLOADS -> Unit // its own screen: the Download Manager
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
        LibraryPage.HISTORY -> !anyHistory && query.isBlank()
        LibraryPage.BOOKMARKS -> bookmarks.isEmpty()
        LibraryPage.DOWNLOADS -> true
    }

    Column(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        PageTopBar(title, onBack) {
            if (page == LibraryPage.HISTORY && anyHistory) {
                BarIcon(Icons.Rounded.Delete, "Clear history", tint = cs.onSurfaceVariant) { confirmClear = true }
            }
        }
        if (page == LibraryPage.HISTORY && !empty) {
            SearchCardField(query, { query = it }, "Search history", Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }

        if (empty) {
            if (page != LibraryPage.DOWNLOADS) {
                InfoNote("Only Normal browsing is saved here. SyncUp and Incognito aren't.", Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp))
            }
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
            val days = remember(history) { history.groupBy { dayLabel(it.visitedAt) } }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                item(key = "note") {
                    InfoNote("Only Normal browsing is saved here. SyncUp and Incognito aren't.", Modifier.padding(top = if (page == LibraryPage.HISTORY) 12.dp else 4.dp))
                }
                when (page) {
                    LibraryPage.HISTORY -> {
                        if (history.isEmpty()) {
                            item(key = "none") {
                                Text(
                                    "No history matches \"${query.trim()}\"", fontSize = 14.sp, color = cs.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 20.dp),
                                )
                            }
                        }
                        // One card per day (history is newest first, so each day's visits are together).
                        days.forEach { (day, visits) ->
                            item(key = "d:$day") { CardHeading(day) }
                            itemsIndexed(visits, key = { _, h -> h.id }) { i, h ->
                                LibraryRow(
                                    title = h.title.ifBlank { UrlInput.display(h.url) },
                                    url = h.url,
                                    first = i == 0,
                                    last = i == visits.lastIndex,
                                    time = DateUtils.formatDateTime(context, h.visitedAt, DateUtils.FORMAT_SHOW_TIME),
                                    removeLabel = "Remove from history",
                                    onRemove = { io { db.deleteHistory(h.id) } },
                                    onClick = { onOpen(h.url) },
                                )
                            }
                        }
                    }
                    LibraryPage.BOOKMARKS -> {
                        item(key = "gap") { Spacer(Modifier.height(12.dp)) }
                        itemsIndexed(bookmarks, key = { _, b -> b.id }) { i, b ->
                            LibraryRow(
                                title = b.title,
                                url = b.url,
                                first = i == 0,
                                last = i == bookmarks.lastIndex,
                                removeLabel = "Remove bookmark",
                                onRemove = { io { db.deleteBookmark(b.id) } },
                                onClick = { onOpen(b.url) },
                            )
                        }
                    }
                    LibraryPage.DOWNLOADS -> Unit
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
                    query = ""
                    io { db.clearHistory() }
                }) { Text("Clear", color = cs.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

/** A page in a History / Bookmarks card: its letter mark, title, site, time and ×. */
@Composable
private fun LibraryRow(
    title: String,
    url: String,
    first: Boolean,
    last: Boolean,
    time: String? = null,
    removeLabel: String,
    onRemove: () -> Unit,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val host = UrlInput.hostAndPath(url).first
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .cardRow(first, last)
            .clickable(onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        LetterMark(host, host)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, lineHeight = 20.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(host, fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        if (time != null) Text(time, fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, removeLabel, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}
