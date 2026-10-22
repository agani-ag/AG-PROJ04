package com.agani.syncup.forest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Tools → Forest (v2): a new forest every month, worked out from the phone's date and time (ForestRules).
 * Nothing is saved or synced. The forest fills the screen; This month is its one other page.
 */

/** Overlays on the forest: paper by day, dark glass at night and in the storm. */
private val Paper = Color(0xFFFFFCF4)
private val PaperInk = Color(0xFF22301F)
private val PaperMuted = Color(0xFF6B7563)
private val PaperBody = Color(0xFF4B5546)
private val Glass = Color(0x8C0A1626) // rgba(10, 22, 38, .55)
private val GlassCard = Color(0xB30A1626)
private val GlassInk = Color(0xFFF3F1E8)
private val GlassMuted = Color(0xFFB9C3CE)
private val GlassBody = Color(0xFFDCE3EA)
private val CatchBlue = Color(0xEB2563EB) // rgba(37, 99, 235, .92)

@Composable
fun ForestScreen(onBack: () -> Unit) {
    var month by rememberSaveable { mutableStateOf(false) }
    val now = rememberForestNow()
    BackHandler(enabled = month) { month = false }
    if (month) ForestMonth(now, onBack = { month = false })
    else ForestHome(now, onBack = onBack, onMonth = { month = true })
}

/** The Tools tile's line: "Day 22 · a mongoose came" (it moves on by itself while the tile is on screen). */
@Composable
fun forestStatus(): String = ForestRules.statusLine(rememberForestNow())

/**
 * The forest at this moment, kept up to date while on screen: on every minute, every 10 s while the install day
 * catches up, and right away when the app comes back or the clock, date or time zone changes. Off screen it stops.
 */
@Composable
internal fun rememberForestNow(): ForestNow {
    val ctx = LocalContext.current
    val install = remember { installTime(ctx) }
    var now by remember { mutableStateOf(forestAt(install)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var shown by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> shown = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var clockChanged by remember { mutableIntStateOf(0) }
    DisposableEffect(shown) {
        if (!shown) return@DisposableEffect onDispose {}
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                clockChanged++
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
    }
    LaunchedEffect(shown, clockChanged) {
        if (!shown) return@LaunchedEffect
        while (true) {
            val n = forestAt(install)
            now = n
            val step = if (n.catchingUp) 10_000L else 60_000L
            val t = System.currentTimeMillis()
            delay(step - t % step + 50) // on the minute, so the clock in the top bar is right
        }
    }
    return now
}

private fun forestAt(installMs: Long): ForestNow = ForestRules.at(System.currentTimeMillis(), installMs, TimeZone.getDefault())

/** When SyncUp was first installed: on that day the month so far plays out (ForestRules' catch-up). */
private fun installTime(ctx: Context): Long = runCatching {
    @Suppress("DEPRECATION")
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).firstInstallTime
}.getOrDefault(0L)

// ------------------------------------------------------------------ the forest
private class TapInfo(val title: String, val text: String, val at: Offset)

@Composable
private fun ForestHome(now: ForestNow, onBack: () -> Unit, onMonth: () -> Unit) {
    val dark = now.night || now.phase == ForestPhase.STORM
    BarIcons(light = !dark, nav = !dark)
    var tap by remember { mutableStateOf<TapInfo?>(null) }
    // Taps the scene answered; a tap it didn't answer closes the card.
    val hits = remember { intArrayOf(0) }
    // A new forest-day: what the card said may be gone.
    LaunchedEffect(now.day, now.phase) { tap = null }
    // The Today card can be put away (×) for the rest of the forest-day; a Today chip at the top brings it back.
    val ctx = LocalContext.current
    val dayKey = "${now.date.year}-${now.date.month}-${now.date.day}:${now.day}"
    var hiddenFor by remember { mutableStateOf(todayHiddenFor(ctx)) }
    val todayHidden = hiddenFor == dayKey
    fun hideToday(hide: Boolean) {
        hiddenFor = if (hide) dayKey else ""
        setTodayHiddenFor(ctx, hiddenFor)
    }
    Box(
        Modifier.fillMaxSize().background(if (dark) Color(0xFF0B1626) else Color(0xFFCFE6F2)).pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val before = hits[0]
                do {
                    val e = awaitPointerEvent(PointerEventPass.Final)
                } while (e.changes.any { it.pressed })
                if (hits[0] == before) tap = null
            }
        },
    ) {
        ForestScene(
            now, Modifier.fillMaxSize(),
            onTap = { t, at ->
                hits[0]++
                val (title, text) = tapText(t, now)
                tap = TapInfo(title, text, at)
            },
        )
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            TopPill(now, dark, onBack, onMonth)
            Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(ForestRules.seasonChip(now), dark)
                Chip("🌳 " + ForestRules.treeChip(now), dark)
                // The Today card put away: this chip brings it back.
                if (todayHidden) Chip("📝 " + if (now.catchingUp) "Day ${now.day}" else "Today", dark, onClick = { hideToday(false) })
            }
            if (now.catchingUp) CatchUpBanner(now)
        }
        val bottom = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
        if (!todayHidden) TodayCard(now, dark, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(bottom), onHide = { hideToday(true) })
        tap?.let { TapBubble(it) }
    }
}

@Composable
private fun TopPill(now: ForestNow, dark: Boolean, onBack: () -> Unit, onMonth: () -> Unit) {
    val fg = if (dark) GlassInk else PaperInk
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp).fillMaxWidth()
            .clip(RoundedCornerShape(26.dp)).background(if (dark) Glass else Paper.copy(alpha = .9f)).padding(2.dp),
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = fg) }
        Column(Modifier.weight(1f)) {
            Text("Forest", fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium, color = fg, maxLines = 1)
            Text(ForestRules.topLine(now), fontSize = 11.5.sp, lineHeight = 15.sp, color = fg.copy(alpha = .8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onMonth) { Icon(Icons.Rounded.CalendarMonth, "This month", tint = fg) }
    }
}

@Composable
private fun Chip(text: String, dark: Boolean, onClick: (() -> Unit)? = null) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.height(26.dp).clip(RoundedCornerShape(13.dp)).background(if (dark) Glass else Paper.copy(alpha = .9f))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = "Show today", onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp),
    ) {
        Text(text, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, color = if (dark) Color(0xFFEDEBE2) else Color(0xFF2F3A2A), maxLines = 1)
    }
}

/** The install day: "Catching up · day 9 of 22", what's happening, and how far along. */
@Composable
private fun CatchUpBanner(now: ForestNow) {
    val c = now.catchUp ?: return
    Column(
        Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp).fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)).background(CatchBlue).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(ForestRules.catchUpTitle(now).orEmpty(), fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium, color = Color.White)
        Text(ForestRules.catchUpText(now).orEmpty(), fontSize = 12.sp, lineHeight = 16.sp, color = Color.White.copy(alpha = .9f), modifier = Modifier.padding(top = 2.dp))
        Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = .3f))) {
            Box(Modifier.fillMaxWidth(c.progress.toFloat().coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(Color.White))
        }
    }
}

@Composable
private fun TodayCard(now: ForestNow, dark: Boolean, modifier: Modifier, onHide: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    val ink = if (dark) GlassInk else PaperInk
    val muted = if (dark) GlassMuted else PaperMuted
    Box(
        modifier.padding(start = 12.dp, end = 12.dp, bottom = 14.dp).fillMaxWidth()
            .then(if (dark) Modifier else Modifier.shadow(14.dp, shape, ambientColor = Color(0x2E141E14), spotColor = Color(0x2E141E14)))
            .clip(shape).background(if (dark) GlassCard else Paper.copy(alpha = .94f)),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)) {
            Text(
                if (now.catchingUp) "DAY ${now.day}" else "TODAY",
                fontSize = 11.5.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = .3.sp, color = muted,
            )
            Text(
                ForestRules.todayTitle(now), fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium, color = ink,
                modifier = Modifier.padding(top = 2.dp, bottom = 3.dp, end = 26.dp),
            )
            for (line in ForestRules.todayLines(now)) {
                Row(Modifier.padding(top = 3.dp)) {
                    Text(line.icon, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.width(22.dp))
                    Text(bolded(line.text, now.todayKind?.title), fontSize = 12.5.sp, lineHeight = 17.sp, color = if (dark) GlassBody else PaperBody)
                }
            }
        }
        IconButton(onClick = onHide, modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 2.dp).size(40.dp)) {
            Icon(Icons.Rounded.Close, "Hide", tint = muted, modifier = Modifier.size(20.dp))
        }
    }
}

/** Which forest-day the Today card was put away for ("" = shown). Kept on the phone so it stays away on reopening. */
private fun todayHiddenFor(ctx: Context): String =
    ctx.getSharedPreferences(FOREST_UI, Context.MODE_PRIVATE).getString("today_hidden", "").orEmpty()

private fun setTodayHiddenFor(ctx: Context, key: String) {
    ctx.getSharedPreferences(FOREST_UI, Context.MODE_PRIVATE).edit().putString("today_hidden", key).apply()
}

private const val FOREST_UI = "forest_ui"

/** [text] with today's tree kind in bold ("Today's tree: **Neem** · sapling"). */
private fun bolded(text: String, word: String?): AnnotatedString = buildAnnotatedString {
    val i = if (word.isNullOrEmpty()) -1 else text.indexOf(word)
    if (i < 0 || word == null) {
        append(text)
    } else {
        append(text.substring(0, i))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(word) }
        append(text.substring(i + word.length))
    }
}

// ------------------------------------------------------------------ the tap card
/** A small paper card pointing at what was tapped: above it when there's room under the top bar, else below. */
@Composable
private fun TapBubble(info: TapInfo) {
    val shape = RoundedCornerShape(14.dp)
    val top = WindowInsets.safeDrawing.only(WindowInsetsSides.Top)
    Layout(
        content = {
            Column(
                Modifier.widthIn(max = 230.dp).shadow(8.dp, shape, ambientColor = Color(0x2E000000), spotColor = Color(0x2E000000))
                    .clip(shape).background(Paper).padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(info.title, fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = PaperInk)
                Text(info.text, fontSize = 11.5.sp, lineHeight = 15.sp, color = PaperMuted, modifier = Modifier.padding(top = 1.dp))
            }
            Pointer(down = true)
            Pointer(down = false)
        },
        modifier = Modifier.fillMaxSize(),
    ) { ms, cs ->
        val margin = 12.dp.roundToPx()
        val body = ms[0].measure(Constraints(maxWidth = max(0, cs.maxWidth - 2 * margin)))
        val aw = 12.dp.roundToPx()
        val ah = 6.dp.roundToPx()
        val down = ms[1].measure(Constraints.fixed(aw, ah))
        val up = ms[2].measure(Constraints.fixed(aw, ah))
        // Keep clear of the top bar (status bar + the Forest pill).
        val minTop = top.getTop(this) + 64.dp.roundToPx()
        layout(cs.maxWidth, cs.maxHeight) {
            val gap = 8.dp.roundToPx()
            val x = info.at.x.roundToInt()
            val y = info.at.y.roundToInt()
            val above = y - gap - ah - body.height >= minTop
            val cardTop = if (above) y - gap - ah - body.height else min(y + gap + ah, cs.maxHeight - body.height - margin)
            val left = (x - 28.dp.roundToPx()).coerceIn(margin, max(margin, cs.maxWidth - margin - body.width))
            body.place(left, cardTop)
            val inset = 10.dp.roundToPx()
            val ax = (x - aw / 2).coerceIn(left + inset, max(left + inset, left + body.width - inset - aw))
            if (above) down.place(ax, cardTop + body.height) else up.place(ax, cardTop - ah)
        }
    }
}

@Composable
private fun Pointer(down: Boolean) {
    Canvas(Modifier) {
        val p = Path().apply {
            if (down) {
                moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2, size.height)
            } else {
                moveTo(0f, size.height); lineTo(size.width, size.height); lineTo(size.width / 2, 0f)
            }
            close()
        }
        drawPath(p, Paper)
    }
}

/** The tap card's two lines for whatever was tapped. */
private fun tapText(tap: ForestTap, now: ForestNow): Pair<String, String> = when (tap) {
    is ForestTap.MoonTap -> ForestRules.moonTitle(now) to ForestRules.moonLine(now)
    is ForestTap.TreeTap -> treeText(tap.tree, now)
    is ForestTap.ThingTap -> tap.thing.tree?.let { treeText(it, now) } ?: thingText(tap.thing.sprite, now)
}

private fun treeText(t: ForestTree, now: ForestNow): Pair<String, String> {
    val kind = t.kind.title
    val planted = when (t.day) {
        now.day -> "Planted today"
        now.day - 1 -> "Planted yesterday"
        else -> "Planted on day ${t.day}"
    }
    return when {
        t.dead -> "$kind · dried out" to when {
            now.phase != ForestPhase.STORM -> "Tomorrow: the storm"
            now.eveningGreen -> "Tomorrow: a new forest"
            else -> "The storm brings the green back"
        }
        t.dry -> "$kind · drying" to "$planted · the dry spell"
        t.growing -> {
            val grown = t.stage == Stage.GROWN
            (if (grown) kind else "$kind · ${ForestRules.stageName(t.stage).lowercase()}") to when {
                now.catchingUp -> "Day ${t.day}'s tree · catching up"
                grown -> "Planted today · grown at ${ForestRules.hm(now.grownBy)}"
                else -> "Planted today · grown by ${ForestRules.hm(now.grownBy)}"
            }
        }
        else -> kind to planted + when (t.sprite) {
            "t-mango-fruit" -> " · in fruit"
            "t-mango-flower", "t-jasmine" -> " · in flower"
            "t-gulmohar" -> " · in bloom"
            else -> ""
        }
    }
}

/** Who came, and on which day (the tile's arrival number; [verb] says how it came). */
private class Who(val name: String, val day: Int, val verb: String = "Came", val note: String? = null)

private val WHO: Map<String, Who> = mapOf(
    "a-sparrow" to Who("Sparrow", 3), "a-bulbul" to Who("Bulbul", 3), "a-squirrel" to Who("Squirrel", 4),
    "a-rabbit" to Who("Rabbit", 5), "a-rabbit2" to Who("Rabbit", 5), "a-owl" to Who("Owl", 6, note = "out at night"),
    "a-frog" to Who("Frog", 7), "o-pond" to Who("The pond", 7, "Appeared", "frogs and lotus"), "a-dragonfly" to Who("Dragonfly", 8),
    "o-mushrooms" to Who("Mushrooms", 9, "Came up"), "o-rocks" to Who("Rocks", 9, "Appeared"), "o-stones" to Who("A stone path", 9, "Appeared"),
    "a-peacock" to Who("Peacock", 10), "a-peacock-dance" to Who("Peacock", 10, note = "dancing in the rain"),
    "a-nest-eggs" to Who("A nest with eggs", 11, "Built"), "a-nest-chicks" to Who("Chicks in the nest", 13, "Hatched"),
    "a-duck" to Who("Duck", 12), "a-deer" to Who("Spotted deer", 14), "a-doe" to Who("Spotted deer", 14), "a-tortoise" to Who("Tortoise", 15),
    "a-monkey" to Who("Monkey", 16), "o-beehive" to Who("Beehive", 17, "Built"), "a-bees" to Who("Bees", 17),
    "o-bench" to Who("Bench", 18, "Put up"), "o-swing" to Who("Swing", 18, "Put up"), "a-kingfisher" to Who("Kingfisher", 19),
    "a-parrot" to Who("Parrot", 20), "o-stream" to Who("Stream and bridge", 21, "Appeared"), "a-mongoose" to Who("Mongoose", 22),
    "a-fawn" to Who("Fawn", 23, note = "with its mother"), "a-elephant" to Who("Elephant", 24, note = "stays till the dry spell"),
    "a-elephant-calf" to Who("Elephant calf", 24, note = "stays till the dry spell"), "a-hornbill" to Who("Hornbill", 25),
    "a-peahen" to Who("Peahen", 26), "a-peachick" to Who("Peachick", 26), "a-tiger" to Who("A tiger, far away", 27, note = "only at dusk"),
)

private fun thingText(sprite: String, now: ForestNow): Pair<String, String> {
    WHO[sprite]?.let { w ->
        // In a short month the bonus things (25–27) come on the last green day.
        val on = min(w.day, now.monthLength - 4)
        val first = when {
            now.phase == ForestPhase.DRY -> "Leaving in the dry spell"
            w.day >= 25 && on == w.day -> "Bonus day ${w.day}" + if (on == now.day) " · today" else ""
            on == now.day -> "${w.verb} today"
            else -> "${w.verb} on day $on"
        }
        return w.name to listOfNotNull(first, w.note).joinToString(" · ")
    }
    val month = when (now.phase) {
        ForestPhase.DRY -> "The dry spell · the animals are leaving"
        ForestPhase.DESERT -> "Tomorrow: the storm"
        ForestPhase.STORM -> if (now.eveningGreen) "Tomorrow: a new forest" else "Rain all day"
        else -> ""
    }
    return when (sprite) {
        "a-trex" -> "A friendly T. rex" to "Visits every Sunday · waves hello"
        "a-dragon" -> "A good dragon" to if (now.night) "Resting till morning · visits every Sunday" else "Flies over every Sunday"
        "p-man-sit", "p-woman-sit", "p-man-walk", "p-woman-walk", "p-person-sit", "p-person-walk", "o-picnic" ->
            "Your picnic day" to "The two of you · back next month"
        "p-man-umbrella", "p-woman-umbrella", "o-umbrella-big" -> "Your picnic day" to "A rainy picnic · under a big umbrella"
        "o-tornado" -> "A tornado, far away" to "Spinning across the dry meadow · gone by tomorrow"
        "a-buffalo" -> "Water buffalo" to "Came to the last of the pond"
        "a-ox" -> "An ox" to "Came to the last of the pond"
        "o-volcano" -> "The volcano" to "Erupting on the far hills · quiet again after the storm"
        "a-camel" -> "Camel" to "Crossing the desert"
        "a-rhino" -> "Indian rhino" to "Wandering the desert"
        "a-whale" -> "A whale" to "Swam in with the flood"
        "a-dolphin" -> "Dolphin" to "Leaping in the flood"
        "a-fish" -> "A jumping fish" to "Swam in with the flood"
        "a-fish2" -> "Little fish" to "Jumping in the flood"
        "a-turtle" -> "Sea turtle" to "Swimming in the flood"
        "a-octopus" -> "Octopus" to "Waving from the flood"
        "a-jellyfish" -> "Jellyfish" to "Drifting in the flood"
        "a-crab" -> "Crab" to "Out on the wet land as the water goes down"
        "o-dead-tree" -> "A dried-out tree" to month
        "o-cactus" -> "Cactus" to month
        "o-dry-bush" -> "A dry bush" to month
        "o-pond-dry" -> "The pond is drying" to "It fills again after the storm"
        "o-cracks" -> "Cracked soil" to month
        "o-shoots" -> "Green shoots" to if (now.phase == ForestPhase.STORM) "Tomorrow: a new forest" else "Fresh after the storm"
        "o-puddle" -> "A puddle" to when {
            now.phase == ForestPhase.STORM -> "Rain all day"
            now.day == 1 -> "From last night's storm"
            else -> "A monsoon shower"
        }
        else -> sprite.substringAfter('-').replace('-', ' ').replaceFirstChar { it.uppercase() } to month
    }
}
