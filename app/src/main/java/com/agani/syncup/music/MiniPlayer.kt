package com.agani.syncup.music

import android.net.Uri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.ui.theme.isLight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Compact "now playing" bar: shown on the Normal home page and at the foot of Music while a song
 * or station is loaded. Tap opens the player; play / pause, next song, ✕ stops. A floating white
 * card (a soft shadow in Light, a thin line in Dark).
 */
@Composable
fun MiniPlayer(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val player = LocalPlayer.current ?: return
    if (player.item == null) return
    val cs = MaterialTheme.colorScheme
    val radio = player.isRadio

    // Thin progress line for songs; a live station has no end.
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(player.item, player.isPlaying, player.durationMs) {
        while (true) {
            progress = if (radio || player.durationMs <= 0) 0f else (player.position.toFloat() / player.durationMs).coerceIn(0f, 1f)
            if (!player.isPlaying) break
            delay(1000)
        }
    }

    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier.fillMaxWidth().height(66.dp)
            .then(
                if (cs.isLight) Modifier.shadow(10.dp, shape, ambientColor = Color(0x290F172A), spotColor = Color(0x290F172A))
                else Modifier.border(1.dp, cs.outlineVariant, shape),
            )
            .clip(shape).background(cs.surfaceContainerLowest)
            .clickable(onClick = onOpen),
    ) {
        Box {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize().padding(start = 10.dp, end = 4.dp)) {
                ArtworkTile(player.artUri, radio, Modifier.size(44.dp), radius = 13.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            player.title.ifBlank { if (radio) "Radio" else "Music" }, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (player.isPlaying) {
                            Spacer(Modifier.width(8.dp))
                            EqBars()
                        }
                    }
                    if (player.subtitle.isNotBlank()) {
                        Text(player.subtitle, fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box(
                    Modifier.padding(start = 4.dp).size(40.dp).clip(CircleShape).background(cs.primary).clickable { player.toggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (player.buffering) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = cs.onPrimary)
                    } else {
                        Icon(if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (player.isPlaying) "Pause" else "Play", tint = cs.onPrimary)
                    }
                }
                if (!radio && player.hasNext) {
                    IconButton(onClick = { player.next() }) { Icon(Icons.Rounded.SkipNext, "Next song", tint = cs.onSurface) }
                }
                IconButton(onClick = { player.stop() }) {
                    Icon(Icons.Rounded.Close, if (radio) "Stop radio" else "Stop music", tint = cs.onSurfaceVariant)
                }
            }
            if (!radio) {
                Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 22.dp).fillMaxWidth().height(2.dp).clip(CircleShape).background(cs.outlineVariant)) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(cs.primary))
                }
            }
        }
    }
}

/** Songs without cover art get a soft tile, its colour picked from the song. */
private val ART_TINTS = listOf(
    ToolTint.VIOLET, ToolTint.AMBER, ToolTint.TEAL, ToolTint.SKY,
    ToolTint.ROSE, ToolTint.GREEN, ToolTint.INDIGO, ToolTint.PINK,
)

/**
 * Cover art for a song (from MediaStore), or a soft coloured tile with a note / radio glyph when
 * there's none. Loads off the main thread and reuses [Artwork]'s cache.
 */
@Composable
internal fun ArtworkTile(uri: Uri?, radio: Boolean, modifier: Modifier, radius: Dp, large: Boolean = false) {
    val context = LocalContext.current
    val px = if (large) Artwork.LARGE else Artwork.SMALL
    val bitmap by produceState(initialValue = uri?.let { Artwork.cached(it, px) }, uri, px) {
        if (uri != null && value == null && Artwork.isLocal(uri)) value = withContext(Dispatchers.IO) { Artwork.load(context, uri, px) }
    }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    val tint = if (radio) ToolTint.ORANGE else ART_TINTS[((uri?.hashCode() ?: 0) and 0x7fffffff) % ART_TINTS.size]
    Box(
        modifier
            .clip(RoundedCornerShape(radius))
            .background(tint.tile),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                if (radio) Icons.Rounded.Radio else Icons.Rounded.MusicNote, null, tint = tint.icon,
                modifier = Modifier.fillMaxSize(if (large) .36f else .46f),
            )
        }
    }
}

/** Three small animated bars — the "playing" indicator. */
@Composable
internal fun EqBars(color: Color = MaterialTheme.colorScheme.primary) {
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
