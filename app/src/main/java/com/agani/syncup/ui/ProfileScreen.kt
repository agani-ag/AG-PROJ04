package com.agani.syncup.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.material3.Checkbox
import android.provider.Settings
import com.agani.syncup.browser.BrowserSettings
import com.agani.syncup.browser.SearchEngine
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.RadioButton
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import com.agani.syncup.AppLock
import com.agani.syncup.data.AppPrefs
import com.agani.syncup.data.SecurityStore
import com.agani.syncup.data.ThemeMode
import com.agani.syncup.data.ProfileUpdateRequest
import com.agani.syncup.data.User
import com.agani.syncup.data.UsernameCheckResponse
import com.agani.syncup.push.DeviceRegistrar
import com.agani.syncup.sync.BrowserSync
import com.agani.syncup.sync.SyncType
import androidx.core.app.NotificationManagerCompat
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.delay
import com.agani.syncup.ui.theme.dialogSurface
import com.agani.syncup.web.WebViewActivity
import kotlinx.coroutines.launch

// Auto-lock choices: seconds of background allowed before the app re-locks.
private val LOCK_GRACE_OPTIONS = listOf(
    0 to "Immediately",
    30 to "After 30 seconds",
    60 to "After 1 minute",
    300 to "After 5 minutes",
)

private fun lockGraceLabel(seconds: Int): String =
    LOCK_GRACE_OPTIONS.firstOrNull { it.first == seconds }?.second ?: "After $seconds seconds"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    user: User?,
    onSignIn: () -> Unit = {},
    appVersion: String,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    supportEmail: String = "",
    supportPhone: String = "",
    privacyPolicyUrl: String = "",
    chatEnabled: Boolean = true,
    chatUnread: Int = 0,
    onOpenChat: () -> Unit = {},
    onBack: () -> Unit,
    onLogout: () -> Unit,
    onChangePassword: suspend (current: String, new: String) -> Result<Unit>,
    onDeleteAccount: suspend () -> Result<Unit>,
    onClearBrowsingData: (history: Boolean, cookies: Boolean, cache: Boolean) -> Unit = { _, _, _ -> },
    partnersWaiting: Int = 0,
    showPartners: Boolean = false,
    onOpenPartners: () -> Unit = {},
    onUpdateProfile: suspend (ProfileUpdateRequest) -> Result<User> = { Result.failure(Exception("Not available")) },
    onCheckUsername: suspend (String) -> Result<UsernameCheckResponse> = { Result.failure(Exception("Not available")) },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val security = remember { SecurityStore(context) }
    val prefs = remember { AppPrefs(context) }
    val biometricAvailable = remember {
        BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK,
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    var hasPin by remember { mutableStateOf(security.hasPin()) }
    var biometricEnabled by remember { mutableStateOf(prefs.biometricEnabled()) }
    var lockGrace by remember { mutableStateOf(prefs.lockGraceSeconds()) }
    var showLockGrace by remember { mutableStateOf(false) }
    var showChangePassword by remember { mutableStateOf(false) }
    var showSetPin by remember { mutableStateOf(false) }
    var showRemovePin by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showDeleteAccount by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var showEngine by remember { mutableStateOf(false) }
    var showClearData by remember { mutableStateOf(false) }
    var editIdentifier by remember { mutableStateOf<String?>(null) } // "email" | "phone"
    var showUsername by remember { mutableStateOf(false) }
    var showDeleteSynced by remember { mutableStateOf(false) }
    var updatesOn by remember { mutableStateOf(DeviceRegistrar.updatesEnabled(context)) }
    // Which collapsed group is open (one at a time); kept across rotation.
    var open by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(6.dp))
            if (user != null) {
                SettingsGroup {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) {
                        Box(
                            modifier = Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(user.name.take(1).uppercase(), fontSize = 22.sp, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(user.name, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                            Text(user.loginLabel, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            Text("Signed in · SyncUp", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            } else {
                // Login is optional — the browser works fully without it; signing in adds SyncUp features.
                SettingsGroup {
                    Column(Modifier.fillMaxWidth().padding(18.dp)) {
                        Text("SyncUp account", fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Optional. Sign in to sync your bookmarks, history and tabs across your devices.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(14.dp))
                        Button(onClick = onSignIn) { Text("Sign in") }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // Collapsed groups: each shows a one-line summary; opening one closes the one before.
            val notificationsAllowed = remember { NotificationManagerCompat.from(context).areNotificationsEnabled() }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (user != null) {
                    SettingsSection(
                        icon = Icons.Rounded.ManageAccounts,
                        title = "Account",
                        summary = buildString {
                            append("Sign-in details, password")
                            if (showPartners) append(if (partnersWaiting > 0) " · $partnersWaiting partner waiting" else ", partners")
                            if (chatEnabled && chatUnread > 0) append(" · $chatUnread new message${if (chatUnread == 1) "" else "s"}")
                        },
                        expanded = open == "account",
                        onToggle = { open = if (open == "account") null else "account" },
                    ) {
                        SettingRow(
                            icon = Icons.Rounded.Email,
                            title = "Email",
                            subtitle = user.email.ifBlank { "Add an email · sign in with it" },
                            onClick = { editIdentifier = "email" },
                        )
                        RowDivider()
                        SettingRow(
                            icon = Icons.Rounded.PhoneAndroid,
                            title = "Mobile number",
                            subtitle = user.phone?.takeIf { it.isNotBlank() }?.let { com.agani.syncup.data.formatPhone(it) } ?: "Add a mobile number · sign in with it",
                            onClick = { editIdentifier = "phone" },
                        )
                        RowDivider()
                        SettingRow(
                            icon = Icons.Rounded.AlternateEmail,
                            title = "Username",
                            subtitle = user.username?.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "Set a username · sign in with it",
                            onClick = { showUsername = true },
                        )
                        RowDivider()
                        SettingRow(icon = Icons.Rounded.Lock, title = "Change password", onClick = { showChangePassword = true })
                        if (showPartners) {
                            RowDivider()
                            SettingRow(
                                icon = Icons.Rounded.Handshake,
                                title = "Partners",
                                subtitle = if (partnersWaiting > 0) "$partnersWaiting waiting for you to enable" else "Services that added you",
                                onClick = onOpenPartners,
                            )
                        }
                        if (chatEnabled) {
                            RowDivider()
                            SettingRow(
                                icon = Icons.Rounded.ChatBubbleOutline,
                                title = "Chat with admin",
                                subtitle = if (chatUnread > 0) "$chatUnread new message${if (chatUnread == 1) "" else "s"}" else "Message support directly",
                                onClick = onOpenChat,
                            )
                        }
                        RowDivider()
                        SettingRow(
                            icon = Icons.Rounded.DeleteOutline,
                            title = "Delete account",
                            subtitle = "Deactivate your account and sign out",
                            danger = true,
                            onClick = { showDeleteAccount = true },
                        )
                    }

                    SettingsSection(
                        icon = Icons.Rounded.Sync,
                        title = "Sync",
                        summary = syncStatus(),
                        expanded = open == "sync",
                        onToggle = { open = if (open == "sync") null else "sync" },
                    ) {
                        SwitchRow(
                            icon = Icons.Rounded.Sync,
                            title = "Sync on this phone",
                            subtitle = if (BrowserSync.enabled) "Your browsing reaches your other devices" else "Off · this phone's data stays here",
                            checked = BrowserSync.enabled,
                            enabled = true,
                            onCheckedChange = { BrowserSync.setSyncEnabled(it) },
                        )
                        if (BrowserSync.enabled) {
                            SyncType.entries.forEach { t ->
                                RowDivider()
                                SwitchRow(
                                    icon = when (t) {
                                        SyncType.BOOKMARKS -> Icons.Rounded.StarBorder
                                        SyncType.HISTORY -> Icons.Rounded.History
                                        SyncType.TABS -> Icons.Rounded.Devices
                                        SyncType.SHORTCUTS -> Icons.Rounded.Apps
                                        SyncType.SETTINGS -> Icons.Rounded.Tune
                                    },
                                    title = t.label,
                                    subtitle = when (t) {
                                        SyncType.BOOKMARKS -> "Up to 5,000"
                                        SyncType.HISTORY -> "Normal browsing, last 90 days"
                                        SyncType.TABS -> "See your open tabs on your other devices"
                                        SyncType.SHORTCUTS -> "New-tab shortcuts"
                                        SyncType.SETTINGS -> "Search engine, pop-ups, theme"
                                    },
                                    checked = BrowserSync.isOn(t),
                                    enabled = true,
                                    onCheckedChange = { BrowserSync.setOn(t, it) },
                                )
                            }
                            RowDivider()
                            SettingRow(icon = Icons.Rounded.CloudSync, title = "Sync now", subtitle = null, onClick = { BrowserSync.syncNow() })
                        }
                        RowDivider()
                        SettingRow(
                            icon = Icons.Rounded.CloudOff,
                            title = "Delete synced data",
                            subtitle = "Erase the copy kept for your account",
                            danger = true,
                            onClick = { showDeleteSynced = true },
                        )
                        Text(
                            "Synced data is stored on SyncUp's servers only to reach your other devices — SyncUp staff can't see what you browse. " +
                                "Never synced: passwords, cookies, SyncUp tabs, Incognito, app lock, downloads and site permissions.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp),
                        )
                    }
                }

                SettingsSection(
                    icon = Icons.Rounded.Language,
                    title = "Browser",
                    summary = "${BrowserSettings.searchEngine.label} · pop-ups ${if (BrowserSettings.blockPopups) "blocked" else "allowed"} · " +
                        "address bar at ${if (BrowserSettings.addressBarBottom) "bottom" else "top"}",
                    expanded = open == "browser",
                    onToggle = { open = if (open == "browser") null else "browser" },
                ) {
                    SettingRow(icon = Icons.Rounded.Search, title = "Search engine", subtitle = BrowserSettings.searchEngine.label, onClick = { showEngine = true })
                    RowDivider()
                    SwitchRow(
                        icon = Icons.Rounded.Block,
                        title = "Block pop-ups",
                        subtitle = null,
                        checked = BrowserSettings.blockPopups,
                        enabled = true,
                        onCheckedChange = { BrowserSettings.updateBlockPopups(it) },
                    )
                    RowDivider()
                    SwitchRow(
                        icon = Icons.Rounded.VerticalAlignBottom,
                        title = "Address bar at bottom",
                        subtitle = null,
                        checked = BrowserSettings.addressBarBottom,
                        enabled = true,
                        onCheckedChange = { BrowserSettings.updateAddressBarBottom(it) },
                    )
                }

                SettingsSection(
                    icon = Icons.Rounded.Shield,
                    title = "Privacy & security",
                    summary = "App lock ${if (hasPin) "on" else "off"}${if (hasPin && biometricEnabled) " · fingerprint" else ""} · clear data · site settings",
                    expanded = open == "privacy",
                    onToggle = { open = if (open == "privacy") null else "privacy" },
                ) {
                    PinLockRow(
                        hasPin = hasPin,
                        onTapChange = { showSetPin = true },
                        onToggle = { on -> if (on) showSetPin = true else showRemovePin = true },
                    )
                    RowDivider()
                    SwitchRow(
                        icon = Icons.Rounded.Fingerprint,
                        title = "Biometric unlock",
                        subtitle = when {
                            !biometricAvailable -> "Not available on this device"
                            !hasPin -> "Set a PIN first"
                            else -> "Use fingerprint or face"
                        },
                        checked = biometricEnabled,
                        enabled = biometricAvailable && hasPin,
                        onCheckedChange = {
                            biometricEnabled = it
                            prefs.setBiometricEnabled(it)
                        },
                    )
                    if (hasPin) {
                        RowDivider()
                        SettingRow(
                            icon = Icons.Rounded.Timer,
                            title = "Auto-lock",
                            subtitle = lockGraceLabel(lockGrace),
                            onClick = { showLockGrace = true },
                        )
                    }
                    RowDivider()
                    SettingRow(
                        icon = Icons.Rounded.DeleteSweep,
                        title = "Clear browsing data",
                        subtitle = "History, cookies, cache",
                        onClick = { showClearData = true },
                    )
                    RowDivider()
                    SettingRow(
                        icon = Icons.Rounded.Tune,
                        title = "Site settings",
                        subtitle = "Camera, mic, location, notifications",
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                                )
                            }
                        },
                    )
                }

                SettingsSection(
                    icon = Icons.Rounded.Palette,
                    title = "Appearance",
                    summary = when (themeMode) {
                        ThemeMode.SYSTEM -> "Theme follows the system"
                        ThemeMode.LIGHT -> "Light theme"
                        ThemeMode.DARK -> "Dark theme"
                        ThemeMode.BLACK -> "Black theme"
                    },
                    expanded = open == "appearance",
                    onToggle = { open = if (open == "appearance") null else "appearance" },
                ) {
                    Box(Modifier.padding(14.dp)) {
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            val options = listOf(
                                ThemeMode.SYSTEM to "System",
                                ThemeMode.LIGHT to "Light",
                                ThemeMode.DARK to "Dark",
                                ThemeMode.BLACK to "Black",
                            )
                            options.forEachIndexed { index, (mode, label) ->
                                SegmentedButton(
                                    selected = themeMode == mode,
                                    onClick = { onThemeChange(mode) },
                                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                                ) { Text(label) }
                            }
                        }
                    }
                }

                SettingsSection(
                    icon = Icons.Rounded.NotificationsActive,
                    title = "Notifications",
                    summary = "SyncUp updates ${if (updatesOn) "on" else "off"} · notifications ${if (notificationsAllowed) "allowed" else "blocked"}",
                    expanded = open == "notifications",
                    onToggle = { open = if (open == "notifications") null else "notifications" },
                ) {
                    SwitchRow(
                        icon = Icons.Rounded.Campaign,
                        title = "SyncUp updates",
                        subtitle = "News and announcements from SyncUp",
                        checked = updatesOn,
                        enabled = true,
                        onCheckedChange = {
                            updatesOn = it
                            DeviceRegistrar.setUpdatesEnabled(context, it)
                        },
                    )
                    RowDivider()
                    SettingRow(
                        icon = Icons.Rounded.NotificationsActive,
                        title = "Notification settings",
                        subtitle = if (notificationsAllowed) "Allowed" else "Blocked · tap to allow",
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    } else {
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                                    },
                                )
                            }
                        },
                    )
                }

                SettingsSection(
                    icon = Icons.Rounded.Info,
                    title = "About",
                    summary = "Help, privacy policy · version $appVersion",
                    expanded = open == "about",
                    onToggle = { open = if (open == "about") null else "about" },
                ) {
                    SettingRow(
                        icon = Icons.AutoMirrored.Rounded.HelpOutline,
                        title = "Help & support",
                        subtitle = "Contact support (password / PIN help)",
                        onClick = { showHelp = true },
                    )
                    if (privacyPolicyUrl.isNotBlank()) {
                        RowDivider()
                        SettingRow(
                            icon = Icons.Rounded.Policy,
                            title = "Privacy policy",
                            subtitle = "How your data is handled",
                            onClick = {
                                // Open inside the app (in-app WebView), not an external browser.
                                runCatching {
                                    context.startActivity(
                                        WebViewActivity.intent(context, privacyPolicyUrl, "Privacy Policy"),
                                    )
                                }.onFailure {
                                    Toast.makeText(context, "Can't open the privacy policy", Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                    }
                    RowDivider()
                    SettingRow(icon = Icons.Rounded.Info, title = "App version", subtitle = appVersion, onClick = null)
                }
            }

            if (user != null) {
            Spacer(Modifier.height(28.dp))
            OutlinedButton(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sign out", fontWeight = FontWeight.Medium)
            }
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    if (showEngine) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.dialogSurface,
            onDismissRequest = { showEngine = false },
            title = { Text("Search engine") },
            text = {
                Column {
                    SearchEngine.entries.forEach { engine ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    BrowserSettings.updateSearchEngine(engine)
                                    showEngine = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = BrowserSettings.searchEngine == engine, onClick = null)
                            Spacer(Modifier.width(10.dp))
                            Text(engine.label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showEngine = false }) { Text("Close") } },
        )
    }
    if (showClearData) {
        ClearDataDialog(
            synced = BrowserSync.active && BrowserSync.isOn(SyncType.HISTORY),
            onDismiss = { showClearData = false },
            onClear = { history, cookies, cache ->
                onClearBrowsingData(history, cookies, cache)
                showClearData = false
                Toast.makeText(context, "Browsing data cleared", Toast.LENGTH_SHORT).show()
            },
        )
    }
    if (showChangePassword) {
        ChangePasswordDialog(onDismiss = { showChangePassword = false }, onSubmit = onChangePassword)
    }
    editIdentifier?.let { kind ->
        IdentifierDialog(
            kind = kind,
            current = if (kind == "email") user?.email.orEmpty() else user?.phone.orEmpty(),
            onDismiss = { editIdentifier = null },
            onSubmit = { value, password ->
                onUpdateProfile(
                    if (kind == "email") ProfileUpdateRequest(email = value, currentPassword = password)
                    else ProfileUpdateRequest(phone = value, currentPassword = password),
                )
            },
        )
    }
    if (showUsername) {
        UsernameDialog(
            current = user?.username.orEmpty(),
            onCheck = onCheckUsername,
            onDismiss = { showUsername = false },
            onSubmit = { name -> onUpdateProfile(ProfileUpdateRequest(username = name)) },
        )
    }
    if (showDeleteSynced) {
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.dialogSurface,
            onDismissRequest = { if (!busy) showDeleteSynced = false },
            title = { Text("Delete synced data?") },
            text = {
                Text(
                    "This erases the bookmarks, history, tabs, shortcuts and settings kept for your account and turns sync off. " +
                        "Each phone keeps its own copy.",
                )
            },
            confirmButton = {
                Button(
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        busy = true
                        scope.launch {
                            BrowserSync.deleteServerData()
                                .onSuccess { Toast.makeText(context, "Synced data deleted · sync is off", Toast.LENGTH_SHORT).show() }
                                .onFailure { Toast.makeText(context, it.message ?: "Couldn't delete", Toast.LENGTH_LONG).show() }
                            busy = false
                            showDeleteSynced = false
                        }
                    },
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { showDeleteSynced = false }) { Text("Cancel") } },
        )
    }
    if (showLockGrace) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.dialogSurface,
            onDismissRequest = { showLockGrace = false },
            title = { Text("Auto-lock") },
            text = {
                Column {
                    Text(
                        "Re-lock the app after it's been in the background for:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    LOCK_GRACE_OPTIONS.forEach { (seconds, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    lockGrace = seconds
                                    prefs.setLockGraceSeconds(seconds)
                                    AppLock.graceMs = seconds * 1000L
                                    showLockGrace = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = lockGrace == seconds, onClick = null)
                            Spacer(Modifier.width(10.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLockGrace = false }) { Text("Close") } },
        )
    }
    if (showSetPin) {
        SetPinDialog(
            title = if (hasPin) "Change PIN" else "Set PIN",
            onDismiss = { showSetPin = false },
            onConfirm = { pin ->
                security.setPin(pin)
                hasPin = true
                showSetPin = false
                Toast.makeText(context, "PIN saved", Toast.LENGTH_SHORT).show()
            },
        )
    }
    if (showRemovePin) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.dialogSurface,
            onDismissRequest = { showRemovePin = false },
            title = { Text("Remove PIN?") },
            text = { Text("This turns off the app lock and biometric unlock.") },
            confirmButton = {
                Button(
                    onClick = {
                        security.clearPin()
                        prefs.setBiometricEnabled(false)
                        hasPin = false
                        biometricEnabled = false
                        showRemovePin = false
                        Toast.makeText(context, "PIN removed", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { showRemovePin = false }) { Text("Cancel") } },
        )
    }
    if (showHelp) {
        HelpDialog(
            email = supportEmail.ifBlank { "support@syncup.app" },
            phone = supportPhone,
            appVersion = appVersion,
            onDismiss = { showHelp = false },
        )
    }
    if (showDeleteAccount) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.dialogSurface,
            onDismissRequest = { if (!deleting) showDeleteAccount = false },
            title = { Text("Delete account?") },
            text = {
                Text(
                    "This deactivates your account, erases its synced browser data and signs you out on all devices. " +
                        "Your links and reminders will stop. Contact SyncUp support to restore access.",
                )
            },
            confirmButton = {
                Button(
                    enabled = !deleting,
                    onClick = {
                        deleting = true
                        scope.launch {
                            val result = onDeleteAccount()
                            deleting = false
                            result.onFailure {
                                showDeleteAccount = false
                                Toast.makeText(
                                    context,
                                    it.message ?: "Couldn't delete account",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                            // On success the screen returns to Login automatically (state change).
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    if (deleting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text("Delete")
                    }
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteAccount = false }, enabled = !deleting) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ClearDataDialog(synced: Boolean, onDismiss: () -> Unit, onClear: (history: Boolean, cookies: Boolean, cache: Boolean) -> Unit) {
    var history by remember { mutableStateOf(true) }
    var cookies by remember { mutableStateOf(true) }
    var cache by remember { mutableStateOf(true) }
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.dialogSurface,
        onDismissRequest = onDismiss,
        title = { Text("Clear browsing data") },
        text = {
            Column {
                CheckRow("Browsing history", history) { history = it }
                CheckRow("Cookies and site data", cookies, "Signs you out of most sites") { cookies = it }
                CheckRow("Cached images and files", cache) { cache = it }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Applies to Normal browsing. SyncUp sessions are wiped when you sign out; Incognito keeps nothing." +
                        if (synced) " Cleared history is also removed from your other signed-in devices." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = history || cookies || cache,
                onClick = { onClear(history, cookies, cache) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("Clear") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HelpDialog(email: String, phone: String, appVersion: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.dialogSurface,
        onDismissRequest = onDismiss,
        title = { Text("Help & support") },
        text = {
            Column {
                Text(
                    "SyncUp support can reset your password and manage your links.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Your app-lock PIN is stored only on this device. If you forget it, use \"Forgot PIN?\" on the lock screen to sign out, then sign in again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "Contact SyncUp support:",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                ContactRow(icon = Icons.Rounded.Email, label = email) {
                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:$email")
                        putExtra(Intent.EXTRA_SUBJECT, "SyncUp help (v$appVersion)")
                    }
                    runCatching { context.startActivity(intent) }.onFailure {
                        Toast.makeText(context, "No email app found", Toast.LENGTH_SHORT).show()
                    }
                }
                if (phone.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    ContactRow(icon = Icons.Rounded.Call, label = phone) {
                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                        runCatching { context.startActivity(intent) }.onFailure {
                            Toast.makeText(context, "Can't open dialer", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ContactRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SetPinDialog(title: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.dialogSurface,
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    "Enter a 4-digit PIN",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                PinInput(
                    value = pin,
                    autoFocus = true,
                    onValueChange = { pin = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = pin.length == PIN_LENGTH,
                onClick = { onConfirm(pin) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChangePasswordDialog(
    onDismiss: () -> Unit,
    onSubmit: suspend (current: String, new: String) -> Result<Unit>,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var current by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.dialogSurface,
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("Change password") },
        text = {
            Column {
                OutlinedTextField(current, { current = it }, label = { Text("Current password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(newPass, { newPass = it }, label = { Text("New password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(confirm, { confirm = it }, label = { Text("Confirm new password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                if (error != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(enabled = !submitting, onClick = {
                error = null
                if (newPass != confirm) { error = "New passwords don't match"; return@Button }
                submitting = true
                scope.launch {
                    val result = onSubmit(current, newPass)
                    submitting = false
                    result.fold(
                        onSuccess = {
                            Toast.makeText(context, "Password changed", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        },
                        onFailure = { error = it.message ?: "Couldn't change password" },
                    )
                }
            }) {
                if (submitting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Update")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting) { Text("Cancel") } },
    )
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column { content() }
    }
}

@Composable
private fun IconTile(icon: ImageVector, danger: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (danger) cs.errorContainer else cs.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (danger) cs.error else cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun RowText(title: String, subtitle: String?, modifier: Modifier, danger: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Column(modifier = modifier) {
        Text(title, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = .1.sp, color = if (danger) cs.error else cs.onSurface)
        if (subtitle != null) {
            Text(subtitle, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = .3.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String? = null, danger: Boolean = false, onClick: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .heightIn(min = 64.dp)
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
    ) {
        IconTile(icon, danger)
        Spacer(Modifier.width(16.dp))
        RowText(title, subtitle, Modifier.weight(1f), danger)
        if (onClick != null && !danger) {
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PinLockRow(hasPin: Boolean, onTapChange: () -> Unit, onToggle: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .let { if (hasPin) it.clickable(onClick = onTapChange) else it }
            .heightIn(min = 64.dp)
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
    ) {
        IconTile(Icons.Rounded.Lock)
        Spacer(Modifier.width(16.dp))
        RowText("App lock (PIN)", if (hasPin) "On · tap to change PIN" else "Off · protect with a 4-digit PIN", Modifier.weight(1f))
        Switch(checked = hasPin, onCheckedChange = onToggle)
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .heightIn(min = 64.dp)
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
    ) {
        IconTile(icon)
        Spacer(Modifier.width(16.dp))
        RowText(title, subtitle, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun RowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 72.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/** One line under the Sync switch: off / syncing / problem / last synced. */
@Composable
private fun syncStatus(): String = when {
    !BrowserSync.enabled -> "Off · this phone's data stays here"
    BrowserSync.syncing -> "Syncing…"
    BrowserSync.lastError != null -> BrowserSync.lastError!!
    BrowserSync.lastSyncMs > 0 -> "On · synced " + com.agani.syncup.browser.relativeTime(BrowserSync.lastSyncMs).replaceFirstChar { it.lowercase() }
    else -> "On · bookmarks, history, tabs and more across your devices"
}

/** Add or change the email or mobile number — confirmed with the current password. */
@Composable
private fun IdentifierDialog(
    kind: String,
    current: String,
    onDismiss: () -> Unit,
    onSubmit: suspend (value: String, password: String) -> Result<User>,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val email = kind == "email"
    var value by remember { mutableStateOf(if (email) current else current.removePrefix("+91")) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val what = if (email) "email" else "mobile number"
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.dialogSurface,
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (current.isBlank()) "Add $what" else "Change $what") },
        text = {
            Column {
                Text(
                    "You can sign in with it. It must not be registered to another account.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value, { value = it; error = null },
                    label = { Text(if (email) "Email" else "Mobile number") },
                    prefix = if (email) null else ({ Text("+91 ") }),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = if (email) KeyboardType.Email else KeyboardType.Phone, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    password, { password = it; error = null },
                    label = { Text("Current password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(enabled = !busy && value.isNotBlank() && password.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    onSubmit(value.trim(), password).fold(
                        onSuccess = {
                            Toast.makeText(context, if (email) "Email saved" else "Mobile number saved", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        },
                        onFailure = { error = it.message ?: "Couldn't save" },
                    )
                    busy = false
                }
            }) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

/** Set or change the username, with a live "available?" check while typing. */
@Composable
private fun UsernameDialog(
    current: String,
    onCheck: suspend (String) -> Result<UsernameCheckResponse>,
    onDismiss: () -> Unit,
    onSubmit: suspend (String) -> Result<User>,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var value by remember { mutableStateOf(current) }
    var check by remember { mutableStateOf<UsernameCheckResponse?>(null) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val typed = value.trim().removePrefix("@").lowercase()

    LaunchedEffect(typed) {
        check = null
        error = null
        checking = false
        if (typed.isBlank() || typed == current) return@LaunchedEffect
        checking = true
        delay(400) // wait until typing pauses
        onCheck(typed).onSuccess { check = it }
        checking = false
    }

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.dialogSurface,
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (current.isBlank()) "Set a username" else "Change username") },
        text = {
            Column {
                Text(
                    "Sign in with it instead of your email or number. 3–30 letters, numbers, dots or underscores.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                val c = check
                OutlinedTextField(
                    value, { value = it },
                    label = { Text("Username") },
                    prefix = { Text("@") },
                    singleLine = true,
                    isError = error != null || (c != null && !c.available),
                    trailingIcon = {
                        when {
                            checking -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            c?.available == true -> Icon(Icons.Rounded.CheckCircle, "Available", tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    supportingText = {
                        Text(
                            error ?: when {
                                typed == current && current.isNotBlank() -> "This is your username"
                                c != null -> c.message ?: if (c.available) "Available" else "Not available"
                                else -> ""
                            },
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(enabled = !busy && check?.available == true && typed != current, onClick = {
                busy = true
                scope.launch {
                    onSubmit(check?.username ?: typed).fold(
                        onSuccess = {
                            Toast.makeText(context, "Username saved", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        },
                        onFailure = { error = it.message ?: "Couldn't save" },
                    )
                    busy = false
                }
            }) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}


/**
 * A collapsed settings group: icon, title and a one-line summary of the current state. Tapping it
 * opens the group's rows below (and the screen closes whichever group was open before).
 */
@Composable
private fun SettingsSection(
    icon: ImageVector,
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, tween(220), label = "chevron")
    val tile by animateColorAsState(if (expanded) cs.primaryContainer else cs.surfaceContainerHigh, tween(220), label = "tile")
    Surface(shape = RoundedCornerShape(20.dp), color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = if (expanded) "Collapse" else "Expand", onClick = onToggle)
                    .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                    .heightIn(min = 72.dp)
                    .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            ) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tile),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, tint = if (expanded) cs.onPrimaryContainer else cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                    Text(
                        summary, fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Icon(Icons.Rounded.ExpandMore, null, tint = cs.onSurfaceVariant, modifier = Modifier.rotate(chevron))
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(240)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(200)) + fadeOut(tween(120)),
            ) {
                Column {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(cs.outlineVariant))
                    content()
                }
            }
        }
    }
}
