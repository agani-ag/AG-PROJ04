package com.agani.syncup.dns

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.PageTopBar
import com.agani.syncup.browser.browserCard
import com.agani.syncup.browser.field
import com.agani.syncup.ui.theme.isLight
import com.agani.syncup.ui.theme.success
import kotlinx.coroutines.launch

/** Settings → Browser → DNS. Everything starts turned off; what's turned on moves up into the person's order. */
@Composable
fun DnsScreen(onBack: () -> Unit) {
    var adding by rememberSaveable { mutableStateOf(false) }
    if (adding) {
        BackHandler { adding = false }
        AddOwnDns(onDone = { adding = false })
        return
    }
    val cs = MaterialTheme.colorScheme
    var deleting by remember { mutableStateOf<DnsChoice?>(null) }
    LaunchedEffect(Unit) { DnsServices.refresh() }
    LaunchedEffect(DnsServices.enabled) { DnsServices.checkTurnedOn() }
    val canUse = DnsServices.supported
    Column(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        PageTopBar("DNS", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            Status(canUse)
            DnsServices.notice?.let { text ->
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(warnColor().copy(alpha = .12f))
                        .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                ) {
                    Text(text, fontSize = 13.sp, lineHeight = 18.sp, color = warnColor(), modifier = Modifier.weight(1f).padding(top = 8.dp, bottom = 8.dp))
                    TextButton(onClick = { DnsServices.clearNotice() }) { Text("OK", color = warnColor()) }
                }
            }
            val on = DnsServices.turnedOn
            if (on.isNotEmpty()) {
                Heading("Your order", "The first one is used")
                Column(Modifier.fillMaxWidth().browserCard()) {
                    val first = DnsServices.inUse
                    on.forEachIndexed { i, s ->
                        if (i > 0) HorizontalDivider(color = cs.outlineVariant)
                        OnRow(s, i, on.size, isInUse = s == first, canUse)
                    }
                }
            }
            val off = DnsServices.turnedOff
            Heading("Available", if (off.isEmpty()) null else "${off.size} turned off")
            Column(Modifier.fillMaxWidth().browserCard()) {
                off.forEachIndexed { i, s ->
                    if (i > 0) HorizontalDivider(color = cs.outlineVariant)
                    OffRow(s, canUse, onDelete = { deleting = s })
                }
                if (off.isNotEmpty()) HorizontalDivider(color = cs.outlineVariant)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = { adding = true }).padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Icon(Icons.Rounded.Add, null, tint = cs.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Add your own DNS", fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = cs.primary)
                }
            }
            Text(
                "Only for web pages in SyncUp, including Incognito tabs. Other apps and the rest of your phone don't change. " +
                    "A DNS service you turn on can see the names of the sites you open. Downloads stay on your phone's DNS.",
                fontSize = 12.5.sp, lineHeight = 18.sp, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 14.dp),
            )
        }
    }
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${s.name}?") },
            text = { Text("It's removed from this phone. You can add it again any time.") },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
            confirmButton = {
                TextButton(onClick = {
                    DnsServices.removeOwn(s.id)
                    deleting = null
                }) { Text("Delete", color = cs.error) }
            },
        )
    }
}

@Composable
private fun warnColor(): Color = if (MaterialTheme.colorScheme.isLight) Color(0xFFB45309) else Color(0xFFFBBF24)

@Composable
private fun Status(canUse: Boolean) {
    val cs = MaterialTheme.colorScheme
    val on = DnsServices.turnedOn
    val working = DnsServices.inUse
    val warn = warnColor()
    val (icon, title, detail) = when {
        !canUse -> Triple(Icons.Rounded.WarningAmber, "Not available on this phone", "This phone's Android System WebView can't use a custom DNS. Updating it from Play Store may help.")
        on.isEmpty() -> Triple(Icons.Rounded.PhoneAndroid, "Using your phone's DNS", "Turn on any DNS below to use it for browsing in SyncUp.")
        DnsServices.couldntStart -> Triple(Icons.Rounded.WarningAmber, "Using your phone's DNS for now", "SyncUp couldn't start custom DNS on this phone, so pages still open this way.")
        working == null -> Triple(Icons.Rounded.WarningAmber, "Using your phone's DNS for now", "None of your DNS services answered, so pages still open this way.")
        else -> {
            val parts = listOfNotNull(
                DnsServices.latency[working.id]?.let { "$it ms" },
                DnsServices.backups.takeIf { it > 0 }?.let { "$it backup${if (it == 1) "" else "s"} ready" },
            )
            Triple(Icons.Rounded.Dns, "Using ${working.name}", parts.joinToString(" · ").ifEmpty { "Checking…" })
        }
    }
    val tint: Color
    val box: Color
    when {
        !canUse || (on.isNotEmpty() && working == null) -> {
            tint = warn
            box = warn.copy(alpha = .14f)
        }
        on.isEmpty() -> {
            tint = cs.onSurfaceVariant
            box = cs.field
        }
        else -> {
            tint = cs.onPrimaryContainer
            box = cs.primaryContainer
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp).fillMaxWidth().browserCard().padding(16.dp)) {
        StatusIcon(icon, tint, box)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
            Text(detail, fontSize = 13.sp, lineHeight = 18.sp, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusIcon(icon: ImageVector, tint: Color, box: Color) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(box), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun Heading(text: String, side: String?) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 18.dp, bottom = 8.dp)) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
        if (side != null) {
            Spacer(Modifier.width(8.dp))
            Text(side, fontSize = 12.5.sp, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, color = color, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = .13f)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tags(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) { content() }
}

@Composable
private fun OnRow(s: DnsChoice, index: Int, count: Int, isInUse: Boolean, canUse: Boolean) {
    val cs = MaterialTheme.colorScheme
    val down = DnsServices.failing[s.id] == true
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(if (isInUse) cs.primaryContainer else cs.field),
            contentAlignment = Alignment.Center,
        ) {
            Text("${index + 1}", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (isInUse) cs.onPrimaryContainer else cs.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(s.name, fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
            Tags {
                when {
                    down -> Tag("Not answering", cs.error)
                    isInUse -> Tag("In use", cs.primary)
                    else -> Tag("Backup", cs.onSurfaceVariant)
                }
                if (s.unencrypted) Tag("Not encrypted", warnColor())
                if (s.own) Tag("Yours", cs.onSurfaceVariant)
            }
        }
        IconButton(onClick = { DnsServices.move(s.id, -1) }, enabled = index > 0, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Rounded.KeyboardArrowUp, "Move ${s.name} up")
        }
        IconButton(onClick = { DnsServices.move(s.id, 1) }, enabled = index < count - 1, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Rounded.KeyboardArrowDown, "Move ${s.name} down")
        }
        Spacer(Modifier.width(4.dp))
        Switch(checked = true, enabled = canUse, onCheckedChange = { DnsServices.setOn(s.id, false) })
    }
}

@Composable
private fun OffRow(s: DnsChoice, canUse: Boolean, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(s.name, fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
            Text(s.description.ifBlank { s.address }, fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant)
            if (s.unencrypted || s.own) {
                Tags {
                    if (s.unencrypted) Tag("Not encrypted", warnColor())
                    if (s.own) Tag("Yours", cs.onSurfaceVariant)
                }
            }
        }
        if (s.own) {
            IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Rounded.Close, "Delete ${s.name}", tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(4.dp))
        }
        Switch(checked = false, enabled = canUse, onCheckedChange = { DnsServices.setOn(s.id, true) })
    }
}

/** A DNS of the person's own: tested before saving, added turned off, kept only on this phone. */
@Composable
private fun AddOwnDns(onDone: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val typed = address.trim()
    val kind = DnsKind.of(typed)
    Column(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        PageTopBar("Add your own DNS", onDone)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it; problem = null }, singleLine = true, enabled = !testing,
                label = { Text("Name") }, placeholder = { Text("My DNS") }, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = address, onValueChange = { address = it; problem = null }, singleLine = true, enabled = !testing,
                label = { Text("Address") }, placeholder = { Text("https://… or dns.example.com or 8.8.8.8") }, modifier = Modifier.fillMaxWidth(),
            )
            val line: Pair<String, Color>? = when {
                problem != null -> problem!! to cs.error
                typed.isNotEmpty() && kind == null -> "That doesn't look like a DNS address." to cs.error
                kind == DnsKind.PLAIN -> "Not encrypted: your internet provider can see or change the answers." to warnColor()
                else -> null
            }
            if (line != null) {
                Text(line.first, fontSize = 13.sp, lineHeight = 18.sp, color = line.second, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp))
            }
            Button(
                enabled = !testing && name.isNotBlank() && kind != null,
                modifier = Modifier.padding(top = 14.dp).fillMaxWidth().height(48.dp),
                onClick = {
                    testing = true
                    scope.launch {
                        val wrong = DnsServices.addOwn(name, address)
                        testing = false
                        problem = wrong
                        if (wrong == null) {
                            Toast.makeText(context, "Added. Turn it on when you want to use it.", Toast.LENGTH_SHORT).show()
                            onDone()
                        }
                    }
                },
            ) {
                if (testing) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = cs.onPrimary, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Testing…")
                } else {
                    Text("Test and add")
                }
            }
            Heading("Addresses it accepts", null)
            Column(Modifier.fillMaxWidth().browserCard().padding(horizontal = 16.dp, vertical = 8.dp)) {
                listOf("https://dns.google/dns-query" to true, "dns.google" to true, "8.8.8.8" to false).forEach { (example, encrypted) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(example, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace, color = cs.onSurface, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        if (encrypted) Tag("Encrypted", cs.success) else Tag("Not encrypted", warnColor())
                    }
                }
            }
            Text(
                "It stays on this phone and is never sent to SyncUp. Not encrypted means your internet provider can see or change the answers.",
                fontSize = 12.5.sp, lineHeight = 18.sp, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 14.dp),
            )
        }
    }
}
