package com.agani.syncup.watchparty

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighlightOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Theaters
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.agani.syncup.BuildConfig
import com.agani.syncup.browser.ui.StepSlider
import com.agani.syncup.downloads.Downloads
import com.agani.syncup.music.SectionHead
import com.agani.syncup.music.ToolTile
import com.agani.syncup.music.ToolTint
import com.agani.syncup.music.cardSlice
import com.agani.syncup.video.SeekLine
import com.agani.syncup.video.SoundEffects
import com.agani.syncup.video.SoundPanel
import com.agani.syncup.video.VideoSeekBar
import com.agani.syncup.video.VideoSound
import com.agani.syncup.video.playerScheme
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private val Rose = Color(0xFFE11D48)
private val RoseDark = Color(0xFFBE123C)

/**
 * The Watch Party player: this phone's copy of the party's video, kept at the party's moment (it
 * nudges its speed a touch, or jumps when far off). The host controls the video for everyone;
 * guests watch, react and can ask to pause. Turn sideways for full screen; upright shows the party
 * panel under the video. Leaving the screen doesn't leave the party.
 */
@OptIn(UnstableApi::class)
class WatchPartyActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private var effects: SoundEffects? = null
    private var inPip by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchParty.init(this)
        VideoSound.init(this)
        if (!WatchParty.active) {
            finish()
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        val session = getSystemService(AudioManager::class.java).generateAudioSessionId()
        exo.setAudioSessionId(session)
        val fx = SoundEffects(session)
        effects = fx
        WatchParty.playerOpen = true
        setContent {
            MaterialTheme(colorScheme = playerScheme(Rose)) {
                LaunchedEffect(VideoSound.boost, VideoSound.style) { fx.apply(VideoSound.boost, VideoSound.style) }
                WatchScreen(exo, inPip, visible = { lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }, onBack = { finish() }, onPip = { enterPip() })
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true) enterPip()
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        val size = player?.videoSize
        val ratio = if (size != null && size.width > 0 && size.height > 0) {
            Rational(size.width, size.height).let {
                when {
                    it.toFloat() > 2.39f -> Rational(239, 100)
                    it.toFloat() < 0.42f -> Rational(42, 100)
                    else -> it
                }
            }
        } else Rational(16, 9)
        runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(ratio).build()) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    override fun onStop() {
        super.onStop()
        if (!(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode)) player?.pause()
    }

    override fun onDestroy() {
        if (player != null) WatchParty.playerOpen = false
        effects?.release()
        effects = null
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, WatchPartyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }
}

/**
 * The video as this phone plays it: the host's own file, a link, or (guests) the pieces arriving —
 * with [subtitles] this person picked (a .srt / .vtt on their phone; only they see it).
 */
@OptIn(UnstableApi::class)
private fun mediaSource(context: Context, v: WatchVideo, subtitles: Uri?): MediaSource? {
    val subs = listOfNotNull(subtitles?.let {
        MediaItem.SubtitleConfiguration.Builder(it)
            .setMimeType(if (it.toString().endsWith(".vtt", true)) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)
            .setLanguage("und").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
    })
    return when {
        v.isLink -> {
            val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setUserAgent(Downloads.defaultUserAgent.ifBlank { null })
            val item = MediaItem.Builder().setUri(v.link).setSubtitleConfigurations(subs).apply {
                if (v.link.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
            }.build()
            DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)).createMediaSource(item)
        }
        WatchParty.isHost -> WatchParty.localUri(v.id)?.let {
            DefaultMediaSourceFactory(context).createMediaSource(MediaItem.Builder().setUri(it).setSubtitleConfigurations(subs).build())
        }
        else -> WatchParty.dataFor(v.id)?.let {
            val factory = RoutingDataSource.Factory(PieceDataSource.Factory(it), DefaultDataSource.Factory(context))
            DefaultMediaSourceFactory(factory).createMediaSource(
                MediaItem.Builder().setUri("syncup-watch://video/${v.id}").setSubtitleConfigurations(subs).build(),
            )
        }
    }
}

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun WatchScreen(exo: ExoPlayer, inPip: Boolean, visible: () -> Boolean, onBack: () -> Unit, onPip: () -> Unit) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffering by remember { mutableStateOf(false) }
    var myReady by remember { mutableStateOf(0) }
    var showControls by remember { mutableStateOf(true) }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var zoom by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    var soundOpen by remember { mutableStateOf(false) }
    var adjustOpen by remember { mutableStateOf(false) }
    // The group: its people / Switch, from the chip at the top.
    val groupSheets = com.agani.syncup.group.rememberGroupSheets()
    var upNextOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    var landscapeSet by remember { mutableStateOf(false) }
    // Just for this person: full screen upright (a tall video), touch lock, ⋮, subtitles / audio track.
    var portraitFull by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    // A subtitles file this person picked, for one video (video id → file).
    var subtitles by remember { mutableStateOf<Pair<Int, Uri>?>(null) }
    var hint by remember { mutableStateOf<Pair<ImageVector, String>?>(null) }
    var hintAt by remember { mutableLongStateOf(0L) }
    fun show(icon: ImageVector, text: String) {
        hint = icon to text
        hintAt = System.currentTimeMillis()
    }
    LaunchedEffect(hintAt) {
        if (hint != null) {
            delay(900)
            hint = null
        }
    }
    DisposableEffect(exo) {
        val l = object : Player.Listener {
            override fun onTracksChanged(t: Tracks) { tracks = t }
        }
        exo.addListener(l)
        onDispose { exo.removeListener(l) }
    }
    val subtitleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        val v = WatchParty.current
        if (picked != null && v != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(picked, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            subtitles = v.id to picked
            show(Icons.Rounded.ClosedCaption, "Subtitles on — just for you")
        }
    }
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val maxVol = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol) }
    var brightness by remember {
        mutableFloatStateOf(
            activity.window.attributes.screenBrightness.takeIf { it >= 0 }
                ?: (runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128) / 255f),
        )
    }

    // ---------------------------------------------------------------- keeping this phone at the party's moment
    LaunchedEffect(exo) {
        var loadedId = -1
        var loadedSubs: Uri? = null
        var prepKey = ""
        var lastSeekAt = 0L
        var lastLogAt = 0L
        // How far ahead to jump when catching up: learned from how late the last jump landed
        // (a link or a slow phone takes seconds to seek; a file on a fast phone, a moment).
        var seekLead = 300L
        var measuring = false
        while (true) {
            delay(150)
            if (!WatchParty.active) break
            val v = WatchParty.current
            buffering = exo.playbackState == Player.STATE_BUFFERING
            if (v == null) {
                if (exo.mediaItemCount > 0) exo.stop()
                loadedId = -1
                continue
            }
            val subs = subtitles?.takeIf { it.first == v.id }?.second
            if (v.id != loadedId || subs != loadedSubs) {
                val src = mediaSource(context, v, subs) ?: continue
                exo.setMediaSource(src)
                exo.prepare()
                exo.pause()
                loadedId = v.id
                loadedSubs = subs
                prepKey = ""
            }
            if (exo.duration > 0) {
                duration = exo.duration
                if (v.durationMs <= 0) WatchParty.reportDuration(v.id, exo.duration)
            } else if (v.durationMs > 0) {
                duration = v.durationMs
            }
            position = if (WatchParty.stage == WpStage.PLAYING) exo.currentPosition else WatchParty.posMs
            if (!visible() && !inPip) {
                if (exo.isPlaying) exo.pause()
                measuring = false
                continue
            }
            // A catch-up jump is only measured while simply playing on.
            if (WatchParty.stage != WpStage.PLAYING) {
                measuring = false
                val now = SystemClock.elapsedRealtime()
                if (BuildConfig.DEBUG && now - lastLogAt > 2_000) {
                    lastLogAt = now
                    android.util.Log.d("WatchParty", "wait stage=${WatchParty.stage} state=${exo.playbackState} posMs=${exo.currentPosition} durMs=${exo.duration} partyPosMs=${WatchParty.posMs}")
                }
            }
            when (WatchParty.stage) {
                WpStage.PREPARING -> {
                    val key = "${v.id}:${WatchParty.posMs}"
                    if (key != prepKey) {
                        exo.pause()
                        exo.setPlaybackSpeed(1f)
                        exo.seekTo(WatchParty.posMs)
                        prepKey = key
                    }
                    val ahead = exo.bufferedPosition - WatchParty.posMs
                    val nearEnd = exo.duration > 0 && WatchParty.posMs + WatchParty.READY_MS >= exo.duration
                    myReady = if (exo.playbackState == Player.STATE_READY && (ahead >= WatchParty.READY_MS || nearEnd)) 100
                    else (ahead * 100 / WatchParty.READY_MS).toInt().coerceIn(0, 95)
                    WatchParty.reportReady(myReady)
                    WatchParty.reportSync(null)
                }
                WpStage.PAUSED -> {
                    prepKey = ""
                    if (exo.isPlaying) exo.pause()
                    exo.setPlaybackSpeed(1f)
                    // Paused at the very end: hold just before it, so the last picture shows (an ended player is black).
                    val hold = if (exo.duration > 0) WatchParty.posMs.coerceAtMost(exo.duration - 250).coerceAtLeast(0) else WatchParty.posMs
                    val pastLastPicture = exo.duration > 0 && exo.currentPosition > exo.duration - 200
                    if (exo.playbackState == Player.STATE_ENDED || pastLastPicture || abs(exo.currentPosition - hold) > 500) exo.seekTo(hold)
                    WatchParty.reportSync(null)
                }
                WpStage.PLAYING -> {
                    prepKey = ""
                    if (!WatchParty.clockReady) {
                        if (exo.isPlaying) exo.pause()
                        measuring = false
                        continue
                    }
                    val target = WatchParty.targetPositionMs()
                    if (v.durationMs > 0 && target > v.durationMs + 300) {
                        if (exo.isPlaying) exo.pause()
                        continue
                    }
                    if (!exo.playWhenReady) {
                        // Waiting (paused) at the start point or after a catch-up jump: go on the dot.
                        if (exo.playbackState != Player.STATE_READY) continue
                        val wait = exo.currentPosition - target
                        if (measuring) {
                            measuring = false
                            seekLead = when {
                                wait < 0 -> (seekLead - wait + 200).coerceAtMost(10_000)
                                wait > 1_500 -> (seekLead - wait + 500).coerceAtLeast(300)
                                else -> seekLead
                            }
                        }
                        when {
                            wait in 1..1_500 -> {
                                delay(wait)
                                exo.play()
                                continue
                            }
                            wait > 1_500 -> continue
                            else -> exo.play()
                        }
                    }
                    if (exo.playbackState != Player.STATE_READY) continue
                    val err = exo.currentPosition - target
                    val now = SystemClock.elapsedRealtime()
                    when {
                        abs(err) > 700 && now - lastSeekAt > 2_000 -> {
                            // Jump ahead of the party, wait there paused, start on the dot.
                            exo.pause()
                            exo.setPlaybackSpeed(1f)
                            exo.seekTo((target + seekLead).coerceAtLeast(0))
                            lastSeekAt = now
                            measuring = true
                            continue
                        }
                        abs(err) > 40 -> exo.setPlaybackSpeed((1f - err / 2000f).coerceIn(0.95f, 1.05f))
                        else -> exo.setPlaybackSpeed(1f)
                    }
                    WatchParty.reportSync(err.toDouble())
                    if (BuildConfig.DEBUG && now - lastLogAt > 2_000) {
                        // For checking phones against each other: which video moment plays at which wall-clock time.
                        lastLogAt = now
                        android.util.Log.d("WatchParty", "sync posMs=${exo.currentPosition} wallMs=${System.currentTimeMillis()} errMs=$err speed=${exo.playbackParameters.speed} lead=$seekLead")
                    }
                }
            }
        }
    }
    // The group ended / this phone left / the group switched to something else: close.
    LaunchedEffect(WatchParty.active) {
        if (!WatchParty.active) onBack()
    }
    LaunchedEffect(WatchParty.notice) {
        WatchParty.notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            WatchParty.notice = null
        }
    }
    // A wide video turns the screen sideways by itself (once).
    LaunchedEffect(exo) {
        while (!landscapeSet) {
            val s = exo.videoSize
            if (s.width > 0 && s.height > 0) {
                landscapeSet = true
                activity.requestedOrientation = if (s.width > s.height) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
            delay(300)
        }
    }
    LaunchedEffect(showControls, lastTouch, soundOpen, WatchParty.stage, menu, dialog) {
        if (showControls && WatchParty.stage == WpStage.PLAYING && !soundOpen && !menu && dialog == null) {
            delay(4_000)
            showControls = false
        }
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val host = WatchParty.isHost
    val shownPos = seeking?.let { (it * duration).toLong() } ?: position
    // Full screen: sideways, or (a tall video) upright with no panel.
    val full = landscape || portraitFull

    /** Full screen on: a wide video turns sideways; a tall one fills the screen upright. */
    fun enterFull() {
        landscapeSet = true
        val s = exo.videoSize
        if (s.width > 0 && s.height > s.width) portraitFull = true
        else activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        lastTouch = System.currentTimeMillis()
    }

    /** Back to upright with the party panel. */
    fun exitFull() {
        landscapeSet = true
        portraitFull = false
        locked = false
        if (landscape) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }

    /** Turn the screen the other way (like the Video player's Rotate). */
    fun rotate() {
        landscapeSet = true
        portraitFull = false
        activity.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
    // Back: out of full screen first (then out of the player).
    BackHandler(enabled = full && !inPip) { exitFull() }

    /** ⋮ — just for this phone. */
    @Composable
    fun MoreMenu() {
        Box {
            RoundIcon(Icons.Rounded.MoreVert, "More", 24) { menu = true; lastTouch = System.currentTimeMillis() }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Subtitles") }, leadingIcon = { Icon(Icons.Rounded.Subtitles, null) }, onClick = { menu = false; dialog = "subs" })
                DropdownMenuItem(text = { Text("Open subtitles file (.srt)") }, leadingIcon = { Icon(Icons.Rounded.ClosedCaption, null) }, onClick = {
                    menu = false
                    subtitleLauncher.launch(arrayOf("application/x-subrip", "text/vtt", "text/plain", "application/octet-stream", "*/*"))
                })
                DropdownMenuItem(text = { Text("Audio track") }, leadingIcon = { Icon(Icons.Rounded.GraphicEq, null) }, onClick = { menu = false; dialog = "audio" })
                DropdownMenuItem(text = { Text(if (zoom) "Fit to screen" else "Fill screen") }, leadingIcon = { Icon(Icons.Rounded.AspectRatio, null) }, onClick = { menu = false; zoom = !zoom })
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Sound & booster · ${VideoSound.boost}%") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.VolumeUp, null) },
                    onClick = { menu = false; soundOpen = true },
                )
                if (full) {
                    DropdownMenuItem(text = { Text("Lock the screen") }, leadingIcon = { Icon(Icons.Rounded.Lock, null) }, onClick = { menu = false; locked = true; showControls = false })
                    DropdownMenuItem(text = { Text("Exit full screen") }, leadingIcon = { Icon(Icons.Rounded.FullscreenExit, null) }, onClick = { menu = false; exitFull() })
                } else {
                    DropdownMenuItem(text = { Text("Full screen") }, leadingIcon = { Icon(Icons.Rounded.Fullscreen, null) }, onClick = { menu = false; enterFull() })
                }
            }
        }
    }

    @Composable
    fun Video(modifier: Modifier) {
        Box(modifier.background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        player = exo
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        setKeepContentOnPlayerReset(true)
                    }
                },
                update = { it.resizeMode = if (zoom) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT },
                modifier = Modifier.fillMaxSize(),
            )
            if (inPip) return@Box
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(host, locked) {
                        detectTapGestures(
                            onTap = { lastTouch = System.currentTimeMillis(); showControls = !showControls },
                            onDoubleTap = { o ->
                                // The host moves everyone ±10 s.
                                if (!host || locked) return@detectTapGestures
                                val back = o.x < size.width / 2
                                WatchParty.seekTo((WatchParty.positionMs() + if (back) -10_000 else 10_000).coerceAtLeast(0))
                                show(if (back) Icons.Rounded.Replay10 else Icons.Rounded.Forward10, if (back) "−10 s for everyone" else "+10 s for everyone")
                            },
                        )
                    }
                    .pointerInput(locked) {
                        if (locked) return@pointerInput
                        var scale = 1f
                        // The right-side slide: 0..1 = volume, 1..2 = boost, kept unrounded while sliding.
                        var level = -1f
                        var levelAt = 0L
                        detectTransformGestures { centroid, pan, gestureZoom, _ ->
                            if (gestureZoom != 1f) {
                                scale *= gestureZoom
                                if (scale > 1.15f && !zoom) { zoom = true; scale = 1f; show(Icons.Rounded.AspectRatio, "Fill screen") }
                                if (scale < 0.87f && zoom) { zoom = false; scale = 1f; show(Icons.Rounded.AspectRatio, "Fit to screen") }
                            } else if (abs(pan.y) > abs(pan.x)) {
                                val delta = -pan.y / size.height * 1.5f
                                if (centroid.x < size.width / 2) {
                                    brightness = (brightness + delta).coerceIn(0.01f, 1f)
                                    activity.window.attributes = activity.window.attributes.apply { screenBrightness = brightness }
                                    show(Icons.Rounded.BrightnessMedium, "Brightness ${(brightness * 100).toInt()}%")
                                } else {
                                    // 0..1 = the phone's volume, 1..2 = the booster on top (100–200%). Just this phone.
                                    val now = System.currentTimeMillis()
                                    if (level < 0f || now - levelAt > 400) {
                                        level = if (VideoSound.boost > 100) 1f + (VideoSound.boost - 100) / 100f else volume
                                    }
                                    levelAt = now
                                    level = (level + delta).coerceIn(0f, 2f)
                                    volume = level.coerceAtMost(1f)
                                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, (volume * maxVol).toInt(), 0)
                                    VideoSound.changeBoost(context, 100 + (((level - 1f).coerceAtLeast(0f) * 100f) / 5f).roundToInt() * 5)
                                    if (VideoSound.boost > 100) show(Icons.AutoMirrored.Rounded.VolumeUp, "Boost ${VideoSound.boost}%")
                                    else show(Icons.AutoMirrored.Rounded.VolumeUp, "Volume ${(volume * 100).toInt()}%")
                                }
                            }
                        }
                    },
            )
            hint?.let { (icon, text) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = if (full) 72.dp else 40.dp).clip(RoundedCornerShape(14.dp))
                        .background(Color.Black.copy(alpha = .6f)).padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(text, color = Color.White, fontSize = 14.sp)
                }
            }
            if (buffering && WatchParty.stage == WpStage.PLAYING) CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center).size(44.dp))
            if (WatchParty.stage == WpStage.PREPARING) ReadyGate(myReady, Modifier.align(Alignment.Center))
            Reactions(Modifier.align(Alignment.BottomEnd).padding(end = 40.dp, bottom = 70.dp))
            WatchParty.askFrom?.takeIf { host }?.let { who ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = if (landscape) 64.dp else 12.dp).clip(RoundedCornerShape(14.dp))
                        .background(Color(0xF21E293B)).padding(start = 16.dp, end = 4.dp),
                ) {
                    Text("$who asked to pause", color = Color.White, fontSize = 13.5.sp)
                    TextButton(onClick = { WatchParty.togglePause(); WatchParty.dismissAsk() }) { Text("Pause", color = Color(0xFFFDA4AF)) }
                    TextButton(onClick = { WatchParty.dismissAsk() }) { Text("Ignore", color = Color(0xFFCBD5E1)) }
                }
            }
        }
    }

    if (inPip) {
        Video(Modifier.fillMaxSize())
        return
    }

    if (full) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Video(Modifier.fillMaxSize())
            // Locked: only the unlock button, shown on a tap.
            if (locked) {
                AnimatedVisibility(showControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterStart).padding(start = 24.dp)) {
                    Box(
                        Modifier.size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = .55f))
                            .clickable { locked = false; showControls = true; lastTouch = System.currentTimeMillis() },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.LockOpen, "Unlock", tint = Color.White, modifier = Modifier.size(26.dp)) }
                }
            }
            AnimatedVisibility(showControls && !locked, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f)).windowInsetsPadding(WindowInsets.safeDrawing)) {
                    TopBar(onBack = { exitFull() }, onGroup = { groupSheets.people = true }, Modifier.align(Alignment.TopStart), more = { MoreMenu() })
                    if (host) {
                        Row(horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.Center)) {
                            RoundIcon(Icons.Rounded.Replay10, "Back 10 seconds", 48) { WatchParty.seekTo((WatchParty.positionMs() - 10_000).coerceAtLeast(0)); lastTouch = System.currentTimeMillis() }
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(72.dp).clip(CircleShape).background(Color.White.copy(alpha = .18f)).clickable { WatchParty.togglePause(); lastTouch = System.currentTimeMillis() },
                            ) {
                                val playing = WatchParty.stage == WpStage.PLAYING
                                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(44.dp))
                            }
                            RoundIcon(Icons.Rounded.Forward10, "Forward 10 seconds", 48) { WatchParty.seekTo(WatchParty.positionMs() + 10_000); lastTouch = System.currentTimeMillis() }
                        }
                    }
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        SeekRow(host, shownPos, duration, onSeek = { seeking = it; lastTouch = System.currentTimeMillis() }, onSeekDone = {
                            seeking?.let { WatchParty.seekTo((it * duration).toLong()) }
                            seeking = null
                        })
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                            if (host) {
                                Chip(Icons.AutoMirrored.Rounded.PlaylistPlay, "Up next · ${WatchParty.upNext.size}") { upNextOpen = true }
                            } else {
                                Chip(Icons.Rounded.PanTool, "Ask to pause") { WatchParty.askToPause() }
                            }
                            Chip(Icons.AutoMirrored.Rounded.VolumeUp, if (VideoSound.boost > 100) "Sound · ${VideoSound.boost}%" else "Sound") { soundOpen = true }
                            if (!host) Chip(Icons.Rounded.Bluetooth, "Sync adjust") { adjustOpen = true }
                            ReactionBar()
                            Spacer(Modifier.weight(1f))
                            Chip(Icons.Rounded.Lock, null) { locked = true; showControls = false }
                            Chip(Icons.Rounded.Subtitles, null) { dialog = "subs" }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Chip(Icons.Rounded.PictureInPictureAlt, null, onClick = onPip)
                            Chip(Icons.Rounded.FullscreenExit, null) { exitFull() }
                            if (host) Chip(Icons.Rounded.HighlightOff, "End group", red = true) { confirmEnd = true }
                            else Chip(Icons.AutoMirrored.Rounded.Logout, "Leave") { WatchParty.leave() }
                        }
                    }
                }
            }
        }
    } else {
        // Upright: the video, then the party panel (dark, like the player).
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                Video(Modifier.fillMaxSize())
                androidx.compose.animation.AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .28f))) {
                        RoundIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", 24, Modifier.align(Alignment.TopStart).padding(4.dp), onClick = onBack)
                        Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) { MoreMenu() }
                        if (host && WatchParty.stage != WpStage.PREPARING) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.align(Alignment.Center).size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = .18f))
                                    .clickable { WatchParty.togglePause(); lastTouch = System.currentTimeMillis() },
                            ) {
                                val playing = WatchParty.stage == WpStage.PLAYING
                                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(34.dp))
                            }
                        }
                        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 4.dp)) {
                            Box(Modifier.weight(1f).padding(bottom = 4.dp)) {
                                SeekRow(host, shownPos, duration, onSeek = { seeking = it; lastTouch = System.currentTimeMillis() }, onSeekDone = {
                                    seeking?.let { WatchParty.seekTo((it * duration).toLong()) }
                                    seeking = null
                                })
                            }
                            RoundIcon(Icons.Rounded.Fullscreen, "Full screen", 26, Modifier.padding(bottom = 2.dp)) { enterFull() }
                        }
                    }
                }
            }
            val next = WatchParty.upNext
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 24.dp), modifier = Modifier.weight(1f)) {
                item(key = "title") {
                    Text(WatchParty.current?.title ?: "Nothing playing", color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    SyncLine(Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item(key = "group") { com.agani.syncup.group.GroupBar(Modifier.padding(top = 12.dp)) }
                item(key = "chips") {
                    // Wraps onto a second line on a narrow phone.
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        PanelChip(Icons.AutoMirrored.Rounded.VolumeUp, if (VideoSound.boost > 100) "Sound · ${VideoSound.boost}%" else "Sound") { soundOpen = true }
                        if (!host) {
                            PanelChip(Icons.Rounded.PanTool, "Ask to pause") { WatchParty.askToPause() }
                            PanelChip(Icons.Rounded.Bluetooth, "Sync adjust") { adjustOpen = true }
                        }
                        PanelChip(Icons.Rounded.Subtitles, "Subtitles") { dialog = "subs" }
                        PanelChip(Icons.Rounded.Fullscreen, "Full screen") { enterFull() }
                    }
                }
                item(key = "react") { ReactionBar(Modifier.padding(top = 12.dp), onVideo = false) }
                item(key = "next-head") {
                    SectionHead("Up next", action = if (host) ({ PanelChip(Icons.Rounded.Add, "Add videos") { addOpen = true } }) else null)
                }
                if (next.isEmpty()) item(key = "next-none") { Text(if (host) "Nothing queued. Add more videos from this phone." else "Nothing queued yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp)) }
                itemsIndexed(next, key = { _, v -> v.id }) { i, v ->
                    QueueRow(v, first = i == 0, last = i == next.lastIndex, onPlay = if (host) ({ WatchParty.playNow(v.id) }) else null, onRemove = if (host) ({ WatchParty.removeFromQueue(v.id) }) else null)
                }
                item(key = "hint") {
                    Text(
                        "Everyone sets their own sound — volume, boost and style on their own phone.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp),
                    )
                }
                item(key = "end") {
                    if (host) OutlinedButton(onClick = { confirmEnd = true }, modifier = Modifier.padding(top = 20.dp).fillMaxWidth().height(46.dp)) { Text("End the group", color = Color(0xFFFDA4AF)) }
                    else OutlinedButton(onClick = { WatchParty.leave() }, modifier = Modifier.padding(top = 20.dp).fillMaxWidth().height(46.dp)) { Text("Leave", color = MaterialTheme.colorScheme.onSurface) }
                }
            }
        }
    }

    if (soundOpen) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth > maxHeight
            Box(Modifier.fillMaxSize().clickable { soundOpen = false })
            SoundPanel(
                onClose = { soundOpen = false },
                modifier = if (wide) Modifier.align(Alignment.CenterEnd).width(360.dp).fillMaxHeight().windowInsetsPadding(WindowInsets.safeDrawing)
                else Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).windowInsetsPadding(WindowInsets.safeDrawing),
            )
        }
    }
    if (adjustOpen) {
        var adjust by remember { mutableFloatStateOf(WatchParty.syncAdjustMs.toFloat()) }
        AlertDialog(
            onDismissRequest = { adjustOpen = false },
            title = { Text("Sync adjust") },
            text = {
                Column {
                    Text("${if (adjust > 0) "+" else ""}${adjust.toInt()} ms", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    StepSlider(
                        value = adjust, range = -400f..400f, step = 10f, label = "sync adjust",
                        onChange = { adjust = (it / 10f).toInt() * 10f },
                        onChangeFinished = { WatchParty.setSyncAdjust(adjust.toInt()) },
                    )
                    Text("Bluetooth earbuds play a little late. If this phone sounds behind the others, tap +; ahead, tap −.", fontSize = 12.5.sp, lineHeight = 17.sp)
                }
            },
            confirmButton = { TextButton(onClick = { WatchParty.setSyncAdjust(adjust.toInt()); adjustOpen = false }) { Text("Done") } },
        )
    }
    if (upNextOpen) {
        ModalBottomSheet(onDismissRequest = { upNextOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Up next", fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Button(onClick = { upNextOpen = false; addOpen = true }, colors = ButtonDefaults.buttonColors(containerColor = Rose, contentColor = Color.White)) { Text("Add videos") }
                }
                val next = WatchParty.upNext
                if (next.isEmpty()) Text("Nothing queued. When this video ends, the party pauses.", fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                else Text("Tap a video to play it now.", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
                Spacer(Modifier.height(6.dp))
                next.forEachIndexed { i, v ->
                    QueueRow(v, first = i == 0, last = i == next.lastIndex, onPlay = { upNextOpen = false; WatchParty.playNow(v.id) }) { WatchParty.removeFromQueue(v.id) }
                }
            }
        }
    }
    if (addOpen) {
        VideoPickerSheet(title = "Add to Up next", onPick = { WatchParty.addVideo(it) }, onDismiss = { addOpen = false })
    }
    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End the group?") },
            text = { Text("The video stops on every phone, and everyone leaves the group.") },
            confirmButton = { TextButton(onClick = { confirmEnd = false; WatchParty.end() }) { Text("End group", color = Color(0xFFFDA4AF)) } },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep watching") } },
        )
    }
    if (dialog == "subs" || dialog == "audio") {
        val type = if (dialog == "subs") C.TRACK_TYPE_TEXT else C.TRACK_TYPE_AUDIO
        val options = tracks.groups.filter { it.type == type }.flatMap { g -> (0 until g.length).map { i -> g to i } }
        val names = options.map { (g, i) ->
            val f = g.getTrackFormat(i)
            listOfNotNull(f.label, f.language?.takeIf { it != "und" }?.let { java.util.Locale(it).displayLanguage }).distinct().joinToString(" · ").ifBlank { "Track ${i + 1}" }
        }
        val text = type == C.TRACK_TYPE_TEXT
        val disabled = exo.trackSelectionParameters.disabledTrackTypes.contains(type)
        val current = if (text && disabled) 0 else options.indexOfFirst { (g, i) -> g.isTrackSelected(i) }.let { if (text) it + 1 else it }
        if (options.isEmpty()) {
            AlertDialog(
                onDismissRequest = { dialog = null },
                confirmButton = {
                    if (text) TextButton(onClick = {
                        dialog = null
                        subtitleLauncher.launch(arrayOf("application/x-subrip", "text/vtt", "text/plain", "application/octet-stream", "*/*"))
                    }) { Text("Open a .srt file") }
                    else TextButton(onClick = { dialog = null }) { Text("OK") }
                },
                dismissButton = if (text) ({ TextButton(onClick = { dialog = null }) { Text("Close") } }) else null,
                title = { Text(if (text) "No subtitles" else "One audio track") },
                text = { Text(if (text) "This video has no subtitles. Open a subtitles file (.srt) from your phone — only you see it." else "This video has only one audio track.") },
            )
        } else {
            ChoiceDialog(
                if (text) "Subtitles · just for you" else "Audio track · just for you",
                (if (text) listOf("Off") else emptyList()) + names, current,
                onPick = { pick ->
                    val b = exo.trackSelectionParameters.buildUpon()
                    if (text && pick == 0) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    else {
                        val (g, i) = options[if (text) pick - 1 else pick]
                        b.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                    }
                    exo.trackSelectionParameters = b.build()
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        }
    }
    com.agani.syncup.group.GroupJoinRequestDialog()
    com.agani.syncup.group.GroupSheets(groupSheets)
}

@Composable
private fun ChoiceDialog(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { i, o ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(i) }.padding(vertical = 2.dp),
                    ) {
                        RadioButton(selected = i == selected, onClick = { onPick(i) })
                        Text(o, fontSize = 15.sp)
                    }
                }
            }
        },
    )
}

// ============================================================================ pieces
@Composable
private fun TopBar(onBack: () -> Unit, onGroup: () -> Unit, modifier: Modifier, more: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
        RoundIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", 26, onClick = onBack)
        Column(Modifier.weight(1f)) {
            Text(WatchParty.current?.title ?: WatchParty.roomName, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            SyncLine()
        }
        Avatars()
        Spacer(Modifier.width(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clip(RoundedCornerShape(15.dp)).background(Rose.copy(alpha = .9f)).clickable(onClick = onGroup).padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(Icons.Rounded.Theaters, null, tint = Color.White, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                (if (WatchParty.isHost) "Group · Switch" else "Group") + " · ${WatchParty.people.size}",
                color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.width(4.dp))
        more()
        Spacer(Modifier.width(4.dp))
    }
}

@Composable
private fun SyncLine(modifier: Modifier = Modifier, color: Color = Color.White.copy(alpha = .85f)) {
    val err = WatchParty.syncErrorMs
    val others = (WatchParty.people.size - 1).coerceAtLeast(0)
    val (dot, text) = when {
        WatchParty.phase == WpPhase.RECONNECTING -> Color(0xFFFCD34D) to "Reconnecting… playing on from what's here"
        WatchParty.current == null -> Color(0xFF94A3B8) to if (WatchParty.isHost) "Add a video to start" else "Waiting for ${WatchParty.hostName}"
        WatchParty.stage == WpStage.PREPARING -> Color(0xFFFCD34D) to "Getting everyone ready…"
        WatchParty.stage == WpStage.PAUSED -> Color(0xFF94A3B8) to "Paused"
        WatchParty.isHost -> Color(0xFF86EFAC) to if (others == 0) "Waiting for friends to join" else "Playing on ${others + 1} phones · in sync"
        err == null -> Color(0xFFFCD34D) to "Getting in sync…"
        abs(err) <= 60 -> Color(0xFF86EFAC) to "${WatchParty.hostName} is playing · in sync"
        else -> Color(0xFFFCD34D) to "Lining up… (${err.toInt()} ms)"
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(7.dp))
        Text(text, color = color, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Avatars() {
    Row {
        WatchParty.people.take(4).forEachIndexed { i, n ->
            Box(
                Modifier.offset(x = (-8 * i).dp).size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = .5f)).padding(2.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = .22f)),
                contentAlignment = Alignment.Center,
            ) { Text(n.take(1).uppercase(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
        }
    }
}

@Composable
private fun PeopleLine() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatars()
        Text(WatchParty.people.joinToString(", "), color = Color(0xFFCBD5E1), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun SeekRow(host: Boolean, pos: Long, duration: Long, onSeek: (Float) -> Unit, onSeekDone: () -> Unit) {
    val fraction = if (duration > 0) pos.toFloat() / duration else 0f
    Column {
        // The host moves everyone; a guest just sees where the video is.
        if (host) VideoSeekBar(fraction, onSeek = onSeek, onSeekDone = onSeekDone)
        else SeekLine(fraction, Modifier.padding(vertical = 14.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(fmt(pos), color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(if (duration > 0) fmt(duration) else "", color = Color.White, fontSize = 12.sp)
        }
    }
}

/** Host: who's ready; guest: its own readiness — before a start / seek / next video. */
@Composable
private fun ReadyGate(myReady: Int, modifier: Modifier) {
    Column(modifier.widthIn(max = 380.dp).padding(16.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xEB0F172A)).padding(horizontal = 20.dp, vertical = 16.dp)) {
        if (WatchParty.isHost) {
            val all = WatchParty.readiness
            val ready = all.values.count { it >= 100 }
            Text("Getting everyone ready… $ready of ${all.size}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text("The video starts on every phone together once each has a few seconds in hand.", color = Color.White.copy(alpha = .75f), fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
            all.entries.sortedBy { it.key }.forEach { (name, pct) -> ReadyRow(name, pct) }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = { WatchParty.startNow() }) { Text("Start anyway", color = Color.White) }
            }
        } else {
            Text("Getting ready…", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            ReadyRow("This phone", myReady)
            Text("Starts when ${WatchParty.hostName} starts it — on every phone together.", color = Color.White.copy(alpha = .75f), fontSize = 12.5.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun ReadyRow(name: String, pct: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        Text(name, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(96.dp))
        Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = .18f))) {
            Box(Modifier.fillMaxWidth(pct / 100f).height(6.dp).background(if (pct >= 100) Color(0xFF86EFAC) else Color(0xFFFCD34D)))
        }
        Text(if (pct >= 100) "Ready" else "$pct%", color = Color.White.copy(alpha = .85f), fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.width(52.dp))
    }
}

/** The reactions: one pill — a card in the panel, see-through over the video. */
@Composable
private fun ReactionBar(modifier: Modifier = Modifier, onVideo: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier.clip(shape)
            .then(if (onVideo) Modifier.background(Color.White.copy(alpha = .14f)) else Modifier.background(cs.surfaceContainerLowest).border(1.dp, cs.outlineVariant, shape))
            .padding(horizontal = if (onVideo) 4.dp else 6.dp, vertical = if (onVideo) 0.dp else 2.dp),
    ) {
        WatchParty.EMOJI.forEach { e ->
            Box(
                Modifier.size(width = if (onVideo) 36.dp else 40.dp, height = if (onVideo) 36.dp else 38.dp).clip(CircleShape).clickable { WatchParty.react(e) },
                contentAlignment = Alignment.Center,
            ) { Text(e, fontSize = if (onVideo) 18.sp else 19.sp) }
        }
    }
}

/** Reactions floating up and fading on every screen. */
@Composable
private fun Reactions(modifier: Modifier) {
    Box(modifier) {
        WatchParty.reactions.forEach { r ->
            key(r.key) {
                val rise = remember { Animatable(0f) }
                LaunchedEffect(Unit) { rise.animateTo(1f, tween(2_400, easing = LinearEasing)) }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.offset(x = (-(r.key % 4).toInt() * 34).dp, y = (-170 * rise.value).dp).alpha(1f - rise.value * rise.value),
                ) {
                    Text(r.emoji, fontSize = 28.sp)
                    Text(r.from, color = Color.White, fontSize = 10.sp)
                }
            }
        }
    }
}

/** A video in Up next — one slice of the queue's card; tap plays it now (host). */
@Composable
private fun QueueRow(v: WatchVideo, first: Boolean, last: Boolean, onPlay: (() -> Unit)? = null, onRemove: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().cardSlice(first, last).then(if (onPlay != null) Modifier.clickable(onClick = onPlay) else Modifier)
            .heightIn(min = 64.dp).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    ) {
        ToolTile(Icons.Rounded.PlayArrow, ToolTint.ROSE, size = 46.dp, iconSize = 22.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(v.title, color = cs.onSurface, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(v.durationMs.takeIf { it > 0 }?.let { fmt(it) }, if (v.isLink) "video link" else "from ${v.addedBy}'s phone").joinToString(" · "),
                color = cs.onSurfaceVariant, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (onRemove != null) RoundIcon(Icons.Rounded.Close, "Remove", 20, tint = cs.onSurfaceVariant, onClick = onRemove)
    }
}

/** A chip in the panel under the video: an outlined dark pill with an icon. */
@Composable
private fun PanelChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(36.dp).clip(shape).background(cs.surfaceContainerLowest).border(1.dp, cs.outlineVariant, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
    ) {
        Icon(icon, null, tint = cs.onSurface, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = cs.onSurface, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun Chip(icon: ImageVector, label: String?, red: Boolean = false, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(36.dp).clip(RoundedCornerShape(18.dp))
            .then(if (red) Modifier.background(RoseDark) else Modifier.background(Color.White.copy(alpha = .14f)))
            .clickable(onClick = onClick).padding(horizontal = if (label == null) 9.dp else 12.dp),
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(18.dp))
        if (label != null) {
            Spacer(Modifier.width(6.dp))
            Text(label, color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, desc: String, size: Int, modifier: Modifier = Modifier, tint: Color = Color.White, onClick: () -> Unit) {
    Box(modifier.size((size + 22).dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(size.dp))
    }
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
