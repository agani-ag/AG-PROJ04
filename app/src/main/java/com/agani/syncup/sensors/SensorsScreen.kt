package com.agani.syncup.sensors

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.SatelliteAlt
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.WavingHand
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.ui.theme.isLight

/**
 * Tools → Sensors: every tool the phone's sensors and parts make possible, in five groups. A tool the
 * phone can't do stays on the page, greyed — tapping it says which part is missing. Each tool reads
 * its sensors only while it's open and SyncUp is on screen; nothing leaves the phone.
 */
@Composable
fun SensorsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    remember { SensorPrefs.init(context) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val tool = open?.let { runCatching { SensorTool.valueOf(it) }.getOrNull() }
    // Kept here, so coming back from a tool lands where the list was.
    val list = rememberLazyListState()
    BackHandler(enabled = tool != null) { open = null }
    if (tool != null) {
        ToolScreen(tool, onBack = { open = null }, onOpen = { open = it.name })
    } else {
        SensorsHome(list, onBack = onBack, onOpen = { open = it.name })
    }
}

@Composable
private fun ToolScreen(tool: SensorTool, onBack: () -> Unit, onOpen: (SensorTool) -> Unit) {
    when (tool) {
        SensorTool.COMPASS -> CompassTool(onBack)
        SensorTool.SPEEDOMETER -> SpeedometerTool(onBack)
        SensorTool.HEIGHT -> HeightTool(onBack)
        SensorTool.GPS_STATUS -> GpsStatusTool(onBack)
        SensorTool.LEVEL -> LevelTool(onBack)
        SensorTool.RULER -> RulerTool(onBack)
        SensorTool.LIGHT -> LightTool(onBack)
        SensorTool.SOUND -> SoundTool(onBack)
        SensorTool.VIBRATION -> VibrationTool(onBack)
        SensorTool.STEPS -> StepsTool(onBack)
        SensorTool.WEATHER -> WeatherTool(onBack)
        SensorTool.CLIMATE -> ClimateTool(onBack)
        SensorTool.METAL -> MetalTool(onBack)
        SensorTool.MAGNIFIER -> MagnifierTool(onBack)
        SensorTool.COLOUR -> ColourTool(onBack)
        SensorTool.EARBUDS -> EarbudsTool(onBack)
        SensorTool.WIFI -> WifiTool(onBack)
        SensorTool.FLASHLIGHT -> FlashlightTool(onBack, onMagnifier = { onOpen(SensorTool.MAGNIFIER) })
        SensorTool.WAVE -> WaveTool(onBack)
        SensorTool.SPEAKER -> SpeakerTool(onBack)
        SensorTool.PHONE_CHECK -> PhoneCheckTool(onBack)
    }
}

// ============================================================================ the page
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SensorsHome(list: LazyListState, onBack: () -> Unit, onOpen: (SensorTool) -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var why by remember { mutableStateOf<SensorTool?>(null) }
    val works = remember { SensorTool.entries.associateWith { it.works(context) } }
    val via = remember { SensorTool.entries.associateWith { it.via(context) } }
    ToolPage("Sensors", onBack) {
        LazyColumn(state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
            for (group in SensorGroup.entries) {
                val tools = SensorTool.entries.filter { it.group == group }
                item(key = group.name) {
                    Text(
                        group.title.uppercase(), fontSize = 12.5.sp, fontWeight = FontWeight.Medium, letterSpacing = .6.sp, color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp),
                    )
                }
                for (row in tools.chunked(3)) {
                    item(key = row.joinToString { it.name }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth()) {
                            row.forEach { t ->
                                ToolTile(
                                    t,
                                    enabled = works.getValue(t),
                                    note = when {
                                        !works.getValue(t) -> "Not in this phone"
                                        t == SensorTool.FLASHLIGHT && Torch.on -> "On"
                                        else -> via.getValue(t)
                                    },
                                    modifier = Modifier.weight(1f),
                                    onClick = { if (works.getValue(t)) onOpen(t) else why = t },
                                )
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text("Units", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                    Choice(
                        listOf("km/h · m · °C", "mph · ft · °F"),
                        selected = if (SensorPrefs.imperial) 1 else 0,
                        onSelect = { SensorPrefs.setImperial(context, it == 1) },
                    )
                }
            }
            if (works.values.any { !it }) {
                item {
                    Note(
                        "Greyed tools need a sensor this phone doesn't have — tap one to see why.",
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            item {
                Text(
                    "Each tool uses its sensors only while it's open. Nothing leaves the phone. Readings are a guide, not certified measurements.",
                    fontSize = 12.5.sp, lineHeight = 18.sp, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }

    why?.let { t ->
        ModalBottomSheet(onDismissRequest = { why = null }) {
            NotInPhone(
                t,
                onAllSensors = {
                    why = null
                    onOpen(SensorTool.PHONE_CHECK)
                },
                onOk = { why = null },
            )
        }
    }
}

@Composable
private fun ToolTile(tool: SensorTool, enabled: Boolean, note: String?, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val light = cs.isLight
    val shape = RoundedCornerShape(18.dp)
    val grey = cs.onSurfaceVariant.copy(alpha = .55f)
    val dash = cs.outline.copy(alpha = .6f)
    Column(
        modifier
            .heightIn(min = 104.dp)
            .clip(shape)
            .background(if (enabled) cs.surfaceContainerLowest else cs.surfaceContainerLow)
            .then(
                if (enabled) {
                    Modifier.border(1.dp, cs.outlineVariant, shape)
                } else {
                    Modifier.drawBehind {
                        drawRoundRect(
                            dash, cornerRadius = CornerRadius(18.dp.toPx()),
                            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                        )
                    }
                },
            )
            .clickable(onClick = onClick)
            .padding(start = 6.dp, end = 6.dp, top = 12.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(13.dp))
                .background(if (!enabled) cs.surfaceContainerHigh else if (light) Sky.containerLight else Sky.containerDark),
            contentAlignment = Alignment.Center,
        ) {
            Icon(tool.icon, null, tint = if (!enabled) grey else if (light) Sky.light else Sky.dark, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(7.dp))
        Text(
            tool.title, fontSize = 12.5.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
            color = if (enabled) cs.onSurface else grey, maxLines = 2,
        )
        if (note != null) {
            Text(
                note, fontSize = 11.sp, lineHeight = 14.sp, textAlign = TextAlign.Center, maxLines = 1,
                color = if (enabled) (if (light) Sky.light else Sky.dark) else grey,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** The sheet for a greyed tool: which part is missing, and what's close. */
@Composable
private fun NotInPhone(tool: SensorTool, onAllSensors: () -> Unit, onOk: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val missing = tool.missing(context)
    Column(Modifier.padding(start = 22.dp, end = 22.dp, bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                Icon(tool.icon, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(tool.title, fontSize = 19.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                Text(tool.blurb, fontSize = 13.sp, color = cs.onSurfaceVariant)
            }
        }
        val names = missing.map { it.short }
        val list = when (names.size) {
            0 -> "the part it needs"
            1 -> names[0]
            else -> names.dropLast(1).joinToString(", ") + " or " + names.last()
        }
        Text(
            "This phone has no $list, so ${tool.missingWhy}.",
            fontSize = 14.sp, lineHeight = 21.sp, color = cs.onSurface, modifier = Modifier.padding(top = 16.dp),
        )
        Column(
            Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerLowest)
                .border(1.dp, cs.outlineVariant, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 4.dp),
        ) {
            tool.needs.forEach { part ->
                val has = part.present(context)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(part.label, fontSize = 14.sp, color = cs.onSurface)
                        Text(if (tool.anyOf) "either one" else "needed", fontSize = 12.sp, color = cs.onSurfaceVariant)
                    }
                    if (has) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = cs.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("In this phone", fontSize = 13.sp, color = cs.onSurfaceVariant)
                    } else {
                        Text("Not in this phone", fontSize = 13.sp, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
        if (tool == SensorTool.CLIMATE) {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Icon(Icons.Rounded.BatteryStd, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "The battery temperature is in Phone check — that's the phone's heat, not the room's.",
                    fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 18.dp).fillMaxWidth()) {
            OutlinedButton(onClick = onAllSensors, modifier = Modifier.weight(1f)) { Text("See all sensors") }
            Button(onClick = onOk, modifier = Modifier.weight(1f)) { Text("OK") }
        }
    }
}

// ============================================================================ icons
val SensorTool.icon: ImageVector
    get() = when (this) {
        SensorTool.COMPASS -> Icons.Rounded.Explore
        SensorTool.SPEEDOMETER -> Icons.Rounded.Speed
        SensorTool.HEIGHT -> Icons.Rounded.Terrain
        SensorTool.GPS_STATUS -> Icons.Rounded.SatelliteAlt
        SensorTool.LEVEL -> LevelIcon
        SensorTool.RULER -> Icons.Rounded.Straighten
        SensorTool.LIGHT -> Icons.Rounded.WbSunny
        SensorTool.SOUND -> Icons.Rounded.GraphicEq
        SensorTool.VIBRATION -> Icons.Rounded.Vibration
        SensorTool.STEPS -> Icons.AutoMirrored.Rounded.DirectionsWalk
        SensorTool.WEATHER -> Icons.Rounded.Cloud
        SensorTool.CLIMATE -> Icons.Rounded.Thermostat
        SensorTool.METAL -> MagnetIcon
        SensorTool.MAGNIFIER -> Icons.Rounded.ZoomIn
        SensorTool.COLOUR -> Icons.Rounded.Colorize
        SensorTool.EARBUDS -> Icons.Rounded.Headphones
        SensorTool.WIFI -> Icons.Rounded.Wifi
        SensorTool.FLASHLIGHT -> Icons.Rounded.FlashlightOn
        SensorTool.WAVE -> Icons.Rounded.WavingHand
        SensorTool.SPEAKER -> Icons.AutoMirrored.Rounded.VolumeUp
        SensorTool.PHONE_CHECK -> Icons.Rounded.PhoneAndroid
    }

/** A line icon from SVG path data (24 × 24, 2-wide round strokes) — for the two Material doesn't have. */
private fun lineIcon(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach {
            addPath(
                addPathNodes(it), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

/** A spirit level: the tube with its bubble. */
val LevelIcon: ImageVector by lazy {
    lineIcon("Level", "M6 8h12a4 4 0 0 1 0 8H6a4 4 0 0 1 0-8z", "M10 12a2 2 0 1 0 4 0a2 2 0 1 0-4 0", "M8 8v8M16 8v8")
}

/** A horseshoe magnet. */
val MagnetIcon: ImageVector by lazy {
    lineIcon("Magnet", "M6 3v8a6 6 0 0 0 12 0V3h-4v8a2 2 0 0 1-4 0V3z", "M6 7h4M14 7h4")
}
