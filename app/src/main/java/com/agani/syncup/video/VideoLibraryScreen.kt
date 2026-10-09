package com.agani.syncup.video

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.browser.ui.EmptyState
import com.agani.syncup.downloads.DlState
import com.agani.syncup.downloads.Downloads
import com.agani.syncup.downloads.FileKind
import com.agani.syncup.downloads.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tools → Video player: a video SyncUp downloaded, a video picked once from the phone (the system
 * picker — no permission, no browsing the rest of the library), or a pasted direct video link. No
 * YouTube handling (would mean either breaking YouTube's terms by pulling its stream out, or just
 * handing off to the YouTube app — neither is in scope right now).
 *
 * M3U/M3U8 playlist links are handled entirely in Tools → Channels instead (see [ChannelsHomeScreen],
 * [M3uPlaylist]) — this screen no longer inspects a pasted link for a channel list, it just plays
 * whatever URL is pasted here directly, same as any other video link.
 */
@Composable
fun VideoLibraryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var link by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }

    // Fresh "Continue watching" every time the screen comes back (after a video).
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) reload++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    val watching by produceState(emptyList<Watched>(), reload) {
        value = withContext(Dispatchers.IO) { WatchHistory.continueWatching(context) }
    }
    val downloads = Downloads.tasks.filter { it.state == DlState.DONE && it.kind == FileKind.VIDEO && it.contentUri != null }

    fun play(uri: String, title: String, source: String) {
        context.startActivity(VideoPlayerActivity.intent(context, uri, title, source))
    }
    fun playLink() {
        val u = link.trim().let { if (it.isNotBlank() && "://" !in it) "https://$it" else it }
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return
        play(u, Uri.parse(u).lastPathSegment ?: u, "Video link")
        link = ""
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && i >= 0) c.getString(i) else null
                }
            }.getOrNull() ?: uri.lastPathSegment ?: "Video"
            play(uri.toString(), name, "Picked from your phone")
        }
    }

    Box(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp)) {
                BarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
                Text("Videos", fontSize = 22.sp, lineHeight = 28.sp, color = cs.onSurface, modifier = Modifier.weight(1f).padding(start = 4.dp))
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().height(52.dp)
                            .clip(RoundedCornerShape(26.dp)).background(cs.surfaceContainerHigh).padding(start = 14.dp, end = 6.dp),
                    ) {
                        Icon(Icons.Rounded.Link, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        TextField(
                            value = link, onValueChange = { link = it }, singleLine = true,
                            placeholder = { Text("Paste a video link (mp4…)", fontSize = 14.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { playLink() }),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = { playLink() }, enabled = link.isNotBlank(), contentPadding = PaddingValues(horizontal = 16.dp), modifier = Modifier.height(40.dp)) {
                            Text("Play")
                        }
                    }
                }
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp)).clickable { pick.launch(arrayOf("video/*")) }
                            .background(cs.surfaceContainerHigh).padding(16.dp),
                    ) {
                        Icon(Icons.Rounded.FolderOpen, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Pick a video from this phone", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                            Text("Opens one file — SyncUp doesn't browse the rest", fontSize = 12.sp, color = cs.onSurfaceVariant)
                        }
                    }
                }
                if (watching.isNotEmpty()) {
                    item { SectionLabel("CONTINUE WATCHING") }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
                            items(watching, key = { it.uri }) { w -> WatchingCard(w) { play(w.uri, w.title, w.source) } }
                        }
                    }
                }
                item { SectionLabel("SYNCUP DOWNLOADS") }
                if (downloads.isEmpty()) {
                    item { Empty("Videos you download with SyncUp show here") }
                } else {
                    items(downloads, key = { it.id }) { t ->
                        ListRow(Icons.Rounded.Download, t.fileName, Files.size(t.total)) {
                            play(t.contentUri!!, t.fileName, "SyncUp downloads")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = .3.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 20.dp, end = 4.dp, top = 18.dp, bottom = 8.dp))
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxWidth().height(160.dp)) {
        EmptyState(Icons.Rounded.VideoLibrary, text)
    }
}

@Composable
private fun Thumb(uri: Uri, modifier: Modifier) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val bmp by produceState<android.graphics.Bitmap?>(null, uri) { value = withContext(Dispatchers.IO) { VideoStore.thumbnail(context, uri) } }
    Box(modifier.background(cs.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Rounded.Movie, null, tint = cs.onSurfaceVariant)
    }
}

@Composable
private fun WatchingCard(w: Watched, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val uri = remember(w.uri) { Uri.parse(w.uri) }
    Column(Modifier.width(168.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(14.dp))) {
            if (uri.scheme == "content") Thumb(uri, Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize().background(cs.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Link, null, tint = cs.onSurfaceVariant)
            }
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = .35f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(w.progress).background(Color(0xFFEF4444)))
            }
        }
        Text(w.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        val left = if (w.durationMs > 0) " · ${duration(w.durationMs - w.positionMs)} left" else ""
        Text(w.source + left, fontSize = 11.5.sp, color = cs.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun ListRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp)) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = cs.onSurfaceVariant)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 12.5.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun duration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
