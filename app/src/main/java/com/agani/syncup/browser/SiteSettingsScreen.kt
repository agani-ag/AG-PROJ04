package com.agani.syncup.browser

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.BarIcon

/** One page of Site settings: the overview, one permission's sites, or one site's permissions. */
private sealed interface SitePage {
    data object Overview : SitePage
    data class Permission(val perm: SitePerm) : SitePage
    data object Popups : SitePage
    data object Ads : SitePage
    data class Site(val origin: String) : SitePage
}

/**
 * Settings → Privacy & security → Site settings: what each site may use (camera, microphone,
 * location, notifications), which sites may open pop-ups and which show ads. Change or remove any
 * answer; a site that's removed asks again next time.
 */
@Composable
fun SiteSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var page by remember { mutableStateOf<SitePage>(SitePage.Overview) }
    val entries = remember(SitePermissions.version) { SitePermissions.all() } // re-read on every change
    BackHandler(page != SitePage.Overview) { page = SitePage.Overview }

    val title = when (val p = page) {
        SitePage.Overview -> "Site settings"
        is SitePage.Permission -> p.perm.title
        SitePage.Popups -> "Pop-ups"
        SitePage.Ads -> "Ads"
        is SitePage.Site -> entries.firstOrNull { it.origin == p.origin }?.label ?: SitePermissions.displayHost(p.origin)
    }
    Column(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp)) {
            BarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { if (page == SitePage.Overview) onBack() else page = SitePage.Overview }
            Text(title, fontSize = 22.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            when (val p = page) {
                SitePage.Overview -> {
                    Group {
                        SitePerm.entries.forEach { perm ->
                            val mine = entries.filter { it.perm == perm }
                            Line(perm.icon, perm.title, summary(mine)) { page = SitePage.Permission(perm) }
                        }
                        Line(
                            Icons.AutoMirrored.Rounded.OpenInNew, "Pop-ups",
                            if (!BrowserSettings.blockPopups) "Allowed everywhere"
                            else "Blocked" + if (BrowserSettings.popupSites.isNotEmpty()) " · allowed on ${count(BrowserSettings.popupSites.size)}" else "",
                        ) { page = SitePage.Popups }
                        Line(
                            Icons.Rounded.Shield, "Ads",
                            if (!BrowserSettings.blockAds) "Not blocked"
                            else "Blocked" + if (AdBlocker.allowedSites.isNotEmpty()) " · allowed on ${count(AdBlocker.allowedSites.size)}" else "",
                        ) { page = SitePage.Ads }
                    }
                    val sites = entries.groupBy { it.origin }
                    if (sites.isNotEmpty()) {
                        Group(header = "Sites") {
                            sites.forEach { (origin, list) ->
                                SiteLine(list.first().label, siteSummary(list)) { page = SitePage.Site(origin) }
                            }
                        }
                    }
                    Group {
                        Line(Icons.Rounded.PhoneAndroid, "SyncUp's own permissions", "What Android lets SyncUp use at all") {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                            }
                        }
                    }
                    Note("A site asks before it uses your camera, microphone, location or notifications. Your answer is remembered for that site. Incognito answers are forgotten when Incognito closes.")
                }
                is SitePage.Permission -> {
                    val mine = entries.filter { it.perm == p.perm }
                    if (mine.isEmpty()) {
                        Note("No site has asked for your ${p.perm.label} yet. When one does, you choose Allow or Block and it shows here.")
                    } else {
                        Group {
                            mine.forEach { e ->
                                ToggleLine(e.label, if (e.allowed) "Allowed" else "Blocked", e.allowed,
                                    onToggle = { SitePermissions.set(e.origin, e.perm, it, inIncognito = false) },
                                    onRemove = { SitePermissions.remove(e.origin, e.perm) })
                            }
                        }
                        Note("Removed sites ask again the next time they need it.")
                    }
                }
                is SitePage.Site -> {
                    val mine = entries.filter { it.origin == p.origin }
                    Group {
                        mine.forEach { e ->
                            ToggleLine(e.perm.title, if (e.allowed) "Allowed" else "Blocked", e.allowed,
                                onToggle = { SitePermissions.set(e.origin, e.perm, it, inIncognito = false) },
                                onRemove = { SitePermissions.remove(e.origin, e.perm) })
                        }
                    }
                    if (mine.isEmpty()) Note("Nothing saved for this site.")
                }
                SitePage.Popups -> {
                    Note(
                        if (BrowserSettings.blockPopups) "Pop-ups a site opens without a tap are blocked, except on these sites. Turn pop-up blocking off in Settings → Browser."
                        else "Pop-up blocking is off in Settings → Browser, so every site can open pop-ups.",
                    )
                    if (BrowserSettings.popupSites.isNotEmpty()) {
                        Group {
                            BrowserSettings.popupSites.toList().forEach { site ->
                                RemoveLine(site, "Can open pop-ups") { BrowserSettings.removePopupSite(site) }
                            }
                        }
                    }
                }
                SitePage.Ads -> {
                    Note(
                        if (BrowserSettings.blockAds) "Ads are blocked, except on these sites (page menu → Allow on site)."
                        else "The ad blocker is off in Settings → Browser.",
                    )
                    if (AdBlocker.allowedSites.isNotEmpty()) {
                        Group {
                            AdBlocker.allowedSites.toList().forEach { site ->
                                RemoveLine(site, "Ads allowed") { AdBlocker.setAllowed(site, false) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun count(n: Int) = if (n == 1) "1 site" else "$n sites"

private fun summary(list: List<SitePermissions.Entry>): String {
    val allowed = list.filter { it.allowed }
    val blocked = list.count { !it.allowed }
    return when {
        list.isEmpty() -> "Asks first"
        allowed.size in 1..2 && blocked == 0 -> "Allowed: " + allowed.joinToString(", ") { it.label }
        else -> listOfNotNull(
            allowed.size.takeIf { it > 0 }?.let { "Allowed: ${count(it)}" },
            blocked.takeIf { it > 0 }?.let { "Blocked: ${count(it)}" },
        ).joinToString(" · ")
    }
}

private fun siteSummary(list: List<SitePermissions.Entry>): String {
    val allowed = list.filter { it.allowed }.joinToString(", ") { it.perm.label }
    val blocked = list.filter { !it.allowed }.joinToString(", ") { it.perm.label }
    return listOfNotNull(
        allowed.takeIf { it.isNotEmpty() }?.let { "${it.replaceFirstChar(Char::uppercase)} allowed" },
        blocked.takeIf { it.isNotEmpty() }?.let { "$it blocked" },
    ).joinToString(" · ")
}

@Composable
private fun Group(header: String? = null, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainerLow).padding(vertical = 4.dp)) {
        if (header != null) Text(header, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.primary, modifier = Modifier.padding(start = 18.dp, top = 12.dp, bottom = 4.dp))
        content()
    }
}

@Composable
private fun Note(text: String) {
    Text(text, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 12.dp))
}

@Composable
private fun Line(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = cs.onSurface)
            Text(subtitle, fontSize = 13.sp, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = cs.onSurfaceVariant)
    }
}

private val SITE_COLORS = listOf(Color(0xFF0F766E), Color(0xFFEA580C), Color(0xFF7C3AED), Color(0xFF1B4FD8), Color(0xFFDC2626), Color(0xFF0891B2))

@Composable
private fun SiteLine(label: String, subtitle: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(SITE_COLORS[(label.hashCode() and 0x7fffffff) % SITE_COLORS.size]),
            contentAlignment = Alignment.Center,
        ) {
            Text(label.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 16.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 13.sp, color = cs.onSurfaceVariant, maxLines = 2)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = cs.onSurfaceVariant)
    }
}

@Composable
private fun ToggleLine(title: String, subtitle: String, on: Boolean, onToggle: (Boolean) -> Unit, onRemove: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 18.dp, end = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 13.sp, color = if (on) cs.primary else cs.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = onToggle)
        BarIcon(Icons.Rounded.Close, "Remove", tint = cs.onSurfaceVariant, onClick = onRemove)
    }
}

@Composable
private fun RemoveLine(title: String, subtitle: String, onRemove: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 18.dp, end = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 13.sp, color = cs.onSurfaceVariant)
        }
        BarIcon(Icons.Rounded.Close, "Remove", tint = cs.onSurfaceVariant, onClick = onRemove)
    }
}
