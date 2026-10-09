package com.agani.syncup.video

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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.browser.ui.EmptyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tools → Channels: every M3U/M3U8 playlist link the user has added, added and managed entirely here.
 * The Video player's paste box plays a direct video link only — a playlist link belongs here instead
 * (the user's explicit call: no M3U/M3U8 handling in the Video player's add-link flow any more).
 */
@Composable
fun ChannelsHomeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(SavedPlaylists.all(context)) }
    var showAdd by remember { mutableStateOf(false) }
    var openEntry by remember { mutableStateOf<SavedPlaylistEntry?>(null) }
    var openCategories by remember { mutableStateOf<List<TvCategoryGroup>?>(null) }
    var openingUrl by remember { mutableStateOf<String?>(null) }

    fun refresh() { entries = SavedPlaylists.all(context) }

    fun openList(entry: SavedPlaylistEntry) {
        openingUrl = entry.url
        scope.launch {
            when (val result = withContext(Dispatchers.IO) { M3uPlaylist.inspect(entry.url) }) {
                is M3uResult.ChannelList -> {
                    SavedPlaylists.upsert(context, entry.url, entry.title, result.categories.sumOf { it.channels.size })
                    refresh()
                    openEntry = entry
                    openCategories = result.categories
                }
                else -> android.widget.Toast.makeText(context, "Couldn't load “${entry.title}” — check your connection.", android.widget.Toast.LENGTH_LONG).show()
            }
            openingUrl = null
        }
    }

    val categories = openCategories
    val entry = openEntry
    if (categories != null && entry != null) {
        androidx.activity.compose.BackHandler { openCategories = null; openEntry = null }
        ChannelBrowserScreen(
            categories = categories,
            title = entry.title,
            playlistUrl = entry.url,
            onPlaylistUpdated = { newUrl, newCategories ->
                SavedPlaylists.replaceUrl(context, entry.url, newUrl, newCategories.sumOf { it.channels.size })
                refresh()
                openEntry = entry.copy(url = newUrl)
                openCategories = newCategories
            },
            onRemoved = {
                SavedPlaylists.remove(context, entry.url)
                refresh()
                openCategories = null
                openEntry = null
            },
            onPlay = { ch -> context.startActivity(VideoPlayerActivity.intent(context, ch.url, ch.title, "Live TV · ${ch.category}")) },
            onBack = { openCategories = null; openEntry = null },
        )
        return
    }

    Box(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp)) {
                BarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
                Text("Channels", fontSize = 22.sp, lineHeight = 28.sp, color = cs.onSurface, modifier = Modifier.weight(1f).padding(start = 4.dp))
                BarIcon(Icons.Rounded.Add, "Add a channel list") { showAdd = true }
            }
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize()) {
                    EmptyState(
                        Icons.Rounded.LiveTv, "No channel lists yet",
                        "Add an M3U or M3U8 playlist link to browse and play its channels.",
                        action = "Add a link", onAction = { showAdd = true },
                    )
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(entries, key = { it.url }) { e ->
                        PlaylistRow(e, loading = openingUrl == e.url, onClick = { openList(e) }, onDelete = { SavedPlaylists.remove(context, e.url); refresh() })
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddPlaylistDialog(
            onDismiss = { showAdd = false },
            onAdded = { url, title, cats ->
                SavedPlaylists.upsert(context, url, title, cats.sumOf { it.channels.size })
                refresh()
                showAdd = false
                openEntry = SavedPlaylists.all(context).firstOrNull { it.url == url } ?: SavedPlaylistEntry(url, title, cats.sumOf { it.channels.size }, System.currentTimeMillis())
                openCategories = cats
            },
        )
    }
}

@Composable
private fun PlaylistRow(entry: SavedPlaylistEntry, loading: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(enabled = !loading, onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            else Icon(Icons.Rounded.LiveTv, null, tint = cs.onSurfaceVariant)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (entry.channelCount > 0) "${entry.channelCount} channels · ${entry.url}" else entry.url,
                fontSize = 12.5.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            BarIcon(Icons.Rounded.MoreVert, "More", tint = cs.onSurfaceVariant) { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Delete", color = cs.error) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun AddPlaylistDialog(onDismiss: () -> Unit, onAdded: (url: String, title: String, categories: List<TvCategoryGroup>) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!checking) onDismiss() },
        title = { Text("Add a channel list") },
        text = {
            Column {
                OutlinedTextField(
                    value = url, onValueChange = { url = it; error = null }, singleLine = true,
                    label = { Text("M3U or M3U8 link") }, enabled = !checking, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("Name (optional)") }, enabled = !checking, modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(error.orEmpty(), fontSize = 12.5.sp, color = cs.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotBlank() && !checking,
                onClick = {
                    val raw = url.trim().let { if ("://" !in it) "https://$it" else it }
                    if (!(raw.startsWith("http://") || raw.startsWith("https://"))) { error = "Enter a valid link."; return@TextButton }
                    checking = true
                    error = null
                    scope.launch {
                        when (val result = withContext(Dispatchers.IO) { M3uPlaylist.inspect(raw) }) {
                            is M3uResult.ChannelList -> {
                                val title = name.trim().ifBlank { runCatching { java.net.URL(raw).host }.getOrDefault(raw) }
                                onAdded(raw, title, result.categories)
                            }
                            else -> error = "That link doesn't look like a channel list. For a single video, use Tools → Video player instead."
                        }
                        checking = false
                    }
                },
            ) {
                if (checking) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("Add")
            }
        },
        dismissButton = { TextButton(enabled = !checking, onClick = onDismiss) { Text("Cancel") } },
    )
}
