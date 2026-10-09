package com.agani.syncup.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.downloads.Downloads
import com.agani.syncup.music.LocalPlayer
import com.agani.syncup.ui.theme.isLight

/** What's open inside Tools. */
enum class ToolsPage { HOME, VIDEOS, FILES, FOLDER, NETWORK_FOLDERS, NETWORK_SERVER, NETWORK_SCANNER }

/** One line for the ⋮ menu's Tools row: what's going on inside right now (null = nothing). */
@Composable
fun toolsStatus(): String? {
    val player = LocalPlayer.current
    return when {
        player?.isPlaying == true -> "Playing music"
        else -> null
    }
}

/**
 * SyncUp's extra features in one place — Music, Video player, Downloads — each tile showing
 * what it's doing right now. New tools are added here as tiles.
 */
@Composable
fun ToolsScreen(
    onMusic: () -> Unit,
    onVideos: () -> Unit,
    onDownloads: () -> Unit,
    onFiles: () -> Unit,
    onNetworkFolders: () -> Unit,
    onNetworkScanner: () -> Unit,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val light = cs.isLight
    val player = LocalPlayer.current
    val running = Downloads.tasks.filter { it.active }
    Box(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp)) {
                BarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
                Text("Tools", fontSize = 22.sp, lineHeight = 28.sp, color = cs.onSurface, modifier = Modifier.weight(1f).padding(start = 4.dp))
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                item { Label("MEDIA") }
                item {
                    TileRow {
                        Tile(
                            Icons.Rounded.LibraryMusic, accent(0xFF6D28D9, 0xFFC4B5FD, light), tint(0xFFEDE9FE, 0xFF3B2A63, light),
                            "Music", "Songs on your phone · Radio",
                            status = player?.title?.takeIf { player.isPlaying && it.isNotBlank() }?.let { "Playing: $it" },
                            onClick = onMusic,
                        )
                        Tile(
                            Icons.Rounded.SmartDisplay, accent(0xFFBE123C, 0xFFFDA4AF, light), tint(0xFFFFE4E6, 0xFF4C0519, light),
                            "Video player", "Your videos, downloads and video links",
                            onClick = onVideos,
                        )
                    }
                }
                item { Label("INTERNET") }
                item {
                    TileRow {
                        Tile(
                            Icons.Rounded.Download, accent(0xFF2563EB, 0xFF9EB8FF, light), tint(0xFFDCE7FF, 0xFF25345C, light),
                            "Downloads",
                            if (running.isEmpty()) "Files you save from pages" else "${running.size} running",
                            onClick = onDownloads,
                        )
                        Tile(
                            Icons.Rounded.Folder, accent(0xFFB45309, 0xFFFCD34D, light), tint(0xFFFEF3C7, 0xFF4A3300, light),
                            "Files", "Folders on your phone",
                            onClick = onFiles,
                        )
                    }
                }
                item { Label("NETWORK") }
                item {
                    TileRow {
                        Tile(
                            Icons.Rounded.Dns, accent(0xFF0E7490, 0xFF67E8F9, light), tint(0xFFCFFAFE, 0xFF0C3B45, light),
                            "Network folders", "PCs and NAS boxes on this Wi-Fi",
                            onClick = onNetworkFolders,
                        )
                        Tile(
                            Icons.Rounded.Radar, accent(0xFF15803D, 0xFF86EFAC, light), tint(0xFFDCFCE7, 0xFF0E3B21, light),
                            "Network scanner", "Find devices on this Wi-Fi",
                            onClick = onNetworkScanner,
                        )
                    }
                }
                item {
                    Box(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().height(96.dp).clip(RoundedCornerShape(22.dp))
                            .border(1.5.dp, cs.outlineVariant, RoundedCornerShape(22.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Text("More tools are on the way", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant) }
                }
            }
        }
    }
}

private fun accent(light: Long, dark: Long, isLight: Boolean) = Color(if (isLight) light else dark)
private fun tint(light: Long, dark: Long, isLight: Boolean) = Color(if (isLight) light else dark)

@Composable
private fun Label(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = .3.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp))
}

@Composable
private fun TileRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(), content = content)
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Tile(
    icon: ImageVector,
    color: Color,
    container: Color,
    title: String,
    subtitle: String,
    status: String? = null,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier.weight(1f).heightIn(min = 156.dp).clip(shape)
            .background(if (highlighted) container.copy(alpha = .45f) else cs.surfaceContainerLowest)
            .border(1.dp, if (highlighted) color.copy(alpha = .5f) else cs.outlineVariant, shape)
            .clickable(onClick = onClick).padding(16.dp),
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(container), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = color, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.weight(1f).heightIn(min = 16.dp))
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
        if (status != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(6.dp))
                Text(status, fontSize = 12.5.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(subtitle, fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}
