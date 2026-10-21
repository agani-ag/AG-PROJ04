package com.agani.syncup.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.CurrencyRupee
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LocalFlorist
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Medication
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agani.syncup.data.UrlItem
import com.agani.syncup.ui.theme.isLight

/**
 * A link's picked icon by name (the server's link_icons.py list), or null for Auto / an unknown name.
 * Older names keep working (youtube, notification, speed, newtab).
 */
fun linkImage(name: String?): ImageVector? = when (name?.trim()?.lowercase()) {
    "link" -> Icons.Rounded.Link
    "globe", "web" -> Icons.Rounded.Language
    "star" -> Icons.Rounded.Star
    "home" -> Icons.Rounded.Home
    "bell", "notification" -> Icons.Rounded.Notifications
    "person", "user", "account" -> Icons.Rounded.Person
    "settings" -> Icons.Rounded.Settings
    "flower" -> Icons.Rounded.LocalFlorist
    "heart" -> Icons.Rounded.Favorite
    "pill" -> Icons.Rounded.Medication
    "fitness" -> Icons.Rounded.FitnessCenter
    "leaf" -> Icons.Rounded.Eco
    "drop" -> Icons.Rounded.WaterDrop
    "chat" -> Icons.AutoMirrored.Rounded.Chat
    "group" -> Icons.Rounded.Groups
    "phone", "call" -> Icons.Rounded.Call
    "mail" -> Icons.Rounded.Mail
    "wallet" -> Icons.Rounded.AccountBalanceWallet
    "bank" -> Icons.Rounded.AccountBalance
    "rupee" -> Icons.Rounded.CurrencyRupee
    "card" -> Icons.Rounded.CreditCard
    "cart", "shop" -> Icons.Rounded.ShoppingCart
    "receipt" -> Icons.Rounded.Receipt
    "document", "doc" -> Icons.Rounded.Description
    "folder" -> Icons.Rounded.Folder
    "upload", "file" -> Icons.Rounded.UploadFile
    "briefcase" -> Icons.Rounded.Work
    "calendar" -> Icons.Rounded.CalendarMonth
    "chart" -> Icons.Rounded.BarChart
    "gov" -> Icons.Rounded.Gavel
    "school" -> Icons.Rounded.School
    "book" -> Icons.AutoMirrored.Rounded.MenuBook
    "location", "map" -> Icons.Rounded.LocationOn
    "car" -> Icons.Rounded.DirectionsCar
    "video", "youtube", "play" -> Icons.Rounded.PlayCircle
    "music" -> Icons.Rounded.MusicNote
    "camera" -> Icons.Rounded.PhotoCamera
    "image", "photo" -> Icons.Rounded.Image
    "news" -> Icons.Rounded.Newspaper
    "speed", "bolt" -> Icons.Rounded.Bolt
    "newtab", "popup", "open" -> Icons.AutoMirrored.Rounded.OpenInNew
    else -> null
}

/** A picked icon's colours: (light accent, light tile, dark accent, dark tile). Blank or unknown = blue. */
private fun linkColors(name: String?): LongArray = when (name?.trim()?.lowercase()) {
    "teal" -> longArrayOf(0xFF0F766E, 0xFFCCFBF1, 0xFF5EEAD4, 0xFF134E4A)
    "green" -> longArrayOf(0xFF15803D, 0xFFDCFCE7, 0xFF86EFAC, 0xFF14532D)
    "lime" -> longArrayOf(0xFF4D7C0F, 0xFFECFCCB, 0xFFBEF264, 0xFF1A2E05)
    "amber" -> longArrayOf(0xFFB45309, 0xFFFEF3C7, 0xFFFCD34D, 0xFF451A03)
    "orange" -> longArrayOf(0xFFC2410C, 0xFFFFEDD5, 0xFFFDBA74, 0xFF431407)
    "rose" -> longArrayOf(0xFFBE123C, 0xFFFFE4E6, 0xFFFDA4AF, 0xFF4C0519)
    "violet" -> longArrayOf(0xFF6D28D9, 0xFFEDE9FE, 0xFFC4B5FD, 0xFF3B2A63)
    "slate" -> longArrayOf(0xFF334155, 0xFFE2E8F0, 0xFFCBD5E1, 0xFF1E293B)
    else -> longArrayOf(0xFF1D4ED8, 0xFFDBEAFE, 0xFF93C5FD, 0xFF1E3A8A)
}

/**
 * A SyncUp link's icon, the same everywhere it shows: its picked icon in its colour; else (Auto)
 * the website's own icon, fetched by the phone (on a white tile, so dark logos stay visible in the
 * dark theme); else its first letter.
 */
@Composable
fun LinkIcon(item: UrlItem, size: Dp = 40.dp) {
    val picked = linkImage(item.icon)
    val shape = RoundedCornerShape(size * .3f)
    if (picked != null) {
        val c = linkColors(item.color)
        val light = MaterialTheme.colorScheme.isLight
        Box(Modifier.size(size).clip(shape).background(Color(if (light) c[1] else c[3])), contentAlignment = Alignment.Center) {
            Icon(picked, null, tint = Color(if (light) c[0] else c[2]), modifier = Modifier.size(size * .55f))
        }
    } else {
        Box(
            Modifier.size(size).clip(shape).background(Color.White).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
            contentAlignment = Alignment.Center,
        ) { SiteMark(item.url, item.title, circle = size, iconSize = size * .7f) }
    }
}

/** The links the browser knows right now, by id — for places that only have a tab (the tab switcher). */
object KnownLinks {
    var byId by mutableStateOf<Map<String, UrlItem>>(emptyMap())
        internal set
}
