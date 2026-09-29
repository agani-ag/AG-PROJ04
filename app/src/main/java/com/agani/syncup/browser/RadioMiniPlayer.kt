package com.agani.syncup.browser

import android.content.ComponentName
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.agani.syncup.radio.RadioPlaybackService

/**
 * Compact "now playing" bar for the new-tab page. Visible only while a station is loaded in the
 * Radio player; tap opens the full Radio screen, pause/play controls it, ✕ stops it.
 */
@Composable
fun RadioMiniPlayer(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var visible by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var station by remember { mutableStateOf("") }
    var nowPlaying by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, RadioPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        var listener: Player.Listener? = null
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            fun sync() {
                visible = c.mediaItemCount > 0 && c.playbackState != Player.STATE_IDLE
                playing = c.isPlaying
                station = c.mediaMetadata.station?.toString() ?: c.mediaMetadata.title?.toString().orEmpty()
                nowPlaying = c.mediaMetadata.artist?.toString().orEmpty()
            }
            listener = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = sync()
            }.also { c.addListener(it) }
            sync()
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            listener?.let { controller?.removeListener(it) }
            MediaController.releaseFuture(future) // the player keeps running in its service
            controller = null
        }
    }

    if (!visible) return
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainerHighest,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth().height(64.dp).clickable(onClick = onOpen),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp, end = 6.dp)) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(cs.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Radio, null, tint = cs.onPrimaryContainer) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        station.ifBlank { "Radio" }, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (playing) {
                        Spacer(Modifier.width(8.dp))
                        EqBars()
                    }
                }
                if (nowPlaying.isNotBlank()) {
                    Text(nowPlaying, fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(cs.primary).clickable { controller?.let { if (it.isPlaying) it.pause() else it.play() } },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = cs.onPrimary)
            }
            IconButton(onClick = {
                controller?.apply {
                    stop()
                    clearMediaItems()
                }
            }) { Icon(Icons.Rounded.Close, "Stop radio", tint = cs.onSurfaceVariant) }
        }
    }
}

/** Three small animated bars — the "playing" indicator from the mockup. */
@Composable
private fun EqBars() {
    val color = MaterialTheme.colorScheme.primary
    val t = rememberInfiniteTransition(label = "eq")
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom, modifier = Modifier.height(12.dp)) {
        listOf(0, 200, 400).forEach { delay ->
            val h by t.animateFloat(
                initialValue = .3f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(500, delayMillis = delay), RepeatMode.Reverse),
                label = "bar$delay",
            )
            Box(Modifier.width(2.dp).fillMaxHeight(h).clip(RoundedCornerShape(1.dp)).background(color))
        }
    }
}
