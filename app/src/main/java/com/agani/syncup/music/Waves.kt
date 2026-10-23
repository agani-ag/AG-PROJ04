package com.agani.syncup.music

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.agani.syncup.ui.theme.isLight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.exp

// Now playing's picture: the cover, or — after a tap — the song's waves on a heart-rate monitor that
// fills the square. Remembered for the next songs and next time.
private const val PREF_WAVES = "waves_on"
private const val PREF_HINT_SONGS = "waves_hint_songs"
private const val PREF_HINT_LAST = "waves_hint_last"
private const val PREF_HINT_DONE = "waves_hint_done"
private const val HINT_SONGS = 3

private val COVER_SHAPE = RoundedCornerShape(28.dp)
private const val SILENT_DB = -60f
private const val TRACE = 220          // points across the monitor
private const val SWEEP_MS = 4000f     // one sweep, left to right
private const val GAP = 8              // the cleared stretch ahead of the dot
private val MONITOR = Color(0xFF0B0F14)
private val GRID = Color(0x0FFFFFFF)

/**
 * The cover in [boxModifier]'s square — tap for the waves, tap the waves for the cover. [overlay] sits
 * on top of both (the LIVE badge).
 */
@Composable
internal fun CoverOrWaves(player: PlayerHandle, boxModifier: Modifier, overlay: @Composable BoxScope.() -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(MUSIC_PREFS, Context.MODE_PRIVATE) }
    var waves by remember { mutableStateOf(prefs.getBoolean(PREF_WAVES, false)) }
    val glow = rememberGlow(player.artUri, player.isRadio)

    // "Tap for waves" on the cover for the first few songs, until the first tap.
    val itemId = player.item?.mediaId
    var hint by remember { mutableStateOf(false) }
    LaunchedEffect(itemId, waves) {
        hint = !waves && itemId != null && hintFor(prefs, itemId)
    }

    val flip = {
        waves = !waves
        hint = false
        prefs.edit().putBoolean(PREF_WAVES, waves).putBoolean(PREF_HINT_DONE, true).apply()
    }

    Box(boxModifier) {
        Crossfade(waves, animationSpec = tween(200), label = "cover-waves") { on ->
            if (on) {
                Box(Modifier.fillMaxSize().clip(COVER_SHAPE).clickable(onClickLabel = "Show cover", onClick = flip)) {
                    Monitor(player, glow)
                }
            } else {
                Box(Modifier.fillMaxSize().clip(COVER_SHAPE).clickable(onClickLabel = "Show waves", onClick = flip)) {
                    ArtworkTile(player.artUri, player.isRadio, Modifier.fillMaxSize(), radius = 28.dp, large = true)
                    if (hint) TapHint(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
                }
            }
        }
        overlay()
    }
}

/** True while [id] is one of the first [HINT_SONGS] songs seen with the cover — counting it the first time. */
private fun hintFor(prefs: SharedPreferences, id: String): Boolean {
    if (prefs.getBoolean(PREF_HINT_DONE, false)) return false
    if (prefs.getString(PREF_HINT_LAST, null) == id) return true
    val seen = prefs.getInt(PREF_HINT_SONGS, 0)
    if (seen >= HINT_SONGS) return false
    prefs.edit().putInt(PREF_HINT_SONGS, seen + 1).putString(PREF_HINT_LAST, id).apply()
    return true
}

@Composable
private fun TapHint(modifier: Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clip(RoundedCornerShape(13.dp)).background(Color(0x990F172A)).padding(horizontal = 11.dp, vertical = 5.dp),
    ) {
        Icon(Icons.Rounded.GraphicEq, null, tint = Color.White, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text("Tap for waves", fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = Color.White)
    }
}

/** What the monitor draws from: its trace, the loudness word, and a per-frame tick. */
@Stable
private class WaveState {
    val tick = mutableLongStateOf(0L)
    var word by mutableStateOf("Quiet")
    private var loudness = SILENT_DB

    /** One value per point across, written by a dot sweeping left to right. */
    val trace = FloatArray(TRACE)
    var head = 0
    private var sweep = 0f
    private var fast = 0f
    private var slow = 0f
    private var primed = false

    /** [dt] ms passed; [frame] = what's heard now, or null while nothing is (yet). */
    fun advance(dt: Float, frame: SoundWaves.Frame?) {
        // The word follows about a second of the song, not every beat.
        loudness += ((frame?.loudnessDb ?: SILENT_DB) - loudness) * (1f - exp(-dt / 800f))
        val w = loudWord(loudness)
        if (w != word) word = w

        // A beat is the bass jumping up: quick minus slow gives a sharp spike on each hit and a small
        // dip after it — the shape of a heartbeat on a monitor. Calm music stays flat.
        val bass = frame?.bass ?: slow
        if (!primed) {
            fast = bass
            slow = bass
            primed = frame != null
        }
        fast += (bass - fast) * (1f - exp(-dt / 12f))
        slow += (bass - slow) * (1f - exp(-dt / 160f))
        val value = ((fast - slow) * 2.8f).coerceIn(-.35f, 1f)

        val from = sweep.toInt()
        sweep = (sweep + dt / SWEEP_MS * TRACE) % TRACE
        val to = sweep.toInt()
        var i = from
        while (i != to) {
            i = (i + 1) % TRACE
            trace[i] = value
        }
        head = to
    }
}

private fun loudWord(db: Float) = when {
    db < -34f -> "Quiet"
    db < -24f -> "Soft"
    db < -16f -> "Medium"
    db < -9f -> "Loud"
    else -> "Very loud"
}

/**
 * Runs the monitor while it can be seen: the song is playing and the app is on screen. Paused, the
 * trace holds still and the drawing stops (no battery used while nothing moves).
 */
@Composable
private fun rememberWaveState(playing: Boolean): WaveState {
    val state = remember { WaveState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var started by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val on = playing && started
    DisposableEffect(on) {
        SoundWaves.active = on
        onDispose { SoundWaves.active = false }
    }
    LaunchedEffect(on) {
        if (!on) return@LaunchedEffect
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            val dt = if (last == 0L) 16f else ((now - last) / 1e6f).coerceIn(1f, 100f)
            last = now
            state.advance(dt, SoundWaves.frameAt(System.nanoTime()))
            state.tick.longValue = now
        }
    }
    return state
}

/**
 * The song's pulse, like a heart-rate monitor filling the square: a glowing trace swept left to right
 * over a dark grid, spiking on each beat. How loud it is is noted top right, what that means bottom right.
 */
@Composable
private fun Monitor(player: PlayerHandle, glow: Color) {
    val playing = player.isPlaying
    val state = rememberWaveState(playing)
    val fresh = remember { Path() }
    val old = remember { Path() }
    Box(Modifier.fillMaxSize().background(MONITOR)) {
        Canvas(Modifier.fillMaxSize()) {
            state.tick.longValue // redraw every frame the monitor runs
            val cell = 14.dp.toPx()
            var gx = cell
            while (gx < size.width) {
                drawLine(GRID, Offset(gx, 0f), Offset(gx, size.height), 1f)
                gx += cell
            }
            var gy = cell
            while (gy < size.height) {
                drawLine(GRID, Offset(0f, gy), Offset(size.width, gy), 1f)
                gy += cell
            }

            // Between the two notes: spikes reach about a fifth down, dips stay well above the foot.
            val stepX = size.width / (TRACE - 1)
            val base = size.height * .58f
            val amp = size.height * .4f
            fun y(i: Int) = (base - state.trace[i] * amp).coerceIn(3f, size.height - 3f)
            val head = state.head
            // This sweep: from the left edge to the dot. The last sweep: after the gap, to the right edge.
            fresh.reset()
            fresh.moveTo(0f, y(0))
            for (i in 1..head) fresh.lineTo(i * stepX, y(i))
            old.reset()
            val oldStart = head + GAP
            if (oldStart < TRACE - 1) {
                old.moveTo(oldStart * stepX, y(oldStart))
                for (i in oldStart + 1 until TRACE) old.lineTo(i * stepX, y(i))
            }
            val thin = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val wide = Stroke(7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawPath(old, glow.copy(alpha = .08f), style = wide)
            drawPath(old, glow.copy(alpha = .32f), style = thin)
            if (head > 0) {
                val fade = Brush.horizontalGradient(listOf(glow.copy(alpha = .55f), glow), startX = 0f, endX = head * stepX)
                drawPath(fresh, glow.copy(alpha = .16f), style = wide)
                drawPath(fresh, fade, style = thin)
            }
            val dot = Offset(head * stepX, y(head))
            drawCircle(glow.copy(alpha = .25f), 7.dp.toPx(), dot)
            drawCircle(glow, 3.5.dp.toPx(), dot)
            drawCircle(Color.White.copy(alpha = .85f), 1.5.dp.toPx(), dot)
        }
        Text(
            if (playing) state.word else "Paused", fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold,
            color = glow, textAlign = TextAlign.End,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 18.dp),
        )
        Text(
            if (player.isRadio) "How loud the station is, right now" else "How loud the song is, right now",
            fontSize = 11.5.sp, lineHeight = 15.sp, color = Color.White.copy(alpha = .62f), textAlign = TextAlign.End,
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 16.dp, end = 18.dp),
        )
    }
}

// ============================================================================ colour from the cover

/** Each cover's main colour (hue, saturation) — or none for a grey / missing picture. */
private object CoverHues {
    private val cache = LruCache<String, FloatArray>(64)
    private val NONE = FloatArray(0)

    fun cached(uri: Uri): FloatArray? = cache.get(uri.toString())

    fun find(context: Context, uri: Uri): FloatArray {
        cached(uri)?.let { return it }
        val bitmap = Artwork.cached(uri, Artwork.LARGE) ?: Artwork.load(context, uri, Artwork.SMALL)
        val hue = bitmap?.let(::mainHue) ?: NONE
        cache.put(uri.toString(), hue)
        return hue
    }

    /** The colour most of the cover's bright, colourful pixels share; null when there are too few. */
    private fun mainHue(source: Bitmap): FloatArray? {
        val b = if (Build.VERSION.SDK_INT >= 26 && source.config == Bitmap.Config.HARDWARE) {
            source.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        } else {
            source
        }
        val side = 32
        val small = Bitmap.createScaledBitmap(b, side, side, true)
        val pixels = IntArray(side * side)
        small.getPixels(pixels, 0, side, 0, 0, side, side)
        val weight = FloatArray(24)
        val hueSum = FloatArray(24)
        val satSum = FloatArray(24)
        val hsv = FloatArray(3)
        for (p in pixels) {
            android.graphics.Color.colorToHSV(p, hsv)
            if (hsv[1] < .22f || hsv[2] < .25f) continue // greys, near-black
            val w = hsv[1] * hsv[2]
            val bucket = (hsv[0] / 15f).toInt() % 24
            weight[bucket] += w
            hueSum[bucket] += hsv[0] * w
            satSum[bucket] += hsv[1] * w
        }
        // The strongest colour, counting its neighbours a little (a gradient across two buckets).
        val best = weight.indices.maxBy { weight[it] + .5f * (weight[(it + 1) % 24] + weight[(it + 23) % 24]) }
        if (weight[best] < 6f) return null
        return floatArrayOf(hueSum[best] / weight[best], satSum[best] / weight[best])
    }

    fun isNone(hue: FloatArray) = hue.isEmpty()
}

/**
 * The monitor's colour: the cover's main colour, bright enough to glow on the dark monitor (in Light
 * and Dark alike). No cover (or a grey one): the soft tile colour the song has in the list.
 */
@Composable
private fun rememberGlow(uri: Uri?, radio: Boolean): Color {
    val tintIcon = artTint(uri, radio).icon
    val h = rememberCoverHue(uri)
    return remember(h, tintIcon) {
        if (h == null || CoverHues.isNone(h)) glowOf(tintIcon) else tone(h[0], h[1].coerceIn(.6f, .95f), .55f)
    }
}

/** The cover's main hue and saturation (worked out once per song, off the main thread); null while unknown. */
@Composable
private fun rememberCoverHue(uri: Uri?): FloatArray? {
    val context = LocalContext.current
    val hue by produceState(uri?.let { CoverHues.cached(it) }, uri) {
        value = if (uri == null || !Artwork.isLocal(uri)) null
        else CoverHues.cached(uri) ?: withContext(Dispatchers.IO) { CoverHues.find(context, uri) }
    }
    return hue
}

/** A song's colour as a soft [tile] and a [solid] fill with [onSolid] on it, like a [ToolTint]. */
internal class CoverColors(val tile: Color, val solid: Color, val onSolid: Color)

/**
 * The cover's own colour, as soft and as solid as the tool colours are (Play at a party). No cover (or a
 * grey one): the soft tile colour the song has in the list.
 */
@Composable
internal fun rememberCoverColors(uri: Uri?, radio: Boolean): CoverColors {
    val tint = artTint(uri, radio)
    val light = MaterialTheme.colorScheme.isLight
    val tintTile = tint.tile
    val tintSolid = tint.icon
    val onSolid = tint.onSolid
    val h = rememberCoverHue(uri)
    return remember(h, light, tintTile, tintSolid, onSolid) {
        when {
            h == null || CoverHues.isNone(h) -> CoverColors(tintTile, tintSolid, onSolid)
            light -> CoverColors(tone(h[0], h[1].coerceIn(.4f, .8f), .8f), tone(h[0], h[1].coerceIn(.55f, .9f), .14f), onSolid)
            else -> CoverColors(tone(h[0], h[1].coerceIn(.3f, .6f), .016f), tone(h[0], h[1].coerceIn(.55f, .9f), .5f), onSolid)
        }
    }
}

/** [color]'s hue, bright enough to glow on the dark monitor. */
private fun glowOf(color: Color): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    return tone(hsl[0], hsl[1].coerceIn(.6f, .95f), .55f)
}

/**
 * [hue] and [saturation] at the lightness whose relative luminance is [luminance] — set by how bright a
 * colour looks, not its HSL lightness (a teal and an orange at the same lightness look very different).
 */
private fun tone(hue: Float, saturation: Float, luminance: Float): Color {
    var lo = 0f
    var hi = 1f
    repeat(16) {
        val mid = (lo + hi) / 2f
        if (Color.hsl(hue, saturation, mid).luminance() < luminance) lo = mid else hi = mid
    }
    return Color.hsl(hue, saturation, (lo + hi) / 2f)
}
