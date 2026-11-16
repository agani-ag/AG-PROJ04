package com.agani.syncup.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.agani.syncup.browser.BrowserSettings
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.browser.ui.BadgeTextStyle
import com.agani.syncup.data.AppPrefs
import com.agani.syncup.data.SecurityStore
import com.agani.syncup.data.ThemeMode
import com.agani.syncup.data.User
import com.agani.syncup.home.SettingsPage
import com.agani.syncup.push.DeviceRegistrar
import com.agani.syncup.ui.theme.CardShape
import com.agani.syncup.ui.theme.appCard

/**
 * Settings, a page of its own (user, 17 Nov 2026: "a page with a proper back button, not a pop-up"): opened from
 * the picture on the main page or Settings in the browser's ⋮. The account and Partners, then the settings
 * groups (Chat among them) — each opens its own page, and Back from there comes back here — and Sign out.
 * Signed out: Sign in, then the settings. Back (the arrow or the phone's) returns to where it was opened from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHome(
    user: User?,
    themeMode: ThemeMode,
    appVersion: String,
    chatEnabled: Boolean,
    chatUnread: Int,
    hasPartners: Boolean,
    partnersWaiting: Int,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onPage: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    var confirmSignOut by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    // Nothing scrolls under the phone's navigation bar: the page stops above it.
    Column(Modifier.fillMaxSize().background(cs.surface).navigationBarsPadding()) {
        // The same top bar as each settings group's page.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().windowInsetsPadding(TopAppBarDefaults.windowInsets).heightIn(min = 60.dp)
                .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            BarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
            Text(
                "Settings", fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, color = cs.onSurface,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        ) {
            if (user != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clip(CardShape).appCard(CardShape)
                        .clickable { onPage(SettingsPage.ACCOUNT) }.padding(16.dp),
                ) {
                    Box(Modifier.size(54.dp).clip(CircleShape).background(cs.primary), contentAlignment = Alignment.Center) {
                        Text(user.name.trim().take(1).uppercase().ifBlank { "?" }, color = cs.onPrimary, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(user.name, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${user.loginLabel} · account details", fontSize = 13.5.sp, lineHeight = 18.sp, color = cs.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = cs.onSurfaceVariant)
                }
                // Partners and Chat with admin sit in the settings list below (user, 16–17 Nov 2026).
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().appCard(CardShape).clickable(onClick = onSignIn).padding(16.dp),
                ) {
                    Box(Modifier.size(54.dp).clip(CircleShape).background(cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Person, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Sign in to SyncUp", fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = cs.primary)
                        Text(
                            "Optional — your tabs, to-dos and forest on every phone", fontSize = 13.5.sp, lineHeight = 18.sp,
                            color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = cs.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(16.dp))
            val hasPin = remember { SecurityStore(context).hasPin() }
            val fingerprint = remember { AppPrefs(context).biometricEnabled() }
            val updatesOn = remember { DeviceRegistrar.updatesEnabled(context) }
            val allowed = remember { NotificationManagerCompat.from(context).areNotificationsEnabled() }
            Column(Modifier.fillMaxWidth().appCard(CardShape)) {
                if (user != null) {
                    GroupRow(Icons.Rounded.Sync, "Sync", syncStatus()) { onPage(SettingsPage.SYNC) }
                    Line()
                }
                GroupRow(
                    Icons.Rounded.Language, "Browser",
                    "${BrowserSettings.searchEngine.label} · DNS ${com.agani.syncup.dns.DnsServices.summary.substringBefore(" · ").lowercase()} · " +
                        "pop-ups ${if (BrowserSettings.blockPopups) "blocked" else "allowed"} · downloads",
                ) { onPage(SettingsPage.BROWSER) }
                Line()
                GroupRow(
                    Icons.Rounded.Shield, "Privacy & security",
                    "App lock ${if (hasPin) "on" else "off"}${if (hasPin && fingerprint) " · fingerprint" else ""} · site settings",
                ) { onPage(SettingsPage.PRIVACY) }
                Line()
                GroupRow(Icons.Rounded.Palette, "Appearance", if (themeMode == ThemeMode.DARK || themeMode == ThemeMode.BLACK) "Dark" else "Light") {
                    onPage(SettingsPage.APPEARANCE)
                }
                Line()
                GroupRow(
                    Icons.Rounded.NotificationsActive, "Notifications",
                    "SyncUp updates ${if (updatesOn) "on" else "off"}${if (allowed) "" else " · blocked"}",
                ) { onPage(SettingsPage.NOTIFICATIONS) }
                Line()
                // Partners, just above Chat with admin, in the same list (user, 17 Nov 2026).
                if (user != null && hasPartners) {
                    GroupRow(
                        Icons.Rounded.Handshake, "Partners",
                        if (partnersWaiting > 0) "$partnersWaiting waiting for their password" else "Services connected to your account",
                        badge = partnersWaiting.takeIf { it > 0 }?.let { "$it new" },
                    ) { onPage(SettingsPage.PARTNERS) }
                    Line()
                }
                if (user != null && chatEnabled) {
                    GroupRow(
                        Icons.AutoMirrored.Rounded.Chat, "Chat with admin",
                        if (chatUnread > 0) "New reply from the admin" else "Ask for help or send feedback",
                        badge = chatUnread.takeIf { it > 0 }?.toString(),
                    ) { onPage(SettingsPage.CHAT) }
                    Line()
                }
                GroupRow(Icons.Rounded.Info, "About", "Help · privacy policy · version $appVersion") { onPage(SettingsPage.ABOUT) }
            }
            if (user != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 14.dp).fillMaxWidth().heightIn(min = 56.dp).appCard(CardShape)
                        .clickable { confirmSignOut = true }.padding(horizontal = 16.dp),
                ) {
                    Text(
                        "Sign out", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = cs.error,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
    if (confirmSignOut) {
        com.agani.syncup.browser.SignOutDialog(
            onDismiss = { confirmSignOut = false },
            onConfirm = {
                confirmSignOut = false
                onSignOut()
            },
        )
    }
}

@Composable
private fun Line() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

/** A settings group: its tile, name and what's set now in one line (and a red [badge]); opens the group's page. */
@Composable
private fun GroupRow(icon: ImageVector, title: String, summary: String, badge: String? = null, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 66.dp).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(cs.surfaceContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
            Text(summary, fontSize = 13.5.sp, lineHeight = 18.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
        }
        if (badge != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(start = 8.dp, end = 6.dp).heightIn(min = 22.dp).clip(RoundedCornerShape(50)).background(cs.error).padding(horizontal = 8.dp),
            ) { Text(badge, style = BadgeTextStyle, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, color = cs.onError) }
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = cs.onSurfaceVariant)
    }
}
