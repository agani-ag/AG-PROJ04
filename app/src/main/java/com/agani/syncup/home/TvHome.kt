package com.agani.syncup.home

import android.text.format.DateFormat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.RecommendedSites
import com.agani.syncup.browser.ShortcutCatalog
import com.agani.syncup.browser.SiteMark
import com.agani.syncup.data.UrlItem
import com.agani.syncup.music.LocalPlayer
import com.agani.syncup.video.VideoPlayerActivity
import com.agani.syncup.video.WatchHistory
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * SyncUp's main page on a TV (docs/mockup/v7/tv_page.py, user 16–18 Nov 2026): rows of big tiles the remote
 * moves between, each with a clear focus ring — Watch (Channels, Videos, Watch Party, carry on watching),
 * Listen (Music, Radio, Party, what's playing), SyncUp links, Websites, and This TV (Settings, Account). Tools that
 * can't work on a TV (Walkie, Sensors, Steps…) aren't here. Light, like the phone.
 */
@Composable
fun TvHome(
    signedIn: Boolean,
    userName: String?,
    links: List<UrlItem>,
    radioAvailable: Boolean,
    onTool: (HomeTool) -> Unit,
    onOpenLink: (UrlItem) -> Unit,
    onOpenUrl: (String) -> Unit,
    onSearch: () -> Unit,
    onNotifications: () -> Unit,
    onSettings: () -> Unit,
    onAccount: () -> Unit,
    /** Changes when something that sat over the page (the sign-in form) goes: the remote is given a tile again. */
    refocusKey: Any? = null,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    // The tiles show where the remote is themselves (they grow, fill and get a ring): no second ring.
    com.agani.syncup.ui.theme.OwnFocusLook()
    // The remote goes back to the tile it was on (coming back from a page, or when a form over the page closes),
    // else it starts on the first tile.
    val tiles = remember { mutableMapOf<String, FocusRequester>() }
    // Read before anything here is selected: Android selects the top element by itself as the page opens.
    val opened = remember { object { var first = true; val spot = TvFocus.last } }
    LaunchedEffect(refocusKey) {
        delay(150)
        val spot = if (opened.first) opened.spot else TvFocus.last
        opened.first = false
        val back = spot?.let { tiles[it] }
        if (back == null || runCatching { back.requestFocus() }.isFailure) runCatching { tiles[FIRST_TILE]?.requestFocus() }
    }
    val page = rememberScrollState(TvFocus.page)
    LaunchedEffect(page) { snapshotFlow { page.value }.collect { TvFocus.page = it } }
    LaunchedEffect(Unit) { ShortcutCatalog.refresh() }
    LaunchedEffect(Unit) { RecommendedSites.refresh(context) }
    val watching by produceState(emptyList<com.agani.syncup.video.Watched>()) {
        value = withContext(Dispatchers.IO) { WatchHistory.continueWatching(context) }
    }
    val player = LocalPlayer.current

    CompositionLocalProvider(LocalTvTiles provides tiles) {
    Column(
        Modifier.fillMaxSize().background(cs.surface).verticalScroll(page)
            // TVs can cut off the screen's edges: everything sits inside the safe area.
            .padding(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 24.dp),
    ) {
        // ---- top: who, Search, bell, picture, clock
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                val name = userName?.trim()?.substringBefore(' ')?.takeIf { signedIn && it.isNotBlank() }
                if (name != null) Text(tvGreeting(), fontSize = 15.sp, color = cs.onSurfaceVariant)
                Text(name ?: "SyncUp", fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
            }
            TvPill(Icons.Rounded.Search, "Search", "search", onSearch)
            Spacer(Modifier.width(12.dp))
            TvRound(onNotifications, "Notifications", "bell") { Icon(Icons.Outlined.Notifications, null, tint = cs.onSurface, modifier = Modifier.size(24.dp)) }
            Spacer(Modifier.width(12.dp))
            TvRound(onSettings, "Settings and account", "picture", background = if (signedIn) cs.primary else cs.surfaceContainerHigh) {
                val initial = userName?.trim()?.take(1)?.uppercase()?.takeIf { signedIn && it.isNotBlank() }
                if (initial != null) Text(initial, color = cs.onPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                else Icon(Icons.Rounded.Person, null, tint = cs.onSurfaceVariant)
            }
            Spacer(Modifier.width(20.dp))
            TvClock()
        }

        // ---- Watch
        TvRowTitle("Watch")
        TvTiles("watch") {
            item { TvTile(Icons.Rounded.LiveTv, ToolTint.GREEN, "Channels", "Live TV lists", FIRST_TILE) { onTool(HomeTool.CHANNELS) } }
            item { TvTile(Icons.Rounded.SmartDisplay, ToolTint.ROSE, "Videos", "On this TV & downloads", "videos") { onTool(HomeTool.VIDEO) } }
            item { TvTile(Icons.Rounded.Groups, ToolTint.INDIGO, "Watch Party", "With phones nearby", "watch-party") { onTool(HomeTool.WATCH_PARTY) } }
            watching.firstOrNull()?.let { w ->
                item {
                    val left = if (w.durationMs > 0) " · ${((w.durationMs - w.positionMs) / 60_000).coerceAtLeast(1)} min left" else ""
                    TvTile(Icons.Rounded.PlayArrow, ToolTint.AMBER, w.title.ifBlank { "Video" }, "Carry on watching$left", "carry-on", wide = true) {
                        context.startActivity(VideoPlayerActivity.intent(context, w.uri, w.title, w.source))
                    }
                }
            }
        }

        // ---- Listen
        TvRowTitle("Listen")
        TvTiles("listen") {
            item { TvTile(Icons.Rounded.LibraryMusic, ToolTint.VIOLET, "Music", "Songs on this TV", "music") { onTool(HomeTool.MUSIC) } }
            if (radioAvailable) item { TvTile(Icons.Rounded.Radio, ToolTint.ORANGE, "Radio", "Live stations", "radio") { onTool(HomeTool.RADIO) } }
            item { TvTile(Icons.Rounded.Celebration, ToolTint.PINK, "Party", "Play with phones", "party") { onTool(HomeTool.PARTY) } }
            if (player?.item != null) {
                item {
                    TvTile(
                        Icons.Rounded.PlayArrow, ToolTint.VIOLET, player.title,
                        (if (player.isRadio) "Radio" else "Music") + if (player.isPlaying) " · playing" else " · paused", "playing", wide = true,
                    ) { onTool(if (player.isRadio) HomeTool.RADIO else HomeTool.MUSIC) }
                }
            }
        }

        // ---- SyncUp links
        if (links.isNotEmpty()) {
            TvRowTitle("SyncUp links", "Open in the browser")
            TvTiles("links") {
                items(links, key = { it.id }) { l ->
                    val sub = l.description?.trim()?.takeIf { it.isNotEmpty() }
                        ?: l.sourceName?.trim()?.takeIf { l.source == "partner" && it.isNotEmpty() }?.let { "From $it" }
                        ?: if (l.general) "For everyone" else "SyncUp"
                    TvTile(null, ToolTint.BLUE, l.title, sub, "link:${l.id}", leading = { com.agani.syncup.browser.LinkIcon(l, 44.dp) }) { onOpenLink(l) }
                }
            }
        }

        // ---- Websites: the most used first, then the ones SyncUp suggests
        val sites = remember(RecommendedSites.used, ShortcutCatalog.categories) {
            val used = RecommendedSites.used.take(4).map { it.title to it.url }
            val catalog = ShortcutCatalog.categories.flatMap { c -> c.items.map { it.title to it.url } }
            (used + catalog).distinctBy { it.second.trimEnd('/').lowercase() }.take(10)
        }
        if (sites.isNotEmpty()) {
            TvRowTitle("Websites", "Your most used, then the ones SyncUp suggests")
            TvTiles("sites") {
                items(sites, key = { it.second }) { (title, url) ->
                    TvTile(null, ToolTint.BLUE, title, null, "site:$url", compact = true, leading = { SiteMark(url, title, circle = 44.dp, iconSize = 26.dp) }) { onOpenUrl(url) }
                }
            }
        }

        // ---- This TV
        TvRowTitle("This TV")
        TvTiles("this-tv") {
            item { TvTile(Icons.Rounded.Settings, ToolTint.SKY, "Settings", "Account, look, about", "settings", onClick = onSettings) }
            item {
                TvTile(
                    Icons.Rounded.Person, ToolTint.BLUE, if (signedIn) "Account" else "Sign in",
                    if (signedIn) "${userName?.trim().orEmpty().ifBlank { "You" }} · signed in" else "Your links and tabs on this TV",
                    "account", onClick = onAccount,
                )
            }
        }

        // ---- the remote's keys
        Text(
            "▲ ▼ ◀ ▶  Move     OK  Open     BACK  Out",
            fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 28.dp),
        )
    }
    }
}

private const val FIRST_TILE = "channels"

/**
 * Where the remote was on the TV main page (user, 19 Nov 2026: "make it easy with a remote"): coming back from a
 * page puts it on the tile you opened, with the page and each row scrolled as you left them — not back on the first
 * tile every time. For this run of the app.
 */
private object TvFocus {
    var last: String? = null
    var page = 0
    val rows = mutableMapOf<String, Pair<Int, Int>>()
}

/** The page's tiles by name, so the remote can be put back on one. */
private val LocalTvTiles = staticCompositionLocalOf<MutableMap<String, FocusRequester>> { mutableMapOf() }

/** Names this tile ([spot]) so the remote can come back to it, and notes it as the last one while selected. */
@Composable
private fun Modifier.tvSpot(spot: String, focused: Boolean): Modifier {
    val tiles = LocalTvTiles.current
    val requester = remember(spot) { tiles.getOrPut(spot) { FocusRequester() } }
    LaunchedEffect(focused) { if (focused) TvFocus.last = spot }
    return focusRequester(requester)
}

private fun tvGreeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning,"
    in 12..16 -> "Good afternoon,"
    else -> "Good evening,"
}

@Composable
private fun TvClock() {
    val context = LocalContext.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(20_000)
        }
    }
    Text(DateFormat.getTimeFormat(context).format(now), fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TvRowTitle(title: String, sub: String? = null) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 26.dp, bottom = 12.dp)) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
        if (sub != null) Text("  $sub", fontSize = 14.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
    }
}

@Composable
private fun TvTiles(row: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    // Scrolled as it was left (see TvFocus), so the tile the remote comes back to is there.
    val saved = TvFocus.rows[row]
    val state = rememberLazyListState(saved?.first ?: 0, saved?.second ?: 0)
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }.collect { TvFocus.rows[row] = it }
    }
    // Room around the row so a tile growing under focus isn't cut off.
    LazyRow(
        state = state,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
        content = content,
    )
}

/**
 * One tile: a soft icon tile (or [leading]), a title and a line under it. Focused, it grows a little and gets a thick
 * ring in the accent colour, so it's clear from across the room where the remote is.
 */
@Composable
private fun TvTile(
    icon: ImageVector?,
    tint: ToolTint,
    title: String,
    sub: String?,
    spot: String,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    compact: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val grow by animateFloatAsState(if (focused) 1.07f else 1f, tween(150), label = "tv-tile")
    val shape = RoundedCornerShape(18.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .tvSpot(spot, focused)
            .graphicsLayer {
                scaleX = grow
                scaleY = grow
            }
            .width(if (wide) 300.dp else if (compact) 180.dp else 220.dp)
            .height(92.dp)
            .clip(shape)
            .background(if (focused) cs.primaryContainer else cs.surfaceContainerLowest)
            .border(if (focused) 3.dp else 1.dp, if (focused) cs.primary else cs.outlineVariant, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
    ) {
        if (leading != null) {
            leading()
        } else if (icon != null) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(tint.tile), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint.icon, modifier = Modifier.size(26.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium,
                color = if (focused) cs.onPrimaryContainer else cs.onSurface, maxLines = if (sub == null) 2 else 1, overflow = TextOverflow.Ellipsis,
            )
            if (sub != null) {
                Text(
                    sub, fontSize = 13.sp, lineHeight = 17.sp, color = if (focused) cs.onPrimaryContainer.copy(alpha = .8f) else cs.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** The Search pill at the top, focusable like a tile. */
@Composable
private fun TvPill(icon: ImageVector, label: String, spot: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .tvSpot(spot, focused)
            .height(48.dp)
            .width(240.dp)
            .clip(RoundedCornerShape(50))
            .background(if (focused) cs.primaryContainer else cs.surfaceContainerLowest)
            .border(if (focused) 3.dp else 1.dp, if (focused) cs.primary else cs.outlineVariant, RoundedCornerShape(50))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp),
    ) {
        Icon(icon, null, tint = if (focused) cs.onPrimaryContainer else cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 16.sp, color = if (focused) cs.onPrimaryContainer else cs.onSurfaceVariant)
    }
}

/** A round button at the top (the bell, the picture), with a focus ring. */
@Composable
private fun TvRound(
    onClick: () -> Unit,
    label: String,
    spot: String,
    background: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainerLowest,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .tvSpot(spot, focused)
            // The ring sits a little outside the circle: the picture's circle is the accent colour itself, and a
            // ring on it didn't show (19 Nov 2026).
            .size(58.dp)
            .border(3.dp, if (focused) cs.primary else androidx.compose.ui.graphics.Color.Transparent, CircleShape)
            .padding(5.dp)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, cs.outlineVariant, CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClickLabel = label, onClick = onClick),
    ) { content() }
}
