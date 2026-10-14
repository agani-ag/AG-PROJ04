package com.agani.syncup.sensors

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.os.Build
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.agani.syncup.browser.ui.StepSlider
import kotlinx.coroutines.delay
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.roundToInt

/** The back camera's picture, filling [modifier]. [onCamera] gets the camera for zoom, light and focus. */
@Composable
private fun CameraPicture(view: PreviewView, modifier: Modifier, onCamera: (Camera?) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        var gone = false
        var provider: ProcessCameraProvider? = null
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (gone) return@addListener
            provider = runCatching { future.get() }.getOrNull()
            val camera = runCatching {
                provider?.unbindAll()
                provider?.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            }.getOrNull()
            onCamera(camera)
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            gone = true
            runCatching { provider?.unbind(preview) }
            onCamera(null)
        }
    }
    AndroidView({ view }, modifier)
}

@Composable
private fun rememberPreviewView(): PreviewView {
    val context = LocalContext.current
    // TextureView-based, so a still of the picture (freeze, colour) can be taken.
    return remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER } }
}

private val darkSlider @Composable get() = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = .3f))

// ============================================================================ Magnifier
@Composable
fun MagnifierTool(onBack: () -> Unit) {
    val access = rememberAccess(Manifest.permission.CAMERA)
    val view = rememberPreviewView()
    var camera by remember { mutableStateOf<Camera?>(null) }
    var zoom by remember { mutableFloatStateOf(.5f) }
    var bright by remember { mutableIntStateOf(0) }
    var light by remember { mutableStateOf(false) }
    var frozen by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(camera, zoom) { camera?.cameraControl?.setLinearZoom(zoom) }
    LaunchedEffect(camera, light) { camera?.takeIf { it.cameraInfo.hasFlashUnit() }?.cameraControl?.enableTorch(light) }
    LaunchedEffect(camera, bright) { camera?.cameraControl?.setExposureCompensationIndex(bright) }
    val range = camera?.cameraInfo?.exposureState?.exposureCompensationRange
    KeepScreenOn()

    ToolPage("Magnifier", onBack) {
        if (!access.granted) {
            AccessCard("Allow the camera", "The magnifier shows the camera's picture, zoomed in. Nothing is saved or sent.", access, Modifier.padding(16.dp))
            return@ToolPage
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            CameraPicture(view, Modifier.fillMaxSize().clickable {
                // Tap to focus on the middle again (small print moves when the hand does).
                val cam = camera ?: return@clickable
                val point = view.meteringPointFactory.createPoint(view.width / 2f, view.height / 2f)
                runCatching { cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build()) }
            }) { camera = it }
            frozen?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(22.dp))
                    .background(Color(0xEB0F172A)).padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Zoom", color = Color.White, fontSize = 13.sp, modifier = Modifier.width(70.dp))
                    Box(Modifier.weight(1f)) {
                        StepSlider(value = zoom, range = 0f..1f, step = .1f, label = "zoom", onChange = { zoom = it }, colors = darkSlider)
                    }
                }
                if (range != null && range.upper > range.lower) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Brighter", color = Color.White, fontSize = 13.sp, modifier = Modifier.width(70.dp))
                        Box(Modifier.weight(1f)) {
                            StepSlider(
                                value = bright.toFloat(), range = range.lower.toFloat()..range.upper.toFloat(), step = 1f, label = "brightness",
                                onChange = { bright = it.roundToInt() }, colors = darkSlider,
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    if (camera?.cameraInfo?.hasFlashUnit() == true) {
                        IconButton(onClick = { light = !light }) {
                            Icon(if (light) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff, if (light) "Light off" else "Light on", tint = if (light) Color(0xFFFDE68A) else Color.White)
                        }
                    }
                    Button(
                        onClick = { frozen = if (frozen == null) view.bitmap else null },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF0F172A)),
                        modifier = Modifier.weight(1f),
                    ) { Text(if (frozen == null) "Freeze" else "Go live") }
                }
            }
        }
    }
}

// ============================================================================ Colour picker
private class Named(val name: String, val rgb: Int) {
    val lab = lab(rgb)
}

/** Everyday colour names — what a tailor, a painter or a shop would say. */
private val NAMES = listOf(
    "Black" to 0x000000, "Charcoal" to 0x36454F, "Dark grey" to 0x555555, "Grey" to 0x808080, "Slate grey" to 0x708090,
    "Silver" to 0xC0C0C0, "Ash grey" to 0xB2BEB5, "Off-white" to 0xFAF9F6, "White" to 0xFFFFFF, "Ivory" to 0xFFFFF0,
    "Cream" to 0xFFFDD0, "Beige" to 0xF5F5DC, "Sand" to 0xC2B280, "Tan" to 0xD2B48C, "Khaki" to 0xC3B091,
    "Taupe" to 0x8B7D6B, "Mocha" to 0x967969, "Camel" to 0xC19A6B, "Walnut" to 0x773F1A,
    "Brown" to 0x8B4513, "Coffee" to 0x6F4E37, "Chocolate" to 0x7B3F00, "Rust" to 0xB7410E, "Copper" to 0xB87333,
    "Bronze" to 0xCD7F32, "Henna" to 0x9C3F25, "Maroon" to 0x800000, "Wine" to 0x722F37, "Burgundy" to 0x800020,
    "Brick red" to 0xB22222, "Red" to 0xE41B17, "Crimson" to 0xDC143C, "Rani pink" to 0xE0115F, "Rose" to 0xFF007F,
    "Hot pink" to 0xFF69B4, "Pink" to 0xFFC0CB, "Baby pink" to 0xF4C2C2, "Peach" to 0xFFDAB9, "Salmon" to 0xFA8072,
    "Coral" to 0xFF7F50, "Orange" to 0xFF8C00, "Saffron" to 0xF4C430, "Turmeric yellow" to 0xE3A857, "Mustard" to 0xE1AD01,
    "Gold" to 0xFFD700, "Yellow" to 0xFFEF00, "Lemon yellow" to 0xFFF44F, "Olive" to 0x808000, "Olive green" to 0x556B2F,
    "Lime green" to 0x32CD32, "Parrot green" to 0x12AD2B, "Mint green" to 0x98FF98, "Pista green" to 0x93C572, "Green" to 0x008000,
    "Emerald" to 0x50C878, "Bottle green" to 0x006A4E, "Dark green" to 0x013220, "Sea green" to 0x2E8B57, "Jade" to 0x00A86B,
    "Teal" to 0x008080, "Peacock blue" to 0x0E7490, "Turquoise" to 0x40E0D0, "Aqua" to 0x00FFFF, "Sky blue" to 0x87CEEB,
    "Light blue" to 0xADD8E6, "Steel blue" to 0x4682B4, "Denim" to 0x1560BD, "Blue" to 0x0047FF, "Royal blue" to 0x4169E1,
    "Cobalt" to 0x0047AB, "Ink blue" to 0x1F3A68, "Navy blue" to 0x000080, "Indigo" to 0x4B0082, "Violet" to 0x8F00FF,
    "Purple" to 0x800080, "Plum" to 0x8E4585, "Magenta" to 0xFF00FF, "Mauve" to 0xE0B0FF, "Lilac" to 0xC8A2C8,
    "Lavender" to 0xE6E6FA,
).map { Named(it.first, it.second) }

private fun lab(rgb: Int): FloatArray {
    fun lin(c: Int): Double = (c / 255.0).let { if (it <= .04045) it / 12.92 else ((it + .055) / 1.055).pow(2.4) }
    val r = lin((rgb shr 16) and 0xFF)
    val g = lin((rgb shr 8) and 0xFF)
    val b = lin(rgb and 0xFF)
    val x = (r * .4124 + g * .3576 + b * .1805) / .95047
    val y = r * .2126 + g * .7152 + b * .0722
    val z = (r * .0193 + g * .1192 + b * .9505) / 1.08883
    fun f(t: Double) = if (t > .008856) cbrt(t) else 7.787 * t + 16.0 / 116
    return floatArrayOf((116 * f(y) - 16).toFloat(), (500 * (f(x) - f(y))).toFloat(), (200 * (f(y) - f(z))).toFloat())
}

private fun nameOf(rgb: Int): String {
    val l = lab(rgb)
    return NAMES.minBy { n -> (0..2).sumOf { ((l[it] - n.lab[it]) * (l[it] - n.lab[it])).toDouble() } }.name
}

private fun hex(rgb: Int) = "#%06X".format(rgb and 0xFFFFFF)

/** The average colour of a small square in the middle of [bitmap]. */
private fun middleColour(bitmap: Bitmap): Int {
    val cx = bitmap.width / 2
    val cy = bitmap.height / 2
    var r = 0
    var g = 0
    var b = 0
    var n = 0
    for (x in cx - 4..cx + 4) for (y in cy - 4..cy + 4) {
        if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) continue
        val p = bitmap.getPixel(x, y)
        r += (p shr 16) and 0xFF
        g += (p shr 8) and 0xFF
        b += p and 0xFF
        n++
    }
    if (n == 0) return 0
    return ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
}

@Composable
fun ColourTool(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { SensorPrefs.prefs(context) }
    val access = rememberAccess(Manifest.permission.CAMERA)
    val view = rememberPreviewView()
    var camera by remember { mutableStateOf<Camera?>(null) }
    var colour by remember { mutableStateOf<Int?>(null) }
    var frozen by remember { mutableStateOf<Bitmap?>(null) }
    var light by remember { mutableStateOf(false) }
    val saved = remember {
        mutableStateListOf<Int>().apply {
            addAll(prefs.getString("colours", "").orEmpty().split(',').mapNotNull { it.toIntOrNull(16) })
        }
    }
    val onScreen = rememberOnScreen()
    LaunchedEffect(camera, frozen, onScreen) {
        while (camera != null && frozen == null && onScreen) {
            view.bitmap?.let { colour = middleColour(it) }
            delay(250)
        }
    }
    LaunchedEffect(camera, light) { camera?.takeIf { it.cameraInfo.hasFlashUnit() }?.cameraControl?.enableTorch(light) }
    fun copy(rgb: Int) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Colour", hex(rgb)))
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Copied ${hex(rgb)}", Toast.LENGTH_SHORT).show()
    }

    ToolPage("Colour picker", onBack) {
        if (!access.granted) {
            AccessCard("Allow the camera", "Point the camera at anything to get its colour. Nothing is saved or sent.", access, Modifier.padding(16.dp))
            return@ToolPage
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            CameraPicture(view, Modifier.fillMaxSize()) { camera = it }
            frozen?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            // The circle: its middle is what's measured.
            Box(
                Modifier.align(Alignment.Center).size(64.dp).clip(CircleShape)
                    .border(3.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(width = 2.dp, height = 22.dp).background(Color.White))
                Box(Modifier.size(width = 22.dp, height = 2.dp).background(Color.White))
            }
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                IconButton(onClick = { light = !light }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    Icon(if (light) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff, if (light) "Light off" else "Light on", tint = if (light) Color(0xFFFDE68A) else Color.White)
                }
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(22.dp))
                    .background(Color(0xEB0F172A)).padding(14.dp),
            ) {
                val c = colour
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(58.dp).clip(RoundedCornerShape(16.dp)).background(if (c != null) Color(0xFF000000.toInt() or c) else Color.DarkGray))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c?.let { nameOf(it) } ?: "Point at a colour", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Color.White)
                        if (c != null) {
                            Text(
                                "${hex(c)} · RGB ${(c shr 16) and 0xFF}, ${(c shr 8) and 0xFF}, ${c and 0xFF}",
                                fontSize = 12.5.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFCBD5E1), maxLines = 1,
                            )
                        }
                    }
                    if (c != null) {
                        IconButton(onClick = { copy(c) }) { Icon(Icons.Rounded.ContentCopy, "Copy", tint = Color.White) }
                    }
                }
                if (saved.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                        Text("Saved", fontSize = 12.sp, color = Color(0xFF94A3B8))
                        saved.take(8).forEach { s ->
                            Box(
                                Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF000000.toInt() or s))
                                    .clickable {
                                        colour = s
                                        copy(s)
                                    },
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 12.dp)) {
                    OutlinedButton(
                        onClick = { frozen = if (frozen == null) view.bitmap else null },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        modifier = Modifier.weight(1f),
                    ) { Text(if (frozen == null) "Freeze" else "Go live") }
                    Button(
                        onClick = {
                            val s = c ?: return@Button
                            saved.remove(s)
                            saved.add(0, s)
                            while (saved.size > 12) saved.removeAt(saved.lastIndex)
                            prefs.edit().putString("colours", saved.joinToString(",") { "%06X".format(it) }).apply()
                        },
                        enabled = c != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("Save colour") }
                }
            }
        }
    }
}
