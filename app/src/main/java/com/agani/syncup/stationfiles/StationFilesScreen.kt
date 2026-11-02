package com.agani.syncup.stationfiles

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.browser.ui.EmptyState
import com.agani.syncup.data.RadioChannel
import com.agani.syncup.downloads.DlState
import com.agani.syncup.downloads.DownloadRequest
import com.agani.syncup.downloads.Downloads
import com.agani.syncup.downloads.Files
import com.agani.syncup.downloads.LinkInfo
import com.agani.syncup.music.FILE_ID
import com.agani.syncup.music.LocalPlayer
import com.agani.syncup.music.NoteBox
import com.agani.syncup.music.SectionHead
import com.agani.syncup.music.ToolTint
import com.agani.syncup.music.ToolTopBar
import com.agani.syncup.music.cardSlice
import com.agani.syncup.video.VideoPlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A folder on a station, as the Files screens open it: the station's key, never its address. */
data class FolderRef(val key: String, val station: String, val listUrl: String, val name: String, val trail: String) {
    val isRoot: Boolean get() = listUrl == ROOT
}

internal const val ROOT = "/api/files/"
private const val PAGE = 200

/** The folders opened in Radio → Files, deepest last (survives rotation). */
val FolderStackSaver: Saver<List<FolderRef>, Any> = Saver(
    save = { stack -> stack.flatMap { listOf(it.key, it.station, it.listUrl, it.name, it.trail) } },
    restore = { saved -> (saved as List<*>).map { it.toString() }.chunked(5).map { FolderRef(it[0], it[1], it[2], it[3], it[4]) } },
)

private val STATION_TINTS = listOf(ToolTint.ORANGE, ToolTint.ROSE, ToolTint.VIOLET, ToolTint.CYAN, ToolTint.GREEN, ToolTint.BLUE, ToolTint.AMBER)

// ============================================================================ Radio → Files
/** Stations sharing folders, with their radio on or off. */
@Composable
fun FilesTab(onOpen: (FolderRef) -> Unit) {
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                Stations.refresh(force = true)
                delay(15_000)
            }
        }
    }
    val sharing = Stations.sharing
    when {
        !Stations.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        sharing.isEmpty() -> EmptyState(
            Icons.Rounded.FolderOpen,
            if (Stations.listError != null) "Couldn't load stations" else "No shared files right now",
            if (Stations.listError != null) "Check your connection. Trying again shortly." else "Stations sharing folders show up here, with their radio on or off.",
        )
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
            item(key = "head") { SectionHead("Stations sharing files", count = sharing.size.toString()) }
            itemsIndexed(sharing, key = { _, s -> "s:" + s.key }) { i, s ->
                StationFilesRow(s, Modifier.cardSlice(first = i == 0, last = i == sharing.lastIndex)) {
                    onOpen(FolderRef(s.key, s.name, ROOT, s.name, ""))
                }
            }
        }
    }
}

@Composable
private fun StationFilesRow(s: RadioChannel, slice: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val tint = STATION_TINTS[(s.name.hashCode() and 0x7fffffff) % STATION_TINTS.size]
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = slice.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 66.dp).padding(start = 14.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(tint.tile), contentAlignment = Alignment.Center) {
            Text(
                s.name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase(),
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = tint.icon,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (if (s.radioOnAir) "On air" else "Radio off") + if (s.filesProtected) " · PIN" else " · open",
                fontSize = 12.5.sp, color = cs.onSurfaceVariant,
            )
        }
        if (s.key in Stations.onSameWifi) {
            WifiTag()
            Spacer(Modifier.width(8.dp))
        }
        if (s.filesProtected) Icon(Icons.Rounded.Lock, "Needs a PIN", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

/** A small green mark: this station's files or radio come straight from its PC, on this phone's Wi-Fi. */
@Composable
internal fun WifiTag(modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clip(RoundedCornerShape(50)).background(ToolTint.GREEN.tile).padding(start = 6.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Icon(Icons.Rounded.Wifi, null, tint = ToolTint.GREEN.icon, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(3.dp))
        Text("WIFI", fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, color = ToolTint.GREEN.icon, maxLines = 1)
    }
}

// ============================================================================ one folder
private sealed interface Problem {
    data object Offline : Problem
    data object Pin : Problem
    data object NotShared : Problem
    data class Error(val message: String) : Problem
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationFolderScreen(
    ref: FolderRef,
    onBack: () -> Unit,
    onOpen: (FolderRef) -> Unit,
    bottom: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val player = LocalPlayer.current
    val scope = rememberCoroutineScope()
    val station = Stations.station(ref.key)

    var noticeSeen by remember(ref.key) { mutableStateOf(Stations.noticeSeen(ref.key)) }
    var listing by remember(ref) { mutableStateOf<StationListing?>(null) }
    var folders by remember(ref) { mutableStateOf<List<StationFolder>>(emptyList()) }
    var files by remember(ref) { mutableStateOf<List<StationFile>>(emptyList()) }
    var total by remember(ref) { mutableIntStateOf(0) }
    var loading by remember(ref) { mutableStateOf(true) }
    var loadingMore by remember(ref) { mutableStateOf(false) }
    var problem by remember(ref) { mutableStateOf<Problem?>(null) }
    var reload by remember(ref) { mutableIntStateOf(0) }
    var searching by rememberSaveable(ref.listUrl) { mutableStateOf(false) }
    var query by rememberSaveable(ref.listUrl) { mutableStateOf("") }
    var kind by rememberSaveable(ref.listUrl) { mutableStateOf("all") }
    var selected by remember(ref) { mutableStateOf<Set<String>>(emptySet()) }
    var showPin by remember(ref) { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var photoAt by remember(ref) { mutableStateOf<Int?>(null) }
    var folderPlan by remember(ref) { mutableStateOf<List<StationFile>?>(null) }
    var walking by remember(ref) { mutableStateOf(false) }
    var sharingFiles by remember(ref) { mutableStateOf<List<StationFile>?>(null) }

    LaunchedEffect(ref, query, reload) {
        loading = true
        problem = null
        if (query.isNotBlank()) delay(350)
        val result = withContext(Dispatchers.IO) { runCatching { Stations.list(ref.key, ref.listUrl, query, 0, PAGE) } }
        result.onSuccess { l ->
            listing = l
            folders = l.shares ?: l.folders
            files = l.files
            total = l.total ?: (folders.size + files.size)
        }.onFailure { e ->
            problem = when {
                e is Stations.PinNeeded -> Problem.Pin.also { showPin = true }
                e is Stations.Gone -> Problem.Offline
                e is Stations.Refused && (e.code == 404) -> Problem.NotShared
                else -> Problem.Error(e.message ?: "Something went wrong.")
            }
        }
        loading = false
    }

    val listState = rememberLazyListState()
    val atEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= listState.layoutInfo.totalItemsCount - 3 } == true } }
    LaunchedEffect(atEnd, total, folders.size + files.size) {
        val have = folders.size + files.size
        if (!atEnd || loadingMore || loading || have >= total || problem != null) return@LaunchedEffect
        loadingMore = true
        withContext(Dispatchers.IO) { runCatching { Stations.list(ref.key, ref.listUrl, query, have, PAGE) } }.onSuccess { l ->
            folders = folders + l.folders
            files = files + l.files
            total = l.total ?: total
        }
        loadingMore = false
    }

    val shown = files.filter { f -> kindMatches(f, kind) }
    val audio = files.filter { it.kind == "audio" }
    val images = files.filter { it.kind == "image" }
    val uploadFolder = listing?.uploadFolder
    val uploading = if (uploadFolder == null || query.isNotBlank()) emptyList() else StationUploads.items.filter { it.key == ref.key && it.folder == uploadFolder }
    // A finished upload leaves the uploads; this folder reloads, so the file shows in the list like the others.
    val finishedTick = StationUploads.finishedTick
    var seenTick by remember(ref) { mutableIntStateOf(finishedTick) }
    LaunchedEffect(finishedTick) {
        if (finishedTick != seenTick) {
            seenTick = finishedTick
            if (StationUploads.lastFinished == ref.key to uploadFolder) reload++
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty() && uploadFolder != null) {
            StationUploads.add(ref.key, ref.station, uploadFolder, ref.name, uris)
            Toast.makeText(context, if (uris.size == 1) "Sending to ${ref.name}…" else "Sending ${uris.size} files to ${ref.name}…", Toast.LENGTH_SHORT).show()
        }
    }

    fun playAudio(f: StationFile?, shuffle: Boolean) {
        val p = player ?: return
        if (audio.isEmpty()) return
        val wasRadio = p.isRadio && p.isPlaying
        p.playStationSongs(audio.map { it.toMediaItem(ref.key, ref.station) }, f?.let { audio.indexOf(it) } ?: 0, shuffle)
        if (wasRadio) Toast.makeText(context, "Radio stopped. Playing from ${ref.station}'s files.", Toast.LENGTH_SHORT).show()
    }

    fun open(f: StationFile) {
        when (f.kind) {
            "audio" -> playAudio(f, false)
            "video" -> context.startActivity(
                VideoPlayerActivity.intent(
                    context, stationUri(ref.key, f.url), f.name, ref.station,
                    fallback = f.hlsUrl?.takeIf { it.isNotBlank() }?.let { stationUri(ref.key, it) },
                ),
            )
            "image" -> photoAt = images.indexOf(f).coerceAtLeast(0)
            else -> {
                downloadStationFile(ref.key, ref.station, f)
                Toast.makeText(context, "Downloading ${f.name}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    BackHandler(enabled = selected.isNotEmpty() || searching) {
        if (selected.isNotEmpty()) selected = emptySet() else {
            searching = false
            query = ""
        }
    }

    Column(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        if (selected.isNotEmpty()) {
            val bytes = files.filter { it.url in selected }.sumOf { it.size }
            ToolTopBar("${selected.size} selected", onBack = { selected = emptySet() }, subtitle = Files.size(bytes))
        } else if (searching) {
            SearchBar(query, onQuery = { query = it }, onClose = {
                searching = false
                query = ""
            })
        } else {
            ToolTopBar(
                ref.name, onBack, subtitle = if (ref.isRoot) "Shared files" else ref.station,
                tag = if (ref.key in Stations.onSameWifi) ({ WifiTag() }) else null,
            ) {
                if (problem == null) BarIcon(Icons.Rounded.Search, "Search this folder") { searching = true }
                BarIcon(Icons.Rounded.MoreVert, "More") { showMenu = true }
            }
        }
        if (!ref.isRoot && ref.trail.isNotBlank() && selected.isEmpty()) {
            Text(
                ref.trail, fontSize = 13.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
            )
        }
        Box(Modifier.weight(1f)) {
            when {
                loading && listing == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                problem == Problem.Offline -> EmptyState(
                    Icons.Rounded.CloudOff, "${ref.station} is offline",
                    "Its files come back when the station does. Downloads waiting for it carry on by themselves.",
                    action = "Try again", actionIcon = Icons.Rounded.Refresh, onAction = { reload++ },
                )
                problem == Problem.Pin -> EmptyState(
                    Icons.Rounded.Lock, "${ref.station}'s files need a PIN", "Ask the owner for the 4-digit PIN.",
                    action = "Enter the PIN", actionIcon = Icons.Rounded.LockOpen, onAction = { showPin = true },
                )
                problem == Problem.NotShared -> EmptyState(Icons.Rounded.FolderOpen, "This folder isn't shared any more", null, action = "Back", onAction = onBack)
                problem is Problem.Error -> EmptyState(
                    Icons.Rounded.CloudOff, "Couldn't open this folder", (problem as Problem.Error).message,
                    action = "Try again", actionIcon = Icons.Rounded.Refresh, onAction = { reload++ },
                )
                folders.isEmpty() && files.isEmpty() && uploading.isEmpty() -> EmptyState(
                    Icons.Rounded.FolderOpen, if (query.isNotBlank()) "Nothing called \"$query\"" else "This folder is empty",
                    if (uploadFolder != null && query.isBlank()) "Tap + to add files." else null,
                )
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
                    if (station?.radioOnAir == true) {
                        item(key = "onair") {
                            NoteBox(Icons.Rounded.Radio, "${ref.station} is on air, so transfers go a little slower to keep the radio smooth.", Modifier.padding(bottom = 10.dp))
                        }
                    }
                    if (audio.isNotEmpty() && selected.isEmpty()) {
                        item(key = "play") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                                Button(onClick = { playAudio(null, false) }) { ButtonLabel(Icons.Rounded.PlayArrow, "Play all") }
                                FilledTonalButton(onClick = { playAudio(null, true) }) { ButtonLabel(Icons.Rounded.Shuffle, "Shuffle") }
                            }
                        }
                    }
                    if (files.isNotEmpty() && files.map { it.kind }.distinct().size > 1) {
                        item(key = "chips") {
                            Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for ((id, label) in listOf("all" to "All", "audio" to "Audio", "video" to "Videos", "image" to "Photos", "docs" to "Docs")) {
                                    if (id == "all" || files.any { kindMatches(it, id) }) FilterChip(selected = kind == id, onClick = { kind = id }, label = { Text(label) })
                                }
                            }
                        }
                    }
                    // Folders, then files still going up into this folder, then its files.
                    val rows = (if (kind == "all") folders.map { Either.F(it) } else emptyList()) + uploading.map { Either.U(it) } + shown.map { Either.L(it) }
                    itemsIndexed(rows, key = { _, r -> r.key }) { i, r ->
                        val slice = Modifier.cardSlice(first = i == 0, last = i == rows.lastIndex)
                        when (r) {
                            is Either.U -> UploadRow(r.upload, slice)
                            is Either.F -> FileRow(Icons.Rounded.Folder, ToolTint.AMBER, r.folder.name, "Folder", slice, onClick = {
                                if (selected.isEmpty()) onOpen(childRef(ref, r.folder))
                            })
                            is Either.L -> {
                                val f = r.file
                                val (icon, tint) = iconFor(f)
                                FileRow(
                                    icon, tint, f.name, listOfNotNull(Files.size(f.size), shortDate(f.modified)).joinToString(" · "), slice,
                                    checked = if (selected.isEmpty()) null else f.url in selected,
                                    onClick = { if (selected.isEmpty()) open(f) else selected = selected.toggle(f.url) },
                                    onLongClick = { selected = selected.toggle(f.url) },
                                    trailing = if (selected.isEmpty()) ({
                                        BarIcon(Icons.Rounded.Share, "Share ${f.name}") { sharingFiles = listOf(f) }
                                        BarIcon(Icons.Rounded.Download, "Download ${f.name}") {
                                            downloadStationFile(ref.key, ref.station, f)
                                            Toast.makeText(context, "Downloading ${f.name}", Toast.LENGTH_SHORT).show()
                                        }
                                    }) else null,
                                )
                            }
                        }
                    }
                    if (loadingMore) item(key = "more") { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
                }
            }
            if (uploadFolder != null && selected.isEmpty() && problem == null) {
                FloatingActionButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp)) {
                    Icon(Icons.Rounded.Add, "Add files here")
                }
            }
            if (selected.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(cs.surface).padding(16.dp),
                ) {
                    FilledTonalButton(
                        onClick = {
                            sharingFiles = files.filter { it.url in selected }
                            selected = emptySet()
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    ) { ButtonLabel(Icons.Rounded.Share, "Share ${selected.size}") }
                    Button(
                        onClick = {
                            files.filter { it.url in selected }.forEach { downloadStationFile(ref.key, ref.station, it) }
                            Toast.makeText(context, "Downloading ${selected.size} files, one at a time", Toast.LENGTH_SHORT).show()
                            selected = emptySet()
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    ) { ButtonLabel(Icons.Rounded.Download, "Download ${selected.size}") }
                }
            }
        }
        bottom()
    }

    // ---- sheets and dialogs
    if (!noticeSeen && problem == null && !loading) {
        AlertDialog(
            onDismissRequest = onBack,
            title = { Text("Files from ${ref.station}") },
            text = { Text("These come straight from this station's PC. SyncUp doesn't check them, so only open what you trust.") },
            confirmButton = {
                TextButton(onClick = {
                    Stations.markNoticeSeen(ref.key)
                    noticeSeen = true
                }) { Text("Got it") }
            },
            dismissButton = { TextButton(onClick = onBack) { Text("Cancel") } },
        )
    }
    if (showPin) {
        PinSheet(ref.key, ref.station, onDone = {
            showPin = false
            reload++
            StationUploads.pinEntered(ref.key)
            Downloads.tasks.filter { t -> t.state == DlState.FAILED && parseStationUri(t.url)?.first == ref.key }.forEach { Downloads.resume(it) }
        }, onDismiss = { showPin = false })
    }
    if (showMenu) {
        ModalBottomSheet(onDismissRequest = { showMenu = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Text(ref.name, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp))
            if (!ref.isRoot && problem == null) {
                MenuRow(Icons.Rounded.Download, if (walking) "Finding the files…" else "Download this folder", "One file at a time, with the folders inside, into Download / SyncUp") {
                    if (walking) return@MenuRow
                    walking = true
                    scope.launch {
                        val all = withContext(Dispatchers.IO) { runCatching { walk(ref.key, ref.listUrl) }.getOrNull() }
                        walking = false
                        showMenu = false
                        if (all == null) Toast.makeText(context, "Couldn't reach ${ref.station}.", Toast.LENGTH_SHORT).show() else folderPlan = all
                    }
                }
            }
            if (audio.isNotEmpty()) MenuRow(Icons.Rounded.PlayArrow, "Play all", "Songs in this folder") {
                showMenu = false
                playAudio(null, false)
            }
            if (uploadFolder != null) MenuRow(Icons.Rounded.UploadFile, "Add files here", "${ref.station} allows uploads") {
                showMenu = false
                picker.launch(arrayOf("*/*"))
            }
            if (Stations.hasRememberedPin(ref.key)) MenuRow(Icons.Rounded.LockOpen, "Forget the PIN", "Ask for it next time") {
                Stations.forgetPin(ref.key)
                showMenu = false
                Toast.makeText(context, "Forgot ${ref.station}'s PIN", Toast.LENGTH_SHORT).show()
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    folderPlan?.let { plan ->
        AlertDialog(
            onDismissRequest = { folderPlan = null },
            title = { Text("Download ${ref.name}?") },
            text = {
                Text(
                    if (plan.isEmpty()) "There are no files in it." else
                        "${plan.size} file${if (plan.size == 1) "" else "s"} · ${Files.size(plan.sumOf { it.size })}. They download one at a time into Download / SyncUp.",
                )
            },
            confirmButton = {
                TextButton(enabled = plan.isNotEmpty(), onClick = {
                    plan.forEach { downloadStationFile(ref.key, ref.station, it) }
                    folderPlan = null
                    Toast.makeText(context, "Downloading ${plan.size} files", Toast.LENGTH_SHORT).show()
                }) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = { folderPlan = null }) { Text("Cancel") } },
        )
    }
    photoAt?.let { at -> PhotoViewer(ref.key, ref.station, images, at, onClose = { photoAt = null }, onShare = { sharingFiles = listOf(it) }) }
    sharingFiles?.let { list -> ShareDialog(ref.key, ref.station, list, onDone = { sharingFiles = null }) }
}

// ============================================================================ sharing to other apps
/**
 * Share station files to any app: each is fetched into SyncUp's cache first (other apps can't reach the
 * station), then Android's share sheet opens. The cache is cleared of earlier shares each time.
 */
@Composable
private fun ShareDialog(key: String, station: String, files: List<StationFile>, onDone: () -> Unit) {
    val context = LocalContext.current
    val total = files.sumOf { it.size }.coerceAtLeast(1)
    var got by remember { mutableStateOf(0L) }
    var failed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(files) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val dir = java.io.File(context.cacheDir, "station_share").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                var before = 0L
                files.map { f ->
                    val out = java.io.File(dir, Files.cleanName(f.name))
                    Stations.call(key, f.downloadUrl.ifBlank { f.url + "?dl=1" }, { it.header(USE_HEADER, "download") }) { r ->
                        if (!r.isSuccessful) throw Stations.refused(r)
                        val body = r.body ?: throw java.io.IOException("Empty reply")
                        out.outputStream().use { o ->
                            body.byteStream().use { input ->
                                val buf = ByteArray(64 * 1024)
                                var done = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    o.write(buf, 0, n)
                                    done += n
                                    got = before + done
                                }
                            }
                        }
                    }
                    before += f.size
                    out to f
                }
            }
        }
        result.onSuccess { saved ->
            val uris = saved.map { (file, _) -> androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file) }
            val send = if (uris.size == 1) {
                android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType(Files.mimeFor(saved[0].second.name, saved[0].second.mime))
                    .putExtra(android.content.Intent.EXTRA_STREAM, uris[0])
            } else {
                android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE)
                    .setType("*/*")
                    .putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
            }
            send.clipData = android.content.ClipData.newUri(context.contentResolver, "Files", uris[0]).apply { uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) } }
            send.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { context.startActivity(android.content.Intent.createChooser(send, "Share")) }
            onDone()
        }.onFailure { e ->
            failed = when (e) {
                is Stations.PinNeeded -> "$station wants its PIN again."
                is Stations.Gone -> "Couldn't reach $station."
                is kotlinx.coroutines.CancellationException -> null
                else -> "Couldn't get the file (${e.message})."
            }
            if (failed == null) onDone()
        }
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (failed != null) "Couldn't share" else if (files.size == 1) "Getting ${files[0].name}" else "Getting ${files.size} files") },
        text = {
            Column {
                if (failed != null) {
                    Text(failed.orEmpty())
                } else {
                    LinearProgressIndicator(progress = { (got.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("${Files.size(got)} of ${Files.size(total)} · then pick an app", fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text(if (failed != null) "OK" else "Cancel") } },
    )
}

private sealed interface Either {
    val key: String

    data class F(val folder: StationFolder) : Either {
        override val key: String get() = "d:" + folder.listUrl
    }

    data class L(val file: StationFile) : Either {
        override val key: String get() = "f:" + file.url
    }

    data class U(val upload: StationUpload) : Either {
        override val key: String get() = "u:" + upload.id
    }
}

private fun Set<String>.toggle(x: String) = if (x in this) this - x else this + x

private fun childRef(parent: FolderRef, f: StationFolder): FolderRef = FolderRef(
    parent.key, parent.station, f.listUrl, f.name,
    if (parent.isRoot) f.name else (if (parent.trail.isBlank()) parent.name else parent.trail) + " › " + f.name,
)

private fun kindMatches(f: StationFile, kind: String) = when (kind) {
    "all" -> true
    "docs" -> f.kind in setOf("pdf", "text", "doc", "archive", "file")
    else -> f.kind == kind
}

private fun iconFor(f: StationFile): Pair<ImageVector, ToolTint> = when (f.kind) {
    "audio" -> Icons.Rounded.MusicNote to ToolTint.VIOLET
    "video" -> Icons.Rounded.Movie to ToolTint.BLUE
    "image" -> Icons.Rounded.Image to ToolTint.GREEN
    "pdf" -> Icons.Rounded.PictureAsPdf to ToolTint.RED
    "archive" -> Icons.Rounded.FolderZip to ToolTint.SLATE
    "doc", "text" -> Icons.Rounded.Description to ToolTint.SLATE
    else -> Icons.AutoMirrored.Rounded.InsertDriveFile to ToolTint.SLATE
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "1 Nov" from AudioSync's ISO date ("2026-11-01T10:00:00+00:00"). Plain text: java.time needs Android 8. */
internal fun shortDate(iso: String?): String? {
    if (iso == null || iso.length < 10) return null
    val month = iso.substring(5, 7).toIntOrNull()?.takeIf { it in 1..12 } ?: return null
    val day = iso.substring(8, 10).toIntOrNull() ?: return null
    return "$day ${MONTHS[month - 1]}"
}

/** A song from a station's files, for the Music player. */
internal fun StationFile.toMediaItem(key: String, station: String): MediaItem = MediaItem.Builder()
    .setMediaId(FILE_ID + key + url)
    .setUri(stationUri(key, url))
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(name.substringBeforeLast('.').ifBlank { name })
            .setArtist(station)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build(),
    )
    .build()

/** Into SyncUp's downloads: kept as the station's key + path, so it carries on after a new address. Main thread. */
internal fun downloadStationFile(key: String, station: String, f: StationFile) {
    val path = f.downloadUrl.ifBlank { f.url + "?dl=1" }
    val uri = stationUri(key, path)
    val name = Files.cleanName(f.name)
    val req = DownloadRequest(url = uri, mime = f.mime.ifBlank { null }, contentLength = f.size, source = station, suggestedName = name)
    val info = LinkInfo(url = uri, fileName = name, mime = Files.mimeFor(name, f.mime), size = f.size, resumable = true, etag = f.etag, lastModified = null)
    Downloads.start(req, name, info)
}

/** Every file in a folder and the folders inside it, page by page. Blocking. */
private fun walk(key: String, listUrl: String): List<StationFile> {
    val out = ArrayList<StationFile>()
    val todo = ArrayDeque(listOf(listUrl))
    while (todo.isNotEmpty() && out.size < 5000) {
        val url = todo.removeFirst()
        var offset = 0
        while (true) {
            val page = Stations.list(key, url, "", offset, 1000)
            out += page.files
            page.folders.forEach { todo.addLast(it.listUrl) }
            offset += page.folders.size + page.files.size
            if (page.folders.isEmpty() && page.files.isEmpty() || offset >= (page.total ?: 0)) break
        }
    }
    return out
}

// ============================================================================ bits
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    icon: ImageVector,
    tint: ToolTint,
    title: String,
    subtitle: String,
    slice: Modifier,
    checked: Boolean? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = slice.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = 62.dp).padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
    ) {
        if (checked != null) {
            Checkbox(checked = checked, onCheckedChange = { onClick() })
        } else {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tint.tile), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint.icon, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, fontSize = 12.5.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

@Composable
private fun ButtonLabel(icon: ImageVector, text: String) {
    Icon(icon, null, Modifier.size(18.dp))
    Spacer(Modifier.width(6.dp))
    Text(text)
}

@Composable
private fun MenuRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Icon(icon, null, tint = cs.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, fontSize = 15.sp, color = cs.onSurface)
            Text(subtitle, fontSize = 12.5.sp, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 4.dp)) {
        BarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Close search", onClick = onClose)
        Box(Modifier.weight(1f).padding(end = 12.dp).clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer).padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (query.isEmpty()) Text("Search this folder", color = cs.onSurfaceVariant, fontSize = 15.sp)
            BasicTextField(
                query, onQuery, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = cs.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(cs.primary),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
    }
}

/** A file on its way up into this folder, in the folder's own list: progress, cancel, or retry if it stopped. */
@Composable
private fun UploadRow(u: StationUpload, slice: Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = slice.fillMaxWidth().heightIn(min = 62.dp).padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(ToolTint.BLUE.tile), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.UploadFile, null, tint = ToolTint.BLUE.icon, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(u.name, fontSize = 15.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val done = StationUploads.sent[u.id] ?: u.received
            if (u.state == StationUpload.RUNNING && u.size > 0) {
                LinearProgressIndicator(progress = { (done.toFloat() / u.size).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp))
            }
            Text(
                when (u.state) {
                    StationUpload.RUNNING -> u.note ?: "Uploading · ${Files.size(done)} of ${Files.size(u.size)}"
                    StationUpload.QUEUED -> "Waiting to upload · one at a time"
                    else -> u.error ?: "Didn't go through"
                },
                fontSize = 12.5.sp, color = if (u.state == StationUpload.FAILED) cs.error else cs.onSurfaceVariant, maxLines = 2,
            )
        }
        if (u.state == StationUpload.FAILED) BarIcon(Icons.Rounded.Refresh, "Try ${u.name} again") { StationUploads.retry(u.id) }
        BarIcon(Icons.Rounded.Close, "Cancel ${u.name}") { StationUploads.cancel(u.id) }
    }
}

// ============================================================================ PIN
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinSheet(key: String, station: String, onDone: () -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var waitUntil by remember { mutableStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val now by produceState(System.currentTimeMillis(), waitUntil) {
        while (System.currentTimeMillis() < waitUntil) {
            value = System.currentTimeMillis()
            delay(1000)
        }
        value = System.currentTimeMillis()
    }
    val waiting = now < waitUntil

    fun submit(p: String) {
        if (busy || waiting) return
        busy = true
        message = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { Stations.login(key, p, remember) }
            busy = false
            when (r) {
                Stations.Login.Ok -> onDone()
                is Stations.Login.Wrong -> {
                    pin = ""
                    message = "Wrong PIN." + (r.attemptsLeft?.let { " $it ${if (it == 1) "try" else "tries"} left." } ?: "")
                }
                is Stations.Login.Wait -> {
                    pin = ""
                    waitUntil = System.currentTimeMillis() + r.seconds * 1000L
                }
                is Stations.Login.Failed -> message = r.message
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 28.dp)) {
            Text(if (waiting) "Too many tries" else "Enter the PIN", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
            Text(
                if (waiting) "$station has paused PIN tries. Try again in ${formatWait((waitUntil - now) / 1000)}." else "$station protects its files with a 4-digit PIN.",
                fontSize = 13.5.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            if (!waiting) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (i in 0 until 4) {
                            val on = i == pin.length && !busy
                            Box(
                                Modifier.size(width = 54.dp, height = 62.dp).clip(RoundedCornerShape(14.dp))
                                    .background(if (on) cs.surface else cs.surfaceContainerHigh)
                                    .then(if (on) Modifier.border(2.dp, cs.primary, RoundedCornerShape(14.dp)) else Modifier)
                                    .then(if (message != null && pin.isEmpty()) Modifier.background(cs.errorContainer.copy(alpha = .4f)) else Modifier),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(if (i < pin.length) "•" else "", fontSize = 26.sp, color = cs.onSurface)
                            }
                        }
                    }
                    // The real input: hidden over the boxes, with the number pad.
                    BasicTextField(
                        pin,
                        onValueChange = { v ->
                            val digits = v.filter { it.isDigit() }.take(4)
                            pin = digits
                            if (digits.length == 4) submit(digits)
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.Transparent),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Transparent),
                        modifier = Modifier.matchParentSizeSafe().focusRequester(focus),
                    )
                }
                Text(message.orEmpty(), color = cs.error, fontSize = 13.5.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { remember = !remember }.padding(top = 4.dp)) {
                    Checkbox(checked = remember, onCheckedChange = { remember = it })
                    Text("Remember on this phone", fontSize = 14.sp, color = cs.onSurface)
                }
                Text(
                    "Kept encrypted on this phone, for this station only. Downloads and uploads then carry on by themselves after $station restarts.",
                    fontSize = 12.5.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

private fun Modifier.matchParentSizeSafe(): Modifier = this.size(width = 252.dp, height = 62.dp)

private fun formatWait(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

// ============================================================================ photos
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoViewer(key: String, station: String, images: List<StationFile>, start: Int, onClose: () -> Unit, onShare: (StationFile) -> Unit) {
    if (images.isEmpty()) return
    val context = LocalContext.current
    val pager = rememberPagerState(initialPage = start.coerceIn(0, images.lastIndex)) { images.size }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, modifier = Modifier.fillMaxSize()) { page ->
                val f = images[page]
                // The station's smaller copy (about 1600 px): quick through the tunnel. The full photo is for downloading.
                val request by produceState<ImageRequest?>(null, f.url) {
                    value = withContext(Dispatchers.IO) {
                        runCatching {
                            val r = Stations.resolve(stationUri(key, f.previewUrl ?: f.url))
                            ImageRequest.Builder(context).data(r.url).apply { r.headers.forEach { (k, v) -> addHeader(k, v) } }.crossfade(true).build()
                        }.getOrNull()
                    }
                }
                var scale by remember { mutableStateOf(1f) }
                var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                val zoom = rememberTransformableState { z, pan, _ ->
                    scale = (scale * z).coerceIn(1f, 5f)
                    offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (request == null) {
                        CircularProgressIndicator(color = Color.White)
                    } else {
                        AsyncImage(
                            model = request, contentDescription = f.name, contentScale = ContentScale.Fit,
                            // Dragging moves a zoomed photo; otherwise it's the pager's, to swipe to the next one.
                            modifier = Modifier.fillMaxSize().transformable(zoom, canPan = { scale > 1f }).graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            },
                        )
                    }
                }
            }
            val f = images[pager.currentPage]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .45f)).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                BarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Close", tint = Color.White, onClick = onClose)
                Column(Modifier.weight(1f)) {
                    Text(f.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${pager.currentPage + 1} of ${images.size}", color = Color.White.copy(alpha = .7f), fontSize = 12.5.sp)
                }
                BarIcon(Icons.Rounded.Share, "Share ${f.name}", tint = Color.White) { onShare(f) }
                BarIcon(Icons.Rounded.Download, "Download ${f.name}", tint = Color.White) {
                    downloadStationFile(key, station, f)
                    Toast.makeText(context, "Downloading ${f.name}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
