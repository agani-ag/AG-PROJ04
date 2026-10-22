package com.agani.syncup.decide

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.music.ToolTopBar
import com.agani.syncup.ui.theme.appCard
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// Decide → Coin toss (docs/mockup/syncup-everyday-tools.html, phones 3–5): a gold coin that flips in
// 3D, a count per side, sides you can rename, and "Toss for everyone" in a SyncUp Group.

/**
 * The side a shared toss lands on (0 = the first side, 1 = the second), worked out from its seed —
 * the same on every phone, so only the seed has to travel.
 */
internal fun coinSide(seed: Long): Int = if (((seed ushr 7) and 1L) == 0L) 0 else 1

private const val FLIP_MS = 1_100

/** Half-turns in a toss when it lands on the side it started on (one more when it doesn't). */
private const val HALF_TURNS = 8

/** A shared toss further back than this (from its start) is history: it isn't played or counted. */
private const val STALE_NS = 5_000_000_000L

private const val MAX_NAME = 24
private val COIN_SIZE = 214.dp

/** How high the coin goes at the top of a toss. */
private val RISE = 40.dp

private const val K_A = "coin_a"
private const val K_B = "coin_b"
private const val K_COUNT_A = "coin_count_a"
private const val K_COUNT_B = "coin_count_b"
private const val K_FACE = "coin_face"

private val QUICK_PICKS = listOf("Heads" to "Tails", "Yes" to "No", "Me" to "You", "Pizza" to "Biryani")

// The coin's own golds, the same in Light and Dark.
private val CoinLight = Color(0xFFFEF3C7)
private val CoinGold = Color(0xFFFBBF24)
private val CoinDeep = Color(0xFFB45309)
private val CoinRim = Color(0xFF92400E)
private val CoinRing = Color(0xFFFDE68A)
private val CoinInk = Color(0xFF78350F)

/**
 * Shared tosses this phone has already played or let pass. Kept for the whole app run, so leaving
 * the coin and coming back doesn't play (or count) the last one again.
 */
private val seenPlays = HashSet<Long>()

/**
 * The coin's motion. [progress] runs 0 → 1 through a toss (1 = resting); it turns [halfTurns] times
 * 180° from [from], the side up at the start — so the side that lands is picked by the parity.
 */
@Stable
private class CoinMotion(from: Int) {
    val progress = Animatable(1f)
    val settle = Animatable(1f)
    var halfTurns by mutableIntStateOf(0)
    var from by mutableIntStateOf(from)

    /** Degrees turned so far: fast at first, slowing down as it lands, face up. */
    fun angle(): Float {
        val left = 1f - progress.value
        return halfTurns * 180f * (1f - left * left)
    }

    /** Height in the air, 0 … 1: up and back down like a thrown coin. */
    fun lift(): Float = progress.value.let { 4f * it * (1f - it) }

    /** Half-turns done so far; each one shows the other side. */
    fun turned(): Int = ((angle() + 90f) / 180f).toInt()

    /** The side facing up right now. */
    fun side(): Int = from xor (turned() and 1)
}

/** The running toss, so a newer one (a shared toss arriving) can take over from it. */
private class TossRun {
    var job: Job? = null
    var token = 0
}

/** Decide → Coin toss. [onOpenGroup] opens SyncUp Group when "Toss for everyone" needs one. */
@Composable
internal fun CoinScreen(onBack: () -> Unit, onOpenGroup: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val amber = DecideTint.COIN.icon
    val prefs = remember { context.getSharedPreferences(DECIDE_PREFS, Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var names by remember {
        mutableStateOf(prefs.getString(K_A, null).orEmpty().ifBlank { "Heads" } to prefs.getString(K_B, null).orEmpty().ifBlank { "Tails" })
    }
    var countA by remember { mutableIntStateOf(prefs.getInt(K_COUNT_A, 0)) }
    var countB by remember { mutableIntStateOf(prefs.getInt(K_COUNT_B, 0)) }
    val motion = remember { CoinMotion(prefs.getInt(K_FACE, 0).coerceIn(0, 1)) }
    // The names on the coin: this phone's own, or those a shared toss brought.
    var coinNames by remember { mutableStateOf(names) }
    // A toss is on (from the tap, or while a shared toss waits for its moment) …
    var busy by remember { mutableStateOf(false) }
    // … and a shared toss is waiting to start, so every phone starts together.
    var waiting by remember { mutableStateOf(false) }
    // The shared toss on screen (its banner stays until the next toss).
    var shared by remember { mutableStateOf<SharedPlay?>(null) }
    var naming by rememberSaveable { mutableStateOf(false) }
    val run = remember { TossRun() }

    /** Flips to [result], starting [from] part-way (a shared toss that reached this phone late). */
    suspend fun flip(result: Int, from: Float) {
        motion.halfTurns = if (result == motion.from) HALF_TURNS else HALF_TURNS + 1
        motion.progress.snapTo(from)
        if (from < .1f) DecideFx.flip(context)
        val left = ((1f - from) * FLIP_MS).toInt()
        if (left > 0) motion.progress.animateTo(1f, tween(left, easing = LinearEasing))
        // Landed: the result is now simply the side up.
        motion.from = result
        motion.halfTurns = 0
        DecideFx.land(context)
        DecideFx.buzz(context, 30, 170)
        if (result == 0) countA++ else countB++
        prefs.edit().putInt(K_COUNT_A, countA).putInt(K_COUNT_B, countB).putInt(K_FACE, result).apply()
        motion.settle.snapTo(0f)
        motion.settle.animateTo(1f, tween(260))
    }

    /** Runs a toss; a newer one cancels it (only the newest clears [busy]). */
    fun start(block: suspend () -> Unit) {
        run.job?.cancel()
        val token = ++run.token
        busy = true
        run.job = scope.launch {
            try {
                block()
            } finally {
                if (run.token == token) {
                    busy = false
                    waiting = false
                }
            }
        }
    }

    fun tossHere() {
        if (busy) return
        shared = null
        coinNames = names
        start { flip(Random.nextInt(2), 0f) }
    }

    fun tossForEveryone() {
        if (busy) return
        val payload = JSONObject().put("a", names.first).put("b", names.second).toString()
        if (!Together.available || !Together.play(Together.TOOL_COIN, payload, Random.nextLong())) onOpenGroup()
    }

    // A toss for everyone — from anyone in the group, this phone included: wait for its moment,
    // then flip to the side its seed says, with its side names.
    val latest = Together.latest
    LaunchedEffect(latest?.id) {
        val play = latest ?: return@LaunchedEffect
        if (play.tool != Together.TOOL_COIN || !seenPlays.add(play.id)) return@LaunchedEffect
        if (System.nanoTime() - play.startAtNs > STALE_NS) return@LaunchedEffect
        val side = coinSide(play.seed)
        shared = play
        coinNames = sidesFrom(play.payload) ?: names
        start {
            waiting = true
            val wait = (play.startAtNs - System.nanoTime()) / 1_000_000L
            if (wait > 0) delay(wait)
            waiting = false
            // Late by a little (it opened this page, or the link was slow)? Join part-way, so it
            // still lands with the others.
            val from = ((System.nanoTime() - play.startAtNs) / 1_000_000f / FLIP_MS).coerceIn(0f, 1f)
            flip(side, from)
        }
    }

    ShakeToDo(enabled = !busy && !naming) { tossHere() }

    Column(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        ToolTopBar("Coin toss", onBack) {
            if (Together.available) DecideGroupChip(Together.phones, Modifier.padding(end = 2.dp))
            BarIcon(Icons.Rounded.Edit, "Name the sides", onClick = { naming = true })
            DecideMoreMenu()
        }
        val by = shared?.takeIf { !it.mine }?.by
        if (by != null) WhoBanner(by, Modifier.padding(horizontal = 16.dp))
        // Outside the scrolling part, so the coin can fly up over the bar without being cut off.
        CoinStage(motion, coinNames, if (by != null) 230.dp else 250.dp)
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            Box(Modifier.fillMaxWidth().height(46.dp), contentAlignment = Alignment.Center) {
                val (text, big) = when {
                    waiting -> "Get ready…" to false
                    busy -> "Flipping…" to false
                    countA + countB == 0 -> "${names.first} or ${names.second}?" to false
                    else -> (if (motion.from == 0) coinNames.first else coinNames.second) to true
                }
                Text(
                    text, fontSize = if (big) 34.sp else 26.sp, fontWeight = FontWeight.SemiBold, color = amber,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
            }
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
                if (busy && shared != null) {
                    val phones = Together.phones
                    Text(
                        if (phones >= 2) "Lands on all $phones phones at the same moment" else "Lands on every phone at the same moment",
                        fontSize = 13.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                    )
                } else {
                    Icon(DecideIcons.Shake, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Tap Toss, or shake the phone", fontSize = 13.sp, color = cs.onSurfaceVariant)
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                CountCard(countA, names.first, Modifier.weight(1f))
                CountCard(countB, names.second, Modifier.weight(1f))
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(cs.surfaceContainer).clickable(onClickLabel = "Reset the count") {
                        countA = 0
                        countB = 0
                        prefs.edit().putInt(K_COUNT_A, 0).putInt(K_COUNT_B, 0).apply()
                    },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Refresh, "Reset the count", tint = cs.onSurface, modifier = Modifier.size(20.dp)) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 16.dp)) {
                // Taps during a toss are ignored (rather than greying the buttons for a second each time).
                DecideButton("Toss", DecideTint.COIN, onClick = ::tossHere)
                DecideButton("Toss for everyone", DecideTint.COIN, onClick = ::tossForEveryone, soft = true, icon = Icons.Rounded.Groups)
            }
        }
    }

    if (naming) {
        SidesSheet(
            names,
            onDismiss = { naming = false },
            onSave = { n ->
                naming = false
                if (n != names) {
                    // New sides, new question: the old count doesn't mean anything for them.
                    names = n
                    if (!busy) coinNames = n
                    countA = 0
                    countB = 0
                    prefs.edit().putString(K_A, n.first).putString(K_B, n.second).putInt(K_COUNT_A, 0).putInt(K_COUNT_B, 0).apply()
                }
            },
        )
    }
}

/** A shared toss's side names ({"a": …, "b": …}); null when they're missing. */
private fun sidesFrom(payload: String): Pair<String, String>? = runCatching {
    val o = JSONObject(payload)
    val a = o.optString("a").trim().take(MAX_NAME)
    val b = o.optString("b").trim().take(MAX_NAME)
    if (a.isEmpty() || b.isEmpty()) null else a to b
}.getOrNull()

/** The coin in the air, and its shadow on the "table" while it's up. */
@Composable
private fun CoinStage(motion: CoinMotion, names: Pair<String, String>, height: Dp) {
    val side by remember(motion) { derivedStateOf { motion.side() } }
    val shadow = MaterialTheme.colorScheme.onSurface
    Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val h = motion.lift()
            if (h < .01f) return@Canvas
            val w = 100.dp.toPx() * (1f - .4f * h)
            drawOval(
                shadow, Offset(center.x - w / 2f, center.y + 100.dp.toPx()), Size(w, 12.dp.toPx()),
                alpha = .09f * (h * 4f).coerceAtMost(1f),
            )
        }
        CoinFace(
            if (side == 0) names.first else names.second,
            Modifier.size(COIN_SIZE).graphicsLayer {
                // Turned so far, less the half-turns done: the face up is always drawn the right way round.
                rotationX = motion.angle() - motion.turned() * 180f
                val h = motion.lift()
                translationY = -RISE.toPx() * h
                // A touch bigger at the top (closer), and a small dip as it lands.
                val s = (1f + .12f * h) * (1f - .05f * sin(PI * motion.settle.value).toFloat())
                scaleX = s
                scaleY = s
                cameraDistance = 16f * density
            },
        )
    }
}

/** One face of the coin: gold, a darker rim, a light inner ring, a ring of dots and the side's name. */
@Composable
private fun CoinFace(word: String, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val text = word.uppercase()
    Spacer(
        modifier.drawWithCache {
            // Drawn to the design's 214-wide coin, scaled to the real size.
            val s = size.minDimension
            val k = s / 214f
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = s / 2f - 8f * k
            val gold = Brush.radialGradient(
                0f to CoinLight, .45f to CoinGold, 1f to CoinDeep,
                center = Offset(c.x - r + 2f * r * .38f, c.y - r + 2f * r * .32f), radius = 2f * r * .75f,
            )
            val dotsR = s / 2f - 30f * k
            val label = fitWord(measurer, text, maxWidth = 2f * (dotsR - 12f * k), startPx = 36f * k, minPx = 13f * k, spacingPx = 2f * k)
            onDrawBehind {
                drawCircle(CoinRim, r, c + Offset(0f, 5f * k))
                drawCircle(gold, r, c)
                drawCircle(CoinRing, s / 2f - 20f * k, c, alpha = .8f, style = Stroke(3f * k))
                for (i in 0 until 36) {
                    val a = i * PI / 18
                    drawCircle(CoinRim, 2.2f * k, Offset(c.x + dotsR * cos(a).toFloat(), c.y + dotsR * sin(a).toFloat()), alpha = .45f)
                }
                drawText(label, topLeft = Offset(c.x - label.size.width / 2f, c.y - label.size.height / 2f))
            }
        },
    )
}

/** The side's name at the design's size, shrunk until it fits inside the dots (cut with … if even the smallest is too long). */
private fun CacheDrawScope.fitWord(measurer: TextMeasurer, text: String, maxWidth: Float, startPx: Float, minPx: Float, spacingPx: Float): TextLayoutResult {
    var px = startPx
    while (true) {
        val style = TextStyle(color = CoinInk, fontSize = px.toSp(), fontWeight = FontWeight.Bold, letterSpacing = (spacingPx * px / startPx).toSp())
        val laid = measurer.measure(text, style, maxLines = 1, softWrap = false)
        if (laid.size.width <= maxWidth) return laid
        if (px <= minPx) {
            return measurer.measure(text, style, overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1, constraints = Constraints(maxWidth = maxWidth.toInt()))
        }
        px = (px * .9f).coerceAtLeast(minPx)
    }
}

/** "7 · Heads": a side's count. */
@Composable
private fun CountCard(count: Int, name: String, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.appCard(RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text("$count", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
        Text(name, fontSize = 12.5.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "Arun tossed for everyone", over the coin while someone else's toss plays. */
@Composable
private fun WhoBanner(by: String, modifier: Modifier) {
    val tint = DecideGroupTint
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tint.tile).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(tint.icon), contentAlignment = Alignment.Center) {
            Text(initial(by), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = tint.onSolid)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(by.trim().ifEmpty { "Someone" }) }
                append(" tossed for everyone")
            },
            fontSize = 14.sp, color = tint.icon, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The first letter of a name (a whole emoji, not half of one), capitalised. */
private fun initial(name: String): String {
    val t = name.trim()
    if (t.isEmpty()) return "?"
    return String(Character.toChars(t.codePointAt(0))).uppercase()
}

/** ✎: name the coin's two sides, typed or from the quick picks. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SidesSheet(names: Pair<String, String>, onDismiss: () -> Unit, onSave: (Pair<String, String>) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var a by rememberSaveable { mutableStateOf(names.first) }
    var b by rememberSaveable { mutableStateOf(names.second) }
    val ok = a.isNotBlank() && b.isNotBlank()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = cs.surfaceContainerLowest,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Text("Name the two sides", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.padding(horizontal = 20.dp))
            Text(
                "Shown on the coin and in the count.", fontSize = 13.sp, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
            )
            Column(Modifier.padding(horizontal = 16.dp)) {
                DecideField(a, { a = it.take(MAX_NAME) }, label = "Heads side", placeholder = "Heads")
                DecideField(b, { b = it.take(MAX_NAME) }, label = "Tails side", placeholder = "Tails", onDone = { if (ok) onSave(a.trim() to b.trim()) })
            }
            Text(
                "Quick picks", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                QUICK_PICKS.forEach { (x, y) ->
                    PickChip("$x / $y", on = a.trim() == x && b.trim() == y) {
                        a = x
                        b = y
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp)) {
                Button(
                    onClick = onDismiss, shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = cs.primaryContainer, contentColor = cs.onPrimaryContainer),
                    modifier = Modifier.weight(1f).height(50.dp),
                ) { Text("Cancel", fontSize = 15.5.sp, fontWeight = FontWeight.Medium) }
                DecideButton("Save", DecideTint.COIN, onClick = { onSave(a.trim() to b.trim()) }, modifier = Modifier.weight(1f), enabled = ok)
            }
        }
    }
}

/** A quick-pick chip; [on] when the fields already hold its pair. */
@Composable
private fun PickChip(text: String, on: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(17.dp)
    Box(
        Modifier.height(34.dp).clip(shape)
            .background(if (on) cs.primaryContainer else cs.surfaceContainerLowest)
            .border(1.dp, if (on) Color.Transparent else cs.outlineVariant, shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 13.5.sp, color = if (on) cs.onPrimaryContainer else cs.onSurface, maxLines = 1)
    }
}
