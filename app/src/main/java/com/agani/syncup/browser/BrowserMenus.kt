package com.agani.syncup.browser

import androidx.compose.material.icons.rounded.Home
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.Notifications
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
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.EmptyState
import com.agani.syncup.browser.ui.accentOn
import com.agani.syncup.browser.ui.IconTile
import com.agani.syncup.browser.ui.MenuRow
import com.agani.syncup.browser.ui.SectionTheme
import com.agani.syncup.browser.ui.SheetDivider
import com.agani.syncup.browser.ui.sectionIcon
import com.agani.syncup.browser.ui.sectionName
import com.agani.syncup.ui.theme.success
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ============================================================================ switch section
/** Tap the section key → pick Normal / Work / Incognito. Work is locked when signed out with no links for everyone. */
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
        BrowserSheetTitle("Switch section")
        SectionChoice(
            Section.NORMAL,
            if (signedIn) "Your browsing · synced to your account" else "Your browsing on this phone",
            tabs.tabsIn(Section.NORMAL).size, current == Section.NORMAL, locked = false,
        ) { onPick(Section.NORMAL) }
        if (!signedIn && !hasWork) {
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
            val cs = MaterialTheme.colorScheme
            FilledTonalButton(
                onClick = onSignIn,
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = cs.primaryContainer, contentColor = cs.onPrimaryContainer),
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp).height(46.dp),
            ) {
                Text("Sign in to SyncUp", fontSize = 15.sp, fontWeight = FontWeight.Medium)
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
    // section's accent — Normal blue, SyncUp green, Incognito violet — in the sheet's lightness.
    MaterialTheme(colorScheme = accentOn(MaterialTheme.colorScheme, section)) {
        val cs = MaterialTheme.colorScheme
        // The section in use is a soft pill; the others are plain rows with their section's soft tile.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 4.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(if (selected) cs.primaryContainer else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(12.dp),
        ) {
            if (selected) IconTile(sectionIcon(section), container = cs.primary, content = cs.onPrimary, size = 42.dp)
            else IconTile(sectionIcon(section), size = 42.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    sectionName(section), fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
                    color = if (selected) cs.onPrimaryContainer else cs.onSurface,
                )
                Text(
                    subtitle, fontSize = 12.5.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (selected) cs.onPrimaryContainer.copy(alpha = .85f) else cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (count > 0 && !locked) {
                Text(
                    "$count", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                    color = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
            Icon(
                when {
                    locked -> Icons.Rounded.Lock
                    selected -> Icons.Rounded.Check
                    else -> Icons.AutoMirrored.Rounded.ArrowForward
                },
                null,
                tint = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant,
                modifier = Modifier.size(18.dp).alpha(if (locked || selected) 1f else 0f),
            )
            Spacer(Modifier.width(4.dp))
        }
    }
}

// ============================================================================ page menu
internal enum class MenuAction {
    NEW_TAB, NEW_INCOGNITO, RELOAD, BOOKMARK, SHARE, FIND, DESKTOP, OPEN_IN_CHROME, ADS,
    WORK, WORK_INFO, SITE_INFO, BOOKMARKS, HISTORY, DOWNLOADS, HOME,
}

/**
 * The ⋮ menu (mockup "After · ⋮ menu", user 14 Nov 2026): six quick buttons — Home (the main page), Bookmark,
 * Find, Reload, Desktop, Share — then the ads on this page, About this site, New tab, New incognito tab,
 * Bookmarks, History, Downloads, SyncUp links and Open in Chrome. No big button; Forward lives on the bar,
 * Notifications on the bell, Partners and Settings in the account sheet.
 */
@Composable
internal fun MenuSheet(tab: BrowserTab, db: BrowserDb, account: BrowserAccount, hasWork: Boolean, onAction: (MenuAction) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val page = !tab.isHome
    val normalPage = page && tab.section == Section.NORMAL
    var bookmarked by remember { mutableStateOf(false) }
    LaunchedEffect(tab.url) { bookmarked = normalPage && withContext(Dispatchers.IO) { db.isBookmarked(tab.url) } }
    val incognito = tab.section == Section.INCOGNITO
    val workPage = page && tab.isWork
    val context = LocalContext.current
    val chromeInstalled = remember { runCatching { context.packageManager.getPackageInfo(CHROME_PACKAGE, 0) }.isSuccess }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
            QuickAction(Icons.Rounded.Home, "Home", true) { onAction(MenuAction.HOME) }
            if (normalPage) {
                QuickAction(if (bookmarked) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (bookmarked) "Bookmarked" else "Bookmark", true, on = bookmarked) {
                    onAction(MenuAction.BOOKMARK)
                }
            }
            QuickAction(Icons.Rounded.FindInPage, "Find", page) { onAction(MenuAction.FIND) }
            QuickAction(Icons.Rounded.Refresh, "Reload", page) { onAction(MenuAction.RELOAD) }
            QuickAction(Icons.Rounded.Computer, "Desktop", page, on = tab.desktop) { onAction(MenuAction.DESKTOP) }
            // SyncUp pages can't be shared, so there's no Share on them.
            if (!tab.isWork) QuickAction(Icons.Rounded.Share, "Share", page) { onAction(MenuAction.SHARE) }
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
        if (workPage) {
            MenuRow(Icons.Rounded.Info, "About this SyncUp link", tint = cs.primary, textColor = cs.primary) { onAction(MenuAction.WORK_INFO) }
        } else if (page) {
            MenuRow(Icons.Rounded.Info, "About this site") { onAction(MenuAction.SITE_INFO) }
        }
        MenuRow(Icons.Rounded.Add, if (tab.isWork) "New Normal tab" else "New tab") { onAction(MenuAction.NEW_TAB) }
        MenuRow(Icons.Rounded.VisibilityOff, "New incognito tab") { onAction(MenuAction.NEW_INCOGNITO) }
        SheetDivider()
        MenuRow(Icons.Rounded.Bookmarks, "Bookmarks") { onAction(MenuAction.BOOKMARKS) }
        MenuRow(Icons.Rounded.History, "History") { onAction(MenuAction.HISTORY) }
        val running = com.agani.syncup.downloads.Downloads.tasks.count { it.state == com.agani.syncup.downloads.DlState.RUNNING }
        MenuRow(Icons.Rounded.Download, "Downloads", trailing = if (running > 0) "$running running" else null) { onAction(MenuAction.DOWNLOADS) }
        val links = !incognito && hasWork && !tab.isWork
        // For the rare page that won't work inside an app (Google sign-in). Never a SyncUp page (its
        // address stays here) or an Incognito one (it would leave Incognito).
        if (links || normalPage) SheetDivider()
        if (links) {
            SectionTheme(Section.WORK) {
                // One link: the row is that link and opens the website directly.
                val single = account.links.singleOrNull()
                MenuRow(
                    SyncUpMark, single?.title ?: "SyncUp links", if (single != null) "Your SyncUp link" else "${account.links.size}",
                    tint = MaterialTheme.colorScheme.primary,
                ) { onAction(MenuAction.WORK) }
            }
        }
        if (normalPage) {
            MenuRow(Icons.AutoMirrored.Rounded.OpenInNew, if (chromeInstalled) "Open in Chrome" else "Open in browser app") {
                onAction(MenuAction.OPEN_IN_CHROME)
            }
        }
    }
}

// ============================================================================ about this site
/** "About this site" (⋮): the site, whether the connection is secure, ads blocked here, and its settings. */
@Composable
internal fun SiteInfoSheet(tab: BrowserTab, onSiteSettings: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val secure = UrlInput.isSecure(tab.url)
    Column(Modifier.padding(bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp)) {
            SiteMark(tab.url, tab.title, circle = 48.dp, iconSize = 26.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    UrlInput.hostAndPath(tab.url).first, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium,
                    color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(tab.title, fontSize = 13.sp, lineHeight = 18.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (secure) MenuRow(Icons.Rounded.Lock, "Connection is secure", tint = cs.success) {}
        else MenuRow(Icons.Rounded.Info, "Connection is not secure", tint = cs.error, textColor = cs.error) {}
        if (BrowserSettings.blockAds) {
            val allowed = AdBlocker.isAllowed(tab.pageHost)
            MenuRow(Icons.Rounded.Shield, if (allowed) "Ads allowed on this site" else "${tab.adsBlocked} ad${if (tab.adsBlocked == 1) "" else "s"} blocked on this page") {}
        }
        MenuRow(Icons.Rounded.Settings, "Site settings", onClick = onSiteSettings)
    }
}

@Composable
private fun AdsRow(text: String, action: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).height(52.dp).padding(start = 24.dp, end = 20.dp),
    ) {
        Icon(Icons.Rounded.Shield, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, fontSize = 14.sp, color = cs.onSurface, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        Text(action, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = cs.primary)
    }
}

@Composable
private fun RowScope.QuickAction(icon: ImageVector, label: String, enabled: Boolean, on: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp)
            .alpha(if (enabled) 1f else .38f),
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(if (on) cs.primaryContainer else cs.field),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = if (on) cs.onPrimaryContainer else cs.onSurface)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, color = cs.onSurface)
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
                .background(cs.field)
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
