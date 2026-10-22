package com.agani.syncup.browser

import com.agani.syncup.browser.ui.SyncUpMark
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.Avatar
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.browser.ui.EmptyState
import com.agani.syncup.data.UrlItem

/**
 * Links grouped by where they come from: each partner first, then "Apps For You" (the admin's for
 * this user), then "For everyone" (the admin's general links — all a signed-out phone has).
 */
private class LinkGroup(val name: String, val partner: Boolean, val items: List<UrlItem>, val general: Boolean = false)

private fun groupLinks(links: List<UrlItem>): List<LinkGroup> {
    val general = links.filter { it.general }
    val partner = links.filter { !it.general && it.source == "partner" && !it.sourceName.isNullOrBlank() }
    val admin = links - partner.toSet() - general.toSet()
    return partner.groupBy { it.sourceName!! }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
        .map { (name, items) -> LinkGroup(name, true, items) } +
        listOfNotNull(
            admin.takeIf { it.isNotEmpty() }?.let { LinkGroup("Apps For You", false, it) },
            general.takeIf { it.isNotEmpty() }?.let { LinkGroup("For everyone", false, it, general = true) },
        )
}

/**
 * Work home: no address bar and no way to add links — every link comes from the admin or a
 * partner. Search filters names; pull down to fetch the latest links. Signed out, it shows the
 * admin's links for everyone, with the sign-in icon where the avatar goes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkHome(
    account: BrowserAccount,
    openCount: (UrlItem) -> Int,
    onOpen: (UrlItem) -> Unit,
    onAvatar: () -> Unit,
    onRefresh: () -> Unit,
    onNotifications: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val user = account.user
    var query by remember { mutableStateOf("") }
    val filtered = account.links.filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
    val groups = groupLinks(filtered)
    val sources = groupLinks(account.links).size

    PullToRefreshBox(isRefreshing = account.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().height(68.dp).padding(start = 4.dp, top = 8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("SyncUp", fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                    Text(
                        if (query.isBlank()) {
                            "${account.links.size} link${if (account.links.size == 1) "" else "s"} from $sources source${if (sources == 1) "" else "s"}"
                        } else {
                            "${filtered.size} of ${account.links.size} links match"
                        },
                        fontSize = 13.sp, lineHeight = 18.sp, color = cs.onSurfaceVariant,
                    )
                }
                Box(Modifier.offset(x = 4.dp)) { com.agani.syncup.notifications.NotificationBell(onNotifications) }
                Box(Modifier.offset(x = 4.dp)) {
                    if (user != null) Avatar(user, unread = account.chatUnread > 0, size = 36.dp, onClick = onAvatar)
                    else BarIcon(Icons.Rounded.AccountCircle, "Sign in to SyncUp", tint = cs.onSurfaceVariant, onClick = onAvatar)
                }
            }
            Spacer(Modifier.height(8.dp))
            SearchCardField(query, { query = it }, "Search SyncUp links", Modifier.fillMaxWidth())

            when {
                account.links.isEmpty() -> Box(Modifier.fillMaxWidth().height(360.dp)) {
                    EmptyState(
                        SyncUpMark,
                        if (account.refreshing) "Loading your links…" else "No SyncUp links yet",
                        "Links from your admin and partners show up here. Pull down to refresh.",
                    )
                }
                filtered.isEmpty() -> Text(
                    "No links match \"${query.trim()}\"", fontSize = 14.sp, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 20.dp),
                )
            }

            // Each source's links sit in one card: names and descriptions, never addresses.
            groups.forEach { g ->
                SourceHeader(
                    g.name,
                    count = "${if (g.partner) "Partner" else "Admin"} · ${g.items.size} link${if (g.items.size == 1) "" else "s"}",
                    mark = when {
                        g.partner -> Icons.Rounded.Handshake
                        g.general -> Icons.Rounded.Public
                        else -> Icons.Rounded.Sync
                    },
                )
                Column(Modifier.fillMaxWidth().browserCard()) {
                    g.items.forEachIndexed { i, item ->
                        if (i > 0) CardDivider()
                        LinkRow(item, openCount(item)) { onOpen(item) }
                    }
                }
            }
        }
    }
}

/** A source's heading: its soft mark, its name and how many links it gives. */
@Composable
private fun SourceHeader(name: String, count: String, mark: ImageVector) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 10.dp),
    ) {
        Box(Modifier.size(26.dp).clip(RoundedCornerShape(9.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(mark, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(count, fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
    }
}

/** One link: its icon, name and description; a soft "Open" pill while it has a tab. */
@Composable
private fun LinkRow(item: UrlItem, open: Int, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        LinkIcon(item, 42.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, fontSize = 15.sp, lineHeight = 20.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            item.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (open > 0) {
            Spacer(Modifier.width(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(cs.primaryContainer).padding(horizontal = 11.dp, vertical = 5.dp),
            ) {
                Icon(if (open > 1) Icons.Rounded.Layers else Icons.Rounded.Tab, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text(if (open > 1) "$open pages" else "Open", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onPrimaryContainer, maxLines = 1)
            }
        }
    }
}
