package com.agani.syncup.video

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
import android.provider.Settings
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.agani.syncup.downloads.Downloads
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * The video player: a phone video, a SyncUp download or a video link (mp4, m3u8 …). Tap for the
 * controls; double-tap a side to skip 10 s; slide the left side for brightness and the right side for
 * volume; pinch to fill the screen. Speed, subtitles (the video's own or a .srt), audio track, lock,
 * rotate and picture-in-picture. Picks up where each video stopped.
 */
@OptIn(UnstableApi::class)
class VideoPlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var inPip by mutableStateOf(false)
    private var uri = ""
    private var title by mutableStateOf("")
    private var source = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        open(intent, exo)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFEF4444))) {
                PlayerScreen(exo, title, inPip, onBack = { finish() }, onPip = { enterPip() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        save()
        player?.let { open(intent, it) }
    }

    private fun open(intent: Intent, exo: ExoPlayer) {
        uri = intent.getStringExtra(EXTRA_URI).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: Uri.parse(uri).lastPathSegment.orEmpty()
        source = intent.getStringExtra(EXTRA_SOURCE).orEmpty()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(intent.getStringExtra(EXTRA_UA)?.takeIf { it.isNotBlank() } ?: Downloads.defaultUserAgent.ifBlank { null })
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(
                buildMap {
                    intent.getStringExtra(EXTRA_REFERER)?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
                    intent.getStringExtra(EXTRA_COOKIES)?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
                },
            )
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))
        val item = MediaItem.Builder().setUri(uri).apply {
            if (uri.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        exo.setMediaSource(factory.createMediaSource(item))
        exo.prepare()
        val resume = WatchHistory.positionOf(this, uri)
        if (resume > 0) exo.seekTo(resume)
        exo.playWhenReady = true
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Going home while a video plays: keep it going in a small window.
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
        save()
        // Leaving the screen (not into the small window): pause.
        if (!(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode)) player?.pause()
    }

    override fun onDestroy() {
        save()
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun save() {
        val p = player ?: return
        if (uri.isBlank()) return
        val dur = p.duration.takeIf { it > 0 } ?: 0L
        WatchHistory.record(this, Watched(uri, title, source, p.currentPosition, dur, System.currentTimeMillis()))
    }

    companion object {
        private const val EXTRA_URI = "uri"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_UA = "ua"
        private const val EXTRA_REFERER = "referer"
        private const val EXTRA_COOKIES = "cookies"

        /** Play [uri] (content://, file:// or http(s)://). [source] is shown under "Continue watching". */
        fun intent(
            context: Context,
            uri: String,
            title: String,
            source: String,
            userAgent: String? = null,
            referer: String? = null,
            cookies: String? = null,
        ): Intent = Intent(context, VideoPlayerActivity::class.java)
            .putExtra(EXTRA_URI, uri).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_SOURCE, source)
            .putExtra(EXTRA_UA, userAgent).putExtra(EXTRA_REFERER, referer).putExtra(EXTRA_COOKIES, cookies)
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerScreen(exo: ExoPlayer, title: String, inPip: Boolean, onBack: () -> Unit, onPip: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as ComponentActivity
    var playing by remember { mutableStateOf(exo.isPlaying) }
    var buffering by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var tracks by remember { mutableStateOf(exo.currentTracks) }
    var showControls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    var hint by remember { mutableStateOf<Pair<ImageVector, String>?>(null) }
    var hintAt by remember { mutableLongStateOf(0L) }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var landscapeSet by remember { mutableStateOf(false) }

    DisposableEffect(exo) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) { duration = exo.duration.coerceAtLeast(0); error = null }
                if (state == Player.STATE_ENDED) showControls = true
            }
            override fun onPlayerError(e: PlaybackException) { error = "Can't play this video (${e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')})" }
            override fun onTracksChanged(t: Tracks) { tracks = t }
            override fun onVideoSizeChanged(size: VideoSize) {
                // A wide video turns the screen sideways by itself (once; Rotate overrides it).
                if (!landscapeSet && size.width > 0 && size.height > 0) {
                    landscapeSet = true
                    activity.requestedOrientation = if (size.width > size.height) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                }
            }
        }
        exo.addListener(l)
        onDispose { exo.removeListener(l) }
    }
    LaunchedEffect(exo) {
        while (true) {
            position = exo.currentPosition
            if (exo.duration > 0) duration = exo.duration
            delay(400)
        }
    }
    // Controls hide themselves after a few seconds of playing.
    LaunchedEffect(showControls, playing, lastTouch, menu, dialog) {
        if (showControls && playing && !menu && dialog == null) {
            delay(3500)
            showControls = false
        }
    }
    LaunchedEffect(hintAt) {
        if (hint != null) { delay(900); hint = null }
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
    fun show(icon: ImageVector, text: String) { hint = icon to text; hintAt = System.currentTimeMillis() }

    val subtitleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            val pos = exo.currentPosition
            val current = exo.currentMediaItem ?: return@rememberLauncherForActivityResult
            val sub = MediaItem.SubtitleConfiguration.Builder(picked)
                .setMimeType(if (picked.toString().endsWith(".vtt", true)) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
            exo.setMediaItem(current.buildUpon().setSubtitleConfigurations(listOf(sub)).build(), pos)
            exo.prepare()
            exo.play()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
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

        // Gestures: taps, double-taps, slides and pinches (not while locked).
        Box(
            Modifier.fillMaxSize()
                .pointerInput(locked) {
                    detectTapGestures(
                        onTap = { lastTouch = System.currentTimeMillis(); showControls = !showControls },
                        onDoubleTap = { o ->
                            if (locked) return@detectTapGestures
                            val back = o.x < size.width / 2
                            exo.seekTo((exo.currentPosition + if (back) -10_000 else 10_000).coerceIn(0, exo.duration.coerceAtLeast(0)))
                            show(if (back) Icons.Rounded.Replay10 else Icons.Rounded.Forward10, if (back) "−10 s" else "+10 s")
                        },
                    )
                }
                .pointerInput(locked) {
                    if (locked) return@pointerInput
                    var scale = 1f
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
                                volume = (volume + delta).coerceIn(0f, 1f)
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (volume * maxVol).toInt(), 0)
                                show(Icons.AutoMirrored.Rounded.VolumeUp, "Volume ${(volume * 100).toInt()}%")
                            }
                        }
                    }
                },
        )

        hint?.let { (icon, text) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp).clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = .6f)).padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(text, color = Color.White, fontSize = 14.sp)
            }
        }
        if (buffering && error == null) CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center).size(48.dp))
        error?.let {
            Text(
                it, color = Color.White, fontSize = 14.sp,
                modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = .7f)).padding(16.dp),
            )
        }

        // Locked: only the unlock button, shown on a tap.
        if (locked) {
            AnimatedVisibility(showControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterStart).padding(start = 24.dp)) {
                RoundButton(Icons.Rounded.LockOpen, "Unlock") { locked = false; showControls = true; lastTouch = System.currentTimeMillis() }
            }
            return@Box
        }

        AnimatedVisibility(showControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f)).windowInsetsPadding(WindowInsets.safeDrawing)) {
                // Top: back, title, small window, more.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                    IconBtn(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
                    Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) IconBtn(Icons.Rounded.PictureInPictureAlt, "Small window", onPip)
                    Box {
                        IconBtn(Icons.Rounded.MoreVert, "More") { menu = true; lastTouch = System.currentTimeMillis() }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Speed · ${fmtSpeed(speed)}") }, leadingIcon = { Icon(Icons.Rounded.Speed, null) }, onClick = { menu = false; dialog = "speed" })
                            DropdownMenuItem(text = { Text("Subtitles") }, leadingIcon = { Icon(Icons.Rounded.Subtitles, null) }, onClick = { menu = false; dialog = "subs" })
                            DropdownMenuItem(text = { Text("Audio track") }, leadingIcon = { Icon(Icons.Rounded.GraphicEq, null) }, onClick = { menu = false; dialog = "audio" })
                            DropdownMenuItem(text = { Text("Open subtitles file (.srt)") }, leadingIcon = { Icon(Icons.Rounded.ClosedCaption, null) }, onClick = {
                                menu = false
                                subtitleLauncher.launch(arrayOf("application/x-subrip", "text/vtt", "text/plain", "application/octet-stream", "*/*"))
                            })
                            DropdownMenuItem(text = { Text(if (zoom) "Fit to screen" else "Fill screen") }, leadingIcon = { Icon(Icons.Rounded.AspectRatio, null) }, onClick = { menu = false; zoom = !zoom })
                        }
                    }
                }
                // Middle: back 10 s, play / pause, forward 10 s.
                Row(horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.Center)) {
                    IconBtn(Icons.Rounded.Replay10, "Back 10 seconds", size = 40) { exo.seekTo((exo.currentPosition - 10_000).coerceAtLeast(0)); lastTouch = System.currentTimeMillis() }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(72.dp).clip(CircleShape).background(Color.White.copy(alpha = .18f)).clickable {
                            if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0)
                            if (exo.isPlaying) exo.pause() else exo.play()
                            lastTouch = System.currentTimeMillis()
                        },
                    ) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(44.dp)) }
                    IconBtn(Icons.Rounded.Forward10, "Forward 10 seconds", size = 40) { exo.seekTo((exo.currentPosition + 10_000).coerceAtMost(exo.duration.coerceAtLeast(0))); lastTouch = System.currentTimeMillis() }
                }
                // Bottom: seek bar, times, quick buttons.
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val shownPos = seeking?.let { (it * duration).toLong() } ?: position
                    Slider(
                        value = if (duration > 0) (shownPos.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                        onValueChange = { seeking = it; lastTouch = System.currentTimeMillis() },
                        onValueChangeFinished = { seeking?.let { exo.seekTo((it * duration).toLong()) }; seeking = null },
                        colors = SliderDefaults.colors(thumbColor = Color(0xFFEF4444), activeTrackColor = Color(0xFFEF4444), inactiveTrackColor = Color.White.copy(alpha = .3f)),
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text(fmtTime(shownPos), color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text(fmtTime(duration), color = Color.White, fontSize = 12.sp)
                    }
                    Row(horizontalArrangement = Arrangement.SpaceAround, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        LabeledBtn(Icons.Rounded.Lock, "Lock") { locked = true; showControls = false }
                        LabeledBtn(Icons.Rounded.Speed, fmtSpeed(speed)) { dialog = "speed" }
                        LabeledBtn(Icons.Rounded.Subtitles, "Subtitles") { dialog = "subs" }
                        LabeledBtn(Icons.Rounded.ScreenRotation, "Rotate") {
                            landscapeSet = true
                            val landscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                            activity.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        }
                    }
                }
            }
        }
    }

    when (dialog) {
        "speed" -> ChoiceDialog(
            "Playback speed",
            SPEEDS.map { fmtSpeed(it) }, SPEEDS.indexOfFirst { it == speed },
            onPick = { i -> speed = SPEEDS[i]; exo.setPlaybackSpeed(speed); dialog = null },
            onDismiss = { dialog = null },
        )
        "subs", "audio" -> {
            val type = if (dialog == "subs") C.TRACK_TYPE_TEXT else C.TRACK_TYPE_AUDIO
            val options = tracks.groups.filter { it.type == type }.flatMap { g -> (0 until g.length).map { i -> g to i } }
            val names = options.map { (g, i) ->
                val f = g.getTrackFormat(i)
                listOfNotNull(f.label, f.language?.let { java.util.Locale(it).displayLanguage }).distinct().joinToString(" · ").ifBlank { "Track ${i + 1}" }
            }
            val off = type == C.TRACK_TYPE_TEXT
            val disabled = exo.trackSelectionParameters.disabledTrackTypes.contains(type)
            val current = if (off && disabled) 0 else options.indexOfFirst { (g, i) -> g.isTrackSelected(i) }.let { if (off) it + 1 else it }
            if (options.isEmpty()) {
                AlertDialog(
                    onDismissRequest = { dialog = null },
                    confirmButton = { TextButton(onClick = { dialog = null }) { Text("OK") } },
                    title = { Text(if (off) "No subtitles" else "One audio track") },
                    text = { Text(if (off) "This video has no subtitles. Use ⋮ → Open subtitles file (.srt) to add one." else "This video has only one audio track.") },
                )
            } else ChoiceDialog(
                if (off) "Subtitles" else "Audio track",
                (if (off) listOf("Off") else emptyList()) + names, current,
                onPick = { pick ->
                    val b = exo.trackSelectionParameters.buildUpon()
                    if (off && pick == 0) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    else {
                        val (g, i) = options[if (off) pick - 1 else pick]
                        b.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                    }
                    exo.trackSelectionParameters = b.build()
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        }
    }
}

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

private fun fmtSpeed(s: Float) = if (s == 1f) "1×" else "${s.toString().trimEnd('0').trimEnd('.')}×"

private fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun IconBtn(icon: ImageVector, desc: String, onClick: () -> Unit) = IconBtn(icon, desc, 26, onClick)

@Composable
private fun IconBtn(icon: ImageVector, desc: String, size: Int, onClick: () -> Unit) {
    Box(Modifier.size((size + 22).dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = Color.White, modifier = Modifier.size(size.dp))
    }
}

@Composable
private fun RoundButton(icon: ImageVector, desc: String, onClick: () -> Unit) {
    Box(Modifier.size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = .55f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = Color.White, modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun LabeledBtn(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White, fontSize = 11.sp)
    }
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
