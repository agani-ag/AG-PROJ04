package com.agani.syncup.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.WavingHand
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.agani.syncup.browser.ui.StepSlider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ============================================================================ Wave to control
@Composable
fun WaveTool(onBack: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    remember { WaveControl.init(context) }
    var near by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf<WaveControl.Gesture?>(null) }
    var lastAt by remember { mutableLongStateOf(0L) }
    var choosing by remember { mutableStateOf<WaveControl.Gesture?>(null) }
    val onScreen = rememberOnScreen()
    // While this is open, waves are only shown here — nothing gets paused by accident.
    DisposableEffect(Unit) {
        WaveControl.trying = true
        onDispose { WaveControl.trying = false }
    }
    DisposableEffect(onScreen) {
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        val detector = sensor?.let {
            WaveDetector(it.maximumRange, onNear = { n -> near = n }) { g ->
                last = g
                lastAt = SystemClock.elapsedRealtime()
            }
        }
        if (onScreen && detector != null) sm.registerListener(detector, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        onDispose {
            detector?.let {
                sm?.unregisterListener(it)
                it.stop()
            }
        }
    }
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(lastAt) {
        now = SystemClock.elapsedRealtime()
        delay(3_000)
        now = SystemClock.elapsedRealtime()
    }
    val fresh = last != null && now - lastAt < 3_000

    ToolPage("Wave to control", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.size(130.dp).clip(CircleShape).background(if (near) cs.primary else cs.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.WavingHand, null, tint = if (near) cs.onPrimary else cs.onPrimaryContainer, modifier = Modifier.size(60.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Wave your hand over the top of the phone — no need to touch the screen.",
                fontSize = 15.sp, lineHeight = 21.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Card {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Music & Video player", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                        Text("While SyncUp is open and the screen is on", fontSize = 12.5.sp, color = cs.onSurfaceVariant)
                    }
                    Switch(checked = WaveControl.enabled, onCheckedChange = { WaveControl.setEnabled(context, it) })
                }
                WaveControl.Gesture.entries.forEach { g ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { choosing = g }.padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(g.label, fontSize = 14.5.sp, color = cs.onSurface)
                            Text(WaveControl.actionFor(g).label, fontSize = 12.5.sp, color = cs.primary)
                        }
                        Text("›", fontSize = 20.sp, color = cs.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Try it", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            val g = last
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(if (fresh) Color(0xFFDCFCE7) else cs.surfaceContainerLow).padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                if (fresh) Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF15803D), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(if (fresh) 10.dp else 0.dp))
                Text(
                    when {
                        fresh && g != null -> "${g.label.substringBefore(" (")} — \"${WaveControl.actionFor(g).label}\""
                        near -> "Hand seen…"
                        else -> "Waiting for a wave"
                    },
                    fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = if (fresh) Color(0xFF15803D) else cs.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(14.dp))
            Note("Handy when cooking with wet hands, or with the phone on a stand. A phone face down or in a pocket doesn't count as a wave.")
            Spacer(Modifier.height(16.dp))
        }
    }

    choosing?.let { g ->
        AlertDialog(
            onDismissRequest = { choosing = null },
            title = { Text(g.label) },
            text = {
                Column {
                    WaveControl.Action.entries.forEach { a ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                WaveControl.setAction(context, g, a)
                                choosing = null
                            },
                        ) {
                            RadioButton(selected = WaveControl.actionFor(g) == a, onClick = {
                                WaveControl.setAction(context, g, a)
                                choosing = null
                            })
                            Text(a.label, fontSize = 15.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = null }) { Text("Close") } },
        )
    }
}

// ============================================================================ Speaker clean
private fun vibrate(context: Context, pattern: LongArray, repeat: Int) {
    @Suppress("DEPRECATION")
    val v = context.getSystemService(Vibrator::class.java) ?: return
    if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, repeat))
    else @Suppress("DEPRECATION") v.vibrate(pattern, repeat)
}

private fun stopVibrating(context: Context) {
    @Suppress("DEPRECATION")
    context.getSystemService(Vibrator::class.java)?.cancel()
}

/** 20 Hz – 20 kHz on a slider: equal steps are equal musical steps. */
private fun hzFromSlider(x: Float) = exp(ln(20.0) + x * (ln(20_000.0) - ln(20.0)))
private fun sliderFromHz(hz: Double) = ((ln(hz) - ln(20.0)) / (ln(20_000.0) - ln(20.0))).toFloat()

@Composable
fun SpeakerTool(onBack: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val prefs = remember { SensorPrefs.prefs(context) }
    val tone = remember { ToneMaker() }
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    var mode by remember { mutableIntStateOf(0) }
    var cleaning by remember { mutableStateOf(false) }
    var left by remember { mutableIntStateOf(30) }
    var warn by remember { mutableStateOf(false) }
    var toneOn by remember { mutableStateOf(false) }
    var pitch by remember { mutableFloatStateOf(sliderFromHz(440.0)) }
    var side by remember { mutableStateOf<String?>(null) }
    var volumeBefore by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

    fun stopAll() {
        tone.stop()
        stopVibrating(context)
        volumeBefore?.let { v -> runCatching { audio?.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) } }
        volumeBefore = null
        cleaning = false
        toneOn = false
        side = null
    }
    DisposableEffect(Unit) { onDispose { stopAll() } }
    val onScreen = rememberOnScreen()
    LaunchedEffect(onScreen) { if (!onScreen) stopAll() }

    fun startCleaning() {
        stopAll()
        volumeBefore = audio?.getStreamVolume(AudioManager.STREAM_MUSIC)
        runCatching { audio?.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0) }
        tone.hz = 165.0
        tone.left = 1f
        tone.right = 1f
        tone.start()
        vibrate(context, longArrayOf(0, 450, 150), 0)
        left = 30
        cleaning = true
    }
    LaunchedEffect(cleaning) {
        while (cleaning && left > 0) {
            delay(1_000)
            left--
        }
        if (cleaning) stopAll()
    }
    LaunchedEffect(pitch) { tone.hz = hzFromSlider(pitch) }

    ToolPage("Speaker clean", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Choice(listOf("Clean (water)", "Tone", "Test L / R"), mode, {
                stopAll()
                mode = it
            })
            Spacer(Modifier.height(24.dp))
            when (mode) {
                0 -> {
                    Box(
                        Modifier.size(170.dp).clip(CircleShape).background(if (cleaning) cs.primary.copy(alpha = .25f) else cs.surfaceContainerLow),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = cs.primary, modifier = Modifier.size(70.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(if (cleaning) "0:%02d".format(left) else "0:30", fontSize = 48.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace, color = cs.onSurface)
                    Text(
                        if (cleaning) "Pushing water out… keep the speaker facing down" else "After rain or a splash",
                        fontSize = 15.sp, color = cs.primary, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Note("Plays a low hum at full volume for 30 seconds while the phone vibrates — it shakes water out of the speaker grill. Your volume goes back after.")
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            if (cleaning) stopAll()
                            else if (!prefs.getBoolean("speaker_warned", false)) warn = true
                            else startCleaning()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (cleaning) "Stop" else "Start") }
                }
                1 -> {
                    val hz = hzFromSlider(pitch)
                    Text(if (hz >= 1000) "%.1f kHz".format(hz / 1000) else "${hz.roundToInt()} Hz", fontSize = 48.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace, color = cs.onSurface)
                    Spacer(Modifier.height(12.dp))
                    StepSlider(value = pitch, range = 0f..1f, step = 1f / 120f, label = "pitch", onChange = { pitch = it })
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(100.0, 440.0, 1_000.0, 10_000.0, 15_000.0).forEach { p ->
                            FilterChip(
                                selected = abs(hz - p) < p * .01,
                                onClick = { pitch = sliderFromHz(p) },
                                label = { Text(if (p >= 1000) "${(p / 1000).roundToInt()}k" else "${p.roundToInt()}") },
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Note("Most adults stop hearing somewhere between 15 and 17 kHz — try it. Keep the volume low for high tones.")
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        if (toneOn) {
                            tone.stop()
                            toneOn = false
                        } else {
                            tone.hz = hz
                            tone.left = 1f
                            tone.right = 1f
                            tone.start()
                            toneOn = true
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (toneOn) "Stop" else "Play") }
                }
                else -> {
                    Text(side?.let { "Playing: $it" } ?: "Which speaker plays?", fontSize = 22.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf("Left" to (1f to 0f), "Both" to (1f to 1f), "Right" to (0f to 1f)).forEach { (name, gains) ->
                            OutlinedButton(onClick = {
                                scope.launch {
                                    tone.stop()
                                    delay(80)
                                    tone.hz = 440.0
                                    tone.left = gains.first
                                    tone.right = gains.second
                                    side = name
                                    tone.start()
                                    delay(1_500)
                                    tone.stop()
                                    if (side == name) side = null
                                }
                            }, modifier = Modifier.weight(1f)) { Text(name) }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Note("With earphones it's easy: the sound should come from that side only. Many phones have one bottom speaker plus the earpiece as the second one.")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (warn) {
        AlertDialog(
            onDismissRequest = { warn = false },
            title = { Text("It's loud") },
            text = { Text("The hum plays at full volume for 30 seconds. Keep the phone away from your ears, speaker facing down.") },
            confirmButton = {
                TextButton(onClick = {
                    warn = false
                    prefs.edit().putBoolean("speaker_warned", true).apply()
                    startCleaning()
                }) { Text("Start") }
            },
            dismissButton = { TextButton(onClick = { warn = false }) { Text("Cancel") } },
        )
    }
}

// ============================================================================ Phone check
@Composable
fun PhoneCheckTool(onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var test by remember { mutableStateOf<String?>(null) }
    when (test) {
        "touch" -> {
            TouchTest(onClose = { test = null })
            return
        }
        "colours" -> {
            ColourTest(onClose = { test = null })
            return
        }
    }
    ToolPage("Phone check", onBack) {
        Choice(listOf("Sensors", "Tests", "Battery"), tab, { tab = it }, Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(8.dp))
        when (tab) {
            0 -> SensorList()
            1 -> Tests(onFullScreen = { test = it })
            else -> Battery()
        }
    }
}

@Composable
private fun SensorList() {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val sm = remember { context.getSystemService(SensorManager::class.java) }
    val all = remember { sm?.getSensorList(Sensor.TYPE_ALL).orEmpty() }
    var showAll by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(Part.entries.toList(), key = { it.name }) { part -> PartRow(part, sm) }
        item {
            TextButton(onClick = { showAll = !showAll }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(if (showAll) "Hide the full list" else "All ${all.size} sensors in this phone")
            }
        }
        if (showAll) {
            items(all, key = { "${it.type}-${it.name}-${it.vendor}" }) { s ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp)) {
                    Text(s.name, fontSize = 13.5.sp, color = cs.onSurface)
                    Text("${s.vendor} · ${s.stringType.substringAfterLast('.')}", fontSize = 11.5.sp, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PartRow(part: Part, sm: SensorManager?) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val type = part.sensorType
    val sensor = remember(type) { type?.let { sm?.getDefaultSensor(it) } }
    val present = remember { part.present(context) }
    // Step counter: needs the activity permission for a value, so just "built in".
    val live = if (sensor != null && part != Part.STEP_COUNTER) rememberSensor(type!!) else null
    val v = live?.values?.takeIf { believable(part, it[0]) }
    val value = when {
        !present -> "—"
        sensor == null || part == Part.STEP_COUNTER -> "Built in ✓"
        v == null -> "…"
        else -> when (part) {
            Part.MOTION -> "%.1f · %.1f · %.1f".format(v[0], v[1], v[2])
            Part.GYRO -> "%.2f rad/s".format(sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]))
            Part.COMPASS -> "${sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).roundToInt()} µT"
            Part.LIGHT -> "${v[0].roundToInt()} lux"
            Part.PROXIMITY -> if (v[0] < minOf(sensor.maximumRange * .9f, 5f)) "Near" else "Far"
            Part.PRESSURE -> "%.1f hPa".format(v[0])
            Part.THERMOMETER -> "%.1f °C".format(v[0])
            Part.HUMIDITY -> "${v[0].roundToInt()} %"
            else -> "✓"
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(part.label, fontSize = 14.5.sp, color = if (present) cs.onSurface else cs.onSurfaceVariant)
            Text(
                if (!present) "not in this phone" else sensor?.name ?: "built in",
                fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(value, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = if (present) cs.onSurface else cs.onSurfaceVariant, maxLines = 1)
    }
}

/** Some sensors send a junk first value — those wait for a real one. */
private fun believable(part: Part, v: Float): Boolean = when (part) {
    Part.THERMOMETER -> v in -40f..85f
    Part.HUMIDITY -> v in 0f..100f
    Part.PRESSURE -> v in 300f..1_100f
    else -> v.isFinite()
}

@Composable
private fun Tests(onFullScreen: (String) -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val tone = remember { ToneMaker() }
    val scope = rememberCoroutineScope()
    val mic = rememberAccess(Manifest.permission.RECORD_AUDIO)
    var status by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { tone.stop() } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        val tests = listOf(
            Triple("Touch", Icons.Rounded.TouchApp, "Draw over the whole screen"),
            Triple("Screen colours", Icons.Rounded.Palette, "Spot dead or stuck dots"),
            Triple("Speakers", Icons.AutoMirrored.Rounded.VolumeUp, "Left, right, both"),
            Triple("Mic", Icons.Rounded.Mic, "Record 3 s and play it back"),
            Triple("Vibration", Icons.Rounded.Vibration, "A short buzz"),
        )
        tests.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                row.forEach { (name, icon, sub) ->
                    TestTile(name, icon, sub, Modifier.weight(1f)) {
                        when (name) {
                            "Touch" -> onFullScreen("touch")
                            "Screen colours" -> onFullScreen("colours")
                            "Speakers" -> scope.launch {
                                for ((side, gains) in listOf("left" to (1f to 0f), "right" to (0f to 1f), "both" to (1f to 1f))) {
                                    status = "Speakers: playing $side…"
                                    tone.hz = 440.0
                                    tone.left = gains.first
                                    tone.right = gains.second
                                    tone.start()
                                    delay(1_200)
                                    tone.stop()
                                    delay(250)
                                }
                                status = "Speakers: done — did you hear left, right and both?"
                            }
                            "Mic" -> if (!mic.granted) mic.ask() else scope.launch {
                                status = "Mic: recording — say something…"
                                val clip = withContext(Dispatchers.IO) { recordClip(3) }
                                if (clip == null) {
                                    status = "Mic: couldn't record — another app may be using it."
                                    return@launch
                                }
                                status = "Mic: playing it back…"
                                withContext(Dispatchers.IO) { playClip(clip) }
                                status = "Mic: done — did you hear yourself?"
                            }
                            "Vibration" -> {
                                vibrate(context, longArrayOf(0, 300, 150, 300), -1)
                                status = "Vibration: did it buzz twice?"
                            }
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        status?.let {
            Spacer(Modifier.height(10.dp))
            Note(it)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Handy before buying or selling a used phone. The Battery tab shows its temperature and health.",
            fontSize = 12.5.sp, lineHeight = 18.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp),
        )
    }
}

@Composable
private fun TestTile(name: String, icon: ImageVector, sub: String, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.heightIn(min = 104.dp).clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainerLowest).clickable(onClick = onClick).padding(14.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(name, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
        Text(sub, fontSize = 12.sp, color = cs.onSurfaceVariant)
    }
}

@SuppressLint("MissingPermission")
private fun recordClip(seconds: Int): ShortArray? {
    val rate = 16_000
    val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val rec = runCatching { AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 3_200) * 2) }.getOrNull() ?: return null
    return try {
        if (rec.state != AudioRecord.STATE_INITIALIZED) return null
        val out = ShortArray(rate * seconds)
        rec.startRecording()
        var got = 0
        while (got < out.size) {
            val n = rec.read(out, got, minOf(1_600, out.size - got))
            if (n <= 0) break
            got += n
        }
        out.copyOf(got)
    } finally {
        runCatching { rec.stop() }
        rec.release()
    }
}

private fun playClip(clip: ShortArray) {
    val track = runCatching {
        AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(clip.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
    }.getOrNull() ?: return
    try {
        track.write(clip, 0, clip.size)
        track.play()
        Thread.sleep(clip.size * 1000L / 16_000 + 200)
    } finally {
        runCatching { track.stop() }
        track.release()
    }
}

/** Draw over every square; each one turns green when touched. */
@Composable
private fun TouchTest(onClose: () -> Unit) {
    val cols = 9
    val rows = 16
    val touched = remember { mutableStateListOf<Int>() }
    BackHandler(onBack = onClose)
    val done = touched.size == cols * rows
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    fun mark(p: Offset) {
                        val c = (p.x / size.width * cols).toInt().coerceIn(0, cols - 1)
                        val r = (p.y / size.height * rows).toInt().coerceIn(0, rows - 1)
                        val i = r * cols + c
                        if (i !in touched) touched.add(i)
                    }
                    detectDragGestures(onDragStart = { mark(it) }) { change, _ -> mark(change.position) }
                }
                .pointerInput(Unit) {
                    detectTapGestures { p ->
                        val c = (p.x / size.width * cols).toInt().coerceIn(0, cols - 1)
                        val r = (p.y / size.height * rows).toInt().coerceIn(0, rows - 1)
                        val i = r * cols + c
                        if (i !in touched) touched.add(i)
                    }
                },
        ) {
            val w = size.width / cols
            val h = size.height / rows
            for (r in 0 until rows) for (c in 0 until cols) {
                val on = (r * cols + c) in touched
                drawRect(if (on) Color(0xFF16A34A) else Color(0xFF1E293B), Offset(c * w + 1, r * h + 1), Size(w - 2, h - 2))
            }
        }
        Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (done) "Every part of the screen answers ✓" else "Draw over every square",
                fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Color.White, textAlign = TextAlign.Center,
            )
            Text("${touched.size} of ${cols * rows}", fontSize = 14.sp, color = Color.White.copy(alpha = .7f))
            Spacer(Modifier.height(12.dp))
            Button(onClick = onClose) { Text(if (done) "Done" else "Stop") }
        }
    }
}

/** Full-screen colours one after another — dead or stuck dots show up on them. */
@Composable
private fun ColourTest(onClose: () -> Unit) {
    val colours = listOf(Color.Red, Color(0xFF00FF00), Color.Blue, Color.White, Color.Black, Color(0xFF808080))
    var i by remember { mutableIntStateOf(0) }
    BackHandler(onBack = onClose)
    Box(
        Modifier.fillMaxSize().background(colours[i]).clickable { if (i == colours.lastIndex) onClose() else i++ },
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (i == 0) {
            Text(
                "Tap for the next colour. Look for dots that stay a different colour.",
                fontSize = 14.sp, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp),
            )
        }
    }
}

@Composable
private fun Battery() {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val bm = remember { context.getSystemService(BatteryManager::class.java) }
    var info by remember { mutableStateOf<Intent?>(null) }
    var currentMa by remember { mutableStateOf<Int?>(null) }
    var chargeMah by remember { mutableStateOf<Int?>(null) }
    val onScreen = rememberOnScreen()
    DisposableEffect(onScreen) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                info = i
            }
        }
        if (onScreen) info = ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { if (onScreen) runCatching { context.unregisterReceiver(receiver) } }
    }
    LaunchedEffect(onScreen) {
        while (onScreen) {
            // Phones report µA (most) or mA (a few); either way, shown in mA.
            currentMa = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.takeIf { it != Int.MIN_VALUE && it != 0 }
                ?.let { if (abs(it) > 20_000) it / 1000 else it }
            chargeMah = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.takeIf { it > 0 && it != Int.MIN_VALUE }?.let { it / 1000 }
            delay(2_000)
        }
    }
    val i = info
    val level = i?.let { val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100); if (l >= 0) l * 100 / s.coerceAtLeast(1) else null }
    val status = when (i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
        BatteryManager.BATTERY_STATUS_FULL -> "Full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "On battery"
        else -> "—"
    }
    val plugged = when (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
        BatteryManager.BATTERY_PLUGGED_AC -> "Charger"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
        else -> "Not plugged in"
    }
    val tempC = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f }
    val volts = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)?.takeIf { it > 0 }?.let { if (it > 1000) it / 1000f else it.toFloat() }
    val health = when (i?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Too hot"
        BatteryManager.BATTERY_HEALTH_DEAD -> "Worn out"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
        BatteryManager.BATTERY_HEALTH_COLD -> "Too cold"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Problem"
        else -> "—"
    }
    val cycles = if (Build.VERSION.SDK_INT >= 34) i?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)?.takeIf { it >= 0 } else null
    val charging = status == "Charging"
    val watts = if (charging && currentMa != null && volts != null) abs(currentMa!!) * volts / 1000f else null

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(12.dp))
        Text(level?.let { "$it%" } ?: "—", fontSize = 56.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace, color = cs.onSurface)
        Text("$status · $plugged", fontSize = 15.sp, color = cs.primary)
        Spacer(Modifier.height(16.dp))
        Card {
            BatteryRow("Temperature", tempC?.let { if (SensorPrefs.imperial) "%.1f °F".format(it * 9 / 5 + 32) else "%.1f °C".format(it) } ?: "—", if ((tempC ?: 0f) >= 42f) Color(0xFFDC2626) else null)
            BatteryRow("Health", health, if (health == "Good") Color(0xFF16A34A) else null)
            BatteryRow("Voltage", volts?.let { "%.2f V".format(it) } ?: "—")
            BatteryRow("Current", currentMa?.let { "$it mA" } ?: "—")
            if (watts != null) BatteryRow("Charging speed", "%.1f W".format(watts))
            if (chargeMah != null) BatteryRow("Charge left", "≈ $chargeMah mAh")
            if (cycles != null) BatteryRow("Charge cycles", "$cycles")
            BatteryRow("Type", i?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.ifBlank { null } ?: "—")
        }
        Spacer(Modifier.height(12.dp))
        Note("Above about 42 °C a battery wears faster — take the phone out of its cover or out of the sun while it charges.")
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun BatteryRow(label: String, value: String, color: Color? = null) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp)) {
        Text(label, fontSize = 14.sp, color = cs.onSurface, modifier = Modifier.weight(1f))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = color ?: cs.onSurface)
    }
}
