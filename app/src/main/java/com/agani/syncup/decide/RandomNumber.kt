package com.agani.syncup.decide

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.ui.theme.CardShape
import com.agani.syncup.ui.theme.appCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

// Decide → Random number (docs/mockup/syncup-everyday-tools.html): any whole number between two.
// "No repeats" turns it into a raffle — each number once, with the drawn ones listed.

private const val K_FROM = "num_from"
private const val K_TO = "num_to"
private const val K_NO_REPEATS = "num_no_repeats"
private const val K_DRAWN = "num_drawn"
private const val K_DRAWN_RANGE = "num_drawn_range"
private const val K_LAST = "num_last"
private const val K_LAST_RANGE = "num_last_range"

/** Up to 999,999,999 either way: plenty, and the sums never overflow. */
private const val MAX_DIGITS = 9

/** Drawn numbers kept (newest first) — far more than anyone draws by hand. */
private const val MAX_KEPT = 10_000

/** Drawn numbers shown as chips; the rest are summed up as "+N more". */
private const val CHIPS_SHOWN = 60

/** Decide → Random number. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RandomNumberScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val sky = DecideTint.NUMBER
    val prefs = remember { context.getSharedPreferences(DECIDE_PREFS, Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    var fromText by rememberSaveable { mutableStateOf(prefs.getString(K_FROM, null) ?: "1") }
    var toText by rememberSaveable { mutableStateOf(prefs.getString(K_TO, null) ?: "100") }
    var noRepeats by remember { mutableStateOf(prefs.getBoolean(K_NO_REPEATS, false)) }
    // Newest first, for the range in drawnRange ("1:100").
    var drawn by remember { mutableStateOf(readNumbers(prefs.getString(K_DRAWN, null))) }
    var drawnRange by remember { mutableStateOf(prefs.getString(K_DRAWN_RANGE, null).orEmpty()) }
    var shown by remember { mutableStateOf(if (prefs.contains(K_LAST)) prefs.getLong(K_LAST, 0L) else null) }
    var shownRange by remember { mutableStateOf(readRange(prefs.getString(K_LAST_RANGE, null))) }
    var rolling by remember { mutableStateOf(false) }
    val pop = remember { Animatable(1f) }

    val from = fromText.toLongOrNull()
    val to = toText.toLongOrNull()
    val range = if (from != null && to != null && from <= to) from to to else null
    val total = range?.let { it.second - it.first + 1 } ?: 0L
    // The drawn list belongs to one range: another range starts afresh (and going back shows it again).
    val drawnHere = if (range != null && drawnRange == key(range)) drawn else emptyList()
    val allDrawn = noRepeats && range != null && drawnHere.size >= total

    fun pick() {
        val r = range ?: return
        if (rolling) return
        focus.clearFocus()
        val (f, t) = r
        val taken = if (noRepeats) drawnHere.toHashSet() else emptySet()
        if (taken.size >= total) return
        val result = randomIn(f, t, taken)
        val before = drawnHere
        scope.launch {
            rolling = true
            shownRange = r
            try {
                // A quick shuffle that slows down (about 0.6 s), then the pick lands.
                for (i in 0 until 12) {
                    shown = Random.nextLong(f, t + 1)
                    DecideFx.tick(context)
                    delay(25L + i * 5L)
                }
                shown = result
                DecideFx.land(context)
                DecideFx.buzz(context, 25, 150)
                if (noRepeats) {
                    drawn = (listOf(result) + before).take(MAX_KEPT)
                    drawnRange = key(r)
                }
                prefs.edit()
                    .putLong(K_LAST, result).putString(K_LAST_RANGE, key(r))
                    .putString(K_DRAWN, drawn.joinToString(",")).putString(K_DRAWN_RANGE, drawnRange)
                    .apply()
            } finally {
                rolling = false
            }
            pop.snapTo(1.12f)
            pop.animateTo(1f, spring(dampingRatio = .45f, stiffness = 500f))
        }
    }

    fun startOver() {
        drawn = emptyList()
        drawnRange = ""
        prefs.edit().remove(K_DRAWN).remove(K_DRAWN_RANGE).apply()
    }

    DecidePage("Random number", onBack, actions = { DecideMoreMenu() }) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            DecideField(
                fromText, {
                    fromText = cleanWhole(it)
                    prefs.edit().putString(K_FROM, fromText).apply()
                },
                Modifier.weight(1f), label = "From", placeholder = "1", keyboardType = KeyboardType.Number,
            )
            DecideField(
                toText, {
                    toText = cleanWhole(it)
                    prefs.edit().putString(K_TO, toText).apply()
                },
                Modifier.weight(1f), label = "To", placeholder = "100", keyboardType = KeyboardType.Number, onDone = ::pick,
            )
        }
        // A gentle word when the range can't be used yet — nothing is changed for the person.
        val note = when {
            from == null || to == null -> "Type a whole number in both boxes."
            from > to -> "“From” should be the smaller number."
            else -> null
        }
        if (note != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp)) {
                Text(note, fontSize = 12.5.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (from != null && to != null) {
                    Text(
                        "Swap", fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = cs.primary,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                            val old = fromText
                            fromText = toText
                            toText = old
                            prefs.edit().putString(K_FROM, fromText).putString(K_TO, toText).apply()
                        }.padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
            }
        }

        // The big number.
        val number = shown?.toString() ?: "?"
        val under = (if (rolling || shown != null) shownRange ?: range else range)
            ?.let { "between ${it.first} and ${it.second}" } ?: "Pick a range"
        Column(
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 18.dp)
                .graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                }
                .size(190.dp).clip(RoundedCornerShape(48.dp)).background(sky.tile).padding(horizontal = 10.dp),
        ) {
            val size = bigSize(number.length)
            Text(number, fontSize = size, lineHeight = size * 1.05f, fontWeight = FontWeight.SemiBold, color = sky.icon, maxLines = 1, softWrap = false)
            Text(under, fontSize = 13.sp, color = sky.icon.copy(alpha = .8f), textAlign = TextAlign.Center, maxLines = 2)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 18.dp).fillMaxWidth().appCard(CardShape)
                .clickable {
                    noRepeats = !noRepeats
                    prefs.edit().putBoolean(K_NO_REPEATS, noRepeats).apply()
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Icon(Icons.Rounded.Repeat, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("No repeats", fontSize = 15.sp, color = cs.onSurface)
                Text("Each number comes up only once", fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant)
            }
            Switch(checked = noRepeats, onCheckedChange = {
                noRepeats = it
                prefs.edit().putBoolean(K_NO_REPEATS, it).apply()
            })
        }

        if (noRepeats && range != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 6.dp)) {
                Text("Drawn · ${drawnHere.size} of ${"%,d".format(total)}", fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (drawnHere.isNotEmpty()) {
                    Text(
                        "Start over", fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = cs.primary,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = ::startOver).padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
            }
            if (drawnHere.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = 4.dp),
                ) {
                    drawnHere.take(CHIPS_SHOWN).forEach { DrawnChip("$it", sky.tile, sky.icon) }
                    if (drawnHere.size > CHIPS_SHOWN) DrawnChip("+${drawnHere.size - CHIPS_SHOWN} more", cs.surfaceContainer, cs.onSurfaceVariant)
                }
            }
        }

        Box(Modifier.padding(top = 16.dp)) {
            if (allDrawn) {
                DecideButton("All numbers drawn — Start over", sky, onClick = ::startOver, soft = true, icon = Icons.Rounded.Refresh)
            } else {
                // Taps while it rolls are ignored, rather than greying the button for a moment.
                DecideButton("Pick a number", sky, onClick = ::pick, enabled = range != null)
            }
        }
    }
}

@Composable
private fun DrawnChip(text: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier.defaultMinSize(minWidth = 38.dp).height(32.dp).clip(RoundedCornerShape(16.dp)).background(bg).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = fg, maxLines = 1) }
}

/** The big number's size: 76 sp for up to three digits, smaller as it gets longer, so it fits the square. */
private fun bigSize(length: Int): TextUnit = when {
    length <= 3 -> 76.sp
    length == 4 -> 62.sp
    length == 5 -> 50.sp
    length == 6 -> 42.sp
    length == 7 -> 36.sp
    length == 8 -> 32.sp
    else -> 28.sp
}

/** What's typed, kept to a whole number: an optional minus, then up to [MAX_DIGITS] digits. */
private fun cleanWhole(typed: String): String {
    val t = typed.trim()
    val minus = t.startsWith("-") || t.startsWith("−")
    val digits = t.filter { it in '0'..'9' }.take(MAX_DIGITS)
    return if (minus) "-$digits" else digits
}

/** A random whole number from [from] to [to], skipping [taken]. */
private fun randomIn(from: Long, to: Long, taken: Set<Long>): Long {
    if (taken.isEmpty()) return Random.nextLong(from, to + 1)
    // A small range: pick from what's left. A big one: just try again on a taken number (rare).
    if (to - from < 20_000) return (from..to).filter { it !in taken }.random()
    while (true) {
        val n = Random.nextLong(from, to + 1)
        if (n !in taken) return n
    }
}

private fun key(range: Pair<Long, Long>) = "${range.first}:${range.second}"

private fun readRange(text: String?): Pair<Long, Long>? {
    val parts = text?.split(':') ?: return null
    val a = parts.getOrNull(0)?.toLongOrNull() ?: return null
    val b = parts.getOrNull(1)?.toLongOrNull() ?: return null
    return a to b
}

private fun readNumbers(text: String?): List<Long> =
    text?.split(',')?.mapNotNull { it.trim().toLongOrNull() }.orEmpty()
