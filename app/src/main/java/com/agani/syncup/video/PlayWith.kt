package com.agani.syncup.video

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap

/** A video player app on this phone. */
class VideoApp(val component: ComponentName, val label: String, val icon: Drawable?)

/**
 * "Play with another app": the phone's video players, and handing one video to the one picked
 * (read-only). Asked each time — tapping a video always plays it in SyncUp.
 */
object PlayWith {
    private fun view(uri: Uri): Intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    fun apps(context: Context, uri: String): List<VideoApp> {
        val pm = context.packageManager
        val found = runCatching { pm.queryIntentActivities(view(Uri.parse(uri)), PackageManager.MATCH_DEFAULT_ONLY) }.getOrDefault(emptyList())
        return found
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { VideoApp(ComponentName(it.activityInfo.packageName, it.activityInfo.name), it.loadLabel(pm).toString(), runCatching { it.loadIcon(pm) }.getOrNull()) }
            .sortedBy { it.label.lowercase() }
    }

    fun open(context: Context, app: VideoApp, uri: String, title: String) {
        val intent = view(Uri.parse(uri)).setComponent(app.component).putExtra(Intent.EXTRA_TITLE, title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "${app.label} can't open this video", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(context, "${app.label} can't open this video", Toast.LENGTH_SHORT).show()
        }
    }
}

/** The "Play with" sheet: SyncUp player first, then the phone's video apps. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayWithSheet(uri: String, title: String, onPlayHere: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val apps = remember(uri) { PlayWith.apps(context, uri) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            Text("Play with", fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp))
            Text(title, fontSize = 13.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 10.dp))
            AppRow(
                icon = {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFBE123C)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.PlayArrow, null, tint = Color.White)
                    }
                },
                title = "SyncUp player", sub = "Boost, Watch Party, subtitles",
            ) {
                onDismiss()
                onPlayHere()
            }
            apps.forEach { app ->
                AppRow(
                    icon = {
                        val bmp = remember(app) { app.icon?.let { runCatching { it.toBitmap(96, 96).asImageBitmap() }.getOrNull() } }
                        if (bmp != null) Image(bmp, null, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
                        else Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHigh))
                    },
                    title = app.label, sub = null,
                ) {
                    onDismiss()
                    PlayWith.open(context, app, uri, title)
                }
            }
            if (apps.isEmpty()) {
                Text(
                    "No other video app on this phone. Install one (VLC, MX Player…) and it shows here.",
                    fontSize = 13.sp, lineHeight = 18.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun AppRow(icon: @Composable () -> Unit, title: String, sub: String?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp),
    ) {
        icon()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (sub != null) Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
