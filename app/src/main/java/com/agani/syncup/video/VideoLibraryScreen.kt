package com.agani.syncup.video

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import com.agani.syncup.browser.ui.PillButton
import com.agani.syncup.music.SortButton
import com.agani.syncup.music.rememberSortOrder
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
 * Tools → Video player: every video on the device by itself or by folder, like Music (user, 16 Nov 2026), a
 * video picked once (the system picker), or a pasted direct video link. No
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
@OptIn(ExperimentalMaterial3Api::class)
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

    // The device's videos (like Music's songs): read again on every return, so new videos and access given in
    // Settings show up.
    var granted by remember { mutableStateOf(LocalVideos.canRead(context)) }
    var onlyPicked by remember { mutableStateOf(LocalVideos.onlyPicked(context)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = LocalVideos.canRead(context)
        onlyPicked = LocalVideos.onlyPicked(context)
        asked = true
        reload++
    }
    var videos by remember { mutableStateOf<List<LocalVideo>?>(null) } // null while loading
    var query by rememberSaveable { mutableStateOf("") }
    // Folders: the videos by the folder they're in; [openFolder] is the one open (its path).
    var byFolder by rememberSaveable { mutableStateOf(false) }
    var openFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSortOrder("videos", VideoSort.values(), VideoSort.NEWEST)
    var linkSheet by remember { mutableStateOf(false) }

    // Fresh "Continue watching" and list every time the screen comes back (after a video, or Settings).
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                granted = LocalVideos.canRead(context)
                onlyPicked = LocalVideos.onlyPicked(context)
                reload++
            }
        }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    val watching by produceState(emptyList<Watched>(), reload) {
        value = withContext(Dispatchers.IO) { WatchHistory.continueWatching(context) }
    }
    LaunchedEffect(granted, reload) {
        if (granted) videos = withContext(Dispatchers.IO) { runCatching { LocalVideos.load(context) }.getOrDefault(emptyList()) }
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
        linkSheet = false
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
            val all = videos
            val q = query.trim()
            val list = remember(all, q, sort) {
                val v = all.orEmpty()
                sort.sort(if (q.isEmpty()) v else v.filter { it.name.contains(q, true) || it.folder.contains(q, true) })
            }
            val folder = openFolder.takeIf { byFolder }
            val shown = remember(list, folder) { if (folder == null) list else list.filter { it.folder == folder } }
            val folders = remember(list, sort) { sort.folders(list.groupBy { it.folder }.toList()) }
            val showFolders = byFolder && folder == null
            if (folder != null) BackHandler { openFolder = null }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                if (granted) {
                    item(key = "search") {
                        val pill = RoundedCornerShape(50)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().height(50.dp).appCard(pill)
                                .then(if (query.isNotEmpty()) Modifier.border(2.dp, cs.primary, pill) else Modifier)
                                .padding(start = 16.dp, end = 4.dp),
                        ) {
                            Icon(Icons.Rounded.Search, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            BasicTextField(
                                value = query, onValueChange = { query = it }, singleLine = true,
                                textStyle = TextStyle(fontSize = 15.sp, color = cs.onSurface),
                                cursorBrush = SolidColor(cs.primary),
                                decorationBox = { inner ->
                                    Box(contentAlignment = Alignment.CenterStart) {
                                        if (query.isEmpty()) Text("Search videos and folders", color = cs.onSurfaceVariant, fontSize = 15.sp)
                                        inner()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                            if (query.isNotEmpty()) PillButton(Icons.Rounded.Close, "Clear", size = 40.dp) { query = "" }
                        }
                    }
                }
                item(key = "actions") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth()) {
                        val small = PaddingValues(horizontal = 10.dp)
                        if (granted) {
                            // Videos by folder (on: tinted), as in Music.
                            FilledTonalButton(
                                onClick = {
                                    byFolder = !byFolder
                                    openFolder = null
                                },
                                colors = if (byFolder) ButtonDefaults.filledTonalButtonColors(containerColor = cs.primaryContainer, contentColor = cs.primary)
                                else ButtonDefaults.filledTonalButtonColors(),
                                contentPadding = small, modifier = Modifier.weight(1f).height(40.dp),
                            ) { ButtonLabel(Icons.Rounded.Folder, "Folders") }
                        }
                        FilledTonalButton(onClick = { linkSheet = true }, contentPadding = small, modifier = Modifier.weight(1f).height(40.dp)) {
                            ButtonLabel(Icons.Rounded.Link, "Play a link")
                        }
                        FilledTonalButton(onClick = { pick.launch(arrayOf("video/*")) }, contentPadding = small, modifier = Modifier.weight(1f).height(40.dp)) {
                            ButtonLabel(Icons.Rounded.FolderOpen, "Open a file")
                        }
                    }
                }
                if (!granted) {
                    item(key = "allow") {
                        val blocked = asked && !LocalVideos.canRead(context)
                        Column(
                            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().appCard(CardShape).padding(18.dp),
                        ) {
                            ToolTile(Icons.Rounded.VideoLibrary, ToolTint.ROSE)
                            Text("See every video on this phone", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.padding(top = 12.dp))
                            Text(
                                if (blocked) "Video access is off for SyncUp. Turn it on in Settings → Permissions."
                                else "Your videos and their folders, like the Music player. They stay on this phone; nothing is uploaded.",
                                fontSize = 13.5.sp, lineHeight = 19.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                            )
                            Button(
                                onClick = { if (blocked) com.agani.syncup.sensors.openAppSettings(context) else ask.launch(LocalVideos.permissions) },
                                modifier = Modifier.padding(top = 12.dp).height(42.dp),
                            ) { Text(if (blocked) "Open settings" else "Allow access") }
                        }
                    }
                } else if (onlyPicked) {
                    item(key = "picked") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
                                .background(cs.primaryContainer).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        ) {
                            Text("Showing only the videos you chose", fontSize = 13.5.sp, color = cs.onPrimaryContainer, modifier = Modifier.weight(1f))
                            TextButton(onClick = { ask.launch(LocalVideos.permissions) }) { Text("Allow all") }
                        }
                    }
                }
                if (watching.isNotEmpty() && q.isEmpty() && !byFolder) {
                    item(key = "watching-head") { SectionHead("Continue watching", Modifier.padding(horizontal = 16.dp)) }
                    item(key = "watching") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
                            items(watching, key = { it.uri }) { w -> WatchingCard(w, menu = { Menu(w.uri, w.title, w.source, Color.White) }) { play(w.uri, w.title, w.source) } }
                        }
                    }
                }
                if (granted) {
                    when {
                        all == null -> item(key = "loading") {
                            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        }
                        all.isEmpty() -> item(key = "none") { Empty("Videos you record, download or copy to this phone show up here") }
                        else -> {
                            item(key = "count") {
                                // Sort sits by the count (nothing to sort when the search matches nothing).
                                val sortButton: @Composable () -> Unit = {
                                    if (list.isNotEmpty()) SortButton(VideoSort.values().toList(), sort) { sort = it }
                                }
                                when {
                                    folder != null -> FolderHead(folder, shown.size, onBack = { openFolder = null }, trailing = sortButton)
                                    showFolders -> SectionHead("${folders.size} folder${if (folders.size == 1) "" else "s"}", Modifier.padding(horizontal = 16.dp)) { sortButton() }
                                    else -> SectionHead("${list.size} video${if (list.size == 1) "" else "s"}", Modifier.padding(horizontal = 16.dp)) { sortButton() }
                                }
                            }
                            if (list.isEmpty()) {
                                item(key = "nomatch") {
                                    Text(
                                        "No videos match “$q”", fontSize = 14.sp, color = cs.onSurfaceVariant,
                                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                                    )
                                }
                            }
                            if (showFolders) {
                                itemsIndexed(folders, key = { _, f -> "folder:${f.first}" }) { i, (path, inIt) ->
                                    FolderRow(path, inIt.size, first = i == 0, last = i == folders.lastIndex) { openFolder = path }
                                }
                            } else {
                                itemsIndexed(shown, key = { _, v -> v.id }) { i, v ->
                                    val source = LocalVideos.folderName(v.folder)
                                    VideoRow(
                                        v, first = i == 0, last = i == shown.lastIndex,
                                        showFolder = folder == null, // inside a folder its name is already above
                                        menu = { Menu(v.uri.toString(), v.name, source) },
                                    ) { play(v.uri.toString(), v.name, source) }
                                }
                            }
                        }
                    }
                } else {
                    // Without access, SyncUp's own downloads still play.
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
    }
    playWith?.let { (uri, title) ->
        PlayWithSheet(uri = uri, title = title, onPlayHere = { play(uri, title, "SyncUp") }, onDismiss = { playWith = null })
    }
    if (linkSheet) {
        ModalBottomSheet(onDismissRequest = { linkSheet = false }, containerColor = cs.surfaceContainerLowest) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
                Text("Play a video link", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                Text("A direct link to a video file (mp4…). Live TV lists go in Channels.", fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 14.dp).fillMaxWidth().height(52.dp)
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
                                if (link.isEmpty()) Text("Paste a video link", color = cs.onSurfaceVariant, fontSize = 14.sp, maxLines = 1)
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
        }
    }
}

/** A pill button's icon + label, sized for the three-across row (as in Music). */
@Composable
private fun ButtonLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Icon(icon, null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(6.dp))
    Text(text, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** A video on the device: its first frame with the length on it, name, size and folder, ⋮ — one slice of the list's card. */
@Composable
private fun VideoRow(v: LocalVideo, first: Boolean, last: Boolean, showFolder: Boolean, menu: @Composable () -> Unit, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().cardSlice(first, last).clickable(onClick = onClick)
            .heightIn(min = 76.dp).padding(start = 12.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
    ) {
        Box(Modifier.size(width = 100.dp, height = 58.dp).clip(RoundedCornerShape(12.dp))) {
            Thumb(v.uri, Modifier.fillMaxSize())
            if (v.durationMs > 0) {
                Text(
                    duration(v.durationMs), fontSize = 11.sp, color = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(5.dp))
                        .background(Color.Black.copy(alpha = .6f)).padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(v.name, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(Files.size(v.sizeBytes), if (showFolder) LocalVideos.folderName(v.folder) else "").filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 12.5.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
            )
        }
        menu()
    }
}

/** A folder of videos: one slice of the list's card. */
@Composable
private fun FolderRow(path: String, count: Int, first: Boolean, last: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().cardSlice(first, last).clickable(onClick = onClick)
            .heightIn(min = 66.dp).padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(ToolTint.ROSE.tile), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Folder, null, tint = ToolTint.ROSE.icon)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(LocalVideos.folderName(path), fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                // The path only when it adds something ("DCIM/Camera", not "Movies · Movies").
                "$count video${if (count == 1) "" else "s"}" + if (path.isNotBlank() && path != LocalVideos.folderName(path)) " · $path" else "",
                fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = cs.onSurfaceVariant)
    }
}

/** Above an open folder's videos: back to the folders, its name and how many. */
@Composable
private fun FolderHead(path: String, count: Int, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    // end = 20 dp: the Sort button lines up with the one by the list's count (SectionHead).
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 20.dp, top = 10.dp, bottom = 6.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "All folders") }
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(LocalVideos.folderName(path), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$count video${if (count == 1) "" else "s"}", fontSize = 12.5.sp, color = cs.onSurfaceVariant)
        }
        trailing()
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
    // At least 160 dp, but as tall as the icon + words need (a fixed 160 dp cut the words off).
    Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) {
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
