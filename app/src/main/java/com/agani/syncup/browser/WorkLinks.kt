package com.agani.syncup.browser

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.ui.graphics.vector.ImageVector
import com.agani.syncup.data.UrlItem

/** Tile icon for a work link: the admin-set icon name wins, else a guess from the title. */
internal fun iconFor(item: UrlItem): ImageVector {
    when (item.icon?.trim()?.lowercase()) {
        "link" -> return Icons.Rounded.Link
        "globe", "web" -> return Icons.Rounded.Language
        "camera" -> return Icons.Rounded.PhotoCamera
        "location", "map" -> return Icons.Rounded.LocationOn
        "upload", "file" -> return Icons.Rounded.UploadFile
        "youtube", "video", "play" -> return Icons.Rounded.PlayCircle
        "notification", "bell" -> return Icons.Rounded.Notifications
        "speed", "bolt" -> return Icons.Rounded.Bolt
        "newtab", "popup", "open" -> return Icons.AutoMirrored.Rounded.OpenInNew
        "home" -> return Icons.Rounded.Home
        "cart", "shop" -> return Icons.Rounded.ShoppingCart
        "person", "user", "account" -> return Icons.Rounded.Person
        "settings" -> return Icons.Rounded.Settings
        "document", "doc" -> return Icons.Rounded.Description
        "phone", "call" -> return Icons.Rounded.Call
        "image", "photo" -> return Icons.Rounded.Image
        "star" -> return Icons.Rounded.Star
    }
    val t = item.title.lowercase()
    return when {
        "camera" in t || "mic" in t -> Icons.Rounded.PhotoCamera
        "location" in t -> Icons.Rounded.LocationOn
        "file" in t || "upload" in t -> Icons.Rounded.UploadFile
        "google" in t || "web" in t || "brows" in t -> Icons.Rounded.Language
        "youtube" in t || "video" in t -> Icons.Rounded.PlayCircle
        "notif" in t || "bell" in t -> Icons.Rounded.Notifications
        "speed" in t -> Icons.Rounded.Bolt
        "popup" in t || "new-tab" in t || "new tab" in t || "blank" in t -> Icons.AutoMirrored.Rounded.OpenInNew
        else -> Icons.Rounded.Link
    }
}
