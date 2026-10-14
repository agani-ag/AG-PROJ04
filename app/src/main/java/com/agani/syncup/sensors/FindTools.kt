package com.agani.syncup.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.agani.syncup.browser.ui.StepSlider
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ============================================================================ Metal finder
@Composable
fun MetalTool(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val beeper = rememberBeeper()
    val history = remember { mutableStateListOf<Float>() }
    var now by remember { mutableFloatStateOf(0f) }
    var zero by remember { mutableStateOf<Float?>(null) }
    var sound by remember { mutableStateOf(true) }
    var last by remember { mutableLongStateOf(0L) }
    SensorStream(Sensor.TYPE_MAGNETIC_FIELD, SensorManager.SENSOR_DELAY_UI) { e ->
        val m = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
        now = if (now == 0f) m else now + (m - now) * .35f
        val t = SystemClock.elapsedRealtime()
        if (t - last >= 50) {
            history.add(now)
            if (history.size > 200) history.removeAt(0)
            last = t
        }
    }
    // How far from normal: from the zero the person set, else from Earth's own field (~25–65 µT).
    val extra = zero?.let { abs(now - it) } ?: (now - 60f).coerceAtLeast(0f)
    val (words, color) = when {
        now == 0f -> "Reading…" to cs.onSurfaceVariant
        extra < 15 -> "Nothing here" to cs.onSurfaceVariant
        extra < 50 -> "Something nearby" to Color(0xFFD97706)
        else -> "Metal close by" to Color(0xFFDC2626)
    }
    LaunchedEffect(sound, extra > 15) {
        while (sound && extra > 15) {
            beeper.beep(40, high = extra > 50)
            delay((900 - extra * 6).coerceIn(90f, 900f).toLong())
        }
    }
    ToolPage("Metal finder", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth(.6f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                val ring = color
                val track = cs.surfaceContainerHigh
                Canvas(Modifier.fillMaxSize()) {
                    val s = 14.dp.toPx()
                    drawArc(track, 135f, 270f, false, Offset(s / 2, s / 2), Size(size.width - s, size.height - s), style = Stroke(s, cap = StrokeCap.Round))
                    drawArc(ring, 135f, 270f * (extra / 150f).coerceIn(0f, 1f), false, Offset(s / 2, s / 2), Size(size.width - s, size.height - s), style = Stroke(s, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${now.roundToInt()}", fontSize = 52.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace, color = cs.onSurface)
                    Text("µT", fontSize = 16.sp, color = cs.onSurfaceVariant)
                }
            }
            Text(words, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = color)
            Spacer(Modifier.height(14.dp))
            LineGraph(history, max(0f, (history.minOrNull() ?: 0f) - 10f), max(120f, (history.maxOrNull() ?: 0f) + 10f), Modifier.fillMaxWidth().height(120.dp), slots = 200)
            Spacer(Modifier.height(12.dp))
            Stats("~45" to "Normal (µT)", "100+" to "Near metal", (zero?.let { "${it.roundToInt()}" } ?: "Earth") to "Zero")
            Spacer(Modifier.height(12.dp))
            Note("Slide the top of the phone slowly along a wall to find nails, screws, pipes or wires. It beeps faster as it gets stronger. Keep it away from speakers and magnets.")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                OutlinedButton(onClick = { sound = !sound }, modifier = Modifier.weight(1f)) { Text(if (sound) "Sound off" else "Sound on") }
                Button(onClick = { zero = if (zero == null) now else null }, modifier = Modifier.weight(1f)) { Text(if (zero == null) "Set this as zero" else "Clear zero") }
            }
        }
    }
}

// ============================================================================ Flashlight
/**
 * The phone's torch (the camera flash). Steady stays on after leaving the tool — like the phone's own
 * torch — and the Sensors tile shows it; Blink and SOS stop when the tool closes.
 */
object Torch {
    var on by mutableStateOf(false)
        private set
    private var cameraId: String? = null
    private var ready = false
    var maxLevel = 1
        private set

    fun init(context: Context) {
        if (ready) return
        ready = true
        val cm = context.getSystemService(CameraManager::class.java) ?: return
        cameraId = runCatching {
            cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        }.getOrNull()
        val id = cameraId ?: return
        if (Build.VERSION.SDK_INT >= 33) {
            maxLevel = runCatching { cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) }.getOrNull() ?: 1
        }
        // Turned off from Quick Settings or by another app: keep up.
        cm.registerTorchCallback(object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(camera: String, enabled: Boolean) {
                if (camera == cameraId) on = enabled
            }
        }, Handler(Looper.getMainLooper()))
    }

    val available: Boolean get() = cameraId != null

    /** [level] 1…[maxLevel] where the phone allows brightness (Android 13+), else just on. */
    fun set(context: Context, turnOn: Boolean, level: Int = maxLevel) {
        val cm = context.getSystemService(CameraManager::class.java) ?: return
        val id = cameraId ?: return
        runCatching {
            if (turnOn && Build.VERSION.SDK_INT >= 33 && maxLevel > 1) cm.turnOnTorchWithStrengthLevel(id, level.coerceIn(1, maxLevel))
            else cm.setTorchMode(id, turnOn)
            on = turnOn
        }
    }
}

private val MORSE_SOS = listOf(1, 1, 1, 3, 3, 3, 1, 1, 1) // · · · — — — · · ·

@Composable
fun FlashlightTool(onBack: () -> Unit, onMagnifier: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val prefs = remember { SensorPrefs.prefs(context) }
    remember { Torch.init(context) }
    var mode by remember { mutableIntStateOf(if (Torch.available) 0 else 3) } // steady, blink, SOS, screen light
    var level by remember { mutableFloatStateOf(prefs.getInt("torch_level", Torch.maxLevel).toFloat()) }
    var blinkMs by remember { mutableFloatStateOf(500f) }
    var running by remember { mutableStateOf(Torch.on) }
    var screenLight by remember { mutableStateOf(false) }

    // Blink and SOS run only while this is open; leaving stops them (and the light).
    LaunchedEffect(mode, running, blinkMs) {
        if (!running || mode == 0 || mode == 3) return@LaunchedEffect
        try {
            while (true) {
                if (mode == 1) {
                    Torch.set(context, true, level.roundToInt())
                    delay(blinkMs.toLong() / 2)
                    Torch.set(context, false)
                    delay(blinkMs.toLong() / 2)
                } else {
                    MORSE_SOS.forEachIndexed { i, units ->
                        Torch.set(context, true, level.roundToInt())
                        delay(200L * units)
                        Torch.set(context, false)
                        delay(if (i == 2 || i == 5) 600 else 200)
                    }
                    delay(1_400)
                }
            }
        } finally {
            Torch.set(context, false)
        }
    }

    if (screenLight) {
        ScreenLight(onClose = { screenLight = false })
        return
    }

    ToolPage("Flashlight", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))
            val lit = if (mode == 0) Torch.on else running
            Box(
                Modifier.size(150.dp).clip(CircleShape).background(if (lit) Color(0xFFFDE68A) else cs.surfaceContainerHigh)
                    .clickable {
                        when (mode) {
                            0 -> Torch.set(context, !Torch.on, level.roundToInt())
                            3 -> screenLight = true
                            else -> running = !running
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PowerSettingsNew, if (lit) "Turn off" else "Turn on", tint = if (lit) Color(0xFF92400E) else cs.onSurfaceVariant, modifier = Modifier.size(64.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(if (lit) "On" else "Off", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
            Spacer(Modifier.height(20.dp))
            if (Torch.available && Torch.maxLevel > 1 && mode != 3) {
                Text("Brightness", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.fillMaxWidth())
                StepSlider(
                    value = level, range = 1f..Torch.maxLevel.toFloat(), step = 1f, label = "brightness",
                    onChange = { level = it.roundToInt().toFloat() },
                    onChangeFinished = {
                        prefs.edit().putInt("torch_level", level.roundToInt()).apply()
                        if (mode == 0 && Torch.on) Torch.set(context, true, level.roundToInt())
                    },
                )
            }
            if (mode == 1) {
                Text("Blink speed", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.fillMaxWidth())
                StepSlider(value = 1_600f - blinkMs, range = 200f..1_400f, step = 100f, label = "speed", onChange = { blinkMs = 1_600f - it })
            }
            Spacer(Modifier.height(8.dp))
            Text("Mode", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Choice(
                listOf("Steady", "Blink", "SOS", "Screen"), mode,
                onSelect = {
                    if (it != 3 && !Torch.available) return@Choice
                    running = false
                    if (Torch.on) Torch.set(context, false)
                    mode = it
                },
            )
            if (!Torch.available) {
                Spacer(Modifier.height(10.dp))
                Note("This phone has no camera flash — Screen light turns the whole screen white instead.")
            }
            Spacer(Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surfaceContainerLow).clickable(onClick = onMagnifier)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Icon(Icons.Rounded.ZoomIn, null, tint = cs.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "Small print on a medicine strip or a bill? Magnifier zooms in with the light on.",
                    fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** The whole screen white and as bright as it goes; tap or Back to close. */
@Composable
private fun ScreenLight(onClose: () -> Unit) {
    val view = LocalView.current
    BackHandler(onBack = onClose)
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val before = window?.attributes?.screenBrightness
        window?.attributes = window?.attributes?.apply { screenBrightness = 1f }
        view.keepScreenOn = true
        onDispose {
            window?.attributes = window?.attributes?.apply { screenBrightness = before ?: -1f }
            view.keepScreenOn = false
        }
    }
    Box(Modifier.fillMaxSize().background(Color.White).clickable(onClick = onClose), contentAlignment = Alignment.BottomCenter) {
        Text("Tap to close", fontSize = 13.sp, color = Color.Black.copy(alpha = .35f), modifier = Modifier.padding(32.dp))
    }
}

// ============================================================================ Wi-Fi finder
private class Spot(val name: String, val rssi: Int, val at: Long)

private fun wifiWords(rssi: Int): Pair<String, Color> = when {
    rssi >= -50 -> "Excellent" to Color(0xFF16A34A)
    rssi >= -60 -> "Very good" to Color(0xFF16A34A)
    rssi >= -67 -> "Good" to Color(0xFF65A30D)
    rssi >= -75 -> "Okay" to Color(0xFFD97706)
    rssi >= -85 -> "Weak" to Color(0xFFDC2626)
    else -> "Very weak" to Color(0xFFDC2626)
}

private fun channelOf(mhz: Int): Int = when {
    mhz in 2412..2484 -> if (mhz == 2484) 14 else (mhz - 2407) / 5
    mhz in 5000..5925 -> (mhz - 5000) / 5
    mhz in 5925..7125 -> (mhz - 5950) / 5
    else -> 0
}

private fun bandOf(mhz: Int): String = when {
    mhz < 3000 -> "2.4 GHz"
    mhz < 5925 -> "5 GHz"
    else -> "6 GHz"
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
@Composable
fun WifiTool(onBack: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val wm = remember { context.applicationContext.getSystemService(WifiManager::class.java) }
    val access = rememberAccess(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    val onScreen = rememberOnScreen()
    var rssi by remember { mutableIntStateOf(-127) }
    var ssid by remember { mutableStateOf<String?>(null) }
    var mhz by remember { mutableIntStateOf(0) }
    var speed by remember { mutableIntStateOf(0) }
    var enabled by remember { mutableStateOf(wm?.isWifiEnabled == true) }
    val spots = remember { mutableStateListOf<Spot>() }
    var naming by remember { mutableStateOf(false) }
    var others by remember { mutableStateOf(false) }
    KeepScreenOn()
    LaunchedEffect(onScreen) {
        while (onScreen) {
            enabled = wm?.isWifiEnabled == true
            val info = wm?.connectionInfo
            if (info != null && info.networkId != -1 && info.rssi > -127) {
                rssi = if (rssi == -127) info.rssi else ((rssi * 2 + info.rssi) / 3f).roundToInt()
                ssid = info.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
                mhz = info.frequency
                speed = info.linkSpeed
            } else {
                rssi = -127
            }
            delay(1_000)
        }
    }
    val connected = rssi > -127
    val (words, color) = wifiWords(rssi)

    ToolPage("Wi-Fi finder", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!enabled) {
                Column(
                    Modifier.padding(vertical = 16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerLow).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Wi-Fi is off", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        val panel = if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)
                        runCatching { context.startActivity(panel.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }) { Text("Turn on Wi-Fi") }
                }
                return@Column
            }
            Box(Modifier.padding(top = 8.dp).fillMaxWidth(.8f).aspectRatio(1.6f), contentAlignment = Alignment.BottomCenter) {
                val track = cs.surfaceContainerHigh
                Canvas(Modifier.fillMaxSize()) {
                    val s = 18.dp.toPx()
                    val d = min(size.width, size.height * 2f) - s
                    val tl = Offset((size.width - d) / 2, s / 2)
                    drawArc(track, 180f, 180f, false, tl, Size(d, d), style = Stroke(s, cap = StrokeCap.Round))
                    if (connected) {
                        drawArc(color, 180f, 180f * ((rssi + 95) / 65f).coerceIn(0.02f, 1f), false, tl, Size(d, d), style = Stroke(s, cap = StrokeCap.Round))
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (connected) "$rssi" else "—", fontSize = 44.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace, color = cs.onSurface)
                    Text(if (connected) "dBm · $words" else "Not connected", fontSize = 13.sp, color = if (connected) color else cs.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(10.dp))
            if (connected) {
                Text(ssid ?: "Your Wi-Fi", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                Text("${bandOf(mhz)} · channel ${channelOf(mhz)} · $speed Mbps", fontSize = 12.5.sp, color = cs.onSurfaceVariant)
                if (ssid == null && !access.granted) {
                    TextButton(onClick = access.ask) { Text("Show the network's name") }
                }
            } else {
                Text("Connect to a Wi-Fi network to measure it. Other networks still shows what's around.", fontSize = 13.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(14.dp))
            Text("Walk around — mark each spot", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Card {
                spots.forEach { s ->
                    val (w, c) = wifiWords(s.rssi)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, fontSize = 14.sp, color = cs.onSurface)
                            Text(ago(s.at), fontSize = 12.sp, color = cs.onSurfaceVariant)
                        }
                        Text("${s.rssi} · $w", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text("Here", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.weight(1f))
                    Text(if (connected) "$rssi · $words" else "—", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (connected) color else cs.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                OutlinedButton(onClick = { naming = true }, enabled = connected, modifier = Modifier.weight(1f)) { Text("Mark this spot") }
                Button(onClick = { if (access.granted) others = true else access.ask() }, modifier = Modifier.weight(1f)) { Text("Other networks") }
            }
            Note("Higher is better: −50 is excellent, −70 is okay, below −80 is weak. Walls, mirrors and fish tanks block Wi-Fi.")
            Spacer(Modifier.height(16.dp))
        }
    }

    if (naming) {
        var name by remember { mutableStateOf("Spot ${spots.size + 1}") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Name this spot") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(30) }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    spots.add(Spot(name.ifBlank { "Spot ${spots.size + 1}" }, rssi, System.currentTimeMillis()))
                    naming = false
                }) { Text("Mark") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
    if (others) {
        ModalBottomSheet(onDismissRequest = { others = false }) { OtherNetworks(wm) }
    }
}

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
@Composable
private fun OtherNetworks(wm: WifiManager?) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var results by remember { mutableStateOf(wm?.scanResults.orEmpty()) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                results = runCatching { wm?.scanResults.orEmpty() }.getOrDefault(emptyList())
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_EXPORTED)
        runCatching { wm?.startScan() }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    val sorted = results.sortedByDescending { it.level }
    val crowd = results.filter { it.frequency < 3000 }.groupBy { channelOf(it.frequency) }.mapValues { it.value.size }
    Column(Modifier.padding(bottom = 24.dp)) {
        Text("Networks around you", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.padding(horizontal = 24.dp))
        // Worth saying only when there's more than one network on 2.4 GHz to compare.
        if (crowd.values.sum() >= 2) {
            Text(
                "2.4 GHz: " + listOf(1, 6, 11).joinToString(" · ") { "channel $it has ${crowd[it] ?: 0}" } + " — the emptiest is best for your router.",
                fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )
        }
        if (sorted.isEmpty()) {
            Text("Looking… (Location must be on for Android to share this list.)", fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(24.dp))
        }
        LazyColumn {
            items(sorted, key = { it.BSSID + it.frequency }) { r ->
                val (w, c) = wifiWords(r.level)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(r.SSID?.takeIf { it.isNotBlank() } ?: "Hidden network", fontSize = 14.5.sp, color = cs.onSurface, maxLines = 1)
                        Text("${bandOf(r.frequency)} · channel ${channelOf(r.frequency)}", fontSize = 12.sp, color = cs.onSurfaceVariant)
                    }
                    Text("${r.level} · $w", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c)
                }
            }
        }
    }
}

internal fun ago(at: Long): String {
    val s = (System.currentTimeMillis() - at) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        else -> "${s / 3600} h ago"
    }
}

// ============================================================================ Find my earbuds
private class Gadget(val address: String, name: String, bonded: Boolean) {
    var name by mutableStateOf(name)
    var bonded by mutableStateOf(bonded)
    var rssi by mutableFloatStateOf(-100f)
    var seenAt by mutableLongStateOf(0L)
    /** The signal a few seconds ago, for "warmer / colder". */
    var before by mutableFloatStateOf(-100f)
    var beforeAt = 0L

    fun heard(level: Int) {
        val now = SystemClock.elapsedRealtime()
        rssi = if (seenAt == 0L || now - seenAt > 8_000) level.toFloat() else rssi + (level - rssi) * .3f
        if (now - beforeAt > 3_000) {
            before = rssi
            beforeAt = now
        }
        seenAt = now
    }
}

private fun closeness(rssi: Float): Pair<String, Color> = when {
    rssi >= -50 -> "Very close — within 1 m" to Color(0xFFDC2626)
    rssi >= -62 -> "Close — a few steps" to Color(0xFFF97316)
    rssi >= -75 -> "In this room or the next" to Color(0xFFF59E0B)
    rssi >= -85 -> "Far" to Color(0xFF38BDF8)
    else -> "Very far" to Color(0xFF64748B)
}

@SuppressLint("MissingPermission")
@Composable
fun EarbudsTool(onBack: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val beeper = rememberBeeper()
    val perms = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val access = rememberAccess(*perms)
    val adapter = remember { context.getSystemService(BluetoothManager::class.java)?.adapter }
    val onScreen = rememberOnScreen()
    var btOn by remember { mutableStateOf(adapter?.isEnabled == true) }
    // Android's "Turn on Bluetooth?" box doesn't take SyncUp off screen, so keep checking.
    LaunchedEffect(onScreen) {
        while (onScreen) {
            btOn = adapter?.isEnabled == true
            delay(1_000)
        }
    }
    val gadgets = remember { mutableStateMapOf<String, Gadget>() }
    var picked by remember { mutableStateOf<String?>(null) }
    var beepOn by remember { mutableStateOf(true) }
    var tick by remember { mutableLongStateOf(0L) }
    KeepScreenOn()

    fun nameOf(d: BluetoothDevice, fromAd: String?): String? =
        fromAd?.takeIf { it.isNotBlank() } ?: runCatching { d.name }.getOrNull()?.takeIf { it.isNotBlank() }

    // Paired things are listed even before they're heard.
    LaunchedEffect(access.granted, btOn) {
        if (!access.granted || !btOn) return@LaunchedEffect
        runCatching { adapter?.bondedDevices.orEmpty() }.getOrDefault(emptySet()).forEach { d ->
            if (gadgets[d.address] == null) gadgets[d.address] = Gadget(d.address, nameOf(d, null) ?: d.address, true)
        }
    }
    // BLE adverts (most earbuds out of the case) and classic discovery (older ones), together.
    DisposableEffect(access.granted, btOn, onScreen) {
        val scanner = adapter?.bluetoothLeScanner
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = nameOf(result.device, result.scanRecord?.deviceName) ?: return
                val g = gadgets.getOrPut(result.device.address) { Gadget(result.device.address, name, false) }
                g.name = name
                g.heard(result.rssi)
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val d = androidx.core.content.IntentCompat.getParcelableExtra(i, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                        val level = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                        val name = nameOf(d, i.getStringExtra(BluetoothDevice.EXTRA_NAME)) ?: return
                        if (level == Short.MIN_VALUE) return
                        val g = gadgets.getOrPut(d.address) { Gadget(d.address, name, false) }
                        g.heard(level.toInt())
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> runCatching { adapter?.startDiscovery() }
                }
            }
        }
        var started = false
        if (access.granted && btOn && onScreen && scanner != null) {
            runCatching {
                scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
                ContextCompat.registerReceiver(
                    context, receiver,
                    IntentFilter().apply {
                        addAction(BluetoothDevice.ACTION_FOUND)
                        addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                    },
                    ContextCompat.RECEIVER_EXPORTED,
                )
                adapter.startDiscovery()
                started = true
            }
        }
        onDispose {
            if (started) {
                runCatching { scanner?.stopScan(callback) }
                runCatching { adapter?.cancelDiscovery() }
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
    }
    // Ticks so "lost the signal" shows even when nothing new is heard.
    LaunchedEffect(onScreen) {
        while (onScreen) {
            tick = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val target = picked?.let { gadgets[it] }
    val lost = target != null && (target.seenAt == 0L || tick - target.seenAt > 8_000)
    LaunchedEffect(picked, beepOn, lost) {
        while (picked != null && beepOn && !lost) {
            val g = gadgets[picked] ?: break
            beeper.beep(50, high = g.rssi >= -62)
            delay((1_500 + (g.rssi + 40) * 27).toLong().coerceIn(150, 1_500))
        }
    }
    BackHandler(enabled = picked != null) { picked = null }

    ToolPage("Find my earbuds", onBack) {
        when {
            !access.granted -> AccessCard(
                "Allow nearby devices",
                "Find my earbuds listens for Bluetooth signals while this screen is open, to tell you how close they are.",
                access, Modifier.padding(16.dp),
            )
            !btOn -> Column(
                Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerLow).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Bluetooth is off", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    runCatching { context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text("Turn on Bluetooth") }
            }
            Build.VERSION.SDK_INT <= 30 && !locationOn(context) -> Column(
                Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerLow).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Turn on Location", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                Text("On this Android version, Bluetooth searching needs Location on.", fontSize = 13.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text("Turn on Location") }
            }
            target != null -> Finder(target, lost, beepOn, onBeep = { beepOn = !beepOn }, onStop = { picked = null })
            else -> GadgetList(gadgets.values.toList(), tick, onPick = { picked = it.address })
        }
    }
}

@Composable
private fun GadgetList(list: List<Gadget>, now: Long, onPick: (Gadget) -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val heard = list.filter { it.seenAt > 0 && now - it.seenAt < 30_000 }
    val sorted = heard.sortedWith(compareByDescending<Gadget> { it.bonded }.thenByDescending { it.rssi }) +
        list.filter { it.bonded && it !in heard }
    LazyColumn {
        item {
            Text(
                "Pick what you're looking for. Paired things come first.",
                fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }
        items(sorted, key = { it.address }) { g ->
            val near = g in heard
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable(enabled = near) { onPick(g) }.padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (near) cs.primaryContainer else cs.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Headphones, null, tint = if (near) cs.onPrimaryContainer else cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(g.name, fontSize = 15.sp, fontWeight = if (g.bonded) FontWeight.Medium else FontWeight.Normal, color = if (near) cs.onSurface else cs.onSurfaceVariant, maxLines = 1)
                    Text(
                        when {
                            near && g.bonded -> "Paired with this phone"
                            near -> "Not paired"
                            else -> "Not heard — out of range, in its case, or connected to this phone"
                        },
                        fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 2,
                    )
                }
                if (near) Text(closeness(g.rssi).first.substringBefore(" —"), fontSize = 12.5.sp, color = cs.primary)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Listening for Bluetooth things nearby…", fontSize = 13.sp, color = cs.onSurfaceVariant)
            }
        }
        item {
            Note(
                "Earbuds show up when they're out of the case. Ones connected to this phone stay quiet — disconnect them in Bluetooth settings first.",
                Modifier.padding(horizontal = 16.dp),
            )
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Bluetooth settings") }
        }
    }
}

@Composable
private fun Finder(g: Gadget, lost: Boolean, beepOn: Boolean, onBeep: () -> Unit, onStop: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val (words, color) = if (lost) "Lost the signal — keep walking slowly" to Color(0xFF64748B) else closeness(g.rssi)
    val warmer = g.rssi - g.before
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(g.name, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.padding(top = 4.dp))
        Text(if (g.bonded) "Paired with this phone" else "Not paired", fontSize = 12.5.sp, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        val heat = if (lost) 0f else ((g.rssi + 95f) / 55f).coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth(.75f).aspectRatio(1f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2
                drawCircle(color.copy(alpha = .15f), r)
                drawCircle(color.copy(alpha = .3f), r * (.45f + heat * .35f))
                drawCircle(color, r * (.2f + heat * .3f))
            }
            Text(
                if (lost) "?" else if (heat > .7f) "Hot" else if (heat > .4f) "Warm" else "Cold",
                fontSize = 26.sp, fontWeight = FontWeight.Medium, color = Color.White,
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(words, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, textAlign = TextAlign.Center)
        if (!lost && abs(warmer) >= 2) {
            Text(if (warmer > 0) "Getting warmer ↑" else "Getting colder ↓", fontSize = 13.5.sp, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        Note("Walk slowly — the circle gets redder and the beep faster as you get closer. Earbuds must be out of the case.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
            OutlinedButton(onClick = onBeep, modifier = Modifier.weight(1f)) { Text(if (beepOn) "Beep off" else "Beep on") }
            Button(onClick = onStop, modifier = Modifier.weight(1f)) { Text("Stop") }
        }
    }
}
