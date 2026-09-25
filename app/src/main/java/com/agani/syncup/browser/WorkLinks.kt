package com.agani.syncup.browser

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agani.syncup.data.UrlItem
import kotlinx.coroutines.launch

/** Work home → "Added by me" + : a user adds their own work link (when the admin allows it). */
@Composable
internal fun AddLinkDialog(
    onDismiss: () -> Unit,
    onSubmit: suspend (title: String, url: String, description: String) -> Result<Unit>,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("Add link", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(url, { url = it }, label = { Text("URL (https://…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(description, { description = it }, label = { Text("Description (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(enabled = !submitting, onClick = {
                error = null
                val t = title.trim()
                val u = url.trim()
                if (t.isBlank() || u.isBlank()) { error = "Title and URL are required"; return@Button }
                if (!u.lowercase().startsWith("https://")) { error = "URL must start with https://"; return@Button }
                submitting = true
                scope.launch {
                    val result = onSubmit(t, u, description.trim())
                    submitting = false
                    result.fold(
                        onSuccess = {
                            Toast.makeText(context, "Link added", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        },
                        onFailure = { error = it.message ?: "Couldn't add link" },
                    )
                }
            }) {
                if (submitting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Add")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting) { Text("Cancel") } },
    )
}

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
