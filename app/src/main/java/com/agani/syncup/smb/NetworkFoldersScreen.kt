package com.agani.syncup.smb

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.browser.ui.EmptyState

/** Tools → Network folders: saved PCs/NAS boxes, ones found on this Wi-Fi, and a way to add one by address. */
@Composable
fun NetworkFoldersScreen(onOpenServer: (SmbServer) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var reload by remember { mutableStateOf(0) }
    val servers = remember(reload) { SmbStore.servers(context) }
    val discovery = remember { SmbDiscovery(context) }
    val wsDiscovery = remember { WsDiscovery(context) }
    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }
    var prefill by remember { mutableStateOf("") }

    // Two discovery methods: NSD/mDNS finds Samba and most NAS boxes; WS-Discovery finds Windows
    // PCs (the protocol Windows itself uses for its own "Network" view) — neither sees the other's kind.
    val foundAll = remember(discovery.found.size, wsDiscovery.found.size) {
        (discovery.found + wsDiscovery.found).distinctBy { it.host }
    }

    DisposableEffect(Unit) {
        discovery.start()
        wsDiscovery.start(scope)
        onDispose { discovery.stop(); wsDiscovery.stop() }
    }

    Box(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp)) {
                BarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
                Text("Network folders", fontSize = 22.sp, lineHeight = 28.sp, color = cs.onSurface, modifier = Modifier.weight(1f).padding(start = 4.dp))
                BarIcon(Icons.Rounded.Add, "Add a network folder") { prefill = ""; showAdd = true }
            }
            if (servers.isEmpty() && foundAll.isEmpty()) {
                EmptyState(
                    Icons.Rounded.Dns, "No network folders yet",
                    "Add a PC or NAS by its address, or pick one found on this Wi-Fi below.",
                    action = "Add a network folder", onAction = { prefill = ""; showAdd = true },
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (servers.isNotEmpty()) {
                        item { SectionLabel("SAVED") }
                        items(servers, key = { it.id }) { s ->
                            ServerRow(s.name, s.displayPath, onClick = { SmbStore.touch(context, s.id); onOpenServer(s) }, onRemove = { SmbStore.remove(context, s.id); reload++ })
                        }
                    }
                    if (foundAll.isNotEmpty()) {
                        item { SectionLabel("FOUND ON THIS WI-FI") }
                        items(foundAll, key = { it.host }) { h ->
                            ServerRow(h.name, h.host, onClick = { prefill = h.host; showAdd = true }, onRemove = null)
                        }
                    }
                    item {
                        Text(
                            "Still don't see a PC here? Make sure "
                                + "\"Network Discovery\" is turned on in its Wi-Fi settings, or add it by address directly (check with ipconfig on the PC).",
                            fontSize = 12.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddServerDialog(
            initialHost = prefill,
            onDismiss = { showAdd = false },
            onConnect = { server ->
                SmbStore.save(context, server)
                showAdd = false
                reload++
                onOpenServer(server)
            },
        )
    }
}

@Composable
private fun AddServerDialog(initialHost: String, onDismiss: () -> Unit, onConnect: (SmbServer) -> Unit) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf(initialHost) }
    var share by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a network folder") },
        text = {
            Column {
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Computer or NAS address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = share, onValueChange = { share = it }, label = { Text("Shared folder name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("User name (leave blank for guest)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = pass, onValueChange = { pass = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name to show (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = host.isNotBlank() && share.isNotBlank(),
                onClick = {
                    onConnect(SmbServer(name = name.ifBlank { host }, host = host.trim(), share = share.trim(), username = user.trim(), password = pass))
                },
            ) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ServerRow(name: String, subtitle: String, onClick: () -> Unit, onRemove: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Computer, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 12.5.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onRemove != null) BarIcon(Icons.Rounded.Close, "Forget this server", tint = cs.onSurfaceVariant, onClick = onRemove)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = .3.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp))
}
