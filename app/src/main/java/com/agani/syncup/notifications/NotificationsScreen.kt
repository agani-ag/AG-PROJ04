package com.agani.syncup.notifications

import com.agani.syncup.ui.theme.appCard
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.agani.syncup.browser.dayLabel
import com.agani.syncup.browser.parseIsoMs
import com.agani.syncup.browser.ui.BadgeTextStyle
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.browser.ui.EmptyState
import com.agani.syncup.data.NotificationItem
import com.agani.syncup.home.ToolTint
import com.agani.syncup.ui.theme.CardShape
import com.agani.syncup.ui.theme.PillTab
import com.agani.syncup.ui.theme.PillTabs
import kotlinx.coroutines.launch

private enum class Tab(val key: String) { NEW("new"), READ("read") }

/**
 * Notifications: every push this user (or, signed out, this phone) got — New (not opened yet) and
 * Read. Tapping one marks it read and opens what its push opens ([onOpen]: the link, Partners…).
 * See [Inbox].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(onBack: () -> Unit, onOpen: (NotificationItem) -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(Tab.NEW) }
    val items = remember { mutableStateListOf<NotificationItem>() }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()

    // (Re)load the open tab from the top.
    LaunchedEffect(tab, version) {
        error = null
        if (!refreshing) loading = true
        runCatching { Inbox.page(context, tab.key, before = null) }
            .onSuccess {
                items.clear()
                items.addAll(it.items.orEmpty())
                hasMore = it.hasMore
            }
            .onFailure { error = it.message ?: "Couldn't load notifications" }
        loading = false
        refreshing = false
        // Back to the top — only with something to show: scrollToItem waits for the list to be on
        // screen, and an empty tab never puts it there (the spinner then never went away).
        if (items.isNotEmpty()) listState.scrollToItem(0)
    }

    // Near the end of the list: the next page.
    val nearEnd by remember {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= listState.layoutInfo.totalItemsCount - 3 } == true }
    }
    LaunchedEffect(nearEnd, hasMore, loadingMore) {
        if (nearEnd && hasMore && !loadingMore && items.isNotEmpty()) {
            loadingMore = true
            val forTab = tab
            runCatching { Inbox.page(context, forTab.key, before = items.last().id) }.onSuccess {
                if (forTab == tab) {
                    val have = items.map { i -> i.id }.toSet()
                    items.addAll(it.items.orEmpty().filter { i -> i.id !in have })
                    hasMore = it.hasMore
                }
            }
            loadingMore = false
        }
    }

    fun open(item: NotificationItem) {
        if (!item.read) {
            // Read now: off the New list (and the phone's notification panel) at once, the server
            // catches up behind.
            if (tab == Tab.NEW) items.remove(item)
            PushTray.clear(context, listOf(item.id))
            Inbox.setCount(Inbox.newCount - 1)
            scope.launch { runCatching { Inbox.markRead(context, ids = listOf(item.id)) } }
        }
        onOpen(item)
    }

    Column(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 4.dp, vertical = 4.dp)) {
            BarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
            Text(
                "Notifications", fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium, color = cs.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            TextButton(
                enabled = tab == Tab.NEW && Inbox.newCount > 0,
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.padding(end = 4.dp).height(36.dp),
                onClick = {
                    items.clear()
                    PushTray.clearAll(context)
                    Inbox.setCount(0)
                    scope.launch { runCatching { Inbox.markRead(context, all = true) } }
                },
            ) { Text("Mark all read", fontSize = 13.5.sp, fontWeight = FontWeight.Medium) }
        }
        // New / Read: the pill switch; New carries how many are waiting.
        val newCount = Inbox.newCount
        PillTabs(
            listOf(PillTab("New", count = newCount), PillTab("Read")),
            selected = tab.ordinal,
            onSelect = { tab = Tab.entries[it] },
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 4.dp),
        )

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; version++ },
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                loading && items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error != null && items.isEmpty() -> EmptyState(
                    Icons.Rounded.CloudOff, "Couldn't load notifications", error, action = "Try again",
                    actionIcon = Icons.Rounded.Refresh, onAction = { version++ },
                )
                items.isEmpty() && tab == Tab.NEW -> EmptyState(
                    Icons.Rounded.CheckCircle, "You're all caught up",
                    "New notifications from SyncUp and your partners show up here.",
                    action = "See read notifications", onAction = { tab = Tab.READ },
                )
                items.isEmpty() -> EmptyState(
                    Icons.Rounded.NotificationsNone, "Nothing read yet", "Notifications you open move here.",
                )
                else -> LazyColumn(state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                    // Grouped by day, newest first. New ones each sit on their own card; read ones share
                    // one card a day (rows with lines between them), so a long list stays light.
                    val days = ArrayList<Pair<String, MutableList<Pair<NotificationItem, Long?>>>>()
                    items.forEach { n ->
                        val ms = parseIsoMs(n.sentAt)
                        val day = ms?.let(::dayLabel).orEmpty()
                        if (days.lastOrNull()?.first != day) days.add(day to mutableListOf())
                        days.last().second.add(n to ms)
                    }
                    days.forEach { (day, list) ->
                        val firstId = list.first().first.id
                        item(key = "day-$firstId") {
                            Text(
                                day, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant,
                                modifier = Modifier.padding(start = 6.dp, top = 16.dp, bottom = 8.dp),
                            )
                        }
                        if (tab == Tab.NEW) {
                            list.forEach { (n, ms) ->
                                item(key = n.id) {
                                    NotificationRow(n, ms, isNew = true, modifier = Modifier.padding(bottom = 10.dp).appCard(CardShape)) { open(n) }
                                }
                            }
                        } else {
                            item(key = "read-$firstId") {
                                Column(Modifier.fillMaxWidth().appCard(CardShape)) {
                                    list.forEachIndexed { i, (n, ms) ->
                                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(cs.outlineVariant))
                                        NotificationRow(n, ms, isNew = false) { open(n) }
                                    }
                                }
                            }
                        }
                    }
                    if (loadingMore) {
                        item(key = "more") { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
                    }
                    if (tab == Tab.READ && !hasMore) {
                        item(key = "end") {
                            Text(
                                "Old notifications are removed automatically.", fontSize = 12.5.sp, color = cs.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(top = 18.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One notification: who sent it, the title (a blue dot while new), the text, then sender · time and Open. */
@Composable
private fun NotificationRow(item: NotificationItem, sentMs: Long?, isNew: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().then(modifier).clickable(onClick = onClick).padding(14.dp)) {
        Leading(item)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    item.title, fontSize = 15.sp, lineHeight = 20.sp, color = cs.onSurface,
                    fontWeight = if (isNew) FontWeight.Medium else FontWeight.Normal, modifier = Modifier.weight(1f),
                )
                if (isNew) {
                    Box(Modifier.padding(start = 8.dp, top = 6.dp).size(9.dp).clip(CircleShape).background(cs.primary)
                        .semantics { contentDescription = "New" })
                }
            }
            if (item.body.isNotBlank()) {
                Text(
                    item.body, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (isNew) cs.onSurface.copy(alpha = .82f) else cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (!item.image.isNullOrBlank()) {
                AsyncImage(
                    model = item.image, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth().aspectRatio(2f).clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainer),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Text(
                    listOfNotNull(item.sender.takeIf { it.isNotBlank() }, sentMs?.let(::timeLabel)).joinToString(" · "),
                    fontSize = 12.5.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (!item.linkUrl.isNullOrBlank()) {
                    Text("Open", fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = cs.primary)
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = cs.primary, modifier = Modifier.padding(start = 3.dp).size(15.dp))
                }
            }
        }
    }
}

/** Who sent it, as a coloured tile: SyncUp, a partner / website, a reminder, "a partner added you". */
@Composable
private fun Leading(item: NotificationItem) {
    val tint = when (item.kind) {
        "partner", "site" -> ToolTint.GREEN
        "reminder" -> ToolTint.AMBER
        "partners" -> ToolTint.VIOLET
        else -> ToolTint.BLUE
    }
    val fg = tint.icon
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(tint.tile), contentAlignment = Alignment.Center) {
        when (item.kind) {
            "partner" -> Text(item.sender.take(1).uppercase().ifBlank { "P" }, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = fg)
            "site" -> Icon(Icons.Rounded.Language, null, tint = fg, modifier = Modifier.size(24.dp))
            "reminder" -> Icon(Icons.Rounded.Alarm, null, tint = fg, modifier = Modifier.size(24.dp))
            "partners" -> Icon(Icons.Rounded.Groups, null, tint = fg, modifier = Modifier.size(24.dp))
            else -> Icon(Icons.Rounded.Campaign, null, tint = fg, modifier = Modifier.size(24.dp))
        }
    }
}

/** "10:42 AM" today, "Thu, 3:30 PM" this week, "1 Oct, 9:00 AM" before. */
private fun timeLabel(ms: Long): String {
    val now = System.currentTimeMillis()
    val pattern = when {
        android.text.format.DateUtils.isToday(ms) -> "h:mm a"
        now - ms < 6L * 24 * 3600 * 1000 -> "EEE, h:mm a"
        else -> "d MMM, h:mm a"
    }
    return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date(ms))
}

/** The bell on the home pages: opens Notifications; the red number is how many are new. */
@Composable
fun NotificationBell(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val count = Inbox.newCount
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick)
            .semantics { contentDescription = if (count > 0) "Notifications, $count new" else "Notifications" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (count > 0) Icons.Rounded.Notifications else Icons.Rounded.NotificationsNone, null, tint = cs.onSurfaceVariant)
        if (count > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 4.dp).heightIn(min = 18.dp).widthIn(min = 18.dp)
                    .clip(RoundedCornerShape(9.dp)).background(cs.error).padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (count > 99) "99+" else "$count", style = BadgeTextStyle, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Bold, color = cs.onError)
            }
        }
    }
}
