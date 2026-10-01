package com.agani.syncup.browser

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import com.agani.syncup.browser.ui.IconTile
import com.agani.syncup.browser.ui.SheetDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.browser.ui.EmptyState
import com.agani.syncup.browser.ui.SectionTheme
import com.agani.syncup.browser.ui.sectionIcon
import com.agani.syncup.browser.ui.sectionName
import com.agani.syncup.browser.ui.SyncUpMark
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.agani.syncup.data.OtherDevice
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Smartphone

private val SKETCH_COLORS = listOf(
    Color(0xFF1B4FD8), Color(0xFF16A085), Color(0xFFDC2626), Color(0xFF7C3AED),
    Color(0xFFEA580C), Color(0xFF0891B2), Color(0xFF4285F4), Color(0xFF374151),
)

private fun sketchColor(tab: BrowserTab): Color = when {
    tab.themeColor != null -> Color(tab.themeColor!!)
    tab.isHome -> Color(0xFF94A3B8)
    tab.isWork -> Color(0xFF0F766E)
    else -> SKETCH_COLORS[(UrlInput.hostAndPath(tab.url).first.hashCode() and 0x7fffffff) % SKETCH_COLORS.size]
}

/**
 * Full-screen tab switcher: section segments, a 2-column grid of cards with page previews (the
 * current tab gets a tinted header and a ring), a dashed "new" card, and Close all · + · Done.
 */
@Composable
internal fun TabSwitcher(
    tabs: TabManager,
    shown: Section,
    onShow: (Section) -> Unit,
    signedIn: Boolean,
    hasWork: Boolean,
    otherDevices: List<OtherDevice> = emptyList(),
    onOpenOther: (url: String) -> Unit = {},
    onSignIn: () -> Unit,
    onUndo: (message: String, undo: () -> Unit) -> Unit,
    onClose: () -> Unit,
) {
    // The SyncUp link whose pages sheet is open (its group id).
    var pagesOf by remember { mutableStateOf<Long?>(null) }
    val sections = buildList {
        add(Section.NORMAL)
        if (!signedIn || hasWork) add(Section.WORK)
        add(Section.INCOGNITO)
    }
    SectionTheme(shown) {
        val cs = MaterialTheme.colorScheme
        Surface(color = cs.surface, modifier = Modifier.fillMaxSize()) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().height(64.dp).padding(start = 12.dp, end = 4.dp),
                ) {
                    SectionSwitch(
                        sections = sections,
                        shown = shown,
                        count = { s -> tabs.cardsIn(s).count { !it.isHome || s == shown } },
                        locked = { s -> s == Section.WORK && !signedIn },
                        onPick = { s -> if (s == Section.WORK && !signedIn) onSignIn() else onShow(s) },
                        modifier = Modifier.weight(1f),
                    )
                    BarIcon(Icons.Rounded.Close, "Close tab switcher", tint = cs.onSurface, onClick = onClose)
                }

                // SyncUp: one card per link (its latest page); elsewhere one per tab.
                val list = tabs.cardsIn(shown)
                // One SyncUp link: one tab is all there can be, so nothing new to open on that side.
                val direct = tabs.directLink.takeIf { shown == Section.WORK }
                val onlyHome = list.all { it.isHome } && list.size <= 1
                val others = if (shown == Section.NORMAL) otherDevices.filter { !it.tabs.isNullOrEmpty() } else emptyList()
                Box(Modifier.weight(1f)) {
                    if (onlyHome && others.isEmpty()) {
                        // New Normal / Incognito tabs come from the + below; only SyncUp has its own
                        // way in (its link list).
                        val work = shown == Section.WORK
                        EmptyState(
                            if (shown == Section.NORMAL) Icons.Rounded.Tab else sectionIcon(shown),
                            if (shown == Section.INCOGNITO) "No incognito tabs" else "No open tabs",
                            when {
                                direct != null -> "Your SyncUp link isn't open"
                                shown == Section.WORK -> "Open a link from your SyncUp home"
                                shown == Section.INCOGNITO -> "Incognito tabs aren't saved. Tap + to open one."
                                else -> "Tap + to open a new tab"
                            },
                            action = if (direct != null) "Open ${direct.name}" else if (work) "SyncUp links" else null,
                            actionIcon = if (work) SyncUpMark else null,
                            onAction = if (work) {
                                {
                                    if (direct != null) tabs.switchTo(Section.WORK)
                                    else list.firstOrNull()?.let { tabs.select(it) } ?: tabs.newTab(shown)
                                    onClose()
                                }
                            } else null,
                        )
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(list, key = { if (shown == Section.WORK) "g${it.groupId}" else "t${it.id}" }) { t ->
                                val onScreen = tabs.activeTab(shown)
                                val link = shown == Section.WORK && !t.isHome
                                val pages = if (link) tabs.groupPages(t.groupId).size else 1
                                val current = shown == tabs.section && onScreen != null &&
                                    (onScreen.id == t.id || (link && onScreen.groupId == t.groupId))
                                TabCard(
                                    t, current, pages,
                                    onSelect = {
                                        tabs.select(t)
                                        onClose()
                                    },
                                    onCloseTab = {
                                        if (pages > 1) {
                                            // A link and all its pages, with Undo.
                                            val closed = tabs.closeGroup(t.groupId)
                                            onUndo("${t.workName ?: "SyncUp link"} closed (${closed.size} pages)") { tabs.reopen(closed) }
                                        } else {
                                            tabs.close(t)
                                        }
                                    },
                                    onPages = { pagesOf = t.groupId },
                                )
                            }
                            if (direct == null) item(key = "new") {
                                NewTabCard(if (shown == Section.WORK) "Open a SyncUp link" else "New tab") {
                                    val home = tabs.tabsIn(shown).firstOrNull { it.isHome }
                                    if (home != null) tabs.select(home) else tabs.newTab(shown)
                                    onClose()
                                }
                            }
                            if (others.isNotEmpty()) {
                                item(key = "others", span = { GridItemSpan(2) }) {
                                    OtherDevicesCard(others, onOpenOther)
                                }
                            }
                            if (shown == Section.WORK || shown == Section.INCOGNITO) {
                                item(key = "note", span = { GridItemSpan(2) }) {
                                    Text(
                                        if (shown == Section.WORK) "SyncUp tabs stay on this device and show link names, never addresses."
                                        else "Incognito tabs aren't kept and close with the app.",
                                        fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().height(72.dp).background(cs.surfaceContainer).padding(horizontal = 12.dp),
                ) {
                    val closable = list.any { !it.isHome }
                    // Nothing to close → no button (the + stays centred either way).
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (closable) {
                            TextButton(onClick = {
                                val closed = tabs.closeAll(shown)
                                val n = closed.size
                                if (n > 0) onUndo("$n tab${if (n == 1) "" else "s"} closed") { tabs.reopen(closed) }
                            }) { Text("Close all", color = if (shown == Section.INCOGNITO) cs.error else cs.primary) }
                        }
                    }
                    if (direct == null) {
                        Box(
                            Modifier.size(56.dp).clip(RoundedCornerShape(20.dp)).background(cs.primary).clickable {
                                tabs.newTab(shown)
                                onClose()
                            },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Add, if (shown == Section.INCOGNITO) "New incognito tab" else "New tab", tint = cs.onPrimary, modifier = Modifier.size(26.dp)) }
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        TextButton(onClick = {
                            if (shown != tabs.section) tabs.switchTo(shown)
                            onClose()
                        }) { Text("Done", fontWeight = FontWeight.Medium) }
                    }
                }
            }
        }
        pagesOf?.let { group ->
            LinkPagesSheet(
                tabs = tabs,
                groupId = group,
                onOpen = { page ->
                    pagesOf = null
                    tabs.select(page)
                    onClose()
                },
                onCloseLink = { name ->
                    pagesOf = null
                    val closed = tabs.closeGroup(group)
                    onUndo("$name closed (${closed.size} pages)") { tabs.reopen(closed) }
                },
                onDismiss = { pagesOf = null },
            )
        }
    }
}

/**
 * A SyncUp link's pages: its first page and the ones its site opened in new windows. Open one, close
 * one, or close the whole link. Pages beyond the latest few are asleep and reload when opened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkPagesSheet(
    tabs: TabManager,
    groupId: Long,
    onOpen: (BrowserTab) -> Unit,
    onCloseLink: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val pages = tabs.groupPages(groupId)
    if (pages.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val cs = MaterialTheme.colorScheme
    val head = pages.first()
    val name = head.workName ?: "SyncUp link"
    val onScreen = tabs.activeTab(Section.WORK)?.id
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp)) {
                IconTile(SyncUpMark, size = 48.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${pages.size} page${if (pages.size == 1) "" else "s"}", fontSize = 13.sp, color = cs.onSurfaceVariant)
                }
            }
            pages.forEach { p ->
                val on = p.id == onScreen
                val origin = if (p.id == head.id) {
                    "The link itself"
                } else {
                    tabs.tabs.firstOrNull { it.id == p.openerId }?.let { "Opened from ${it.title.ifBlank { it.label }}" } ?: "Opened by the site"
                }
                val state = when {
                    on -> "Showing now"
                    p.asleep -> "Asleep"
                    else -> null
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (on) cs.primaryContainer else Color.Transparent)
                        .clickable { onOpen(p) }
                        .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .alpha(if (p.asleep) .45f else 1f),
                    ) {
                        val thumb = p.thumbnail
                        if (thumb != null) {
                            Image(thumb, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
                        } else {
                            Box(Modifier.fillMaxWidth().height(12.dp).background(sketchColor(p)))
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            p.title.ifBlank { p.label }, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = when {
                                on -> cs.onPrimaryContainer
                                p.asleep -> cs.onSurfaceVariant
                                else -> cs.onSurface
                            },
                        )
                        Text(
                            listOfNotNull(state, origin).joinToString(" · "), fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, color = if (on) cs.onPrimaryContainer.copy(alpha = .8f) else cs.onSurfaceVariant,
                        )
                    }
                    Box(Modifier.size(44.dp).clip(CircleShape).clickable { tabs.close(p) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, "Close ${p.title.ifBlank { p.label }}", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
            }
            SheetDivider()
            TextButton(onClick = { onCloseLink(name) }, modifier = Modifier.padding(horizontal = 12.dp)) {
                Icon(Icons.Rounded.Close, null, tint = cs.error, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (pages.size == 1) "Close $name" else "Close $name and its ${pages.size} pages",
                    color = cs.error, fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun TabCard(
    t: BrowserTab,
    current: Boolean,
    pages: Int,
    onSelect: () -> Unit,
    onCloseTab: () -> Unit,
    onPages: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val color = sketchColor(t)
    val stacked = pages > 1
    // Every card keeps the same 10 dp above it so rows line up; a link with more pages shows two
    // pages stacked behind its card there.
    Box(Modifier.height(214.dp)) {
    if (stacked) {
        val top = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        Box(Modifier.align(Alignment.TopCenter).padding(horizontal = 16.dp).fillMaxWidth().height(24.dp).clip(top).background(cs.surfaceContainerHigh))
        Box(Modifier.align(Alignment.TopCenter).padding(start = 8.dp, end = 8.dp, top = 5.dp).fillMaxWidth().height(24.dp).clip(top).background(cs.surfaceContainerHighest))
    }
    Column(
        Modifier
            .padding(top = 10.dp)
            .height(204.dp)
            .clip(shape)
            .background(cs.surfaceContainerLow)
            .then(if (current) Modifier.border(2.5.dp, cs.primary, shape) else Modifier)
            .clickable(onClick = onSelect),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(if (current) cs.primaryContainer else Color.Transparent)
                .padding(start = 12.dp, end = 4.dp),
        ) {
            if (t.isWork) {
                Icon(SyncUpMark, null, tint = cs.primary, modifier = Modifier.size(18.dp))
            } else {
                Box(Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(color), contentAlignment = Alignment.Center) {
                    Text(
                        t.label.take(1).uppercase(), fontSize = 10.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold,
                        color = if (color.luminance() > .6f) Color(0xFF111111) else Color.White,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                t.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f), color = if (current) cs.onPrimaryContainer else cs.onSurface,
            )
            Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onCloseTab), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, if (stacked) "Close ${t.label} and its pages" else "Close tab", modifier = Modifier.size(18.dp), tint = cs.onSurfaceVariant)
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp, top = if (current) 8.dp else 0.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (t.isHome) cs.surfaceContainerLowest else Color.White),
        ) {
            val thumb = t.thumbnail
            when {
                thumb != null && !t.isHome -> Image(
                    thumb, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize(),
                )
                t.isHome -> HomeSketch(t.section)
                else -> PageSketch(t, color)
            }
            if (stacked) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(cs.primary)
                        .clickable(onClickLabel = "Show its pages", onClick = onPages)
                        .padding(start = 8.dp, end = 12.dp),
                ) {
                    Icon(Icons.Rounded.Layers, null, tint = cs.onPrimary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("$pages pages", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onPrimary)
                }
            }
        }
    }
    }
}

/** A quiet page sketch used until the tab has a real snapshot (e.g. restored after a restart). */
@Composable
private fun PageSketch(t: BrowserTab, color: Color) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(22.dp).background(color))
        Text(
            if (t.isMasked) t.workName ?: "SyncUp link" else t.title.ifBlank { UrlInput.hostAndPath(t.url).first },
            fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF111111), maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp),
        )
        listOf(.85f, .7f, .8f, .55f).forEach { w ->
            Box(Modifier.padding(start = 8.dp, top = 7.dp).fillMaxWidth(w).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFE5E7EB)))
        }
        Box(Modifier.padding(8.dp).fillMaxWidth().height(34.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFFE5E7EB)))
    }
}

@Composable
private fun HomeSketch(section: Section) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(10.dp))
        Box(Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(cs.primary), contentAlignment = Alignment.Center) {
            Icon(sectionIcon(section), null, tint = cs.onPrimary, modifier = Modifier.size(13.dp))
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(50)).background(cs.surfaceContainerHigh))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(4) { Box(Modifier.size(14.dp).clip(CircleShape).background(cs.surfaceContainerHigh)) }
        }
    }
}

@Composable
private fun NewTabCard(label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val outline = cs.outline
    Column(
        Modifier
            .height(204.dp)
            .clip(RoundedCornerShape(20.dp))
            .drawBehind {
                drawRoundRect(
                    color = outline,
                    style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
                    cornerRadius = CornerRadius(20.dp.toPx()),
                )
            }
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Add, null, tint = cs.onPrimaryContainer)
        }
        Spacer(Modifier.height(8.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant)
    }
}

/**
 * Normal · SyncUp · Incognito. The section on show gets the room — icon, name and tab count — and
 * the others shrink to icon pills (with a small count badge, or a lock when SyncUp needs sign-in).
 * Widths animate between the two, so switching reads as one pill sliding across.
 */
@Composable
private fun SectionSwitch(
    sections: List<Section>,
    shown: Section,
    count: (Section) -> Int,
    locked: (Section) -> Boolean,
    onPick: (Section) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val pad = 4.dp
    val gap = 4.dp
    val compact = 48.dp
    BoxWithConstraints(modifier.height(48.dp)) {
        val others = sections.size - 1
        val wide = (maxWidth - pad * 2 - compact * others - gap * others).coerceAtLeast(compact)
        Row(
            horizontalArrangement = Arrangement.spacedBy(gap),
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(50))
                .background(cs.surfaceContainer)
                .padding(pad)
                .selectableGroup(),
        ) {
            sections.forEach { s ->
                val sel = s == shown
                val width by animateDpAsState(if (sel) wide else compact, tween(260), label = "segment")
                SectionSegment(s, sel, locked(s), count(s), width) { onPick(s) }
            }
        }
    }
}

@Composable
private fun SectionSegment(section: Section, selected: Boolean, locked: Boolean, count: Int, width: Dp, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) cs.primaryContainer else Color.Transparent, tween(220), label = "segmentBg")
    val fg = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant
    val name = sectionName(section)
    val spoken = name + when {
        locked -> ", sign in to use"
        count > 0 -> ", $count tab${if (count == 1) "" else "s"}"
        else -> ""
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .clip(RoundedCornerShape(50))
            .background(bg)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
            Icon(sectionIcon(section), null, tint = fg, modifier = Modifier.size(20.dp))
            AnimatedVisibility(
                visible = selected,
                enter = fadeIn(tween(180, delayMillis = 90)) + expandHorizontally(tween(260), expandFrom = Alignment.Start),
                exit = fadeOut(tween(90)) + shrinkHorizontally(tween(260), shrinkTowards = Alignment.Start),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(8.dp))
                    Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (count > 0) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier.height(20.dp).clip(RoundedCornerShape(50)).background(cs.primary).padding(horizontal = 7.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("$count", fontSize = 11.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold, color = cs.onPrimary)
                        }
                    }
                }
            }
        }
        // Icon-only pills keep a hint of what's inside: a tab count in that section's own colour
        // (Normal blue, SyncUp teal, Incognito purple), or a lock when sign-in is needed.
        if (!selected && (locked || count > 0)) {
            val ring = cs.surfaceContainer
            SectionTheme(section) {
                val sc = MaterialTheme.colorScheme
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 2.dp, end = 2.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(if (locked) sc.surfaceContainerHighest else sc.primary)
                        .border(1.5.dp, ring, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (locked) Icon(Icons.Rounded.Lock, null, tint = sc.onSurfaceVariant, modifier = Modifier.size(10.dp))
                    else Text(if (count > 9) "9+" else "$count", fontSize = 10.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold, color = sc.onPrimary)
                }
            }
        }
    }
}

/** "From your other devices": the open Normal tabs of the user's other phones (browser sync). */
@Composable
private fun OtherDevicesCard(devices: List<OtherDevice>, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(cs.surfaceContainerLow)
            .padding(vertical = 8.dp),
    ) {
        Text(
            "From your other devices", fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = cs.onSurface,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        )
        devices.forEach { d ->
            var expanded by remember(d.deviceId) { mutableStateOf(false) }
            val all = d.tabs.orEmpty().filter { !it.url.isNullOrBlank() }
            val shown = if (expanded) all else all.take(3)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
            ) {
                Icon(Icons.Rounded.Smartphone, null, tint = cs.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "${d.deviceName ?: "Another device"} · ${all.size} tab${if (all.size == 1) "" else "s"}",
                    fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Text(relativeTime(d.updatedAt), fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
            shown.forEach { t ->
                val url = t.url.orEmpty()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(url) }
                        .padding(start = 42.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            t.title?.takeIf { it.isNotBlank() } ?: UrlInput.display(url),
                            fontSize = 14.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(UrlInput.hostAndPath(url).first, fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
            if (all.size > 3) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(start = 42.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                ) {
                    Text(
                        if (expanded) "Show less" else "Show all ${all.size}",
                        fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.primary,
                    )
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = cs.primary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** "5 min ago" from the server's ISO time (empty when it can't be read). */
private fun relativeTime(iso: String?): String {
    return relativeTime(parseIsoMs(iso) ?: return "")
}

/** "Just now" / "5 min ago" — clocks differ a little between phones and the server. */
internal fun relativeTime(ms: Long): String {
    val now = System.currentTimeMillis()
    if (now - ms < 60_000) return "Just now"
    return android.text.format.DateUtils.getRelativeTimeSpanString(
        ms, now, android.text.format.DateUtils.MINUTE_IN_MILLIS,
    ).toString()
}

/** Epoch ms from a server time like "2026-09-29T10:15:30.123456+00:00" (java.time needs API 26). */
internal fun parseIsoMs(iso: String?): Long? = runCatching {
    val text = iso ?: return null
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
    var ms = fmt.parse(text.take(19))!!.time
    Regex("""([+-])(\d{2}):(\d{2})$""").find(text)?.let { m ->
        val offset = (m.groupValues[2].toInt() * 60 + m.groupValues[3].toInt()) * 60_000L
        ms -= if (m.groupValues[1] == "+") offset else -offset
    }
    ms
}.getOrNull()
