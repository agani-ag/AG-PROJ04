package com.agani.syncup.video

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.StepSlider
import kotlinx.coroutines.delay
import kotlin.math.log2
import kotlin.math.roundToInt

/** A sound style for videos — the phone's own sound effects, nothing downloaded. */
enum class SoundStyle(val key: String, val label: String) {
    NORMAL("normal", "Normal"),
    VOICE("voice", "Clear voice"),
    BASS("bass", "Bass"),
    NIGHT("night", "Night"),
    SURROUND("surround", "Surround");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: NORMAL
    }
}

/**
 * How videos sound: the volume booster (100–300%) and a [SoundStyle]. Remembered for all videos and
 * used by every player (the Video player, Watch Party) — each phone sets its own.
 */
object VideoSound {
    private const val PREFS = "video_sound"
    const val MAX_BOOST = 300

    var boost by mutableIntStateOf(100)
        private set
    var style by mutableStateOf(SoundStyle.NORMAL)
        private set
    private var ready = false

    fun init(context: Context) {
        if (ready) return
        ready = true
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        boost = p.getInt("boost", 100).coerceIn(100, MAX_BOOST)
        style = SoundStyle.of(p.getString("style", null))
    }

    fun changeBoost(context: Context, value: Int) {
        boost = value.coerceIn(100, MAX_BOOST)
        save(context)
    }

    fun changeStyle(context: Context, value: SoundStyle) {
        style = value
        save(context)
    }

    fun reset(context: Context) {
        boost = 100
        style = SoundStyle.NORMAL
        save(context)
    }

    private fun save(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("boost", boost).putString("style", style.key).apply()
    }
}

/** A booster's gain in millibels: twice as loud (200%) = +10 dB, three times (300%) about +16 dB. */
internal fun boostGain(boost: Int): Int = if (boost <= 100) 0 else (1000 * log2(boost / 100.0)).roundToInt()

/**
 * The players' sound level, for the right-side slide and the volume keys, one unit per 100%: 0..1 = the phone's
 * volume, 1..2 = the booster's 100–200%, 2..3 = its 200–300%.
 */
internal object SoundLevel {
    const val MAX = VideoSound.MAX_BOOST / 100f

    fun of(volume: Float, boost: Int): Float = if (boost > 100) boost / 100f else volume.coerceIn(0f, 1f)

    /** The booster at [level], in 5% steps. */
    fun boostAt(level: Float): Int = 100 + (((level - 1f).coerceAtLeast(0f) * 100f) / 5f).roundToInt() * 5

    /**
     * A volume key: the phone's volume a step; past full volume the booster ([boost], changed through [setBoost])
     * 10% at a time (down takes it off first).
     */
    fun key(context: Context, up: Boolean, boost: Int = VideoSound.boost, setBoost: (Int) -> Unit = { VideoSound.changeBoost(context, it) }) {
        val audio = context.getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        when {
            up && volume >= max -> setBoost(boost + 10)
            !up && boost > 100 -> setBoost(boost - 10)
            else -> runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, (volume + if (up) 1 else -1).coerceIn(0, max), 0) }
        }
    }
}

/** Which bar shows at the side of a player while sliding: brightness on the left, volume on the right. */
internal enum class PlayerBar { BRIGHTNESS, VOLUME }

/** The left-side bar: the screen's brightness, 0..1. */
@Composable
internal fun BrightnessBar(brightness: Float, modifier: Modifier = Modifier, height: Dp = 150.dp) {
    val icon = when {
        brightness < .34f -> Icons.Rounded.BrightnessLow
        brightness < .67f -> Icons.Rounded.BrightnessMedium
        else -> Icons.Rounded.BrightnessHigh
    }
    LevelBar("${(brightness * 100).roundToInt()}%", brightness, listOf(Color.White), height, modifier) {
        Icon(icon, "Brightness", tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

/** The volume bar's bands, one per 100%: the phone's volume, then the booster's 100–200% and 200–300%. */
private val VolumeBands = listOf(Color.White, Color(0xFFF59E0B), Color(0xFFEF4444))

/** The colour of the band [boost] (or full volume, 100) is in. */
internal fun boostColor(boost: Int): Color = VolumeBands[((boost - 1) / 100).coerceIn(0, VolumeBands.lastIndex)]

/**
 * The right-side bar in three equal bands: the phone's volume (white), the booster's 100–200% (amber) and its
 * 200–300% (red).
 */
@Composable
internal fun VolumeBar(volume: Float, boost: Int, modifier: Modifier = Modifier, height: Dp = 150.dp) {
    val boosted = boost > 100
    LevelBar(if (boosted) "$boost%" else "${(volume * 100).roundToInt()}%", SoundLevel.of(volume, boost) / SoundLevel.MAX, VolumeBands, height, modifier) {
        if (boosted) {
            Text("BOOST", color = boostColor(boost), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = .5.sp)
        } else {
            val icon = when {
                volume <= 0f -> Icons.AutoMirrored.Rounded.VolumeOff
                volume < .5f -> Icons.AutoMirrored.Rounded.VolumeDown
                else -> Icons.AutoMirrored.Rounded.VolumeUp
            }
            Icon(icon, "Volume", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

/** A slim upright bar filled from the bottom to [fraction], in equal [bands] of colour (the value text takes its band's). */
@Composable
private fun LevelBar(value: String, fraction: Float, bands: List<Color>, height: Dp, modifier: Modifier, foot: @Composable () -> Unit) {
    val f = fraction.coerceIn(0f, 1f)
    val n = bands.size
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.width(52.dp).clip(RoundedCornerShape(26.dp)).background(Color.Black.copy(alpha = .8f)).padding(vertical = 12.dp),
    ) {
        val band = (kotlin.math.ceil(f * n - 1e-4f).toInt() - 1).coerceIn(0, n - 1)
        Text(value, color = bands[band], fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.width(6.dp).height(height).clip(RoundedCornerShape(3.dp))) {
            drawRect(Color.White.copy(alpha = .25f))
            bands.forEachIndexed { i, color ->
                val from = i.toFloat() / n
                val filled = (f - from).coerceIn(0f, 1f / n)
                if (filled > 0f) drawRect(color, Offset(0f, size.height * (1f - from - filled)), Size(size.width, size.height * filled))
                // Where one band ends and the next starts.
                if (i > 0) drawRect(Color.Black.copy(alpha = .7f), Offset(0f, size.height * (1f - from) - 1.dp.toPx()), Size(size.width, 2.dp.toPx()))
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.height(20.dp), contentAlignment = Alignment.Center) { foot() }
    }
}

/**
 * The booster and style on one player's sound (its audio session). Create once per player, call
 * [apply] when the settings change, [release] with the player. Effects a phone lacks are skipped.
 */
class SoundEffects(private val session: Int) {
    private var loudness: LoudnessEnhancer? = null
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var dynamics: DynamicsProcessing? = null

    fun apply(boost: Int, style: SoundStyle) {
        // Boost: 100% = as is, 200% = +10 dB (about twice as loud), 300% ≈ +16 dB, with the effect's own limiter.
        runCatching {
            val l = loudness ?: LoudnessEnhancer(session).also { loudness = it }
            l.setTargetGain(boostGain(boost))
            l.enabled = boost > 100
        }
        runCatching {
            val e = eq ?: Equalizer(0, session).also { eq = it }
            val (min, max) = e.bandLevelRange.let { it[0].toInt() to it[1].toInt() }
            for (b in 0 until e.numberOfBands) {
                val hz = e.getCenterFreq(b.toShort()) / 1000
                val level = when (style) {
                    // Speech lives around 1–4 kHz: lift it, trim the rumble.
                    SoundStyle.VOICE -> when {
                        hz in 1_000..4_500 -> 600
                        hz < 250 -> -400
                        else -> 0
                    }
                    SoundStyle.BASS -> if (hz < 250) 600 else 0
                    else -> 0
                }
                e.setBandLevel(b.toShort(), level.coerceIn(min, max).toShort())
            }
            e.enabled = style == SoundStyle.VOICE || style == SoundStyle.BASS
        }
        runCatching {
            val b = bass ?: BassBoost(0, session).also { bass = it }
            if (b.strengthSupported) b.setStrength(700)
            b.enabled = style == SoundStyle.BASS
        }
        runCatching {
            val v = virtualizer ?: Virtualizer(0, session).also { virtualizer = it }
            if (v.strengthSupported) v.setStrength(1000)
            v.enabled = style == SoundStyle.SURROUND
        }
        if (Build.VERSION.SDK_INT >= 28) runCatching {
            // Night: quiet talk up, loud bangs down (a compressor), so nobody reaches for the volume.
            val d = dynamics ?: nightCompressor().also { dynamics = it }
            d?.enabled = style == SoundStyle.NIGHT
        }
    }

    private fun nightCompressor(): DynamicsProcessing? {
        if (Build.VERSION.SDK_INT < 28) return null
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
            false, 0, true, 1, false, 0, true,
        ).build()
        val d = DynamicsProcessing(0, session, config)
        val band = DynamicsProcessing.MbcBand(
            true, 20_000f, 5f, 200f, 4f, -32f, 8f, -90f, 1f, 0f, 10f,
        )
        d.setMbcBandAllChannelsTo(0, band)
        d.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -2f, 0f))
        return d
    }

    fun release() {
        listOf(loudness, eq, bass, virtualizer, dynamics).forEach { runCatching { it?.release() } }
        loudness = null
        eq = null
        bass = null
        virtualizer = null
        dynamics = null
    }
}

private val PanelBg = Color(0xFF0F1115)
private val Rose = Color(0xFFE11D48)

/** The red of the players' seek bar and progress lines. */
internal val SeekRed = Color(0xFFEF4444)

/**
 * The players' colours (Video player, Watch Party) — always dark: SyncUp's Dark (pure black, cards a
 * hair lighter with thin lines) with the player's [accent] for buttons, radio dots and links.
 */
internal fun playerScheme(accent: Color): ColorScheme = darkColorScheme(
    primary = accent, onPrimary = Color.White,
    primaryContainer = Color(0xFF3B1218), onPrimaryContainer = Color(0xFFFECDD3),
    secondary = Color(0xFF98A2B3), onSecondary = Color(0xFF0F172A),
    secondaryContainer = Color(0xFF1C2130), onSecondaryContainer = Color(0xFFDCE3F0),
    background = Color(0xFF000000), onBackground = Color(0xFFE8ECF2),
    surface = Color(0xFF000000), onSurface = Color(0xFFE8ECF2),
    surfaceVariant = Color(0xFF1E232C), onSurfaceVariant = Color(0xFF98A2B3),
    surfaceContainerLowest = Color(0xFF0F1115), surfaceContainerLow = Color(0xFF121419),
    surfaceContainer = Color(0xFF161A21), surfaceContainerHigh = Color(0xFF1E232C),
    surfaceContainerHighest = Color(0xFF262C38), surfaceBright = Color(0xFF262C38), surfaceDim = Color(0xFF000000),
    outline = Color(0xFF3A4250), outlineVariant = Color(0xFF1E232C),
    error = Color(0xFFF87171), onError = Color(0xFF3A0A0A),
    errorContainer = Color(0xFF3B1515), onErrorContainer = Color(0xFFFFD9D9),
    scrim = Color(0xFF000000),
)

/**
 * The players' seek bar: a slim red line with a round red dot. Drag or tap it — no − / + here (only
 * value sliders have those). [fraction] is 0..1.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoSeekBar(fraction: Float, onSeek: (Float) -> Unit, onSeekDone: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Slider(
        value = fraction.coerceIn(0f, 1f), onValueChange = onSeek, onValueChangeFinished = onSeekDone, enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        thumb = { Box(Modifier.size(16.dp).clip(CircleShape).background(SeekRed)) },
        track = { state -> SeekLine(state.value) },
    )
}

/** The seek bar's line on its own (a guest's view of where the video is). */
@Composable
internal fun SeekLine(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .3f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(SeekRed))
    }
}

/**
 * Sound & booster, over the video (dark): this phone's volume, the booster and the style. The same
 * panel in the Video player and in a Watch Party (where each phone sets its own).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SoundPanel(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    remember { VideoSound.init(context) }
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val maxVol = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }
    LaunchedEffect(Unit) {
        // The volume keys change it too: follow them.
        while (true) {
            delay(800)
            volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
        }
    }
    val sliderColors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = .25f))
    Column(
        modifier.background(PanelBg).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Sound & booster", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, "Close", tint = Color.White)
            }
        }
        Label("My volume", "${volume.toInt()} / $maxVol")
        StepSlider(
            value = volume, range = 0f..maxVol.toFloat(), step = 1f, steps = (maxVol - 1).coerceAtLeast(0), label = "volume",
            onChange = {
                volume = it
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, it.roundToInt(), 0)
            },
            colors = sliderColors,
        )
        Label("Volume boost", "${VideoSound.boost}%")
        StepSlider(
            value = VideoSound.boost.toFloat(), range = 100f..VideoSound.MAX_BOOST.toFloat(), step = 10f, label = "boost",
            onChange = { VideoSound.changeBoost(context, ((it / 5f).roundToInt() * 5)) },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = boostColor(VideoSound.boost), inactiveTrackColor = Color.White.copy(alpha = .25f)),
        )
        if (VideoSound.boost > 150) {
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = 2.dp)) {
                Icon(Icons.Rounded.WarningAmber, null, tint = Color(0xFFFCD34D), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Loud. Long listening at high boost can hurt your ears and your phone's speaker.", color = Color(0xFFFCD34D), fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
        Text("Sound style", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 16.dp, bottom = 10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SoundStyle.entries.forEach { s ->
                if (s == SoundStyle.NIGHT && Build.VERSION.SDK_INT < 28) return@forEach
                val on = s == VideoSound.style
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(34.dp).clip(RoundedCornerShape(17.dp))
                        .then(if (on) Modifier.background(Rose) else Modifier.background(Color.White.copy(alpha = .08f)).border(1.dp, Color.White.copy(alpha = .12f), RoundedCornerShape(17.dp)))
                        .clickable { VideoSound.changeStyle(context, s) }.padding(horizontal = 14.dp),
                ) {
                    Text(s.label, color = Color.White, fontSize = 13.sp)
                    if (s == SoundStyle.SURROUND) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Rounded.Headphones, "headphones", tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
        Text(
            when (VideoSound.style) {
                SoundStyle.NORMAL -> "The video as it is."
                SoundStyle.VOICE -> "Talking stands out from music and noise."
                SoundStyle.BASS -> "More low end — best on headphones or a speaker."
                SoundStyle.NIGHT -> "Quiet talk up, loud bangs down."
                SoundStyle.SURROUND -> "A wider sound — on headphones."
            },
            color = Color(0xFF94A3B8), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text("Remembered for all videos", color = Color(0xFF94A3B8), fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(
                "Reset", color = Color(0xFFFDA4AF), fontSize = 13.5.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { VideoSound.reset(context) }.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun Label(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp)) {
        Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(value, color = Color(0xFFFDA4AF), fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
    }
}
