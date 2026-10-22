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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Theaters
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.agani.syncup.browser.ui.EmptyState
import com.agani.syncup.downloads.DlState
import com.agani.syncup.downloads.Downloads
import com.agani.syncup.downloads.FileKind
import com.agani.syncup.downloads.Files
import com.agani.syncup.music.SectionHead
import com.agani.syncup.music.ToolTile
import com.agani.syncup.music.ToolTint
import com.agani.syncup.music.ToolTopBar
import com.agani.syncup.music.cardSlice
import com.agani.syncup.music.playAudioOnly
import com.agani.syncup.ui.theme.CardShape
import com.agani.syncup.ui.theme.PillTabs
import com.agani.syncup.ui.theme.appCard
import com.agani.syncup.watchparty.WatchParty
import com.agani.syncup.watchparty.WatchPartyTab
import com.agani.syncup.watchparty.WatchSource
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
 *
 * Two tabs: My videos (this page) and Watch Party ([WatchPartyTab]). Every video has a ⋮: Play, Play
 * audio only, Play with another app, Watch together. [startWith] opens Watch Party ready to start with
 * that video ("Watch together" from the player).
 */
@Composable
fun VideoLibraryScreen(onBack: () -> Unit, startWith: WatchSource? = null, onStartWithUsed: () -> Unit = {}, partyTab: Boolean = false) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    remember { WatchParty.init(context) }
    var tab by remember { mutableIntStateOf(if (partyTab || startWith != null || WatchParty.active) 1 else 0) }
    LaunchedEffect(partyTab, startWith) { if (partyTab || startWith != null) tab = 1 }
    // "Watch together" from a video's ⋮ here.
    var together by remember { mutableStateOf<WatchSource?>(null) }
    var playWith by remember { mutableStateOf<Pair<String, String>?>(null) }
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

    Box(Modifier.fillMaxSize().background(cs.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            ToolTopBar("Videos", onBack)
            PillTabs(
                listOf("My videos", "Watch Party"), selected = tab, onSelect = { tab = it },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
            )
            if (tab == 1) {
                Box(Modifier.weight(1f).padding(top = 8.dp)) {
                    WatchPartyTab(startWith = startWith ?: together, onStartWithUsed = { onStartWithUsed(); together = null })
                }
                return@Column
            }
            /** The ⋮ on a video. */
            @Composable
            fun Menu(uri: String, title: String, source: String, tint: Color = cs.onSurfaceVariant) = VideoMenu(
                onPlay = { play(uri, title, source) },
                onAudioOnly = { playAudioOnly(context, uri, title, source, WatchHistory.positionOf(context, uri)) },
                onPlayWith = { playWith = uri to title },
                onTogether = {
                    together = WatchSource(uri, title, WatchHistory.positionOf(context, uri))
                    tab = 1
                },
                tint = tint,
            )
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "link") {
                    // Paste a link: a white pill with Play at the end.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().height(52.dp)
                            .appCard(RoundedCornerShape(50)).padding(start = 16.dp, end = 7.dp),
                    ) {
                        Icon(Icons.Rounded.Link, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        BasicTextField(
                            value = link, onValueChange = { link = it }, singleLine = true,
                            textStyle = TextStyle(fontSize = 14.sp, color = cs.onSurface),
                            cursorBrush = SolidColor(cs.primary),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { playLink() }),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (link.isEmpty()) Text("Paste a video link (mp4…)", color = cs.onSurfaceVariant, fontSize = 14.sp, maxLines = 1)
                                    inner()
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { playLink() }, enabled = link.isNotBlank(), contentPadding = PaddingValues(horizontal = 18.dp), modifier = Modifier.height(38.dp)) {
                            Text("Play", fontSize = 13.5.sp)
                        }
                    }
                }
                item(key = "pick") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth()
                            .appCard(CardShape).clickable { pick.launch(arrayOf("video/*")) }.padding(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        ToolTile(Icons.Rounded.FolderOpen, ToolTint.ROSE)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Pick a video from this phone", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Opens one file — SyncUp doesn't browse the rest", fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                        }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                }
                if (watching.isNotEmpty()) {
                    item(key = "watching-head") { SectionHead("Continue watching", Modifier.padding(horizontal = 16.dp)) }
                    item(key = "watching") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
                            items(watching, key = { it.uri }) { w -> WatchingCard(w, menu = { Menu(w.uri, w.title, w.source, Color.White) }) { play(w.uri, w.title, w.source) } }
                        }
                    }
                }
                item(key = "downloads-head") { SectionHead("SyncUp downloads", Modifier.padding(horizontal = 16.dp)) }
                if (downloads.isEmpty()) {
                    item(key = "downloads-none") { Empty("Videos you download with SyncUp show here") }
                } else {
                    itemsIndexed(downloads, key = { _, t -> t.id }) { i, t ->
                        DownloadRow(
                            t.contentUri!!, t.fileName, Files.size(t.total),
                            first = i == 0, last = i == downloads.lastIndex,
                            menu = { Menu(t.contentUri!!, t.fileName, "SyncUp downloads") },
                        ) { play(t.contentUri!!, t.fileName, "SyncUp downloads") }
                    }
                }
            }
        }
    }
    playWith?.let { (uri, title) ->
        PlayWithSheet(uri = uri, title = title, onPlayHere = { play(uri, title, "SyncUp") }, onDismiss = { playWith = null })
    }
}

/** ⋮ on a video: Play, Play audio only, Play with another app, Watch together. */
@Composable
private fun VideoMenu(onPlay: () -> Unit, onAudioOnly: () -> Unit, onPlayWith: () -> Unit, onTogether: () -> Unit, tint: Color) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.size(40.dp).clip(CircleShape).clickable { open = true }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.MoreVert, "More", tint = tint, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Play") }, leadingIcon = { Icon(Icons.Rounded.PlayArrow, null) }, onClick = { open = false; onPlay() })
            DropdownMenuItem(text = { Text("Play audio only") }, leadingIcon = { Icon(Icons.Rounded.Headphones, null) }, onClick = { open = false; onAudioOnly() })
            DropdownMenuItem(text = { Text("Play with another app") }, leadingIcon = { Icon(Icons.Rounded.OpenInNew, null) }, onClick = { open = false; onPlayWith() })
            DropdownMenuItem(text = { Text("Watch together") }, leadingIcon = { Icon(Icons.Rounded.Theaters, null) }, onClick = { open = false; onTogether() })
        }
    }
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
    val bmp by produceState(VideoStore.cached(uri), uri) { if (value == null) value = withContext(Dispatchers.IO) { VideoStore.thumbnail(context, uri) } }
    Box(modifier.background(ToolTint.ROSE.tile), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Rounded.Movie, null, tint = ToolTint.ROSE.icon)
    }
}

@Composable
private fun WatchingCard(w: Watched, menu: @Composable () -> Unit, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val uri = remember(w.uri) { Uri.parse(w.uri) }
    Column(Modifier.width(168.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(16.dp))) {
            if (uri.scheme == "content") Thumb(uri, Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize().background(ToolTint.ROSE.tile), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Link, null, tint = ToolTint.ROSE.icon)
            }
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = .45f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(w.progress).background(Color(0xFFEF4444)))
            }
            Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = .45f)), contentAlignment = Alignment.Center) {
                menu()
            }
        }
        Text(w.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
        val left = if (w.durationMs > 0) " · ${duration(w.durationMs - w.positionMs)} left" else ""
        Text(w.source + left, fontSize = 11.5.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A SyncUp download: its first frame, name and size, ⋮ — one slice of the downloads card. */
@Composable
private fun DownloadRow(uri: String, title: String, subtitle: String, first: Boolean, last: Boolean, menu: @Composable () -> Unit, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val parsed = remember(uri) { Uri.parse(uri) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().cardSlice(first, last).clickable(onClick = onClick)
            .heightIn(min = 66.dp).padding(start = 14.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
    ) {
        Thumb(parsed, Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 12.5.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        menu()
    }
}

private fun duration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
