package com.agani.syncup.group

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.agani.syncup.forest.BarIcons
import com.agani.syncup.music.NoteBox
import com.agani.syncup.music.ToolTile
import com.agani.syncup.music.tintButton
import com.agani.syncup.ui.theme.CardShape
import com.agani.syncup.ui.theme.PillTabs
import com.agani.syncup.ui.theme.appCard
import kotlin.math.min

// Group photos (docs/mockup/syncup-new-picks.html, 17): the Photos grid and the Photo Show tab on the
// group's page, and — full screen over it — the photo viewer and the Photo Show itself.

/** Each person's badge colour, in the order people added photos: blue, green, pink, amber, then violet, sky, red, slate. */
private val OwnerColors = listOf(
    Color(0xFF2563EB), Color(0xFF15803D), Color(0xFFBE185D), Color(0xFFB45309),
    Color(0xFF7C3AED), Color(0xFF0369A1), Color(0xFFB91C1C), Color(0xFF475569),
)

@Composable
private fun ownerColor(p: GroupPhoto): Color = OwnerColors[GroupPhotos.ownerSlot(p) % OwnerColors.size]

/** The show's choices: a new photo every 3 / 5 / 10 s. */
private val ShowSpeeds = listOf(3_000L, 5_000L, 10_000L)

private fun plural(n: Int, one: String) = "$n $one${if (n == 1) "" else "s"}"

// ============================================================================ the page
/** The group's page in Group photos: Photos (everyone's, newest first) and Photo Show. */
@Composable
internal fun PhotosView() {
    val cs = MaterialTheme.colorScheme
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var saveSheet by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(GroupPhotos.MAX_PICK)) { uris ->
        GroupPhotos.addPhotos(uris)
    }
    val withStorage = rememberStorageGate()
    val pool = GroupPhotos.pool
    Column(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { GroupBar(Modifier.padding(top = 4.dp)) }
            item(span = { GridItemSpan(maxLineSpan) }) {
                PillTabs(listOf("Photos", "Photo Show"), tab, { tab = it }, Modifier.padding(top = 10.dp, bottom = 6.dp))
            }
            if (tab == 0) {
                if (pool.isNotEmpty() || GroupPhotos.preparing > 0) item(span = { GridItemSpan(maxLineSpan) }) { PoolHeader() }
                items(pool, key = { it.id }) { p -> PhotoTile(p) { GroupPhotos.viewing = p.id } }
                if (pool.isEmpty() && GroupPhotos.preparing == 0) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        NoteBox(
                            Icons.Rounded.PhotoLibrary,
                            "Everyone's photos in one place. Photos anyone adds reach every phone in the group — tap Add photos to pick yours. " +
                                "Save all keeps them in your Gallery, full size.",
                            Modifier.padding(top = 8.dp),
                        )
                    }
                }
            } else {
                item(span = { GridItemSpan(maxLineSpan) }) { ShowTab() }
            }
        }
        if (tab == 0) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().background(cs.surface).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
            ) {
                FilledTonalButton(
                    onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add photos")
                }
                Button(
                    onClick = {
                        withStorage {
                            GroupPhotos.saveAll()
                            saveSheet = true
                        }
                    },
                    enabled = pool.isNotEmpty(),
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    Icon(Icons.Rounded.Download, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Save all")
                }
            }
        }
    }
    if (saveSheet) SaveSheet(onDismiss = { saveSheet = false })
}

/** "48 photos · from 4 phones · 2 arriving". */
@Composable
private fun PoolHeader() {
    val cs = MaterialTheme.colorScheme
    val n = GroupPhotos.pool.size
    val adding = GroupPhotos.preparing
    val arriving = GroupPhotos.arrivingCount()
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = 4.dp, bottom = 6.dp)) {
        Text(
            if (n == 0 && adding > 0) "Adding ${plural(adding, "photo")}…" else plural(n, "photo"),
            fontSize = 16.sp, fontWeight = FontWeight.Medium, color = cs.onSurface,
        )
        Spacer(Modifier.weight(1f))
        val line = listOfNotNull(
            if (n > 0) "from ${plural(GroupPhotos.phoneCount(), "phone")}" else null,
            if (arriving > 0) "$arriving arriving" else null,
            if (adding > 0 && n > 0) "adding $adding" else null,
        ).joinToString(" · ")
        Text(line, fontSize = 12.5.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One photo in the grid: its preview, whose it is (a letter in their colour), a spinner while it's on its way. */
@Composable
private fun PhotoTile(p: GroupPhoto, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(cs.surfaceContainerHigh)
            .clickable(onClickLabel = "Open") { onClick() },
    ) {
        p.thumb?.let { AsyncImage(model = it, contentDescription = "${p.owner}'s photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (p.full == null) {
            Box(Modifier.fillMaxSize().background(Color(0x730F172A)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = Color.White, trackColor = Color.White.copy(alpha = .35f), strokeWidth = 3.dp, modifier = Modifier.size(28.dp),
                )
            }
        }
        OwnerBadge(p, Modifier.align(Alignment.BottomEnd).padding(5.dp))
    }
}

@Composable
private fun OwnerBadge(p: GroupPhoto, modifier: Modifier = Modifier) {
    Box(
        modifier.size(22.dp).clip(CircleShape).background(ownerColor(p)).border(2.dp, Color.White.copy(alpha = .85f), CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(p.initial, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
}

// ============================================================================ Photo Show tab
@Composable
private fun ShowTab() {
    val cs = MaterialTheme.colorScheme
    val tint = GroupMode.PHOTOS.tint()
    val s = GroupPhotos.show
    val n = GroupPhotos.pool.size
    var speed by rememberSaveable { mutableIntStateOf(1) }
    var shuffled by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Column(Modifier.fillMaxWidth().appCard(CardShape).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolTile(Icons.Rounded.Slideshow, tint)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            s == null -> "Photo Show"
                            GroupPhotos.showIsMine -> "You're showing"
                            else -> "${s.by} is showing"
                        },
                        fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    )
                    Text(
                        if (s == null) "${plural(n, "photo")} · on ${plural(GroupLink.people.size, "phone")}"
                        else listOfNotNull(
                            "${GroupPhotos.showIndex + 1} of ${s.count}", "every ${s.everyMs / 1000} s",
                            if (s.shuffled) "shuffled" else "in order", if (s.paused) "paused" else null,
                        ).joinToString(" · "),
                        fontSize = 13.sp, color = cs.onSurfaceVariant,
                    )
                }
            }
            if (s == null) {
                Text("A new photo", fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
                PillTabs(ShowSpeeds.map { "Every ${it / 1000} s" }, speed, { speed = it })
                Text("Order", fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                PillTabs(listOf("In order", "Shuffled"), if (shuffled) 1 else 0, { shuffled = it == 1 })
                Button(
                    onClick = { GroupPhotos.startShow(ShowSpeeds[speed], shuffled) }, enabled = n > 0, colors = tintButton(tint),
                    modifier = Modifier.padding(top = 18.dp).fillMaxWidth().height(48.dp),
                ) {
                    Icon(Icons.Rounded.Slideshow, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Start Photo Show")
                }
            } else {
                Button(
                    onClick = { GroupPhotos.showOpen = true }, colors = tintButton(tint),
                    modifier = Modifier.padding(top = 16.dp).fillMaxWidth().height(48.dp),
                ) { Text(if (GroupPhotos.showIsMine) "Back to the show" else "Watch the show") }
                if (GroupPhotos.showIsMine) {
                    OutlinedButton(onClick = { GroupPhotos.stopShow() }, modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(48.dp)) {
                        Text("Stop the show", color = cs.error)
                    }
                }
            }
        }
        NoteBox(
            Icons.Rounded.Info,
            if (n == 0 && s == null) "Add photos first — then anyone in the group can start a show."
            else "Every phone shows the same photo at the same moment. Whoever starts the show has the controls; Back leaves the show, the group stays.",
            Modifier.padding(top = 12.dp),
        )
    }
}

// ============================================================================ Save all
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveSheet(onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val job = GroupPhotos.saveJob ?: return
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = cs.surfaceContainerLowest,
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text(if (job.finished) "Saved to your Gallery" else "Saving to your Gallery", fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Text("${GroupLink.groupName} · ${plural(job.total, "photo")}", fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            LinearProgressIndicator(
                progress = { if (job.total == 0) 1f else (job.done.toFloat() / job.total).coerceIn(0f, 1f) },
                color = cs.primary, trackColor = cs.outlineVariant,
                modifier = Modifier.padding(top = 18.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            )
            val waiting = GroupPhotos.saveWaiting()
            Row(Modifier.padding(top = 8.dp).fillMaxWidth()) {
                Text(
                    "${job.done} of ${job.total} saved" + if (waiting > 0) " · $waiting arriving" else "",
                    fontSize = 12.5.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                Text("Pictures / ${GroupPhotos.GALLERY_FOLDER}", fontSize = 12.5.sp, color = cs.onSurfaceVariant)
            }
            NoteBox(
                Icons.Rounded.Info, "Full size, as they were taken. Photos you already have aren't saved twice.",
                Modifier.padding(top = 14.dp),
            )
            FilledTonalButton(onClick = onDismiss, modifier = Modifier.padding(top = 14.dp).fillMaxWidth().height(48.dp)) {
                Text(if (job.finished) "Done" else "Hide")
            }
        }
    }
}

/**
 * Saving into the Gallery needs no permission on Android 10+; on Android 9 and older it needs
 * storage (WRITE_EXTERNAL_STORAGE, declared up to API 28 for Downloads) — asked once, then [action] runs.
 */
@Composable
private fun rememberStorageGate(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val run = pending
        pending = null
        if (ok) run?.invoke() else GroupPhotos.notice = "SyncUp needs storage access to save photos on this phone."
    }
    return { action ->
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pending = action
            ask.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            action()
        }
    }
}

// ============================================================================ full screen
/** Over the group's page: the Photo Show (when this phone shows it) or the photo viewer. */
@Composable
internal fun PhotosFullScreen() {
    val viewing = GroupPhotos.viewing
    when {
        GroupPhotos.showOpen && GroupPhotos.show != null -> ShowScreen()
        viewing != null -> ViewerScreen(viewing)
    }
}

/** Black, over everything (touches don't reach the page underneath), white status-bar icons. */
@Composable
private fun DarkPage(content: @Composable () -> Unit) {
    BarIcons(light = false, nav = false)
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) { content() }
}

/** A photo, fitted: the preview at once, the full photo over it when it's here (a spinner until then). */
@Composable
private fun PhotoImage(p: GroupPhoto?, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (p == null) return@Box
        p.thumb?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        p.full?.let { AsyncImage(model = it, contentDescription = "${p.owner}'s photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        if (p.full == null) {
            CircularProgressIndicator(color = Color.White, trackColor = Color.White.copy(alpha = .3f), strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
        }
    }
}

/** A see-through pill on black: an icon (or a [lead]) and a few words; tappable when [onClick] is given. */
@Composable
private fun DarkChip(text: String, icon: ImageVector? = null, lead: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(Color.White.copy(alpha = .16f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = if (lead != null) 5.dp else 12.dp, end = 12.dp),
    ) {
        lead?.invoke()
        if (icon != null) Icon(icon, null, tint = Color.White, modifier = Modifier.size(16.dp))
        if (icon != null || lead != null) Spacer(Modifier.width(6.dp))
        Text(text, color = Color.White, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "Priya's photo", on Priya's colour. */
@Composable
private fun OwnerLabel(p: GroupPhoto, modifier: Modifier = Modifier) {
    Text(
        if (p.mine) "Your photo" else "${p.owner}'s photo", color = Color.White, fontSize = 12.5.sp, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(14.dp)).background(ownerColor(p)).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** Save this photo — or where it is already. */
@Composable
private fun SaveChip(p: GroupPhoto?, withStorage: (() -> Unit) -> Unit) {
    when {
        p == null -> Unit
        p.mine -> DarkChip("In your Gallery", Icons.Rounded.Check)
        p.saved -> DarkChip("Saved", Icons.Rounded.CheckCircle)
        GroupPhotos.isSaving(p) -> DarkChip(if (p.full == null) "Saved when it arrives" else "Saving…", Icons.Rounded.Download)
        else -> DarkChip("Save this photo", Icons.Rounded.Download) { withStorage { GroupPhotos.save(p) } }
    }
}

/** Tap a photo: full screen, swipe through the pool (as it was when opened). */
@Composable
private fun ViewerScreen(startId: Long) {
    val list = remember { GroupPhotos.pool.toList() }
    val withStorage = rememberStorageGate()
    BackHandler { GroupPhotos.viewing = null }
    if (list.isEmpty()) {
        LaunchedEffect(Unit) { GroupPhotos.viewing = null }
        return
    }
    val pager = rememberPagerState(initialPage = list.indexOfFirst { it.id == startId }.coerceAtLeast(0)) { list.size }
    DarkPage {
        HorizontalPager(state = pager, key = { list[it].id }, modifier = Modifier.fillMaxSize()) { i -> PhotoImage(list[i], Modifier.fillMaxSize()) }
        val p = list.getOrNull(pager.currentPage)
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
                IconButton(onClick = { GroupPhotos.viewing = null }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                DarkChip("${pager.currentPage + 1} of ${list.size}")
                Spacer(Modifier.width(8.dp))
            }
            Spacer(Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 16.dp),
            ) {
                p?.let { OwnerLabel(it) }
                Spacer(Modifier.weight(1f))
                SaveChip(p, withStorage)
            }
        }
    }
}

/**
 * The Photo Show, full screen: the same photo as every other phone. Whoever started it gets the
 * controls (previous / pause / next, how often, in order or shuffled, and the photos to jump to);
 * everyone else sees whose photo it is and can save it. Back leaves the show — the group stays.
 */
@Composable
private fun ShowScreen() {
    val s = GroupPhotos.show ?: return
    val mine = GroupPhotos.showIsMine
    val idx = GroupPhotos.showIndex
    val current = GroupPhotos.showPhoto()
    val next = GroupPhotos.showNextPhoto()
    val withStorage = rememberStorageGate()
    BackHandler { GroupPhotos.showOpen = false }
    // A slideshow: the screen stays on while it's open.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    DarkPage {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(start = if (mine) 4.dp else 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            ) {
                if (mine) {
                    IconButton(onClick = { GroupPhotos.showOpen = false }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
                    DarkChip("On ${plural(GroupLink.people.size, "phone")}", Icons.Rounded.Groups)
                } else {
                    DarkChip("${s.by} is showing", lead = {
                        Box(Modifier.size(24.dp).clip(CircleShape).background(GroupTint.icon), contentAlignment = Alignment.Center) {
                            Text(s.by.take(1).uppercase(), color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                        }
                    })
                }
                Spacer(Modifier.weight(1f))
                DarkChip("${idx + 1} of ${s.count}")
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // The next photo is drawn (unseen) underneath, so it's ready the moment it comes up.
                listOfNotNull(next?.takeIf { it.id != current?.id }, current).forEach { p ->
                    key(p.id) { PhotoImage(p, Modifier.fillMaxSize().alpha(if (p.id == current?.id) 1f else 0f)) }
                }
                if (!mine && current != null) OwnerLabel(current, Modifier.align(Alignment.BottomStart).padding(12.dp))
            }
            if (mine) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 10.dp),
                ) {
                    IconButton(onClick = { GroupPhotos.showPrev() }, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Rounded.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    Box(
                        Modifier.size(64.dp).clip(RoundedCornerShape(22.dp)).background(Color.White)
                            .clickable(onClickLabel = if (s.paused) "Play" else "Pause") { GroupPhotos.showPause() },
                        contentAlignment = Alignment.Center,
                    ) { Icon(if (s.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null, tint = Color.Black, modifier = Modifier.size(32.dp)) }
                    IconButton(onClick = { GroupPhotos.showNext() }, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Rounded.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    DarkChip("Every ${s.everyMs / 1000} s", Icons.Rounded.Timer) {
                        val at = ShowSpeeds.indexOf(s.everyMs)
                        GroupPhotos.showEvery(ShowSpeeds[(at + 1) % ShowSpeeds.size])
                    }
                    DarkChip(if (s.shuffled) "Shuffled" else "In order", Icons.Rounded.Shuffle) { GroupPhotos.showShuffle(!s.shuffled) }
                }
                ShowStrip(s, idx)
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 16.dp),
                ) {
                    SaveChip(current, withStorage)
                    DarkChip("All photos", Icons.Rounded.PhotoLibrary) { GroupPhotos.showOpen = false }
                }
                ShowDots(idx, s.count)
            }
        }
    }
}

/** The starter's strip of the show's photos: the one on screen outlined; tap one to go to it. */
@Composable
private fun ShowStrip(s: PhotoShow, idx: Int) {
    val strip = rememberLazyListState()
    LaunchedEffect(idx) { strip.animateScrollToItem((idx - 2).coerceAtLeast(0)) }
    LazyRow(
        state = strip, contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp),
    ) {
        itemsIndexed(s.order, key = { _, id -> id }) { i, id ->
            val on = i == idx
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(10.dp))
                    .then(if (on) Modifier.border(2.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier.alpha(.55f))
                    .background(Color.White.copy(alpha = .12f))
                    .clickable(onClickLabel = "Show this photo") { GroupPhotos.showJump(i) },
            ) {
                GroupPhotos.photo(id)?.thumb?.let {
                    AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().padding(if (on) 2.dp else 0.dp).clip(RoundedCornerShape(8.dp)))
                }
            }
        }
    }
}

/** Everyone else: where the show is, as a few dots (the one on screen long). */
@Composable
private fun ShowDots(idx: Int, count: Int) {
    val n = min(count, 5)
    if (n <= 1) return
    val on = when {
        count <= 5 || idx < 2 -> idx
        idx >= count - 2 -> n - (count - idx)
        else -> 2
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp),
    ) {
        repeat(n) { i ->
            Box(
                Modifier.height(6.dp).width(if (i == on) 18.dp else 6.dp).clip(RoundedCornerShape(3.dp))
                    .background(if (i == on) Color.White else Color.White.copy(alpha = .35f)),
            )
        }
    }
}
