package com.agani.syncup.browser

import com.agani.syncup.browser.ui.SyncUpMark
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FindInPage
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.EmptyState
import com.agani.syncup.browser.ui.accentOn
import com.agani.syncup.browser.ui.IconTile
import com.agani.syncup.browser.ui.MenuRow
import com.agani.syncup.browser.ui.SectionTheme
import com.agani.syncup.browser.ui.SheetDivider
import com.agani.syncup.browser.ui.SheetHeader
import com.agani.syncup.browser.ui.TonalRow
import com.agani.syncup.browser.ui.sectionIcon
import com.agani.syncup.browser.ui.sectionName
import com.agani.syncup.ui.theme.success
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ============================================================================ switch section
/** Tap the section key → pick Normal / Work / Incognito. Work is locked when signed out. */
@Composable
internal fun SectionSheet(
    current: Section,
    tabs: TabManager,
    signedIn: Boolean,
    hasWork: Boolean,
    workCount: Int,
    singleLinkName: String? = null,
    onPick: (Section) -> Unit,
    onSignIn: () -> Unit,
) {
    Column(Modifier.padding(bottom = 16.dp)) {
        SheetHeader("Switch section")
        SectionChoice(
            Section.NORMAL,
            if (signedIn) "Your browsing · synced to your account" else "Your browsing on this phone",
            tabs.tabsIn(Section.NORMAL).size, current == Section.NORMAL, locked = false,
        ) { onPick(Section.NORMAL) }
        if (!signedIn) {
            SectionChoice(Section.WORK, "Sign in to use your SyncUp links", 0, false, locked = true) { onSignIn() }
        } else if (hasWork) {
            val open = tabs.tabsIn(Section.WORK).count { !it.isHome }
            SectionChoice(
                Section.WORK,
                (singleLinkName ?: "$workCount link${if (workCount == 1) "" else "s"}") + if (open > 0) " · $open open" else "",
                open, current == Section.WORK, locked = false,
            ) { onPick(Section.WORK) }
        }
        SectionChoice(Section.INCOGNITO, "Private · nothing is kept", tabs.tabsIn(Section.INCOGNITO).size, current == Section.INCOGNITO, locked = false) {
            onPick(Section.INCOGNITO)
        }
        if (!signedIn) {
            FilledTonalButton(onClick = onSignIn, modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp).height(48.dp)) {
                Text("Sign in to SyncUp")
            }
            Text(
                "Signing in syncs your bookmarks, history and tabs",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SectionChoice(section: Section, subtitle: String, count: Int, selected: Boolean, locked: Boolean, onClick: () -> Unit) {
    // Every row keeps the sheet's own text and surfaces (light, dark or Incognito) and takes only its
    // section's accent — Normal blue, SyncUp teal, Incognito violet — in the sheet's lightness.
    MaterialTheme(colorScheme = accentOn(MaterialTheme.colorScheme, section)) {
        val cs = MaterialTheme.colorScheme
        TonalRow(
            title = sectionName(section),
            subtitle = subtitle,
            selected = selected,
            leading = {
                if (selected) IconTile(sectionIcon(section), container = cs.primary, content = cs.onPrimary)
                else IconTile(sectionIcon(section))
            },
            trailing = {
                if (count > 0 && !locked) {
                    Box(
                        Modifier.size(24.dp).clip(RoundedCornerShape(8.dp)).background(Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$count", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Icon(
                    when {
                        locked -> Icons.Rounded.Lock
                        selected -> Icons.Rounded.Check
                        else -> Icons.AutoMirrored.Rounded.ArrowForward
                    },
                    null,
                    tint = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant,
                    modifier = Modifier.size(20.dp).alpha(if (locked || selected) 1f else 0f),
                )
                Spacer(Modifier.width(8.dp))
            },
            onClick = onClick,
        )
    }
}

// ============================================================================ page menu
internal enum class MenuAction {
    NEW_TAB, NEW_INCOGNITO, FORWARD, RELOAD, BOOKMARK, SHARE, FIND, DESKTOP, OPEN_IN_CHROME, ADS,
    CHAT, MUSIC, TOOLS, WORK, WORK_INFO, BOOKMARKS, HISTORY, DOWNLOADS, SETTINGS, SIGN_IN, PARTNERS,
}

@Composable
internal fun MenuSheet(tab: BrowserTab, db: BrowserDb, account: BrowserAccount, hasWork: Boolean, onAction: (MenuAction) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val page = !tab.isHome
    val normalPage = page && tab.section == Section.NORMAL
    var bookmarked by remember { mutableStateOf(false) }
    LaunchedEffect(tab.url) { bookmarked = normalPage && withContext(Dispatchers.IO) { db.isBookmarked(tab.url) } }
    val signedIn = account.user != null
    val incognito = tab.section == Section.INCOGNITO
    val workPage = page && tab.isWork
    val context = LocalContext.current
    val chromeInstalled = remember { runCatching { context.packageManager.getPackageInfo(CHROME_PACKAGE, 0) }.isSuccess }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        // Quick actions that apply to what's on screen — none on a home page, so no row at all.
        if (page || tab.canGoForward) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                if (tab.canGoForward) QuickAction(Icons.AutoMirrored.Rounded.ArrowForward, "Forward", true) { onAction(MenuAction.FORWARD) }
                if (normalPage) {
                    QuickAction(if (bookmarked) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (bookmarked) "Bookmarked" else "Bookmark", true, on = bookmarked) {
                        onAction(MenuAction.BOOKMARK)
                    }
                }
                if (page) {
                    QuickAction(Icons.Rounded.FindInPage, "Find", true) { onAction(MenuAction.FIND) }
                    QuickAction(Icons.Rounded.Refresh, "Reload", true) { onAction(MenuAction.RELOAD) }
                    QuickAction(Icons.Rounded.Computer, "Desktop", true, on = tab.desktop) { onAction(MenuAction.DESKTOP) }
                }
                if (workPage) QuickAction(Icons.Rounded.Info, "About", true) { onAction(MenuAction.WORK_INFO) }
            }
        }
        // The ad blocker on this page: how much it blocked, and a way out for a site that breaks.
        if (page && BrowserSettings.blockAds) {
            val allowed = AdBlocker.isAllowed(tab.pageHost)
            AdsRow(
                text = when {
                    allowed -> "Ads allowed on this site"
                    tab.adsBlocked == 1 -> "1 ad blocked on this page"
                    else -> "${tab.adsBlocked} ads blocked on this page"
                },
                action = if (allowed) "Block again" else "Allow on site",
            ) { onAction(MenuAction.ADS) }
        }
        if (workPage) MenuRow(Icons.Rounded.Info, "About this SyncUp link", tint = cs.primary, textColor = cs.primary) { onAction(MenuAction.WORK_INFO) }
        MenuRow(Icons.Rounded.Add, if (tab.isWork) "New Normal tab" else "New tab") { onAction(MenuAction.NEW_TAB) }
        MenuRow(Icons.Rounded.VisibilityOff, "New incognito tab") { onAction(MenuAction.NEW_INCOGNITO) }
        if (signedIn && !incognito && (hasWork || account.chatEnabled || account.hasPartners)) {
            SheetDivider()
            if (hasWork && !tab.isWork) {
                SectionTheme(Section.WORK) {
                    // One link: the row is that link and opens the website directly.
                    val single = account.links.singleOrNull()
                    MenuRow(
                        SyncUpMark, single?.title ?: "SyncUp links", if (single != null) "Your SyncUp link" else "${account.links.size}",
                        tint = MaterialTheme.colorScheme.primary,
                    ) { onAction(MenuAction.WORK) }
                }
            }
            if (account.chatEnabled) {
                MenuRow(Icons.Rounded.ChatBubbleOutline, "Chat with admin", badge = if (account.chatUnread > 0) "${account.chatUnread} new" else null) {
                    onAction(MenuAction.CHAT)
                }
            }
            if (account.hasPartners) {
                MenuRow(Icons.Rounded.Handshake, "Partners", badge = if (account.partnersWaiting > 0) "${account.partnersWaiting} new" else null) {
                    onAction(MenuAction.PARTNERS)
                }
            }
        }
        SheetDivider()
        MenuRow(Icons.Rounded.Bookmarks, "Bookmarks") { onAction(MenuAction.BOOKMARKS) }
        MenuRow(Icons.Rounded.History, "History") { onAction(MenuAction.HISTORY) }
        val running = com.agani.syncup.downloads.Downloads.tasks.count { it.state == com.agani.syncup.downloads.DlState.RUNNING }
        MenuRow(Icons.Rounded.Download, "Downloads", trailing = if (running > 0) "$running running" else null) { onAction(MenuAction.DOWNLOADS) }
        // Tools: Music, Video player, Downloads … — the row says what's going on inside.
        MenuRow(Icons.Rounded.GridView, "Tools", trailing = com.agani.syncup.tools.toolsStatus()) { onAction(MenuAction.TOOLS) }
        // SyncUp pages can't be shared, so the row simply isn't there.
        if (page && !tab.isWork) MenuRow(Icons.Rounded.Share, "Share page") { onAction(MenuAction.SHARE) }
        // For the rare page that won't work inside an app (Google sign-in). Never a SyncUp page (its
        // address stays here) or an Incognito one (it would leave Incognito).
        if (normalPage) {
            MenuRow(Icons.AutoMirrored.Rounded.OpenInNew, if (chromeInstalled) "Open in Chrome" else "Open in browser app") {
                onAction(MenuAction.OPEN_IN_CHROME)
            }
        }
        MenuRow(Icons.Rounded.Settings, "Settings") { onAction(MenuAction.SETTINGS) }
        if (!signedIn) MenuRow(Icons.Rounded.Person, "Sign in to SyncUp", tint = cs.primary, textColor = cs.primary) { onAction(MenuAction.SIGN_IN) }
    }
}

@Composable
private fun AdsRow(text: String, action: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).height(52.dp).padding(horizontal = 24.dp),
    ) {
        Icon(Icons.Rounded.Shield, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Text(text, fontSize = 15.sp, letterSpacing = .1.sp, color = cs.onSurface, modifier = Modifier.weight(1f), maxLines = 1)
        Text(action, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.primary)
    }
}

@Composable
private fun RowScope.QuickAction(icon: ImageVector, label: String, enabled: Boolean, on: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp)
            .alpha(if (enabled) 1f else .38f),
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(if (on) cs.primaryContainer else cs.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = if (on) cs.onPrimaryContainer else cs.onSurface)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, letterSpacing = .3.sp, color = cs.onSurface)
    }
}

// ============================================================================ about this work link
@Composable
internal fun WorkInfoSheet(tab: BrowserTab, onReload: () -> Unit, onSearchNormal: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp)) {
            if (tab.isMasked) IconTile(SyncUpMark, container = cs.primaryContainer, content = cs.onPrimaryContainer, size = 48.dp)
            else IconTile(Icons.Rounded.Language, container = cs.surfaceContainerHigh, content = cs.onSurfaceVariant, size = 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (tab.isMasked) tab.workName ?: "SyncUp link" else UrlInput.hostAndPath(tab.url).first,
                    fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1,
                )
                Text(
                    if (tab.isMasked) "SyncUp link · address hidden" else "Opened from ${tab.workName ?: "a SyncUp link"} · SyncUp",
                    fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant,
                )
            }
        }
        Row(
            Modifier
                .padding(start = 20.dp, end = 20.dp, bottom = 8.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(cs.surfaceContainerLowest)
                .padding(14.dp),
        ) {
            Icon(if (tab.isMasked) Icons.Rounded.VisibilityOff else SyncUpMark, null, tint = cs.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                if (tab.isMasked) "The address of this page is hidden by your organisation. You can use it here, but not copy, share or open it anywhere else."
                else "You reached this site from a SyncUp link, so it stays in this SyncUp tab. Its address is shown, but it can't be shared or opened anywhere else.",
                fontSize = 12.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant,
            )
        }
        if (UrlInput.isSecure(tab.url)) MenuRow(Icons.Rounded.Lock, "Connection is secure", tint = cs.success) {}
        MenuRow(Icons.Rounded.Refresh, "Reload", onClick = onReload)
        MenuRow(Icons.Rounded.Search, "Search the web in Normal", onClick = onSearchNormal)
    }
}

// ============================================================================ error page
@Composable
internal fun ErrorPage(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        EmptyState(
            Icons.Rounded.CloudOff, "Can't load this page", message,
            action = "Retry", actionIcon = Icons.Rounded.Refresh, onAction = onRetry,
        )
    }
}
