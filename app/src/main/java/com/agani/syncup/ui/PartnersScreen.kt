package com.agani.syncup.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.PasswordField
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.browser.ui.EmptyState
import com.agani.syncup.browser.ui.IconTile
import com.agani.syncup.browser.ui.StatusChip
import com.agani.syncup.browser.ui.TonalRow
import com.agani.syncup.data.PartnerDto
import com.agani.syncup.ui.theme.dialogSurface
import com.agani.syncup.ui.theme.success
import kotlinx.coroutines.launch

/**
 * Partners: services (e.g. GSTSync) that added this user. Each one reaches the user — its Work
 * links, notices, verification prompts and pushes — only while enabled. The user enables a partner
 * with the password that partner gave them (5 wrong tries → a 15-minute wait) and can disable it
 * any time; the partner is told.
 */
@Composable
fun PartnersScreen(
    load: suspend () -> Result<List<PartnerDto>>,
    enable: suspend (id: String, password: String) -> Result<PartnerDto?>,
    disable: suspend (id: String) -> Result<PartnerDto?>,
    onChanged: () -> Unit,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var partners by remember { mutableStateOf<List<PartnerDto>>(emptyList()) }
    var enabling by remember { mutableStateOf<PartnerDto?>(null) }
    var disabling by remember { mutableStateOf<PartnerDto?>(null) }

    LaunchedEffect(version) {
        loading = true
        load().onSuccess {
            partners = it
            error = null
        }.onFailure { error = it.message ?: "Couldn't load partners" }
        loading = false
    }

    fun replace(p: PartnerDto?) {
        if (p != null) partners = partners.map { if (it.id == p.id) p else it }
        onChanged() // links and the badge follow the new status
    }

    Column(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp)) {
            BarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
            Text("Partners", fontSize = 22.sp, lineHeight = 28.sp, color = cs.onSurface, modifier = Modifier.weight(1f).padding(start = 4.dp))
            BarIcon(Icons.Rounded.Refresh, "Refresh", tint = cs.onSurfaceVariant) { version++ }
        }
        Row(
            modifier = Modifier
                .padding(start = 20.dp, end = 20.dp, bottom = 8.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(cs.surfaceContainerLow)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Icon(Icons.Rounded.Info, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "Services that added you to SyncUp. A partner can send you links, notices and verification prompts only while it's enabled. Enable one with the password it gave you.",
                fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant,
            )
        }

        when {
            loading && partners.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null && partners.isEmpty() -> EmptyState(
                Icons.Rounded.CloudOff, "Can't load partners", error,
                action = "Retry", actionIcon = Icons.Rounded.Refresh, onAction = { version++ },
            )
            partners.isEmpty() -> EmptyState(
                Icons.Rounded.Handshake, "No partners yet",
                "When a service like GSTSync adds you, it shows up here for you to enable.",
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(partners, key = { it.id }) { p ->
                    val (chip, container, content) = when (p.status) {
                        "enabled" -> Triple("Enabled", cs.success.copy(alpha = .16f), cs.success)
                        "not_enabled" -> Triple("Not enabled", cs.tertiaryContainer, cs.onTertiaryContainer)
                        "suspended" -> Triple("Paused", cs.surfaceContainerHighest, cs.onSurfaceVariant)
                        else -> Triple("Disabled", cs.surfaceContainerHighest, cs.onSurfaceVariant)
                    }
                    TonalRow(
                        title = p.partner,
                        subtitle = when {
                            p.lockedUntil != null -> "Too many wrong tries — try again later"
                            p.status == "enabled" -> "Can send you links and prompts · tap to disable"
                            p.status == "not_enabled" -> "Added you · enter its password to enable"
                            p.status == "suspended" -> "${p.partner} has paused your access"
                            else -> "Turned off by you · tap to enable again"
                        },
                        leading = {
                            IconTile(
                                Icons.Rounded.Handshake,
                                container = if (p.status == "enabled") cs.primaryContainer else cs.surfaceContainerHigh,
                                content = if (p.status == "enabled") cs.onPrimaryContainer else cs.onSurfaceVariant,
                            )
                        },
                        trailing = {
                            StatusChip(chip, container = container, content = content)
                            Spacer(Modifier.width(8.dp))
                        },
                        minHeight = 72.dp,
                        onClick = {
                            when (p.status) {
                                "enabled" -> disabling = p
                                "suspended" -> Toast.makeText(context, "${p.partner} has paused your access. Contact ${p.partner}.", Toast.LENGTH_LONG).show()
                                else -> enabling = p
                            }
                        },
                    )
                }
            }
        }
    }

    enabling?.let { p ->
        EnablePartnerDialog(
            partner = p,
            onEnable = { password -> enable(p.id, password) },
            onDone = { updated ->
                enabling = null
                replace(updated ?: p.copy(status = "enabled"))
                Toast.makeText(context, "${p.partner} enabled", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { enabling = null },
        )
    }
    disabling?.let { p ->
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!busy) disabling = null },
            containerColor = cs.dialogSurface,
            title = { Text("Disable ${p.partner}?") },
            text = {
                Text(
                    "Its Work links disappear and its notices and verification prompts are refused. ${p.partner} is told you turned it off. " +
                        "To enable it again you'll need its password.",
                    color = cs.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        disable(p.id).onSuccess {
                            disabling = null
                            replace(it ?: p.copy(status = "disabled"))
                        }.onFailure {
                            Toast.makeText(context, it.message ?: "Couldn't disable", Toast.LENGTH_LONG).show()
                        }
                        busy = false
                    }
                }) { Text("Disable", color = cs.error) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { disabling = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EnablePartnerDialog(
    partner: PartnerDto,
    onEnable: suspend (password: String) -> Result<PartnerDto?>,
    onDone: (PartnerDto?) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = cs.dialogSurface,
        icon = { Icon(Icons.Rounded.Handshake, null) },
        title = { Text("Enable ${partner.partner}") },
        text = {
            Column {
                Text(
                    "Enter the password ${partner.partner} gave you. Once enabled, ${partner.partner} can send you links, notices and verification prompts.",
                    fontSize = 14.sp, lineHeight = 20.sp, color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                PasswordField(password, { password = it; error = null }, label = "${partner.partner} password", error = error)
            }
        },
        confirmButton = {
            Button(enabled = !busy && password.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    onEnable(password)
                        .onSuccess { onDone(it) }
                        .onFailure { error = it.message ?: "Couldn't enable" }
                    busy = false
                }
            }) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = cs.onPrimary)
                else Text("Enable")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}
