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
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.agani.syncup.data.LoginMode
import com.agani.syncup.R
import com.agani.syncup.browser.ui.Avatar
import com.agani.syncup.browser.ui.CountBadge
import com.agani.syncup.browser.ui.IconTile
import com.agani.syncup.browser.ui.SectionTheme
import com.agani.syncup.browser.ui.SheetDivider
import com.agani.syncup.browser.ui.TonalRow
import com.agani.syncup.ui.theme.dialogSurface

/** Indian mobile number check (the backend is the judge; this only catches typos early). */
internal fun isIndianMobile(raw: String): Boolean {
    var d = raw.filter { it.isDigit() }
    if (d.length == 12 && d.startsWith("91")) d = d.drop(2)
    if (d.length == 11 && d.startsWith("0")) d = d.drop(1)
    return d.length == 10 && d[0] in '6'..'9'
}

/**
 * Optional SyncUp sign-in, as a sheet over the browser so the user's tabs stay put. Sign in with
 * email, phone or username (password is the only way in); "Create account" appears while the
 * admin allows sign-up.
 */
@Composable
fun SignInSheet(
    loading: Boolean,
    error: String?,
    supportEmail: String,
    supportPhone: String,
    signupEnabled: Boolean,
    privacyUrl: String,
    onLogin: (mode: LoginMode, login: String, password: String) -> Unit,
    onSignup: (name: String, email: String, phone: String, password: String) -> Unit,
    onClearError: () -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
    ) {
        if (creating && signupEnabled) {
            SignUpForm(loading, error, privacyUrl, onSignup) {
                onClearError()
                creating = false
            }
        } else {
            SignInForm(loading, error, supportEmail, supportPhone, signupEnabled, onLogin) {
                onClearError()
                creating = true
            }
        }
    }
}

@Composable
private fun SheetTitle(title: String, subtitle: String) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(cs.primary), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_sync), null, colorFilter = ColorFilter.tint(cs.onPrimary), modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, fontSize = 22.sp, lineHeight = 28.sp, color = cs.onSurface)
            Text(subtitle, fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
internal fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String = "Password",
    error: String? = null,
    supporting: String? = null,
    imeAction: ImeAction = ImeAction.Done,
    onDone: (() -> Unit)? = null,
) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        label = { Text(label) },
        leadingIcon = { Icon(Icons.Rounded.Lock, null) },
        trailingIcon = {
            IconButton(onClick = { show = !show }) {
                Icon(if (show) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, if (show) "Hide password" else "Show password")
            }
        },
        isError = error != null,
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        singleLine = true, shape = RoundedCornerShape(12.dp),
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { if (onDone != null) onDone() else defaultKeyboardAction(ImeAction.Done) }),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PrimaryButton(text: String, loading: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !loading && enabled, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        } else {
            Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignInForm(
    loading: Boolean,
    error: String?,
    supportEmail: String,
    supportPhone: String,
    signupEnabled: Boolean,
    onLogin: (LoginMode, String, String) -> Unit,
    onCreate: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var mode by remember { mutableStateOf(LoginMode.EMAIL) }
    val values = remember { mutableStateMapOf<LoginMode, String>() }
    var password by remember { mutableStateOf("") }
    var showHelp by remember { mutableStateOf(false) }
    val login = values[mode].orEmpty()

    SheetTitle("Sign in to SyncUp", "Sync your bookmarks, history and tabs across devices")
    Spacer(Modifier.height(20.dp))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        LoginMode.entries.forEachIndexed { i, m ->
            SegmentedButton(
                selected = mode == m,
                onClick = { mode = m },
                shape = SegmentedButtonDefaults.itemShape(i, LoginMode.entries.size),
            ) { Text(m.label) }
        }
    }
    Spacer(Modifier.height(14.dp))
    OutlinedTextField(
        value = login, onValueChange = { values[mode] = it },
        label = {
            Text(
                when (mode) {
                    LoginMode.EMAIL -> "Email"
                    LoginMode.PHONE -> "Mobile number"
                    LoginMode.USERNAME -> "Username"
                },
            )
        },
        leadingIcon = {
            Icon(
                when (mode) {
                    LoginMode.EMAIL -> Icons.Rounded.MailOutline
                    LoginMode.PHONE -> Icons.Rounded.PhoneAndroid
                    LoginMode.USERNAME -> Icons.Rounded.AlternateEmail
                },
                null,
            )
        },
        prefix = if (mode == LoginMode.PHONE) ({ Text("+91 ") }) else null,
        singleLine = true, shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(
            keyboardType = when (mode) {
                LoginMode.EMAIL -> KeyboardType.Email
                LoginMode.PHONE -> KeyboardType.Phone
                LoginMode.USERNAME -> KeyboardType.Ascii
            },
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(14.dp))
    // Done on the keyboard signs in, like the button.
    PasswordField(password, { password = it }, error = error, onDone = {
        if (!loading && login.isNotBlank() && password.isNotBlank()) onLogin(mode, login, password)
    })
    Spacer(Modifier.height(16.dp))
    PrimaryButton("Sign in", loading, login.isNotBlank() && password.isNotBlank()) { onLogin(mode, login, password) }
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { showHelp = true }) { Text("Forgot password?") }
        if (signupEnabled) TextButton(onClick = onCreate) { Text("Create account") }
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
                        "SyncUp support can reset your password. Contact them from the email or phone number on your account:",
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

/** Self sign-up: name, email and/or phone (India), password, privacy consent. No verification. */
@Composable
private fun SignUpForm(
    loading: Boolean,
    error: String?,
    privacyUrl: String,
    onSignup: (name: String, email: String, phone: String, password: String) -> Unit,
    onSignIn: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val shape = RoundedCornerShape(12.dp)
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var agreed by remember { mutableStateOf(false) }

    val emailBad = email.isNotBlank() && !android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
    val phoneBad = phone.isNotBlank() && !isIndianMobile(phone)
    val ready = name.isNotBlank() && (email.isNotBlank() || phone.isNotBlank()) && !emailBad && !phoneBad &&
        password.length >= 6 && agreed

    SheetTitle("Create your account", "Sign in on any device to get your bookmarks, history and tabs")
    Spacer(Modifier.height(20.dp))
    OutlinedTextField(
        value = name, onValueChange = { name = it },
        label = { Text("Your name") },
        leadingIcon = { Icon(Icons.Rounded.Person, null) },
        singleLine = true, shape = shape,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = email, onValueChange = { email = it },
        label = { Text("Email") },
        leadingIcon = { Icon(Icons.Rounded.MailOutline, null) },
        isError = emailBad,
        supportingText = if (emailBad) ({ Text("Enter a valid email") }) else null,
        singleLine = true, shape = shape,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = phone, onValueChange = { phone = it },
        label = { Text("Mobile number") },
        leadingIcon = { Icon(Icons.Rounded.PhoneAndroid, null) },
        prefix = { Text("+91 ") },
        isError = phoneBad,
        supportingText = { Text(if (phoneBad) "Enter a 10-digit Indian mobile number" else "Email, mobile number or both — you can sign in with either") },
        singleLine = true, shape = shape,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
    PasswordField(password, { password = it }, supporting = "At least 6 characters")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { agreed = !agreed }.padding(vertical = 4.dp),
    ) {
        Checkbox(checked = agreed, onCheckedChange = { agreed = it })
        Text("I agree to the ", fontSize = 14.sp, color = cs.onSurface)
        Text(
            "Privacy policy", fontSize = 14.sp, color = cs.primary, fontWeight = FontWeight.Medium,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = privacyUrl.isNotBlank()) {
                runCatching { context.startActivity(com.agani.syncup.web.WebViewActivity.intent(context, privacyUrl, "Privacy Policy")) }
            },
        )
    }
    if (error != null) {
        Text(error, color = cs.error, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(vertical = 6.dp))
    }
    Spacer(Modifier.height(8.dp))
    PrimaryButton("Create account", loading, ready) { onSignup(name, email, phone, password) }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text("Already have an account?", fontSize = 14.sp, color = cs.onSurfaceVariant)
        TextButton(onClick = onSignIn) { Text("Sign in") }
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
    onPartners: () -> Unit,
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
                Text(user.loginLabel, fontSize = 13.sp, color = cs.onSurfaceVariant, maxLines = 1)
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
            "Partners", if (account.partnersWaiting > 0) "Waiting for you to enable" else "Services that added you",
            leading = { IconTile(Icons.Rounded.Handshake, container = cs.surfaceContainerHigh, content = cs.onSurfaceVariant) },
            trailing = { if (account.partnersWaiting > 0) CountBadge("${account.partnersWaiting} new") },
            minHeight = 60.dp,
            onClick = onPartners,
        )
        TonalRow(
            "Account & security", "Sign-in details, sync, app lock",
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
            "Signing out keeps this device's bookmarks and history (they stop syncing). It closes Work tabs and signs you out of work sites.",
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
                "Bookmarks, history and tabs stay on this device and stop syncing. Work tabs close and work sites are signed out.",
                fontSize = 14.sp, lineHeight = 20.sp, color = cs.onSurfaceVariant,
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Sign out", color = cs.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
