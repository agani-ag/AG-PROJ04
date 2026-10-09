package com.agani.syncup.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.browser.ui.EmptyState
import com.agani.syncup.browser.ui.PillButton
import com.agani.syncup.browser.ui.SyncPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Language chip order: these first (if present), rest after by channel count. */
private val LANGUAGE_PRIORITY = listOf("Tamil", "Telugu", "Malayalam", "Kannada", "Hindi", "English")

private const val CAT_ALL = "__all__"
private const val CAT_FAVORITES = "__favorites__"

/**
 * The page one saved playlist (added from Tools → Channels) opens into — search, category + language
 * filters and starred channels. Everything here is a client-side filter over [categories], which
 * [M3uPlaylist] already parsed from [playlistUrl]; nothing is fetched again until the user asks to.
 *
 * Every list that reaches this screen is already saved (added in [ChannelsHomeScreen]) — the ⋮ menu
 * only lets the user update its link (re-fetch a different URL in its place) or remove it; the caller
 * owns the actual [SavedPlaylists] bookkeeping via [onPlaylistUpdated] / [onRemoved].
 */
@Composable
fun ChannelBrowserScreen(
    categories: List<TvCategoryGroup>,
    title: String,
    playlistUrl: String,
    onPlaylistUpdated: (newUrl: String, newCategories: List<TvCategoryGroup>) -> Unit,
    onRemoved: () -> Unit,
    onPlay: (TvChannel) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val totalCount = remember(categories) { categories.sumOf { it.channels.size } }
    var favoriteUrls by remember { mutableStateOf(ChannelFavorites.all(context)) }
    var categoryId by rememberSaveable { mutableStateOf(CAT_ALL) }
    var language by rememberSaveable(categoryId) { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateError by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }

    val categoryChannels = remember(categoryId, categories, favoriteUrls) {
        when (categoryId) {
            CAT_ALL -> categories.flatMap { it.channels }
            CAT_FAVORITES -> categories.flatMap { it.channels }.filter { it.url in favoriteUrls }
            else -> categories.firstOrNull { it.id == categoryId }?.channels.orEmpty()
        }
    }
    val languageCounts = remember(categoryChannels) {
        categoryChannels.mapNotNull { it.language.takeIf { l -> l.isNotBlank() } }.groupingBy { it }.eachCount()
    }
    val languages = remember(languageCounts) {
        LANGUAGE_PRIORITY.filter { it in languageCounts } +
            languageCounts.keys.filterNot { it in LANGUAGE_PRIORITY }.sortedByDescending { languageCounts.getValue(it) }
    }
    val q = query.trim()
    val shown = remember(categoryChannels, language, q) {
        categoryChannels
            .let { list -> language?.let { l -> list.filter { it.language == l } } ?: list }
            .let { list -> if (q.isEmpty()) list else list.filter { it.title.contains(q, ignoreCase = true) } }
            .sortedBy { it.title.lowercase() }
    }

    fun toggleFavorite(url: String) {
        ChannelFavorites.toggle(context, url)
        favoriteUrls = ChannelFavorites.all(context)
        if (categoryId == CAT_FAVORITES && url !in favoriteUrls && categoryChannels.size <= 1) categoryId = CAT_ALL
    }

    Box(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp)) {
                BarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(title.ifBlank { "Channels" }, fontSize = 20.sp, lineHeight = 24.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("$totalCount channels", fontSize = 12.sp, color = cs.onSurfaceVariant)
                }
                Box {
                    BarIcon(Icons.Rounded.MoreVert, "More", tint = cs.onSurfaceVariant) { showMenu = true }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text("Update link") }, onClick = { showMenu = false; showUpdateDialog = true })
                        DropdownMenuItem(text = { Text("Remove this list", color = cs.error) }, onClick = { showMenu = false; onRemoved() })
                    }
                }
            }
            SyncPill(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                height = 48.dp, focused = query.isNotEmpty(),
            ) {
                Icon(Icons.Rounded.Search, null, tint = cs.onSurfaceVariant)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, color = cs.onSurface),
                    cursorBrush = SolidColor(cs.primary),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) Text("Search channels", color = cs.onSurfaceVariant, fontSize = 15.sp)
                            inner()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                if (query.isNotEmpty()) PillButton(Icons.Rounded.Close, "Clear", size = 40.dp) { query = "" }
            }
            val categoryRowState = rememberLazyListState()
            // The ★ Favorites chip is prepended once something gets starred — jump the row back to
            // the start so it's immediately visible instead of staying scrolled past it.
            LaunchedEffect(favoriteUrls.isNotEmpty()) {
                if (favoriteUrls.isNotEmpty()) categoryRowState.scrollToItem(0)
            }
            LazyRow(state = categoryRowState, horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
                if (favoriteUrls.isNotEmpty()) {
                    item(key = "favorites") {
                        TvChip(
                            "★ Favorites",
                            categories.flatMap { it.channels }.count { it.url in favoriteUrls },
                            selected = categoryId == CAT_FAVORITES,
                        ) { categoryId = CAT_FAVORITES }
                    }
                }
                item(key = "all") { TvChip("All channels", totalCount, selected = categoryId == CAT_ALL) { categoryId = CAT_ALL } }
                items(categories, key = { it.id }) { c ->
                    TvChip(c.name, c.channels.size, selected = categoryId == c.id) { categoryId = c.id }
                }
            }
            if (languages.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp)) {
                    item(key = "all-lang") { TvChip("All languages", categoryChannels.size, selected = language == null) { language = null } }
                    items(languages, key = { it }) { lang ->
                        TvChip(lang, languageCounts.getValue(lang), selected = lang == language) { language = lang }
                    }
                }
            }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize()) {
                    EmptyState(
                        if (q.isNotEmpty()) Icons.Rounded.SearchOff else Icons.Rounded.LiveTv,
                        if (q.isNotEmpty()) "No channels match “$q”" else "No channels here",
                        if (categoryId == CAT_FAVORITES) "Tap the star on a channel to save it here" else null,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(horizontal = 11.dp, vertical = 5.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    gridItems(shown, key = { it.id }) { ch ->
                        TvChannelCard(
                            ch, isFavorite = ch.url in favoriteUrls,
                            onToggleFavorite = { toggleFavorite(ch.url) },
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 5.dp),
                        ) { onPlay(ch) }
                    }
                }
            }
        }

        if (showUpdateDialog) {
            var newUrl by remember { mutableStateOf(playlistUrl) }
            AlertDialog(
                onDismissRequest = { if (!updating) { showUpdateDialog = false; updateError = null } },
                title = { Text("Update link") },
                text = {
                    Column {
                        Text("Replace the saved link with a different one — it's re-fetched and takes over right away.", fontSize = 13.sp, color = cs.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = newUrl, onValueChange = { newUrl = it; updateError = null }, singleLine = true,
                            label = { Text("Playlist link") }, enabled = !updating, modifier = Modifier.fillMaxWidth(),
                        )
                        if (updateError != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(updateError.orEmpty(), fontSize = 12.5.sp, color = cs.error)
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = newUrl.isNotBlank() && !updating,
                        onClick = {
                            val url = newUrl.trim()
                            updating = true
                            updateError = null
                            scope.launch {
                                when (val result = withContext(Dispatchers.IO) { M3uPlaylist.inspect(url) }) {
                                    is M3uResult.ChannelList -> {
                                        showUpdateDialog = false
                                        onPlaylistUpdated(url, result.categories)
                                    }
                                    else -> updateError = "That link isn't a channel list — nothing changed."
                                }
                                updating = false
                            }
                        },
                    ) {
                        if (updating) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text("Update")
                    }
                },
                dismissButton = {
                    TextButton(enabled = !updating, onClick = { showUpdateDialog = false; updateError = null }) { Text("Cancel") }
                },
            )
        }
    }
}

@Composable
private fun TvChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(34.dp).clip(RoundedCornerShape(17.dp))
            .background(if (selected) cs.primaryContainer else cs.surfaceContainerHigh)
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant)
        Text(
            " $count", fontSize = 12.sp,
            color = (if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant).copy(alpha = .6f),
        )
    }
}

@Composable
private fun TvChannelCard(ch: TvChannel, isFavorite: Boolean, onToggleFavorite: () -> Unit, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.35f).clip(RoundedCornerShape(14.dp)).background(cs.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            if (ch.logo.isBlank()) {
                Icon(Icons.Rounded.LiveTv, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(28.dp))
            } else {
                SubcomposeAsyncImage(
                    model = ch.logo, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(14.dp),
                    loading = { Box(Modifier.fillMaxSize()) },
                    error = { Icon(Icons.Rounded.LiveTv, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(28.dp)) },
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(5.dp))
                    .background(Color(0xFFDC2626)).padding(horizontal = 5.dp, vertical = 2.dp),
            ) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(Color.White))
                Spacer(Modifier.width(3.dp))
                Text("LIVE", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White, letterSpacing = .3.sp)
            }
            Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(26.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = .4f)).clickable(onClick = onToggleFavorite),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    if (isFavorite) "Remove from favourites" else "Add to favourites",
                    tint = if (isFavorite) Color(0xFFFFC107) else Color.White, modifier = Modifier.size(15.dp),
                )
            }
        }
        Text(ch.title, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}
