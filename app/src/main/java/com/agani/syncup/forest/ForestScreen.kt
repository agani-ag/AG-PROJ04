package com.agani.syncup.forest

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Cyclone
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.FlutterDash
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Thunderstorm
import androidx.compose.material.icons.rounded.Umbrella
import androidx.compose.material.icons.rounded.Water
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.StepSlider
import com.agani.syncup.ui.theme.CardShape
import kotlinx.coroutines.delay

/** What the calm sheet opens with (the night card's "Wind down" = Breathe, Sleep 4-7-8). */
private enum class Preset { NONE, WIND_DOWN }

/**
 * Tools → Forest. Its own pages ([route]) over the forest; calm time takes the whole screen while
 * it runs (or waits, paused), then "It grew".
 */
@Composable
fun ForestScreen(signedIn: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    remember {
        ForestStore.init(ctx)
        CalmSession.init(ctx)
        ForestStore.observe()
    }
    var route by rememberSaveable { mutableStateOf("home") }
    var treeId by rememberSaveable { mutableStateOf<String?>(null) }
    var sheet by rememberSaveable { mutableStateOf<Preset?>(null) }
    var hidePaused by rememberSaveable { mutableStateOf(false) }
    // Time passing grows trees too: notice it every minute while Forest is open.
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            ForestStore.observe()
        }
    }
    LaunchedEffect(CalmSession.active, CalmSession.paused) { if (!CalmSession.paused) hidePaused = false }

    val result = CalmSession.result
    when {
        CalmSession.active && CalmSession.paused && !hidePaused -> PausedScreen(onHide = { hidePaused = true })
        CalmSession.active && !CalmSession.paused -> if (CalmSession.mode == CalmMode.BREATHE) BreatheScreen() else QuietScreen()
        result != null -> ResultScreen(
            result,
            onAgain = {
                CalmSession.clearResult()
                route = "home"
                sheet = Preset.NONE
            },
            onDone = {
                CalmSession.clearResult()
                route = "home"
            },
        )
        else -> {
            BackHandler(enabled = route != "home") { route = "home" }
            when (route) {
                "tree" -> TreePage(treeId, onBack = { route = "home" })
                "how" -> HowPage(onBack = { route = "home" })
                "kinds" -> KindsPage(onBack = { route = "home" })
                "seasons" -> SeasonsPage(onBack = { route = "home" })
                "week" -> WeekPage(onBack = { route = "home" })
                else -> ForestHome(
                    signedIn = signedIn,
                    onBack = onBack,
                    onBegin = { sheet = it },
                    onResume = { CalmSession.resume(ctx) },
                    onOpenTree = {
                        treeId = it
                        route = "tree"
                    },
                    onPage = { route = it },
                )
            }
        }
    }
    sheet?.let { preset ->
        CalmSheet(preset, onDismiss = { sheet = null })
    }
}

// ------------------------------------------------------------------ home
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ForestHome(
    signedIn: Boolean,
    onBack: () -> Unit,
    onBegin: (Preset) -> Unit,
    onResume: () -> Unit,
    onOpenTree: (String) -> Unit,
    onPage: (String) -> Unit,
) {
    val c = forestColors()
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val trees = ForestStore.trees
    val night = DayPart.now() == DayPart.NIGHT
    BarIcons(light = !night)
    var selected by remember { mutableStateOf<String?>(null) }
    var cardH by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().background(if (night) Color(0xFF0B1626) else Color(0xFFF6EDD6))) {
        val cardDp = with(density) { cardH.toDp() }.takeIf { cardH > 0 } ?: 290.dp
        val ground = (maxHeight - cardDp + if (night) 6.dp else 26.dp).coerceAtLeast(maxHeight * .5f)
        ForestScene(trees, ground, selected, { selected = it }, onOpenTree, Modifier.fillMaxSize())
        Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
            ForestTopBar("Forest", onBack, color = if (night) Color(0xFFF1F5EE) else Color(0xFF0F172A)) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = if (night) Color(0xFFF1F5EE) else Color(0xFF0F172A)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Trees & seeds") }, onClick = { menu = false; onPage("kinds") })
                        DropdownMenuItem(text = { Text("How trees grow") }, onClick = { menu = false; onPage("how") })
                        DropdownMenuItem(text = { Text("Seasons & visitors") }, onClick = { menu = false; onPage("seasons") })
                        DropdownMenuItem(text = { Text("Your calm week") }, onClick = { menu = false; onPage("week") })
                        if (signedIn && ForestSync.active) {
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("Sync now")
                                        Text(
                                            if (ForestSync.syncing) "Syncing…" else "Synced · ${com.agani.syncup.calendar.agoText(ForestSync.lastSyncMs)}",
                                            fontSize = 12.sp, color = c.muted,
                                        )
                                    }
                                },
                                onClick = { menu = false; ForestSync.syncNow() },
                            )
                        }
                    }
                }
            }
        }
        val card = Modifier.align(Alignment.BottomCenter).onSizeChanged { cardH = it.height }
        if (night) NightCard(card, onBegin, onResume) else DayCard(card, onBegin, onResume)
    }
}

private fun greeting(): String = when (FDays.hour()) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..18 -> "Good evening"
    in 19..23 -> "A calm evening"
    else -> "A quiet night"
}

/** "2 trees grew this week · the jasmine is in flower" — what's new, in a line. */
private fun storyLine(): String {
    val now = System.currentTimeMillis()
    val trees = ForestStore.trees
    if (trees.isEmpty()) return "Plant your first seed — it grows with calm minutes"
    if (ForestStore.resting()) return "Your forest is resting — one calm session and it's green again"
    val parts = ArrayList<String>()
    val grewThisWeek = trees.count { (it.stageMs[Stage.GROWN] ?: 0) > now - 7 * 86_400_000L }
    if (grewThisWeek > 0) parts.add("$grewThisWeek tree${if (grewThisWeek == 1) "" else "s"} grew this week")
    val month = FDays.month()
    trees.firstOrNull { it.stage(now) == Stage.GROWN && month in it.species.season }?.let {
        parts.add("the ${it.label.lowercase()} is ${if (it.species.fruitsIn(month)) "fruiting" else "in flower"}")
    }
    if (parts.isEmpty()) {
        val g = ForestStore.growing(now)
        val grown = ForestStore.grownCount(now)
        parts.add(
            when {
                g.isEmpty() -> "$grown grown tree${if (grown == 1) "" else "s"} · seeds waiting to be planted"
                grown == 0 && g.size == 1 -> "Your ${g[0].stageName(now).lowercase()} is growing"
                grown == 0 -> "${g.size} trees growing"
                else -> "${g.size} growing · $grown grown"
            },
        )
    }
    return parts.joinToString(" · ").replaceFirstChar { it.uppercase() }
}

private fun seedsInHand(): Int = Species.entries.sumOf { ForestStore.seeds(it) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayCard(modifier: Modifier, onBegin: (Preset) -> Unit, onResume: () -> Unit) {
    val c = forestColors()
    val today = ForestStore.minutesOn(FDays.today())
    val goal = ForestStore.goalMinutes
    val vis = visitors(ForestStore.trees)
    val tree = ForestStore.growing().maxByOrNull { it.lastCareMs }
    Column(
        modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(start = 12.dp, end = 12.dp, bottom = 10.dp).fillMaxWidth()
            .then(if (c.light) Modifier.shadow(10.dp, CardShape, ambientColor = Color(0x330F172A), spotColor = Color(0x330F172A)) else Modifier)
            .clip(CardShape).background(c.card)
            .then(if (c.light) Modifier else Modifier.border(1.dp, c.line, CardShape))
            .padding(18.dp),
    ) {
        Text(greeting(), fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, color = c.ink)
        Text(storyLine(), fontSize = 13.5.sp, lineHeight = 18.sp, color = c.muted, modifier = Modifier.padding(top = 2.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 14.dp)) {
            Ring(today / goal.toFloat(), 46.dp, 5.dp, c.moss, c.soft)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (today >= goal) "Goal met · $today calm minutes today" else "$today of $goal calm minutes today",
                    fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.ink,
                )
                Text(
                    when {
                        tree != null && today < goal -> "Your ${tree.stageName().lowercase()} would like ${goal - today} more"
                        tree != null -> tree.nextText()
                        today < goal -> "${goal - today} more makes today's goal"
                        else -> "A lovely day for the forest"
                    },
                    fontSize = 12.5.sp, color = c.muted, maxLines = 1,
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 14.dp)) {
            CountPill("${ForestStore.trees.size}", if (ForestStore.trees.size == 1) "tree" else "trees")
            CountPill("${ForestStore.calmDays()}", if (ForestStore.calmDays() == 1) "calm day" else "calm days")
            CountPill("${seedsInHand()}", if (seedsInHand() == 1) "seed" else "seeds")
            if (vis.butterflies > 0 && DayPart.now() != DayPart.NIGHT && !rainyToday()) CountPill("🦋 ${vis.butterflies}", "")
        }
        Spacer(Modifier.height(16.dp))
        BeginButton(onBegin, onResume, Preset.NONE)
    }
}

/** Today's numbers on the card, in Forest's soft lime ("6 trees"). */
@Composable
private fun CountPill(bold: String, rest: String) {
    val c = forestColors()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(c.soft).padding(horizontal = 11.dp),
    ) {
        Text(bold, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = c.moss)
        if (rest.isNotEmpty()) Text(rest, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = c.moss)
    }
}

@Composable
private fun BeginButton(onBegin: (Preset) -> Unit, onResume: () -> Unit, preset: Preset, night: Boolean = false) {
    if (CalmSession.active && CalmSession.paused) {
        val ctx = LocalContext.current
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (night) {
                LineButton("End here", Modifier.weight(1f), color = Color(0xFFE7F3E2), border = Color(0x59E7F3E2)) { CalmSession.stop(ctx) }
            } else {
                LineButton("End here", Modifier.weight(1f)) { CalmSession.stop(ctx) }
            }
            MossButton("Go on · ${clock(CalmSession.leftMs())} left", Modifier.weight(1.4f), onClick = onResume)
        }
    } else if (night) {
        MossButton("Wind down · Breathe 4-7-8", Modifier.fillMaxWidth(), icon = Icons.Rounded.Bedtime) { onBegin(preset) }
    } else {
        MossButton("Begin calm time", Modifier.fillMaxWidth(), icon = Icons.Rounded.Eco) { onBegin(preset) }
    }
}

@Composable
private fun NightCard(modifier: Modifier, onBegin: (Preset) -> Unit, onResume: () -> Unit) {
    val today = ForestStore.minutesOn(FDays.today())
    val goal = ForestStore.goalMinutes
    val vis = visitors(ForestStore.trees)
    Column(
        modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(start = 12.dp, end = 12.dp, bottom = 16.dp).fillMaxWidth()
            .clip(RoundedCornerShape(24.dp)).background(Color(0xEB0E1E2C)).border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(24.dp))
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp),
    ) {
        TitleText(greeting(), 21.sp, Color(0xFFEEF2F6))
        Text(
            when {
                vis.fireflies > 0 -> "${vis.fireflies} firefl${if (vis.fireflies == 1) "y" else "ies"} tonight — one for each grown tree"
                ForestStore.trees.isEmpty() -> "Plant your first seed — even 5 calm minutes"
                else -> "Fireflies come when a tree is grown"
            },
            fontSize = 13.sp, color = Color(0xFFB8C7D3), modifier = Modifier.padding(top = 2.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
            Ring(today / goal.toFloat(), 30.dp, 3.5.dp, Color(0xFFE8DDB5), Color(0x29FFFFFF))
            Spacer(Modifier.width(10.dp))
            Text(if (today >= goal) "Goal met · $today calm minutes today" else "$today of $goal calm minutes today", fontSize = 13.sp, color = Color(0xFFDCE6EE))
        }
        Spacer(Modifier.height(12.dp))
        BeginButton(onBegin, onResume, Preset.WIND_DOWN, night = true)
        if (!CalmSession.active) {
            Text(
                "Or another calm time", fontSize = 13.sp, color = Color(0xFFB8C7D3), textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onBegin(Preset.NONE) }.padding(top = 10.dp, bottom = 2.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ the calm sheet
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CalmSheet(preset: Preset, onDismiss: () -> Unit) {
    val c = forestColors()
    val ctx = LocalContext.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val growing = remember { ForestStore.growing().sortedByDescending { it.lastCareMs } }
    val firstSeed = remember { Species.entries.firstOrNull { ForestStore.seeds(it) > 0 } }
    // "tree:<id>", "seed:<KIND>" or "" (calm time for the day only).
    var target by rememberSaveable {
        mutableStateOf(growing.firstOrNull()?.let { "tree:${it.id}" } ?: firstSeed?.let { "seed:${it.name}" } ?: "")
    }
    var growDays by rememberSaveable { mutableIntStateOf(ForestStore.lastGrowDays) }
    var mode by rememberSaveable { mutableStateOf(if (preset == Preset.WIND_DOWN) CalmMode.BREATHE else CalmMode.QUIET) }
    var minutes by rememberSaveable { mutableIntStateOf(if (preset == Preset.WIND_DOWN) 5 else 20) }
    var pattern by rememberSaveable { mutableStateOf(if (preset == Preset.WIND_DOWN) BreathPattern.SLEEP else CalmSession.pattern) }
    var sound by rememberSaveable { mutableStateOf(runCatching { NatureSound.valueOf(ForestStore.lastSound) }.getOrDefault(NatureSound.RAIN)) }
    var otherDays by remember { mutableStateOf(false) }
    val seed = target.removePrefix("seed:").takeIf { target.startsWith("seed:") }?.let { runCatching { Species.valueOf(it) }.getOrNull() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = c.card, dragHandle = {
        Box(Modifier.padding(top = 10.dp, bottom = 10.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.outline))
    }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 22.dp)) {
            Text("Calm time", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = c.ink)
            if (growing.isNotEmpty()) {
                Label("Care for")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    growing.forEach { t ->
                        val on = target == "tree:${t.id}"
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) c.soft else c.card)
                                .border(if (on) 2.dp else 1.dp, if (on) c.moss else c.line, RoundedCornerShape(18.dp))
                                .clickable { target = "tree:${t.id}" }.padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                        ) {
                            TreePicture(t.species, t.stage(), t.variant, Modifier.size(40.dp, 44.dp).clip(RoundedCornerShape(10.dp)).background(if (c.light) Color(0xFFCFE9F7) else Color(0xFF0B1A2A)))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(t.stageName(), fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = c.ink, maxLines = 1)
                                Text(t.nextText(), fontSize = 11.5.sp, color = c.muted, maxLines = 1)
                            }
                        }
                    }
                }
            }
            Label(if (growing.isEmpty()) "Plant a seed" else "Or plant a seed")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Species.entries.forEach { s ->
                    val found = ForestStore.found(s)
                    val n = ForestStore.seeds(s)
                    SeedPacket(s, found, n, on = seed == s) { target = "seed:${s.name}" }
                }
            }
            if (seed != null) {
                Label("This ${seed.title.lowercase()} grows in")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (d in listOf(1, 3, 7, 14)) FChip(growDaysText(d), growDays == d) { growDays = d }
                    FChip(if (growDays in listOf(1, 3, 7, 14)) "Other…" else growDaysText(growDays), growDays !in listOf(1, 3, 7, 14)) { otherDays = true }
                }
                val (cAll, _) = Tree.needs(Stage.GROWN, growDays)
                Text("About ${minutesText(cAll)} of calm time in all, over ${growDaysText(growDays)}", fontSize = 12.sp, color = c.muted, modifier = Modifier.padding(top = 8.dp, start = 2.dp))
            }
            Label("Calm now")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in CalmMode.entries) {
                    val on = mode == m
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(if (on) c.soft else c.card)
                            .border(if (on) 2.dp else 1.dp, if (on) c.moss else c.line, RoundedCornerShape(18.dp))
                            .clickable { mode = m }.padding(vertical = 10.dp, horizontal = 4.dp),
                    ) {
                        Icon(
                            when (m) {
                                CalmMode.QUIET -> Icons.Rounded.Bedtime
                                CalmMode.BREATHE -> Icons.Rounded.Spa
                                CalmMode.SOUNDS -> Icons.Rounded.Umbrella
                            },
                            null, tint = c.moss, modifier = Modifier.size(22.dp),
                        )
                        Text(m.title, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = if (on) c.moss else c.ink, modifier = Modifier.padding(top = 4.dp))
                        Text(m.hint, fontSize = 11.sp, color = c.muted)
                    }
                }
            }
            when (mode) {
                CalmMode.BREATHE -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                    for (p in BreathPattern.entries) FChip(p.title, pattern == p) { pattern = p }
                }
                CalmMode.SOUNDS -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                    for (s in NatureSound.entries) FChip(s.title, sound == s, icon = soundIcon(s)) { sound = s }
                }
                else -> {}
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                for (m in listOf(5, 10, 20, 30, 45, 60)) FChip(if (m == 5) "5 min" else "$m", minutes == m) { minutes = m }
            }
            Spacer(Modifier.height(16.dp))
            val begin = if (seed != null) "Plant & begin · $minutes min" else "Begin · $minutes min"
            MossButton(begin, Modifier.fillMaxWidth()) {
                val tree = when {
                    seed != null -> ForestStore.plant(seed, growDays)?.id
                    target.startsWith("tree:") -> target.removePrefix("tree:")
                    else -> null
                }
                CalmSession.start(ctx, tree, mode, minutes, if (mode == CalmMode.SOUNDS) sound else null, pattern)
                if (mode == CalmMode.BREATHE) CalmSession.changePattern(pattern)
                onDismiss()
            }
            if (target.isEmpty()) {
                Text("No seeds right now — your calm minutes still count for today.", fontSize = 12.sp, color = c.muted, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
    if (otherDays) {
        var v by remember { mutableIntStateOf(growDays) }
        AlertDialog(
            onDismissRequest = { otherDays = false },
            title = { Text("Grows in") },
            text = {
                Column {
                    TitleText(growDaysText(v), 26.sp, modifier = Modifier.padding(bottom = 8.dp))
                    StepSlider(
                        value = v.toFloat(), range = 1f..14f, step = 1f, label = "days", steps = 12,
                        onChange = { v = it.toInt().coerceIn(1, 14) },
                    )
                    Text("Between 1 day and 2 weeks. A tree needs its time and its calm minutes.", fontSize = 12.5.sp, color = c.muted)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    growDays = v
                    otherDays = false
                }) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = { otherDays = false }) { Text("Cancel") } },
        )
    }
}

internal fun soundIcon(s: NatureSound): ImageVector = when (s) {
    NatureSound.RAIN -> Icons.Rounded.Umbrella
    NatureSound.STORM -> Icons.Rounded.Thunderstorm
    NatureSound.RIVER -> Icons.Rounded.Waves
    NatureSound.OCEAN -> Icons.Rounded.Water
    NatureSound.BIRDS -> Icons.Rounded.FlutterDash
    NatureSound.NIGHT -> Icons.Rounded.NightsStay
    NatureSound.WIND -> Icons.Rounded.Air
    NatureSound.FIRE -> Icons.Rounded.LocalFireDepartment
    NatureSound.FAN -> Icons.Rounded.Cyclone
    NatureSound.NOISE -> Icons.Rounded.GraphicEq
}

/** A paper seed packet showing the tree it becomes; one without seeds is faded. */
@Composable
private fun SeedPacket(s: Species, found: Boolean, seeds: Int, on: Boolean, onClick: () -> Unit) {
    val c = forestColors()
    val usable = found && seeds > 0
    val paper = if (c.light) Color(0xFFFEF3C7) else Color(0xFF30240C)
    val crimp = (if (c.light) Color(0xFFB45309) else Color(0xFFFCD34D)).copy(alpha = .35f)
    val shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomStart = 14.dp, bottomEnd = 14.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(82.dp)
            .let { if (on) it.border(2.5.dp, c.moss, shape) else it }
            .padding(if (on) 3.dp else 0.dp)
            .alpha(if (usable) 1f else .45f)
            .clip(shape)
            .background(paper)
            .clickable(enabled = usable, onClick = onClick)
            .padding(bottom = 7.dp),
    ) {
        // The packet's crimped top.
        Row(Modifier.fillMaxWidth().height(7.dp)) {
            repeat(7) { i -> Box(Modifier.weight(1f).height(7.dp).background(if (i % 2 == 0) crimp else Color.Transparent)) }
        }
        TreePicture(s, Stage.GROWN, 7, Modifier.padding(top = 4.dp).size(54.dp))
        Text(s.title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = c.ink, maxLines = 1)
        Text(
            when {
                !found -> "find later"
                seeds == 0 -> "no seeds"
                else -> "$seeds seed${if (seeds == 1) "" else "s"}"
            },
            fontSize = 11.sp, color = c.muted,
        )
    }
}

/** For the Tools tile: what's growing, or calm time's minutes left. */
@Composable
fun forestStatus(): String? {
    val ctx = LocalContext.current
    remember {
        ForestStore.init(ctx)
        CalmSession.init(ctx)
    }
    if (CalmSession.active) {
        if (CalmSession.paused) return "Calm time paused"
        var left by remember { mutableStateOf(CalmSession.leftMs()) }
        LaunchedEffect(Unit) {
            while (true) {
                left = CalmSession.leftMs()
                delay(1_000)
            }
        }
        return "Calm time · ${clock(left)} left"
    }
    val t = ForestStore.growing().maxByOrNull { it.lastCareMs } ?: return null
    return "${t.stageName()} growing"
}

