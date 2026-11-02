package com.agani.syncup.music

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.os.SystemClock
import android.view.KeyEvent
import android.view.Window
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import com.agani.syncup.video.SoundLevel
import com.agani.syncup.video.VideoSound
import com.agani.syncup.video.VolumeBar
import com.agani.syncup.video.boostGain
import kotlinx.coroutines.delay

/**
 * The Music player's volume boost on top of the phone's volume, up to 300%, for songs and stations alike. No
 * buttons: on the Music pages the volume keys go on past full volume into it, shown on the same side bar as the
 * Video player's. Remembered; [MusicPlaybackService] puts it on the player's sound.
 */
object MusicBoost {
    private const val PREFS = "music_sound"

    var boost by mutableIntStateOf(100)
        private set
    /** When a volume key was last pressed on a Music page (uptime): the side bar shows. */
    var keyAt by mutableLongStateOf(0L)
        private set
    /** The playback service, told when it changes. */
    internal var onChange: (() -> Unit)? = null
    /** How many Music pages (the list, Radio, Now playing) are on screen: the volume keys are ours only then. */
    internal var pages = 0
    private var ready = false

    fun init(context: Context) {
        if (ready) return
        ready = true
        boost = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("boost", 100).coerceIn(100, VideoSound.MAX_BOOST)
    }

    fun change(context: Context, value: Int) {
        boost = value.coerceIn(100, VideoSound.MAX_BOOST)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("boost", boost).apply()
        onChange?.invoke()
    }

    /** A volume key while a Music page is on screen: the phone's volume, then the boost. False = not ours. */
    fun onVolumeKey(context: Context, event: KeyEvent): Boolean {
        val up = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        if (pages <= 0 || !up && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            init(context)
            SoundLevel.key(context, up, boost) { change(context, it) }
            keyAt = SystemClock.uptimeMillis()
        }
        return true
    }
}

/**
 * A Music page on screen: the volume keys show [MusicVolumeBar]. Inside a sheet (its own window, which the
 * Activity doesn't see keys from) the sheet's window passes them on too.
 */
@Composable
internal fun MusicVolumeKeys() {
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(view) {
        MusicBoost.pages++
        val window = (view.parent as? DialogWindowProvider)?.window
        val original = window?.callback
        if (window != null && original != null) {
            window.callback = object : Window.Callback by original {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                    MusicBoost.onVolumeKey(context, event) || original.dispatchKeyEvent(event)
            }
        }
        onDispose {
            MusicBoost.pages--
            if (window != null && original != null) window.callback = original
        }
    }
}

/** The right-side volume bar after a volume key on a Music page: the phone's volume, then the boost to 300%. */
@Composable
internal fun MusicVolumeBar(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    var visible by remember { mutableStateOf(false) }
    var volume by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(MusicBoost.keyAt) {
        if (SystemClock.uptimeMillis() - MusicBoost.keyAt > 1_000) return@LaunchedEffect
        volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        visible = true
        delay(1_200)
        visible = false
    }
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        VolumeBar(volume, MusicBoost.boost)
    }
}

/** [MusicBoost] on one player's sound (its audio session), with the effect's own limiter. A phone without it plays as is. */
internal class BoostEffect {
    private var loudness: LoudnessEnhancer? = null
    private var session = 0

    fun apply(session: Int, boost: Int) {
        if (session != this.session) {
            release()
            this.session = session
        }
        if (session == 0) return
        runCatching {
            val l = loudness ?: LoudnessEnhancer(session).also { loudness = it }
            l.setTargetGain(boostGain(boost))
            l.enabled = boost > 100
        }
    }

    fun release() {
        runCatching { loudness?.release() }
        loudness = null
        session = 0
    }
}
