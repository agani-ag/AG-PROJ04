package com.agani.syncup.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A tab group's pages, one tap apart (user, 17 Nov 2026: "10 pages in a group are hard to move between"). It
 * shows above the bottom bar whenever the page on screen is in a group of two or more: every page as its icon
 * and title, the one on screen highlighted and kept in view. Tap one to go there. The button at the end opens
 * the group's full page list (close a page, or the whole group).
 */
@Composable
internal fun GroupStrip(
    pages: List<BrowserTab>,
    current: BrowserTab,
    onPick: (BrowserTab) -> Unit,
    onAll: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val list = rememberLazyListState()
    val index = pages.indexOfFirst { it.id == current.id }
    // The page on screen stays in view, with its left neighbour showing, as pages open and close.
    LaunchedEffect(current.id, pages.size) {
        if (index >= 0) list.animateScrollToItem((index - 1).coerceAtLeast(0))
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(cs.bar).height(46.dp),
    ) {
        LazyRow(
            state = list,
            contentPadding = PaddingValues(start = 10.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f),
        ) {
            itemsIndexed(pages, key = { _, p -> p.id }) { _, p ->
                PageChip(p, on = p.id == current.id) { onPick(p) }
            }
        }
        // The whole group: every page in a list, with Close.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(end = 6.dp).size(40.dp).clip(CircleShape).clickable(onClickLabel = "All ${pages.size} pages in this group", onClick = onAll),
        ) {
            Icon(Icons.Rounded.Layers, "All ${pages.size} pages in this group", tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Text(
                "${pages.size}", fontSize = 9.5.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold, color = cs.onPrimary,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 3.dp).clip(RoundedCornerShape(50))
                    .background(cs.primary).padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

/** One page of the group: its site's icon and its title; the page on screen is tinted. Asleep pages are dimmed. */
@Composable
private fun PageChip(page: BrowserTab, on: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(50))
            .background(if (on) cs.primaryContainer else cs.field)
            .clickable(onClickLabel = page.title.ifBlank { page.label }, onClick = onClick)
            .padding(start = 6.dp, end = 10.dp)
            .alpha(if (page.asleep && !on) .6f else 1f),
    ) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(cs.surface), contentAlignment = Alignment.Center) {
            SiteMark(page.url, page.title, circle = 20.dp, iconSize = 13.dp)
        }
        Spacer(Modifier.width(6.dp))
        Text(
            page.title.ifBlank { page.label }, fontSize = 12.5.sp, lineHeight = 16.sp,
            fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
            color = if (on) cs.onPrimaryContainer else cs.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 120.dp),
        )
    }
}
