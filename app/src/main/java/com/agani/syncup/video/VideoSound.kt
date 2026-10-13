package com.agani.syncup.video

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.StepSlider
import kotlinx.coroutines.delay
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
 * How videos sound: the volume booster (100–200%) and a [SoundStyle]. Remembered for all videos and
 * used by every player (the Video player, Watch Party) — each phone sets its own.
 */
object VideoSound {
    private const val PREFS = "video_sound"
    const val MAX_BOOST = 200

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
        // Boost: 100% = as is, 200% = +10 dB (about twice as loud), with the effect's own limiter.
        runCatching {
            val l = loudness ?: LoudnessEnhancer(session).also { loudness = it }
            l.setTargetGain(((boost - 100) * 10).coerceIn(0, 1000))
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

private val PanelBg = Color(0xFF111827)
private val Rose = Color(0xFFE11D48)
private val Amber = Color(0xFFF59E0B)

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
        modifier.background(PanelBg).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
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
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = if (VideoSound.boost > 100) Amber else Color.White, inactiveTrackColor = Color.White.copy(alpha = .25f)),
        )
        if (VideoSound.boost > 150) {
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = 2.dp)) {
                Icon(Icons.Rounded.WarningAmber, null, tint = Color(0xFFFCD34D), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Loud. Long listening at high boost can hurt your ears and your phone's speaker.", color = Color(0xFFFCD34D), fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
        Text("Sound style", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SoundStyle.entries.forEach { s ->
                if (s == SoundStyle.NIGHT && Build.VERSION.SDK_INT < 28) return@forEach
                val on = s == VideoSound.style
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) Rose else Color.White.copy(alpha = .1f))
                        .clickable { VideoSound.changeStyle(context, s) }.padding(horizontal = 12.dp, vertical = 7.dp),
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
    Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(title, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(value, color = Color(0xFFFDA4AF), fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
    }
}
