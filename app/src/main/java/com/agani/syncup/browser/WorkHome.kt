package com.agani.syncup.browser

import com.agani.syncup.browser.ui.SyncUpMark
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.Avatar
import com.agani.syncup.browser.ui.EmptyState
import com.agani.syncup.browser.ui.GroupHeader
import com.agani.syncup.browser.ui.IconTile
import com.agani.syncup.browser.ui.PillButton
import com.agani.syncup.browser.ui.StatusChip
import com.agani.syncup.browser.ui.SyncPill
import com.agani.syncup.browser.ui.TonalRow
import com.agani.syncup.data.UrlItem

/** Links grouped by where they come from: each partner first, then "Apps For You" (the admin). */
private class LinkGroup(val name: String, val partner: Boolean, val items: List<UrlItem>)

private fun groupLinks(links: List<UrlItem>): List<LinkGroup> {
    val partner = links.filter { it.source == "partner" && !it.sourceName.isNullOrBlank() }
    val admin = links - partner.toSet()
    return partner.groupBy { it.sourceName!! }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
        .map { (name, items) -> LinkGroup(name, true, items) } +
        listOfNotNull(admin.takeIf { it.isNotEmpty() }?.let { LinkGroup("Apps For You", false, it) })
}

/**
 * Work home: no address bar and no way to add links — every link comes from the admin or a
 * partner. Search filters names; pull down to fetch the latest links.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkHome(
    account: BrowserAccount,
    openCount: (UrlItem) -> Int,
    onOpen: (UrlItem) -> Unit,
    onAvatar: () -> Unit,
    onRefresh: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val user = account.user ?: return
    var query by remember { mutableStateOf("") }
    val filtered = account.links.filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
    val groups = groupLinks(filtered)
    val sources = groupLinks(account.links).size

    PullToRefreshBox(isRefreshing = account.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().height(72.dp).padding(start = 20.dp, end = 16.dp, top = 8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("SyncUp", fontSize = 24.sp, lineHeight = 32.sp, color = cs.onSurface)
                    Text(
                        if (query.isBlank()) {
                            "${account.links.size} link${if (account.links.size == 1) "" else "s"} from $sources source${if (sources == 1) "" else "s"}"
                        } else {
                            "${filtered.size} of ${account.links.size} links match"
                        },
                        fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant,
                    )
                }
                Box(Modifier.offset(x = 4.dp)) { Avatar(user, unread = account.partnersWaiting > 0, onClick = onAvatar) }
            }
            SyncPill(modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp), height = 48.dp, focused = query.isNotEmpty()) {
                Icon(Icons.Rounded.Search, null, tint = cs.onSurfaceVariant)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, color = cs.onSurface),
                    cursorBrush = SolidColor(cs.primary),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) Text("Search SyncUp links", color = cs.onSurfaceVariant, fontSize = 15.sp)
                            inner()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                if (query.isNotEmpty()) PillButton(Icons.Rounded.Close, "Clear", size = 40.dp) { query = "" }
            }

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
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                )
            }

            groups.forEach { g ->
                GroupHeader(
                    g.name,
                    count = "${if (g.partner) "Partner" else "Admin"} · ${g.items.size} link${if (g.items.size == 1) "" else "s"}",
                    mark = if (g.partner) Icons.Rounded.Handshake else Icons.Rounded.Sync,
                )
                g.items.forEach { item ->
                    val open = openCount(item)
                    TonalRow(
                        title = item.title,
                        subtitle = item.description?.takeIf { it.isNotBlank() },
                        leading = { IconTile(iconFor(item)) },
                        trailing = when {
                            open > 1 -> ({ StatusChip("$open pages", Icons.Rounded.Layers) })
                            open == 1 -> ({ StatusChip("Open", Icons.Rounded.Tab) })
                            else -> null
                        },
                        onClick = { onOpen(item) },
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
