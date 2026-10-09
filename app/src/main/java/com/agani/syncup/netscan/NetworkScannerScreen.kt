package com.agani.syncup.netscan

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.browser.ui.EmptyState
import kotlinx.coroutines.launch

/**
 * Tools → Network scanner: checks every address on the phone's current Wi-Fi for a set of TCP ports
 * and lists what answers. Wi-Fi only, same-subnet only, no exploitation — just "is something there."
 */
@Composable
fun NetworkScannerScreen(onBack: () -> Unit, onAddNetworkFolder: (String) -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var network by remember { mutableStateOf(NetworkScanner.currentNetwork(context)) }
    var depth by remember { mutableStateOf(ScanDepth.COMMON) }
    var customRange by remember { mutableStateOf(1..1024) }
    var showSettings by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ScannedDevice?>(null) }
    var hasScanned by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { NetworkScanner.stop() }
    }
    LaunchedEffect(Unit) { network = NetworkScanner.currentNetwork(context) }

    fun startScan() {
        hasScanned = true
        scope.launch { NetworkScanner.scan(context, depth, customRange) }
    }

    Box(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp)) {
                BarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
                Text("Network scanner", fontSize = 22.sp, lineHeight = 28.sp, color = cs.onSurface, modifier = Modifier.weight(1f).padding(start = 4.dp))
                BarIcon(Icons.Rounded.Settings, "Scan settings", onClick = { showSettings = true })
            }
            if (network == null) {
                EmptyState(
                    Icons.Rounded.WifiOff, "Connect to Wi-Fi",
                    "The scanner only looks at your current Wi-Fi network, never mobile data.",
                    action = "Check again", onAction = { network = NetworkScanner.currentNetwork(context) },
                    modifier = Modifier.weight(1f),
                )
            } else {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Wifi, null, tint = cs.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${network!!.address.hostAddress}/${network!!.prefixLength} · ${depthLabel(depth, customRange)}",
                            fontSize = 13.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    if (NetworkScanner.scanning) {
                        LinearProgress(NetworkScanner.progress)
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { NetworkScanner.stop() }, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
                    } else {
                        Button(onClick = { startScan() }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (hasScanned) "Scan again" else "Scan this network")
                        }
                    }
                }
                if (!hasScanned && NetworkScanner.devices.isEmpty()) {
                    EmptyState(
                        Icons.Rounded.Router, "Find devices on this Wi-Fi",
                        "Checks every address on this network for common ports (web pages, file shares, printers, and more) and lists what answers.",
                        modifier = Modifier.weight(1f),
                    )
                } else if (hasScanned && !NetworkScanner.scanning && NetworkScanner.devices.isEmpty()) {
                    EmptyState(
                        Icons.Rounded.Router, "Nothing found",
                        "No device on this network answered on the ports checked. Some devices don't answer on any of them.",
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(NetworkScanner.devices, key = { it.address }) { d ->
                            DeviceRow(d, onClick = { selected = d })
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        ScanSettingsDialog(
            depth = depth, customRange = customRange,
            onChange = { d, r -> depth = d; customRange = r },
            onDismiss = { showSettings = false },
        )
    }
    selected?.let { d ->
        DeviceDetailDialog(
            d,
            onOpenBrowser = { port ->
                val scheme = if (port == 443 || port == 8443) "https" else "http"
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://${d.address}:$port"))) }
            },
            onAddNetworkFolder = { onAddNetworkFolder(d.address); selected = null },
            onDismiss = { selected = null },
        )
    }
}

private fun depthLabel(depth: ScanDepth, range: IntRange) = when (depth) {
    ScanDepth.COMMON -> "common ports"
    ScanDepth.EXTENDED -> "extended list"
    ScanDepth.CUSTOM -> "ports ${range.first}–${range.last}"
}

@Composable
private fun LinearProgress(progress: Float) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(cs.surfaceContainerHigh)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxSize().background(cs.primary))
    }
}

@Composable
private fun DeviceRow(device: ScannedDevice, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Dns, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(device.name ?: device.address, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (device.name != null) device.address else "${device.openPorts.size} open port${if (device.openPorts.size == 1) "" else "s"}",
                fontSize = 12.5.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text("${device.openPorts.size}", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.primary)
    }
}

@Composable
private fun DeviceDetailDialog(
    device: ScannedDevice,
    onOpenBrowser: (Int) -> Unit,
    onAddNetworkFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val webPort = device.openPorts.firstOrNull { it == 80 || it == 443 || it == 8080 || it == 8443 }
    val sharePort = device.openPorts.firstOrNull { it == 445 || it == 139 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(device.name ?: device.address) },
        text = {
            Column {
                if (device.name != null) Text(device.address, fontSize = 12.5.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
                device.openPorts.sorted().forEach { port ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text("$port", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.width(56.dp))
                        Text(PortCatalog.nameOf(port), fontSize = 13.sp, color = cs.onSurfaceVariant)
                    }
                }
                if (webPort != null || sharePort != null) {
                    Spacer(Modifier.height(10.dp))
                    if (webPort != null) {
                        TextButton(onClick = { onOpenBrowser(webPort) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Language, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Open in browser")
                        }
                    }
                    if (sharePort != null) {
                        TextButton(onClick = onAddNetworkFolder, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Folder, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Add as network folder")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ScanSettingsDialog(depth: ScanDepth, customRange: IntRange, onChange: (ScanDepth, IntRange) -> Unit, onDismiss: () -> Unit) {
    var choice by remember { mutableStateOf(depth) }
    var start by remember { mutableStateOf(customRange.first.toString()) }
    var end by remember { mutableStateOf(customRange.last.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scan settings") },
        text = {
            Column {
                DepthOption("Common services", "About 35 ports most devices actually use — fastest.", choice == ScanDepth.COMMON) { choice = ScanDepth.COMMON }
                DepthOption("Extended list", "A few hundred well-known ports — slower, more thorough.", choice == ScanDepth.EXTENDED) { choice = ScanDepth.EXTENDED }
                DepthOption("Custom range", "Pick the exact ports to check.", choice == ScanDepth.CUSTOM) { choice = ScanDepth.CUSTOM }
                if (choice == ScanDepth.CUSTOM) {
                    Row(modifier = Modifier.padding(start = 40.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = start, onValueChange = { start = it.filter(Char::isDigit) }, label = { Text("From") }, singleLine = true, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(value = end, onValueChange = { end = it.filter(Char::isDigit) }, label = { Text("To") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val range = if (choice == ScanDepth.CUSTOM) {
                    val s = start.toIntOrNull()?.coerceIn(1, 65535) ?: 1
                    val e = end.toIntOrNull()?.coerceIn(1, 65535) ?: s
                    (minOf(s, e))..(maxOf(s, e))
                } else customRange
                onChange(choice, range)
                onDismiss()
            }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DepthOption(title: String, body: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 2.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
            Text(body, fontSize = 12.sp, color = cs.onSurfaceVariant)
        }
    }
}
