package com.agani.syncup.browser

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.R
import com.agani.syncup.browser.ui.Avatar
import com.agani.syncup.browser.ui.CountBadge
import com.agani.syncup.browser.ui.IconTile
import com.agani.syncup.browser.ui.SectionTheme
import com.agani.syncup.browser.ui.SheetDivider
import com.agani.syncup.browser.ui.TonalRow
import com.agani.syncup.ui.theme.dialogSurface

/** Optional SyncUp sign-in, as a sheet over the browser so the user's tabs stay put. */
@Composable
fun SignInSheet(
    loading: Boolean,
    error: String?,
    supportEmail: String,
    supportPhone: String,
    onLogin: (email: String, password: String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)

    Column(Modifier.fillMaxWidth().imePadding().padding(start = 24.dp, end = 24.dp, bottom = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(cs.primary), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_sync), null, colorFilter = ColorFilter.tint(cs.onPrimary), modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Sign in to SyncUp", fontSize = 22.sp, lineHeight = 28.sp, color = cs.onSurface)
                Text("Sync your bookmarks, history and tabs across devices", fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = email, onValueChange = { email = it },
            label = { Text("Email") },
            leadingIcon = { Icon(Icons.Rounded.MailOutline, null) },
            singleLine = true, shape = shape,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Password") },
            leadingIcon = { Icon(Icons.Rounded.Lock, null) },
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(if (showPassword) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, if (showPassword) "Hide password" else "Show password")
                }
            },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            singleLine = true, shape = shape,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onLogin(email, password) },
            enabled = !loading && email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = cs.onPrimary)
            } else {
                Text("Sign in", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { showHelp = true }) { Text("Forgot password?") }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Icon(Icons.Rounded.Shield, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Passwords, cookies, Work and Incognito never leave this device.",
                fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant,
            )
        }
    }

    if (showHelp) {
        val context = LocalContext.current
        val mail = supportEmail.ifBlank { "support@syncup.app" }
        AlertDialog(
            onDismissRequest = { showHelp = false },
            containerColor = cs.dialogSurface,
            icon = { Icon(Icons.AutoMirrored.Rounded.HelpOutline, null) },
            title = { Text("Need help signing in?", textAlign = TextAlign.Center) },
            text = {
                Column {
                    Text(
                        "Your SyncUp account is managed by your admin. Contact them to reset your password:",
                        fontSize = 14.sp, lineHeight = 20.sp, color = cs.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    HelpRow(Icons.Rounded.MailOutline, mail, "Email") {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$mail")).putExtra(Intent.EXTRA_SUBJECT, "SyncUp — sign-in help"),
                            )
                        }
                    }
                    if (supportPhone.isNotBlank()) {
                        HelpRow(Icons.Rounded.Call, supportPhone, "Phone") {
                            runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$supportPhone"))) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun HelpRow(icon: ImageVector, label: String, kind: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
    ) {
        IconTile(icon, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
            Text(kind, fontSize = 12.sp, color = cs.onSurfaceVariant)
        }
    }
}

/**
 * Opened from the avatar: rows appear only for what the admin enabled for this user (Work links,
 * Chat, Radio), then account settings and sign out.
 */
@Composable
fun AccountSheet(
    account: BrowserAccount,
    hasWork: Boolean,
    onWork: () -> Unit,
    onChat: () -> Unit,
    onRadio: () -> Unit,
    onSettings: () -> Unit,
    onSignOut: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val user = account.user ?: return
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp)) {
            Avatar(user, size = 56.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(user.name, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                Text(user.email, fontSize = 13.sp, color = cs.onSurfaceVariant, maxLines = 1)
            }
        }
        if (hasWork || account.chatEnabled || account.radioEnabled) {
            SheetDivider()
            if (hasWork) {
                SectionTheme(Section.WORK) {
                    TonalRow(
                        "Work links", "From your admin and partners",
                        leading = { IconTile(Icons.Rounded.Work) },
                        trailing = { Text("${account.links.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp)) },
                        minHeight = 60.dp,
                        onClick = onWork,
                    )
                }
            }
            if (account.chatEnabled) {
                TonalRow(
                    "Chat with admin", "SyncUp support",
                    leading = { IconTile(Icons.Rounded.ChatBubbleOutline) },
                    trailing = { if (account.chatUnread > 0) CountBadge("${account.chatUnread} new") },
                    minHeight = 60.dp,
                    onClick = onChat,
                )
            }
            if (account.radioEnabled) {
                TonalRow("Radio", "Live stations", leading = { IconTile(Icons.Rounded.Radio) }, minHeight = 60.dp, onClick = onRadio)
            }
        }
        SheetDivider()
        TonalRow(
            "Account & security", "Password, app lock and devices",
            leading = { IconTile(Icons.Rounded.ManageAccounts, container = cs.surfaceContainerHigh, content = cs.onSurfaceVariant) },
            minHeight = 60.dp,
            onClick = onSettings,
        )
        TonalRow(
            "Sign out", null,
            titleColor = cs.error,
            leading = { IconTile(Icons.AutoMirrored.Rounded.Logout, container = cs.errorContainer, content = cs.error) },
            minHeight = 60.dp,
            onClick = onSignOut,
        )
        Text(
            "Signing out keeps this device's bookmarks and history. It closes Work tabs and signs you out of work sites.",
            fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
    }
}

@Composable
fun SignOutDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cs.dialogSurface,
        title = { Text("Sign out of SyncUp?") },
        text = {
            Text(
                "Bookmarks, history and tabs stay on this device. Work tabs close and work sites are signed out.",
                fontSize = 14.sp, lineHeight = 20.sp, color = cs.onSurfaceVariant,
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Sign out", color = cs.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
