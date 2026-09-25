package com.agani.syncup.browser

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.R

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
    val shape = RoundedCornerShape(14.dp)

    Column(Modifier.fillMaxWidth().imePadding().padding(start = 24.dp, end = 24.dp, bottom = 22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color.Black), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_sync), null, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Sign in to SyncUp", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Text("Your tabs & bookmarks stay on this device", fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(22.dp))
        OutlinedTextField(
            value = email, onValueChange = { email = it },
            label = { Text("Email") },
            leadingIcon = { Icon(Icons.Rounded.MailOutline, null) },
            singleLine = true, shape = shape,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
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
            singleLine = true, shape = shape,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        if (error != null) {
            Spacer(Modifier.height(10.dp))
            Text(error, color = cs.error, fontSize = 12.sp)
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { onLogin(email, password) },
            enabled = !loading,
            shape = shape,
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = cs.onPrimary)
            } else {
                Text("Log in", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text("Accounts are created by your admin or partner. ", fontSize = 12.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
            Text(
                if (error != null) "Forgot password?" else "Need help?",
                fontSize = 12.sp, color = cs.primary, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { showHelp = true },
            )
        }
    }

    if (showHelp) {
        val context = LocalContext.current
        val mail = supportEmail.ifBlank { "support@syncup.app" }
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("Need help signing in?", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "Accounts and passwords are managed by your admin. Contact them to get access or reset your password:",
                        fontSize = 14.sp, color = cs.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    HelpRow(Icons.Rounded.Email, mail) {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$mail")).putExtra(Intent.EXTRA_SUBJECT, "SyncUp — sign-in help"),
                            )
                        }
                    }
                    if (supportPhone.isNotBlank()) {
                        HelpRow(Icons.Rounded.Call, supportPhone) {
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
private fun HelpRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Opened from the avatar: the signed-in user's SyncUp entry points. */
@Composable
fun AccountSheet(
    account: BrowserAccount,
    onWork: () -> Unit,
    onChat: () -> Unit,
    onRadio: () -> Unit,
    onSettings: () -> Unit,
    onSignOut: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val user = account.user ?: return
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp)) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                Text(user.name.take(1).uppercase(), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(user.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Text(user.email, fontSize = 14.sp, color = cs.onSurfaceVariant)
            }
        }
        HorizontalDivider(color = cs.outlineVariant, modifier = Modifier.padding(vertical = 6.dp))
        SheetRow(Icons.Rounded.Work, "Work links", "${account.links.size}", iconTint = Color(0xFF0F766E), onClick = onWork)
        if (account.chatEnabled) {
            SheetRow(
                Icons.Rounded.ChatBubbleOutline, "Chat with admin",
                if (account.chatUnread > 0) "${account.chatUnread} new" else null,
                trailingColor = cs.error, onClick = onChat,
            )
        }
        if (account.radioEnabled) SheetRow(Icons.Rounded.Radio, "Radio", onClick = onRadio)
        HorizontalDivider(color = cs.outlineVariant, modifier = Modifier.padding(vertical = 6.dp))
        SheetRow(Icons.Rounded.ManageAccounts, "Account & security", onClick = onSettings)
        SheetRow(Icons.AutoMirrored.Rounded.Logout, "Sign out", iconTint = cs.error, textColor = cs.error, onClick = onSignOut)
        Text(
            "Signing out keeps your Normal tabs, bookmarks and history. It closes all Work tabs and wipes Work sessions (logins, cookies), and removes Chat and Radio.",
            fontSize = 12.sp, color = cs.onSurfaceVariant, lineHeight = 17.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
        )
    }
}

@Composable
fun SheetRow(
    icon: ImageVector,
    title: String,
    trailing: String? = null,
    iconTint: Color? = null,
    textColor: Color? = null,
    trailingColor: Color? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val alpha = if (enabled) 1f else .38f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Icon(icon, null, tint = (iconTint ?: cs.onSurfaceVariant).copy(alpha = alpha))
        Spacer(Modifier.width(18.dp))
        Text(title, fontSize = 15.sp, color = (textColor ?: cs.onSurface).copy(alpha = alpha), modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, fontSize = 12.sp, color = (trailingColor ?: cs.onSurfaceVariant).copy(alpha = alpha))
    }
}
