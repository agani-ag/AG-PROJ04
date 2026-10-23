package com.agani.syncup.home

import com.agani.syncup.ui.theme.CardShape
import com.agani.syncup.ui.theme.appCard
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.LibraryPage
import com.agani.syncup.browser.ui.BadgeTextStyle
import com.agani.syncup.data.User

/**
 * You: the user's own things in one place — the account (or "Sign in"), notifications, chat and
 * partners, bookmarks / history / downloads, Home's cards, and Settings.
 */
@Composable
fun YouScreen(
    user: User?,
    chatEnabled: Boolean,
    chatUnread: Int,
    hasPartners: Boolean,
    partnersWaiting: Int,
    appVersion: String,
    onSignIn: () -> Unit,
    onNotifications: () -> Unit,
    onChat: () -> Unit,
    onPartners: () -> Unit,
    onLibrary: (LibraryPage) -> Unit,
    onCustomise: () -> Unit,
    onSettings: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
    ) {
        item(key = "top") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(top = 6.dp, bottom = 4.dp)) {
                Text("You", fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.padding(start = 4.dp))
            }
        }
        item(key = "account") {
            Group {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = if (user == null) onSignIn else onSettings).padding(16.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(56.dp).clip(CircleShape).background(if (user != null) cs.primary else cs.surfaceContainer),
                    ) {
                        if (user != null) Text(user.name.trim().take(1).uppercase().ifBlank { "?" }, color = cs.onPrimary, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                        else Icon(Icons.Rounded.Person, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        if (user != null) {
                            Text(user.name, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(user.loginLabel, fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                        } else {
                            Text("Sign in to SyncUp", fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = cs.primary)
                            Text("Optional — your tabs, to-dos and forest on every phone", fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
        }
        item(key = "mine") {
            Spacer(Modifier.height(12.dp))
            Group {
                val n = com.agani.syncup.notifications.Inbox.newCount
                Item(Icons.Rounded.Notifications, "Notifications", badge = if (n > 0) "$n new" else null, onClick = onNotifications)
                if (user != null && chatEnabled) {
                    Line()
                    Item(Icons.AutoMirrored.Rounded.Chat, "Chat", badge = if (chatUnread > 0) "$chatUnread" else null, onClick = onChat)
                }
                if (user != null && hasPartners) {
                    Line()
                    Item(Icons.Rounded.Handshake, "Partners", badge = if (partnersWaiting > 0) "$partnersWaiting new" else null, onClick = onPartners)
                }
            }
        }
        item(key = "library") {
            Spacer(Modifier.height(12.dp))
            Group {
                Item(Icons.Rounded.Bookmarks, "Bookmarks") { onLibrary(LibraryPage.BOOKMARKS) }
                Line()
                Item(Icons.Rounded.History, "History") { onLibrary(LibraryPage.HISTORY) }
                Line()
                val running = com.agani.syncup.downloads.Downloads.tasks.count { it.state == com.agani.syncup.downloads.DlState.RUNNING }
                Item(Icons.Rounded.Download, "Downloads", subtitle = if (running > 0) "$running downloading" else null) { onLibrary(LibraryPage.DOWNLOADS) }
            }
        }
        item(key = "app") {
            Spacer(Modifier.height(12.dp))
            Group {
                Item(Icons.Rounded.Tune, "Customise Home", subtitle = "Open to Home or Browse · which cards show", onClick = onCustomise)
                Line()
                Item(Icons.Rounded.Settings, "Settings", subtitle = "Sync, app lock, appearance, browser, notifications", onClick = onSettings)
            }
        }
        item(key = "version") {
            Text("SyncUp $appVersion", fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 14.dp, start = 4.dp))
        }
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().appCard(CardShape)) { content() }
}

@Composable
private fun Line() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

/** A row: grey icon, title (and a line under it), a red badge, a chevron. */
@Composable
private fun Item(icon: ImageVector, title: String, subtitle: String? = null, badge: String? = null, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(icon, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, lineHeight = 20.sp, color = cs.onSurface)
            if (subtitle != null) Text(subtitle, fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        if (badge != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(start = 8.dp, end = 6.dp).heightIn(min = 20.dp).clip(RoundedCornerShape(50)).background(cs.error).padding(horizontal = 7.dp),
            ) {
                Text(badge, style = BadgeTextStyle, fontSize = 11.5.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, color = cs.onError)
            }
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}
