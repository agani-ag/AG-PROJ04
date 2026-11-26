package com.agani.syncup.cloud

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DriveFolderUpload
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.browser.ui.EmptyState
import com.agani.syncup.downloads.DownloadRequest
import com.agani.syncup.downloads.Downloads
import com.agani.syncup.music.ToolTint
import com.agani.syncup.music.ToolTopBar
import com.agani.syncup.music.cardSlice
import com.agani.syncup.stationfiles.FolderRef
import com.agani.syncup.stationfiles.StationFile
import com.agani.syncup.stationfiles.Stations
import com.agani.syncup.ui.theme.AlertDialog
import com.agani.syncup.ui.theme.DropdownMenu
import com.agani.syncup.ui.theme.ModalBottomSheet
import com.agani.syncup.ui.theme.swipeTabs
import com.agani.syncup.ui.theme.PillTabs
import com.agani.syncup.video.VideoPlayerActivity
import kotlinx.coroutines.launch

/**
 * Tools → Cloud (2026-11-26): folders and files in Cloudinary, called "Cloud" in the app. "Own folder" accounts see their own folder
 * only (the server signs each action); "Everything" accounts see all of Cloudinary (full API on the phone).
 * Photos open in a viewer, videos and sound in the player, other files download. Picking files uses Android's
 * own pickers — SyncUp needs no storage permission.
 */
@Composable
fun CloudinaryScreen(onBack: () -> Unit, myFiles: Boolean, sharedFiles: Boolean) {
    // My files (Cloudinary, when the admin switched it on) and Shared (stations' folders, while Live stations are
    // on — they were Radio's Files tab). Both: two tabs, swipe between them. One: just that one.
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(if (myFiles) 0 else 1) }
    val both = myFiles && sharedFiles
    val tabs: (@Composable () -> Unit)? = if (both) {
        { PillTabs(listOf("My files", "Shared"), tab, { tab = it }, Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 4.dp)) }
    } else null
    val swipe = if (both) Modifier.swipeTabs(2, tab) { tab = it } else Modifier
    // Shared → Cloud: the station files picked, waiting for the person to choose a cloud folder.
    var toCloud by remember { mutableStateOf<Pair<FolderRef, List<Pair<String, StationFile>>>?>(null) }
    // Copy to Shared only while some station takes files (user, 26 Nov 2026: "if it's not available, don't
    // show it"): looked at again every 30 s while Cloud is open.
    if (sharedFiles) {
        val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
        LaunchedEffect(owner) {
            owner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                while (true) {
                    runCatching { Stations.checkTakesFiles() }
                    kotlinx.coroutines.delay(30_000)
                }
            }
        }
    }
    val canShare = sharedFiles && Stations.takesFiles.isNotEmpty()
    if (!myFiles || tab == 1) {
        BackHandler { onBack() }
        if (!sharedFiles) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
                Column {
                    ToolTopBar("Cloud", onBack)
                    EmptyState(Icons.Rounded.CloudOff, "Nothing here", "Cloud isn't switched on for your account.")
                }
            }
            return
        }
        com.agani.syncup.music.StationFilesPane(
            header = {
                ToolTopBar("Cloud", onBack, subtitle = "Shared by stations") { TransfersBell() }
                tabs?.invoke()
            },
            contentModifier = swipe,
            folderTop = { TransfersBell() },
            toCloud = if (myFiles) { ref, files -> toCloud = ref to files } else null,
        )
    } else {
        MyFilesScreen(onBack, tabs, swipe, canShare = canShare)
    }
    toCloud?.let { (ref, files) ->
        val many = files.size != 1
        CloudFolderPicker(
            title = if (many) "Copy ${files.size} files to Cloud" else "Copy “${files[0].second.name}” to Cloud",
            confirm = "Copy here",
            onDismiss = { toCloud = null },
        ) { source, path, label ->
            CloudTransfers.toCloud(source, files, ref.key, ref.station, path, label)
            toCloud = null
            Toast.makeText(context, if (many) "Copying ${files.size} files to $label" else "Copying to $label", Toast.LENGTH_SHORT).show()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun MyFilesScreen(onBack: () -> Unit, tabs: (@Composable () -> Unit)?, swipe: Modifier, canShare: Boolean) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var source by remember { mutableStateOf<CloudSource?>(null) }
    var access by remember { mutableStateOf<CloudAccess?>(null) }
    var openError by remember { mutableStateOf<String?>(null) }
    var opening by remember { mutableIntStateOf(0) }
    var path by rememberSaveable { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf<CloudPage?>(null) }
    var loading by remember { mutableStateOf(false) }
    var listError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var sort by rememberSaveable { mutableStateOf("new") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sheetFile by remember { mutableStateOf<CloudItem?>(null) }
    var viewer by remember { mutableStateOf<Pair<List<CloudItem>, Int>?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<CloudItem?>(null) }
    var deleting by remember { mutableStateOf<CloudItem?>(null) }
    var folderMenu by remember { mutableStateOf<CloudFolderItem?>(null) }
    var deletingFolder by remember { mutableStateOf<CloudFolderItem?>(null) }
    var pending by remember { mutableStateOf<CloudPending?>(null) }
    var working by remember { mutableStateOf<String?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var showStorage by remember { mutableStateOf(false) }
    // Picking several files (from a file's "Select", then taps): their keys.
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Cloud → Shared: the files, waiting for the person to choose a station folder.
    var toShared by remember { mutableStateOf<ShareRequest?>(null) }
    var deletingMany by remember { mutableStateOf<List<CloudItem>?>(null) }
    // A reload nobody asked for (a push, or the 15 s check): no progress bar.
    var quiet by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    // Live (user, 26 Nov 2026: "new files should show at once, and a changed access too"): a silent push reloads or
    // reopens at once; while the page is on screen it also checks the server's change mark every 15 s.
    LaunchedEffect(CloudEvents.access) { if (CloudEvents.access > 0) opening++ }
    LaunchedEffect(CloudEvents.files) { if (CloudEvents.files > 0) { quiet = true; reload++ } }
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            var mark: String? = null
            while (true) {
                kotlinx.coroutines.delay(15_000)
                val a = runCatching { server(context) { com.agani.syncup.data.ApiClient.service.cloudAccess(it) } }.getOrNull() ?: continue
                val was = access
                when {
                    was != null && a.access != was.access -> opening++  // Own folder ↔ Everything ↔ off
                    mark != null && a.changes != mark -> { quiet = true; reload++ }
                }
                mark = a.changes
            }
        }
    }

    // Open: what this account gets, then the right source (the server's list, or Cloudinary's full API).
    LaunchedEffect(opening) {
        openError = null
        runCatching { CloudSession.open(context) }
            .onSuccess { (a, s) ->
                access = a
                source = s
                if (path == null || !(path!!.startsWith(s.root))) path = s.root
                if (s is AdminSource) pending = runCatching { s.pending() }.getOrNull()
            }
            .onFailure { openError = it.message ?: "Couldn't open Cloud." }
    }

    val src = source
    val here = path
    LaunchedEffect(here) { selected = emptySet() }
    LaunchedEffect(src, here, reload, CloudUploads.finished, CloudTransfers.finished) {
        if (src == null || here == null) return@LaunchedEffect
        val silent = quiet && page != null
        quiet = false
        if (!silent) loading = true
        listError = null
        runCatching { src.list(here) }
            .onSuccess { page = it }
            .onFailure { if (!silent) listError = it.message ?: "Couldn't open this folder." }
        loading = false
    }

    fun goUp(): Boolean {
        val s = src ?: return false
        val p = here ?: return false
        if (p == s.root || p.isEmpty()) return false
        path = p.substringBeforeLast('/', "").let { if (it.length < s.root.length) s.root else it }
        page = null
        return true
    }
    fun leave() {
        if (!goUp()) onBack()
    }
    BackHandler(enabled = viewer == null) { leave() }
    BackHandler(enabled = viewer == null && selected.isNotEmpty()) { selected = emptySet() }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        val s = src
        val p = here
        if (uris.isNotEmpty() && s != null && p != null) CloudUploads.add(s, p, uris.map { UploadSource.of(context, it) })
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val s = src
        val p = here
        if (uris.isNotEmpty() && s != null && p != null) CloudUploads.add(s, p, uris.map { UploadSource.of(context, it) })
    }

    // A whole folder from this phone, with the folders inside (Android's folder picker: no storage permission).
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val s = src
        val p = here
        if (tree == null || s == null || p == null) return@rememberLauncherForActivityResult
        scope.launch {
            working = "Reading the folder…"
            val read = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { PhoneFolder.walk(context, tree) } }
            working = null
            read.onSuccess { (name, files) ->
                if (files.isEmpty()) {
                    toast("There are no files in “$name”.")
                    return@onSuccess
                }
                files.groupBy { it.rel }.forEach { (rel, group) ->
                    val sub = cloudFolderPath(if (rel.isEmpty()) name else "$name/$rel")
                    val target = listOf(p, sub).filter { it.isNotEmpty() }.joinToString("/")
                    CloudUploads.add(s, target, group.map { UploadSource(context.applicationContext, it.uri, it.name, it.mime, it.size) })
                }
                toast("Uploading ${files.size} file${if (files.size == 1) "" else "s"} from “$name”")
            }.onFailure { toast("Couldn't read that folder. Please pick it again.") }
        }
    }

    // A cloud folder to Shared: every file in it and its folders, then where it goes.
    fun folderToShared(f: CloudFolderItem) {
        val s = src ?: return
        scope.launch {
            working = "Finding the files in “${f.name}”…"
            val found = runCatching { walkCloud(s, f.path, f.name) }
            working = null
            found.onSuccess { (entries, _) ->
                if (entries.isEmpty()) toast("There are no files in “${f.name}”.")
                else toShared = ShareRequest(entries, "“${f.name}” (${entries.size} file${if (entries.size == 1) "" else "s"})")
            }.onFailure { toast(it.message ?: "Couldn't open that folder.") }
        }
    }

    val shown = remember(page, sort, query) {
        val pg = page
        if (pg == null) null else {
            val q = query.trim().lowercase()
            val files = pg.files.filter { q.isEmpty() || it.name.lowercase().contains(q) }.let { fs ->
                when (sort) {
                    "name" -> fs.sortedBy { it.name.lowercase() }
                    "size" -> fs.sortedByDescending { it.bytes }
                    else -> fs.sortedByDescending { it.createdMs }
                }
            }
            pg.copy(folders = pg.folders.filter { q.isEmpty() || it.label.lowercase().contains(q) }, files = files)
        }
    }

    val rootLabel = if (src?.everything == true) "Cloud" else "My folder"
    val title = when {
        here == null || src == null -> "Cloud"
        here == src.root -> rootLabel
        else -> here.substringAfterLast('/')
    }

    Box(Modifier.fillMaxSize().background(cs.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val picked = page?.files?.filter { it.key in selected }.orEmpty()
        Column(Modifier.fillMaxSize()) {
            if (selected.isNotEmpty()) ToolTopBar(
                "${selected.size} selected", onBack = { selected = emptySet() }, subtitle = fmtSize(picked.sumOf { it.bytes }),
            ) {
                BarIcon(Icons.Rounded.SelectAll, "Select all") { selected = shown?.files?.map { it.key }.orEmpty().toSet() }
                BarIcon(Icons.Rounded.Download, "Download") {
                    picked.forEach { download(context, it) }
                    selected = emptySet()
                }
                BarIcon(Icons.Rounded.Delete, "Delete") { deletingMany = picked }
            } else ToolTopBar(
                title, onBack = { leave() },
                subtitle = when (access?.access) { "all" -> "Everything"; "own" -> "Your own folder"; else -> null },
            ) {
                TransfersBell()
                if (src != null) {
                    BarIcon(Icons.Rounded.Search, "Search this folder") { searching = !searching; if (!searching) query = "" }
                    Box {
                        BarIcon(Icons.AutoMirrored.Rounded.Sort, "Sort") { sortMenu = true }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            listOf("new" to "Newest first", "name" to "Name", "size" to "Biggest first").forEach { (k, label) ->
                                DropdownMenuItem(
                                    text = { Text(label, fontWeight = if (sort == k) FontWeight.SemiBold else FontWeight.Normal) },
                                    onClick = { sort = k; sortMenu = false },
                                )
                            }
                        }
                    }
                    // Storage details in a pop-up (user, 26 Nov 2026: not always on the page).
                    BarIcon(Icons.Rounded.DataUsage, "Storage") { showStorage = true }
                    BarIcon(Icons.Rounded.Refresh, "Refresh") { reload++ }
                }
            }
            tabs?.invoke()
            if (src != null && here != null) {
                Breadcrumbs(rootLabel, src.root, here) { path = it; page = null }
            }
            if (searching) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Search this folder") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            Box(Modifier.fillMaxSize().then(swipe)) { when {
                openError != null -> EmptyState(
                    Icons.Rounded.CloudOff, "Can't open Cloud", openError,
                    action = "Try again", onAction = { opening++ },
                )
                listError != null && page == null -> EmptyState(
                    Icons.Rounded.ErrorOutline, "Can't open this folder", listError,
                    action = "Try again", onAction = { reload++ },
                )
                shown == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 110.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    val full: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
                    // Space used (Own folder).
                    // Everything: Cloudinary's folder mode differs from the server's; files of deleted accounts.
                    val admin = src as? AdminSource
                    if (admin != null && admin.modeMismatch && here == src.root) item(span = full) {
                        Note(Icons.Rounded.ErrorOutline, "Cloudinary uses ${admin.folderMode} folders, but the server expects " +
                            "${access?.folderMode}. Own-folder uploads may not land in the right folder — tell the developer.")
                    }
                    val waiting = pending
                    if (admin != null && waiting != null && waiting.count > 0 && here == src.root) item(span = full) {
                        Note(Icons.Rounded.Delete, "${waiting.count} file${if (waiting.count == 1) "" else "s"} of deleted accounts are still in the cloud.",
                            action = "Delete now") {
                            scope.launch {
                                working = "Deleting files of deleted accounts…"
                                runCatching { admin.clearPending(waiting.files) }
                                    .onSuccess { n -> toast("Deleted $n file${if (n == 1) "" else "s"}.") }
                                    .onFailure { toast(it.message ?: "Couldn't delete them.") }
                                pending = runCatching { admin.pending() }.getOrNull()
                                working = null
                            }
                        }
                    }
                    if (loading) item(span = full) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 2.dp)) }

                    itemsIndexed(shown.folders, key = { _, f -> "d:" + f.path }, span = { _, _ -> GridItemSpan(maxLineSpan) }) { i, f ->
                        FolderRow(
                            f, first = i == 0, last = i == shown.folders.lastIndex,
                            onOpen = { path = f.path; page = null; query = "" },
                            onMenu = { folderMenu = f },
                            menuOpen = folderMenu == f,
                            onDismissMenu = { folderMenu = null },
                            onDelete = { folderMenu = null; deletingFolder = f },
                            onCopy = if (canShare) ({ folderMenu = null; folderToShared(f) }) else null,
                        )
                    }
                    if (shown.folders.isEmpty() && shown.files.isEmpty() && !loading) item(span = full) {
                        Box(Modifier.fillMaxWidth().heightIn(min = 320.dp)) {
                            EmptyState(
                                Icons.Rounded.Cloud, if (query.isNotBlank()) "Nothing matches" else "Nothing here yet",
                                if (query.isNotBlank()) null else "Tap + to upload photos, videos or files.",
                            )
                        }
                    }
                    itemsIndexed(shown.files, key = { _, f -> "f:" + f.resourceType + f.publicId }) { _, f ->
                        val picking = selected.isNotEmpty()
                        FileTile(
                            f,
                            checked = if (picking) f.key in selected else null,
                            onOpen = {
                                if (picking) {
                                    selected = if (f.key in selected) selected - f.key else selected + f.key
                                    return@FileTile
                                }
                                when {
                                    f.isImage -> {
                                        val images = shown.files.filter { it.isImage }
                                        viewer = images to images.indexOf(f).coerceAtLeast(0)
                                    }
                                    f.isVideo || f.isAudio -> context.startActivity(VideoPlayerActivity.intent(context, f.url, f.name, "Cloud"))
                                    else -> sheetFile = f
                                }
                            },
                            onMenu = {
                                if (picking) selected = if (f.key in selected) selected - f.key else selected + f.key
                                else sheetFile = f
                            },
                        )
                    }
                }
            } }
        }

        // Only once the folder is open: nothing can be added where it couldn't open.
        if (selected.isNotEmpty() && canShare) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(cs.surface).navigationBarsPadding().padding(16.dp),
            ) {
                androidx.compose.material3.Button(
                    onClick = { toShared = ShareRequest.files(picked) },
                    enabled = picked.isNotEmpty(),
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                ) { ButtonText(Icons.Rounded.ContentCopy, "Copy ${picked.size} to Shared") }
            }
        } else if (selected.isEmpty() && src != null && here != null && page != null) {
            FloatingActionButton(
                onClick = { showAdd = true },
                containerColor = cs.primary, contentColor = cs.onPrimary,
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp),
            ) { Icon(Icons.Rounded.Add, "Upload or new folder") }
        }
        working?.let { msg ->
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainerHigh).padding(20.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(msg, color = cs.onSurface)
                }
            }
        }
    }

    // ---- the + : upload, or a new folder
    if (showAdd) {
        ModalBottomSheet(onDismissRequest = { showAdd = false }, containerColor = cs.surfaceContainerLowest) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text("Add to “$title”", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = cs.onSurface,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp))
                SheetRow(Icons.Rounded.PhotoLibrary, "Photos & videos", "From your gallery") {
                    showAdd = false
                    pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                }
                SheetRow(Icons.Rounded.UploadFile, "Files", "Documents, music, anything") {
                    showAdd = false
                    pickFiles.launch(arrayOf("*/*"))
                }
                SheetRow(Icons.Rounded.DriveFolderUpload, "A folder", "A whole folder from this phone, with the folders inside") {
                    showAdd = false
                    pickFolder.launch(null)
                }
                SheetRow(Icons.Rounded.CreateNewFolder, "New folder", null) {
                    showAdd = false
                    newFolder = true
                }
            }
        }
    }

    if (newFolder) {
        NameDialog("New folder", "Folder name", "", "Create", onDismiss = { newFolder = false }) { name ->
            val s = src ?: return@NameDialog
            val p = here ?: return@NameDialog
            newFolder = false
            scope.launch {
                runCatching { s.createFolder(if (p.isEmpty()) name else "$p/$name") }
                    .onSuccess { reload++ }
                    .onFailure { toast(it.message ?: "Couldn't make the folder.") }
            }
        }
    }

    // ---- a file's actions
    sheetFile?.let { f ->
        ModalBottomSheet(onDismissRequest = { sheetFile = null }, containerColor = cs.surfaceContainerLowest) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 10.dp)) {
                    Thumb(f, Modifier.size(56.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(fmtSize(f.bytes).takeIf { f.bytes > 0 }, f.createdMs.takeIf { it > 0 }?.let { DateFormat.getMediumDateFormat(context).format(it) })
                                .joinToString(" · "),
                            fontSize = 12.5.sp, color = cs.onSurfaceVariant,
                        )
                    }
                }
                when {
                    f.isImage -> SheetRow(Icons.Rounded.PhotoLibrary, "View", null) {
                        sheetFile = null
                        val images = shown?.files?.filter { it.isImage }.orEmpty().ifEmpty { listOf(f) }
                        viewer = images to images.indexOf(f).coerceAtLeast(0)
                    }
                    f.isVideo || f.isAudio -> SheetRow(Icons.Rounded.PlayArrow, "Play", null) {
                        sheetFile = null
                        context.startActivity(VideoPlayerActivity.intent(context, f.url, f.name, "Cloud"))
                    }
                    else -> SheetRow(Icons.Rounded.OpenInNew, "Open", "In another app") {
                        sheetFile = null
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(f.url))) }
                            .onFailure { toast("No app here can open it — download it instead.") }
                    }
                }
                SheetRow(Icons.Rounded.Download, "Download", "To SyncUp Downloads") {
                    sheetFile = null
                    download(context, f)
                }
                if (canShare) {
                    SheetRow(Icons.Rounded.ContentCopy, "Copy to Shared", "Into a station's shared folder") {
                        sheetFile = null
                        toShared = ShareRequest.files(listOf(f))
                    }
                }
                SheetRow(Icons.Rounded.CheckCircle, "Select", "Pick more files to copy, download or delete together") {
                    sheetFile = null
                    selected = setOf(f.key)
                }
                SheetRow(Icons.Rounded.Share, "Share link", null) {
                    sheetFile = null
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, f.url), f.name))
                }
                SheetRow(Icons.Rounded.ContentCopy, "Copy link", null) {
                    sheetFile = null
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(f.name, f.url))
                    toast("Link copied.")
                }
                SheetRow(Icons.Rounded.Edit, "Rename", null) {
                    sheetFile = null
                    renaming = f
                }
                SheetRow(Icons.Rounded.Delete, "Delete", null, danger = true) {
                    sheetFile = null
                    deleting = f
                }
            }
        }
    }

    renaming?.let { f ->
        val shownName = if (f.isImage || f.isVideo || f.isAudio) f.name.substringBeforeLast('.') else f.name
        NameDialog("Rename", "Name", shownName, "Rename", onDismiss = { renaming = null }) { typed ->
            val s = src ?: return@NameDialog
            renaming = null
            // The box hides a photo's / video's extension: keep it unless they typed one.
            val name = if ((f.isImage || f.isVideo || f.isAudio) && !typed.contains('.') && f.ext.isNotBlank()) "$typed.${f.ext}" else typed
            scope.launch {
                working = "Renaming…"
                runCatching { s.rename(f, name) }
                    .onSuccess { reload++ }
                    .onFailure { toast(it.message ?: "Couldn't rename it.") }
                working = null
            }
        }
    }

    deleting?.let { f ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${f.name}”?") },
            text = { Text("It's deleted from the cloud straight away. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    val s = src
                    deleting = null
                    if (s != null) scope.launch {
                        working = "Deleting…"
                        runCatching { s.delete(f) }
                            .onSuccess { reload++ }
                            .onFailure { toast(it.message ?: "Couldn't delete it.") }
                        working = null
                    }
                }) { Text("Delete", color = cs.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    deletingFolder?.let { f ->
        AlertDialog(
            onDismissRequest = { deletingFolder = null },
            title = { Text("Delete the folder “${f.name}”?") },
            text = { Text("Only an empty folder can be deleted — delete the files in it first.") },
            confirmButton = {
                TextButton(onClick = {
                    val s = src
                    deletingFolder = null
                    if (s != null) scope.launch {
                        runCatching { s.deleteFolder(f.path) }
                            .onSuccess { reload++ }
                            .onFailure { toast(it.message ?: "Couldn't delete the folder.") }
                    }
                }) { Text("Delete", color = cs.error) }
            },
            dismissButton = { TextButton(onClick = { deletingFolder = null }) { Text("Cancel") } },
        )
    }

    if (showStorage) StorageSheet(source = src, onDismiss = { showStorage = false })

    toShared?.let { req ->
        SharedFolderPicker(
            title = "Copy ${req.what} to Shared",
            confirm = "Copy here",
            onDismiss = { toShared = null },
        ) { ref, uploadFolder ->
            val s = src
            toShared = null
            selected = emptySet()
            if (s != null) {
                CloudTransfers.toShared(s, req.entries, ref.key, ref.station, uploadFolder, ref.name, move = false)
                toast("Copying ${req.what} to ${ref.station} › ${ref.name}")
            }
        }
    }

    deletingMany?.let { items ->
        AlertDialog(
            onDismissRequest = { deletingMany = null },
            title = { Text("Delete ${items.size} file${if (items.size == 1) "" else "s"}?") },
            text = { Text("They're deleted from the cloud straight away. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    val s = src
                    deletingMany = null
                    selected = emptySet()
                    if (s != null) scope.launch {
                        var failed = 0
                        items.forEachIndexed { i, f ->
                            working = "Deleting ${i + 1} of ${items.size}…"
                            runCatching { s.delete(f) }.onFailure { failed++ }
                        }
                        working = null
                        reload++
                        if (failed > 0) toast("Couldn't delete $failed of them. Please try again.")
                    }
                }) { Text("Delete", color = cs.error) }
            },
            dismissButton = { TextButton(onClick = { deletingMany = null }) { Text("Cancel") } },
        )
    }

    viewer?.let { (images, start) ->
        PhotoViewer(images, start, onClose = { viewer = null }, onMore = { sheetFile = it; viewer = null })
    }
}

// =============================================================================================== parts
@Composable
private fun Breadcrumbs(rootLabel: String, root: String, path: String, onGo: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val parts = path.removePrefix(root).trim('/').split('/').filter { it.isNotEmpty() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Crumb(rootLabel, current = parts.isEmpty()) { onGo(root) }
        parts.forEachIndexed { i, name ->
            Icon(Icons.Rounded.ChevronRight, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
            val target = (if (root.isEmpty()) "" else "$root/") + parts.take(i + 1).joinToString("/")
            Crumb(name, current = i == parts.lastIndex) { onGo(target) }
        }
    }
}

@Composable
private fun Crumb(label: String, current: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(
        label, fontSize = 13.sp, maxLines = 1,
        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
        color = if (current) cs.onSurface else cs.primary,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !current, onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp),
    )
}

private data class KindLook(val label: String, val icon: ImageVector, val tint: ToolTint)

private fun kindLook(kind: String?): KindLook = when (kind) {
    "photos" -> KindLook("Photos", Icons.Rounded.PhotoLibrary, ToolTint.BLUE)
    "videos" -> KindLook("Videos", Icons.Rounded.Movie, ToolTint.ROSE)
    "audio" -> KindLook("Audio", Icons.Rounded.MusicNote, ToolTint.VIOLET)
    "documents" -> KindLook("Documents", Icons.Rounded.Description, ToolTint.AMBER)
    "archives" -> KindLook("Archives", Icons.Rounded.FolderZip, ToolTint.SLATE)
    else -> KindLook("Other files", Icons.AutoMirrored.Rounded.InsertDriveFile, ToolTint.CYAN)
}

/**
 * Storage, from the bar's Storage button: what the person's own files take — by kind and by file type — and
 * the Cloudinary plan everyone shares (used, its size, free). No limit per person.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StorageSheet(source: CloudSource?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var stats by remember { mutableStateOf<CloudStats?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        // Everything: the whole of Cloudinary, counted through its API on the phone. Own folder: the server's count.
        runCatching {
            if (source is AdminSource) source.wholeStats()
            else server(context) { com.agani.syncup.data.ApiClient.service.cloudStats(it) }
        }
            .onSuccess { stats = it }
            .onFailure { error = it.message ?: "Couldn't load the details. Please try again." }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = cs.surfaceContainerLowest) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 28.dp)) {
            Text("Storage", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
            val st = stats
            when {
                error != null -> Text(error.orEmpty(), fontSize = 14.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
                st == null -> Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> StorageDetails(st)
            }
        }
    }
}

@Composable
private fun StorageDetails(st: CloudStats) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current

    // ---- your files
    SectionTitle(if (st.wholeCloud) "All of Cloudinary" else "Your files")
    Row(verticalAlignment = Alignment.Bottom) {
        Text(fmtSize(st.usedBytes), fontSize = 26.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
        Text("  ·  ${st.files} file${if (st.files == 1) "" else "s"}", fontSize = 14.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
    }
    if (st.usedBytes > 0) {
        // One bar, a colour per kind.
        Row(Modifier.fillMaxWidth().padding(top = 10.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(cs.surfaceContainerHigh)) {
            st.kinds.filter { it.bytes > 0 }.forEach { k ->
                Box(Modifier.weight(k.bytes.toFloat()).fillMaxHeight().background(kindLook(k.kind).tint.icon))
            }
        }
    }
    if (st.kinds.isEmpty()) {
        Text("Nothing uploaded yet.", fontSize = 13.5.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
    st.kinds.forEach { k ->
        val look = kindLook(k.kind)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(look.tint.tile), contentAlignment = Alignment.Center) {
                Icon(look.icon, null, tint = look.tint.icon, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(look.label, fontSize = 14.5.sp, color = cs.onSurface)
                Text("${k.count} file${if (k.count == 1) "" else "s"}", fontSize = 12.5.sp, color = cs.onSurfaceVariant)
            }
            Text(fmtSize(k.bytes), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
        }
    }

    if (st.wholeCloud) {
        st.bandwidthBytes?.takeIf { it > 0 }?.let { bw ->
            Text("Downloads this month: ${fmtSize(bw)}", fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
        }
        if (st.partial) Text("Counted the first 10,000 files of each kind.", fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }

    // ---- by file type
    if (st.formats.isNotEmpty()) {
        SectionTitle("By file type")
        val biggest = st.formats.maxOf { it.bytes }.coerceAtLeast(1)
        st.formats.forEach { f ->
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Row {
                    Text(f.format.orEmpty().uppercase().ifBlank { "?" }, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.width(64.dp))
                    Text("${f.count} file${if (f.count == 1) "" else "s"}", fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(fmtSize(f.bytes), fontSize = 13.sp, color = cs.onSurface)
                }
                Box(Modifier.padding(top = 4.dp).fillMaxWidth(f.bytes.toFloat() / biggest).height(4.dp).clip(RoundedCornerShape(2.dp)).background(cs.primary.copy(alpha = .55f)))
            }
        }
    }

    // ---- the plan everyone shares
    SectionTitle("Cloud (shared by everyone)")
    val plan = st.cloud
    if (plan == null || plan.totalBytes <= 0) {
        Text("The plan's numbers show here once an admin has opened Cloud.", fontSize = 13.5.sp, color = cs.onSurfaceVariant)
    } else {
        val share = (plan.usedBytes.toFloat() / plan.totalBytes).coerceIn(0f, 1f)
        Row {
            Text("${fmtSize(plan.usedBytes)} used of ${fmtSize(plan.totalBytes)}", fontSize = 14.sp, color = cs.onSurface, modifier = Modifier.weight(1f))
            if (plan.plan.isNotBlank()) Text("${plan.plan} plan", fontSize = 13.sp, color = cs.onSurfaceVariant)
        }
        LinearProgressIndicator(
            progress = { share },
            color = if (share > .9f) cs.error else cs.primary,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(4.dp)),
        )
        Row(Modifier.padding(top = 6.dp)) {
            Text("${fmtSize(plan.freeBytes)} free", fontSize = 13.5.sp, fontWeight = FontWeight.Medium,
                color = if (share > .9f) cs.error else cs.primary, modifier = Modifier.weight(1f))
            if (plan.updatedMs > 0) Text(
                "Updated " + android.text.format.DateUtils.getRelativeTimeSpanString(plan.updatedMs, System.currentTimeMillis(), 60_000L),
                fontSize = 12.5.sp, color = cs.onSurfaceVariant,
            )
        }
        Text("Storage, downloads and picture changes all count towards the plan.", fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }

    // ---- everyone (Everything accounts)
    st.everyone?.let { e ->
        SectionTitle("Everyone's own folders")
        Text("${e.people} ${if (e.people == 1) "person" else "people"} · ${e.files} file${if (e.files == 1) "" else "s"} · ${fmtSize(e.bytes)}",
            fontSize = 14.sp, color = cs.onSurface)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 22.dp, bottom = 6.dp))
}

@Composable
private fun Note(icon: ImageVector, text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(ToolTint.AMBER.tile).padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Icon(icon, null, tint = ToolTint.AMBER.icon, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurface, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(
    f: CloudFolderItem,
    first: Boolean,
    last: Boolean,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    menuOpen: Boolean,
    onDismissMenu: () -> Unit,
    onDelete: () -> Unit,
    onCopy: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().cardSlice(first, last)
                .combinedClickable(onClick = onOpen, onLongClick = onMenu)
                .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(ToolTint.SKY.tile), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Folder, null, tint = ToolTint.SKY.icon, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Text(f.label, fontSize = 15.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            // Copy or delete the folder (a long-press opens the same).
            BarIcon(Icons.Rounded.MoreVert, "More for ${f.label}", onClick = onMenu)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
            if (onCopy != null) DropdownMenuItem(text = { Text("Copy to Shared") }, onClick = onCopy,
                leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) })
            DropdownMenuItem(text = { Text("Delete folder", color = cs.error) }, onClick = onDelete,
                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = cs.error) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTile(f: CloudItem, checked: Boolean? = null, onOpen: () -> Unit, onMenu: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.clip(RoundedCornerShape(14.dp))
            .background(if (checked == true) cs.primary.copy(alpha = .14f) else Color.Transparent)
            .combinedClickable(onClick = onOpen, onLongClick = onMenu).padding(2.dp),
    ) {
        Box {
            Thumb(f, Modifier.fillMaxWidth().aspectRatio(1f))
            if (checked != null) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape)
                        .background(if (checked) cs.primary else Color.Black.copy(alpha = .35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (checked) Icon(Icons.Rounded.Check, "Selected", tint = cs.onPrimary, modifier = Modifier.size(16.dp))
                }
            }
            if (f.isVideo) {
                Box(
                    Modifier.align(Alignment.Center).size(34.dp).clip(CircleShape).background(Color.Black.copy(alpha = .45f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
            }
        }
        Text(
            f.name, fontSize = 12.sp, lineHeight = 15.sp, color = cs.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
    }
}

/** The picture for a file: Cloudinary's small square of it, or an icon for the kind of file. */
@Composable
private fun Thumb(f: CloudItem, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val (icon, tint) = when {
        f.isAudio -> Icons.Rounded.MusicNote to ToolTint.VIOLET
        f.isPdf -> Icons.Rounded.PictureAsPdf to ToolTint.RED
        else -> Icons.AutoMirrored.Rounded.InsertDriveFile to ToolTint.SLATE
    }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(tint.tile), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = tint.icon, modifier = Modifier.size(28.dp))
            if (f.ext.isNotBlank() && !f.isImage && !f.isVideo) {
                Text(f.ext.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant)
            }
        }
        val thumb = f.thumbUrl
        if (thumb != null) AsyncImage(model = thumb, contentDescription = f.name, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
    }
}

@Composable
private fun SheetRow(icon: ImageVector, title: String, sub: String?, danger: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 24.dp, vertical = 6.dp),
    ) {
        Icon(icon, null, tint = if (danger) cs.error else cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Column {
            Text(title, fontSize = 15.sp, color = if (danger) cs.error else cs.onSurface)
            if (sub != null) Text(sub, fontSize = 12.5.sp, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun NameDialog(title: String, label: String, initial: String, confirm: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val ok = name.isNotBlank() && !name.contains('/') && name.trim() !in setOf(".", "..")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, singleLine = true, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onDone(name.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Photos full screen: swipe between them, pinch to zoom. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoViewer(images: List<CloudItem>, start: Int, onClose: () -> Unit, onMore: (CloudItem) -> Unit) {
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (images.size - 1).coerceAtLeast(0))) { images.size }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { i ->
                var scale by remember { mutableStateOf(1f) }
                var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                val zoom = rememberTransformableState { z, pan, _ ->
                    scale = (scale * z).coerceIn(1f, 5f)
                    offset = if (scale > 1f) offset + pan else androidx.compose.ui.geometry.Offset.Zero
                }
                AsyncImage(
                    model = images[i].viewUrl, contentDescription = images[i].name, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                        .transformable(zoom, canPan = { scale > 1f })
                        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).background(Color.Black.copy(alpha = .35f)).padding(4.dp),
            ) {
                BarIcon(Icons.Rounded.Close, "Close", tint = Color.White, onClick = onClose)
                val cur = images.getOrNull(pager.currentPage)
                Text(cur?.name.orEmpty(), color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp))
                if (images.size > 1) Text("${pager.currentPage + 1} / ${images.size}", color = Color.White.copy(alpha = .8f), fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
                if (cur != null) BarIcon(Icons.Rounded.MoreVert, "More", tint = Color.White) { onMore(cur) }
            }
        }
    }
}

/**
 * Cloud files on their way to Shared (copies only — no Move for now, user 26 Nov 2026): each with its folder path
 * there ("" = straight in) and a few words for people.
 */
private class ShareRequest(val entries: List<Pair<String, CloudItem>>, val what: String) {
    companion object {
        fun files(items: List<CloudItem>) =
            ShareRequest(items.map { "" to it }, if (items.size == 1) "“${items[0].name}”" else "${items.size} files")
    }
}

/** A file's key while picking several. */
private val CloudItem.key: String get() = "$resourceType|$publicId"

@Composable
private fun ButtonText(icon: ImageVector, text: String) {
    Icon(icon, null, Modifier.size(18.dp))
    Spacer(Modifier.width(6.dp))
    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

private fun download(context: Context, f: CloudItem) {
    runCatching {
        Downloads.start(DownloadRequest(url = f.url, source = "Cloud"), f.name)
        Toast.makeText(context, "Downloading “${f.name}” to SyncUp Downloads.", Toast.LENGTH_SHORT).show()
    }.onFailure { Toast.makeText(context, "Couldn't start the download.", Toast.LENGTH_LONG).show() }
}

internal fun fmtSize(n: Long): String = when {
    n >= 1024L * 1024 * 1024 -> "%.1f GB".format(n / (1024.0 * 1024 * 1024))
    n >= 1024L * 1024 -> "%.1f MB".format(n / (1024.0 * 1024))
    n >= 1024 -> "${n / 1024} KB"
    else -> "$n B"
}
