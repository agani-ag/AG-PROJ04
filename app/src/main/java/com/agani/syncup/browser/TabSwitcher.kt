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
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
    signedIn: Boolean,
    hasWork: Boolean,
    otherDevices: List<OtherDevice> = emptyList(),
    onOpenOther: (url: String) -> Unit = {},
    onSignIn: () -> Unit,
    onUndo: (message: String, undo: () -> Unit) -> Unit,
    onClose: () -> Unit,
) {
    var shown by remember { mutableStateOf(tabs.section) }
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
                    Row(
                        Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(50)).background(cs.surfaceContainer).padding(3.dp),
                    ) {
                        sections.forEach { s ->
                            val sel = s == shown
                            val locked = s == Section.WORK && !signedIn
                            val count = tabs.tabsIn(s).count { !it.isHome || s == shown }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(50))
                                    .background(if (sel) cs.primaryContainer else Color.Transparent)
                                    .clickable { if (locked) onSignIn() else shown = s }
                                    .alpha(if (locked) .6f else 1f),
                            ) {
                                Icon(
                                    if (locked) Icons.Rounded.Lock else sectionIcon(s), null, modifier = Modifier.size(16.dp),
                                    tint = if (sel) cs.onPrimaryContainer else cs.onSurfaceVariant,
                                )
                                Text(
                                    (if (s == Section.INCOGNITO && !sel) "Incog." else sectionName(s)) + if (count > 0 && !locked) " $count" else "",
                                    fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                                    color = if (sel) cs.onPrimaryContainer else cs.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    BarIcon(Icons.Rounded.Close, "Close tab switcher", tint = cs.onSurface, onClick = onClose)
                }

                val list = tabs.tabsIn(shown)
                val onlyHome = list.all { it.isHome } && list.size <= 1
                val others = if (shown == Section.NORMAL) otherDevices.filter { !it.tabs.isNullOrEmpty() } else emptyList()
                Box(Modifier.weight(1f)) {
                    if (onlyHome && others.isEmpty()) {
                        EmptyState(
                            Icons.Rounded.Tab, "No open tabs",
                            if (shown == Section.WORK) "Open a link from your Work home" else "Your tabs will show here",
                            action = if (shown == Section.WORK) "Work links" else "New tab",
                            actionIcon = if (shown == Section.WORK) Icons.Rounded.Work else Icons.Rounded.Add,
                            onAction = {
                                list.firstOrNull()?.let { tabs.select(it) } ?: tabs.newTab(shown)
                                onClose()
                            },
                        )
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(list, key = { it.id }) { t ->
                                val current = t.id == tabs.activeTab(shown)?.id && shown == tabs.section
                                TabCard(
                                    t, current,
                                    onSelect = {
                                        tabs.select(t)
                                        onClose()
                                    },
                                    onCloseTab = { tabs.close(t) },
                                )
                            }
                            item(key = "new") {
                                NewTabCard(if (shown == Section.WORK) "Open a work link" else "New tab") {
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
                                        if (shown == Section.WORK) "Work tabs stay on this device and show link names, never addresses."
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
                    TextButton(
                        enabled = closable,
                        onClick = {
                            val closed = tabs.closeAll(shown)
                            val n = closed.size
                            if (n > 0) onUndo("$n tab${if (n == 1) "" else "s"} closed") { tabs.reopen(closed) }
                        },
                    ) { Text("Close all", color = if (!closable) cs.onSurface.copy(alpha = .38f) else if (shown == Section.INCOGNITO) cs.error else cs.primary) }
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.size(56.dp).clip(RoundedCornerShape(20.dp)).background(cs.primary).clickable {
                            tabs.newTab(shown)
                            onClose()
                        },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Add, "New tab", tint = cs.onPrimary, modifier = Modifier.size(26.dp)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        if (shown != tabs.section) tabs.switchTo(shown)
                        onClose()
                    }) { Text("Done", fontWeight = FontWeight.Medium) }
                }
            }
        }
    }
}

@Composable
private fun TabCard(t: BrowserTab, current: Boolean, onSelect: () -> Unit, onCloseTab: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val color = sketchColor(t)
    Column(
        Modifier
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
                Icon(Icons.Rounded.Work, null, tint = cs.primary, modifier = Modifier.size(18.dp))
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
                Icon(Icons.Rounded.Close, "Close tab", modifier = Modifier.size(18.dp), tint = cs.onSurfaceVariant)
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
        }
    }
}

/** A quiet page sketch used until the tab has a real snapshot (e.g. restored after a restart). */
@Composable
private fun PageSketch(t: BrowserTab, color: Color) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(22.dp).background(color))
        Text(
            if (t.isMasked) t.workName ?: "Work link" else t.title.ifBlank { UrlInput.hostAndPath(t.url).first },
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
