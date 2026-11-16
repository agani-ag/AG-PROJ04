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
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NoPhotography
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.foundation.border
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.delay
import com.agani.syncup.R
import com.agani.syncup.browser.ui.Avatar
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.browser.ui.PillButton
import com.agani.syncup.browser.ui.SectionTheme
import java.util.Calendar

// The Normal start page is SyncUp's main page now (home/MainPage.kt, user 14 Nov 2026); the Incognito
// one stays here.

/** Tiles four to a row. */
@Composable
internal fun TileRows(cells: List<@Composable RowScope.() -> Unit>) {
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
                Icon(Icons.Rounded.VisibilityOff, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text("You've gone incognito", fontSize = 24.sp, lineHeight = 32.sp, color = cs.onSurface, textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            IncognitoPoint(Icons.Rounded.History, "Pages you view won't be kept in history")
            IncognitoPoint(Icons.Rounded.Cookie, "Cookies and site data are deleted when you close all incognito tabs")
            IncognitoPoint(Icons.Rounded.Download, "Downloads and bookmarks are kept")
            IncognitoPoint(Icons.Rounded.NoPhotography, "Screenshots are blocked")
            Spacer(Modifier.height(16.dp))
            InfoNote("SyncUp links, Chat and Radio aren't available in Incognito")
            Spacer(Modifier.height(24.dp))
        }
        // The search box stays at the bottom, near the thumb.
        SearchCard(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), onClick = onSearch) {
            Text("Search privately", fontSize = 15.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1)
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
