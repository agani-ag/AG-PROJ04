package com.agani.syncup.browser

import com.agani.syncup.browser.ui.SyncUpMark
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Cookie
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NoPhotography
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.agani.syncup.music.LocalPlayer
import com.agani.syncup.music.MiniPlayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.agani.syncup.data.UrlItem
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.R
import com.agani.syncup.browser.ui.Avatar
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.browser.ui.SectionTheme
import com.agani.syncup.browser.ui.SyncPill
import java.util.Calendar

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 0..11 -> "Good morning,"
    in 12..16 -> "Good afternoon,"
    else -> "Good evening,"
}

// ============================================================================ Normal new-tab page
/**
 * No address bar. Signed out: account icon, SyncUp mark, search, shortcuts. Signed in: greeting +
 * avatar, search, admin announcement (strip), Work card (only when the user has work links),
 * shortcuts; the Music mini player floats above the bottom bar while something is loaded.
 */
@Composable
internal fun NormalHome(
    account: BrowserAccount,
    hasWork: Boolean,
    workOpenTabs: Int,
    singleLink: UrlItem? = null,
    onSearch: () -> Unit,
    onVoice: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onAvatar: () -> Unit,
    onOpenWork: () -> Unit,
    onOpenMusic: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val user = account.user
    val playerShown = LocalPlayer.current?.item != null

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = if (playerShown) 96.dp else 20.dp),
        ) {
            if (user == null) {
                Row(Modifier.fillMaxWidth().padding(end = 0.dp), horizontalArrangement = Arrangement.End) {
                    Box(Modifier.offset(x = 8.dp)) {
                        BarIcon(Icons.Rounded.AccountCircle, "Sign in to SyncUp", tint = cs.onSurfaceVariant, onClick = onAvatar)
                    }
                }
                Column(
                    Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(cs.primary), contentAlignment = Alignment.Center) {
                        Image(painterResource(R.drawable.ic_sync), null, colorFilter = ColorFilter.tint(cs.onPrimary), modifier = Modifier.size(34.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("SyncUp", fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(64.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(greeting(), fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = .2.sp, color = cs.onSurfaceVariant)
                        Text(
                            user.name.substringBefore(' '), fontSize = 24.sp, lineHeight = 30.sp, color = cs.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(Modifier.offset(x = 4.dp)) { Avatar(user, unread = account.partnersWaiting > 0, onClick = onAvatar) }
                }
                Spacer(Modifier.height(12.dp))
            }

            SyncPill(modifier = Modifier.fillMaxWidth(), height = 56.dp, onClick = onSearch) {
                Icon(Icons.Rounded.Search, null, tint = cs.onSurfaceVariant)
                Text("Search or type web address", fontSize = 15.sp, letterSpacing = .15.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1)
                BarIcon(Icons.Rounded.Mic, "Voice search", tint = cs.onSurfaceVariant, onClick = onVoice)
            }

            val ann = account.announcement
            if (user != null && ann != null && ann.active && !ann.fullscreen && (ann.title.isNotBlank() || ann.message.isNotBlank())) {
                Spacer(Modifier.height(20.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(cs.secondaryContainer)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Icon(Icons.Rounded.Campaign, null, tint = cs.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        if (ann.title.isNotBlank()) Text(ann.title, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = cs.onSecondaryContainer)
                        if (ann.message.isNotBlank()) Text(ann.message, fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSecondaryContainer.copy(alpha = .85f))
                    }
                }
            }

            if (user != null && hasWork) {
                Spacer(Modifier.height(12.dp))
                SectionTheme(Section.WORK) {
                    val w = MaterialTheme.colorScheme
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(w.primaryContainer)
                            .clickable(onClick = onOpenWork)
                            .padding(16.dp),
                    ) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(w.primary), contentAlignment = Alignment.Center) {
                            Icon(SyncUpMark, null, tint = w.onPrimary)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            // One link: the card is that link and opens the website itself.
                            Text(
                                singleLink?.title ?: "SyncUp", fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium,
                                color = w.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            val n = account.links.size
                            Text(
                                when {
                                    singleLink == null -> "$n link${if (n == 1) "" else "s"} · $workOpenTabs open tab${if (workOpenTabs == 1) "" else "s"}"
                                    workOpenTabs > 0 -> "Open · $workOpenTabs page${if (workOpenTabs == 1) "" else "s"}"
                                    else -> "Your SyncUp link"
                                },
                                fontSize = 12.sp, lineHeight = 16.sp, color = w.onPrimaryContainer.copy(alpha = .85f),
                            )
                        }
                        Button(onClick = onOpenWork, contentPadding = PaddingValues(horizontal = 18.dp), modifier = Modifier.height(36.dp)) { Text("Open") }
                    }
                }
            }

            // SyncUp's shortcut catalogue, by category.
            LaunchedEffect(Unit) { ShortcutCatalog.refresh() }
            Spacer(Modifier.height(24.dp))
            CatalogShortcuts(onOpen = onOpenUrl)
        }
        if (playerShown) {
            MiniPlayer(onOpen = onOpenMusic, modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 12.dp))
        }
    }
}

/** How many of each category "All" shows before "Show all". */
private const val PER_CATEGORY = 8

/**
 * SyncUp's shortcut catalogue: category chips (All + each category), then on All up to
 * [PER_CATEGORY] of each category with "Show all", or a chosen category's whole grid.
 */
@Composable
private fun CatalogShortcuts(onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val cats = ShortcutCatalog.categories
    val chosen = ShortcutCatalog.chosen?.let { id -> cats.firstOrNull { it.id == id } }
    Column {
        if (cats.size > 1) {
            // The chips run to the screen's edges (past the page's side margins) and keep the
            // chosen one in view.
            val chips = rememberLazyListState()
            LaunchedEffect(chosen?.id) {
                val index = chosen?.let { ch -> cats.indexOfFirst { it.id == ch.id } + 1 } ?: 0
                chips.animateScrollToItem((index - 1).coerceAtLeast(0))
            }
            LazyRow(
                state = chips,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
                modifier = Modifier
                    .layout { measurable, constraints ->
                        val bleed = 20.dp.roundToPx()
                        val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + bleed * 2))
                        layout(constraints.maxWidth, placeable.height) { placeable.place(-bleed, 0) }
                    }
                    .padding(bottom = 6.dp),
            ) {
                item { CategoryChip("All", chosen == null) { ShortcutCatalog.chosen = null } }
                items(cats, key = { it.id }) { cat -> CategoryChip(cat.name, chosen?.id == cat.id) { ShortcutCatalog.chosen = cat.id } }
            }
        }
        val shown = if (chosen != null) listOf(chosen) else cats
        Column { shown.forEach { cat ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp)) {
                Text(cat.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (chosen != null) "${cat.items.size} link${if (cat.items.size == 1) "" else "s"}" else "${cat.items.size}",
                    fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                if (chosen == null && cat.items.size > PER_CATEGORY) {
                    Text(
                        "Show all ›", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.primary,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { ShortcutCatalog.chosen = cat.id }.padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
            }
            val items = if (chosen != null) cat.items else cat.items.take(PER_CATEGORY)
            TileRows(items.map { item -> { CatalogTile(item, onOpen) } })
        } }
    }
}

@Composable
private fun CategoryChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(if (selected) cs.primaryContainer else cs.surfaceContainerHigh)
            .clickable(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (selected) cs.onPrimaryContainer else cs.onSurface, maxLines = 1)
    }
}

@Composable
private fun RowScope.CatalogTile(item: CatalogShortcut, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onOpen(item.url) }
            .padding(vertical = 4.dp),
    ) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            SiteMark(item.url, item.title)
        }
        Spacer(Modifier.height(8.dp))
        Text(item.title, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = .3.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Tiles four to a row. */
@Composable
private fun TileRows(cells: List<@Composable RowScope.() -> Unit>) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        cells.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { cell -> cell() }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ============================================================================ Incognito home
@Composable
internal fun IncognitoHome(onSearch: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))
            Box(Modifier.size(72.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.VisibilityOff, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text("You've gone incognito", fontSize = 24.sp, lineHeight = 32.sp, color = cs.onSurface, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            IncognitoPoint(Icons.Rounded.History, "Pages you view won't be kept in history")
            IncognitoPoint(Icons.Rounded.Cookie, "Cookies and site data are deleted when you close all incognito tabs")
            IncognitoPoint(Icons.Rounded.Download, "Downloads and bookmarks are kept")
            IncognitoPoint(Icons.Rounded.NoPhotography, "Screenshots are blocked")
            Spacer(Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerLow).padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Icon(Icons.Rounded.Info, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(12.dp))
                Text("SyncUp links, Chat and Radio aren't available in Incognito", fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
        }
        SyncPill(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            height = 56.dp,
            onClick = onSearch,
        ) {
            Icon(Icons.Rounded.Search, null, tint = cs.onSurfaceVariant)
            Text("Search privately", fontSize = 15.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun IncognitoPoint(icon: ImageVector, text: String) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Icon(icon, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, fontSize = 14.sp, lineHeight = 20.sp, color = cs.onSurface)
    }
}
