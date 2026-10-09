package com.agani.syncup.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddBox
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.IconTile
import com.agani.syncup.browser.ui.MenuRow
import com.agani.syncup.browser.ui.SheetDivider
import com.agani.syncup.downloads.DownloadRequest
import com.agani.syncup.downloads.Downloads
import com.agani.syncup.downloads.Files
import com.agani.syncup.downloads.KindTile

// ============================================================================ pull to refresh
/** The round arrow that follows a pull at the top of the page, then spins while the page reloads. */
@Composable
fun PullIndicator(tab: BrowserTab, modifier: Modifier = Modifier) {
    val pull = tab.pull
    if (pull <= 0f && !tab.pullRefreshing) return
    val cs = MaterialTheme.colorScheme
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val progress = if (tab.pullRefreshing) 1f else (pull / threshold).coerceIn(0f, 1f)
    val dropPx = if (tab.pullRefreshing) threshold * .55f else (pull * .55f).coerceAtMost(threshold * 1.4f)
    val drop = with(LocalDensity.current) { dropPx.toDp() }
    Surface(
        shape = CircleShape,
        color = cs.surfaceContainerLowest,
        shadowElevation = 4.dp,
        modifier = modifier.offset(y = drop - 20.dp).size(40.dp).alpha(.35f + .65f * progress),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (tab.pullRefreshing) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = cs.primary)
            } else {
                Icon(
                    Icons.Rounded.Refresh, "Pull to refresh", tint = if (progress >= 1f) cs.primary else cs.onSurfaceVariant,
                    modifier = Modifier.size(24.dp).rotate(progress * 300f),
                )
            }
        }
    }
}

// ============================================================================ long-press menu
enum class PressAction { OPEN_NEW, OPEN_INCOGNITO, COPY_LINK, SHARE_LINK, DOWNLOAD_LINK, DOWNLOAD_IMAGE, SHARE_IMAGE, OPEN_IMAGE, COPY_IMAGE_LINK }

/**
 * Long-press on a link or an image. On a SyncUp page the address stays hidden: no Copy / Share /
 * Incognito for its links, and the link is shown by the SyncUp link's name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PressMenuSheet(target: PressTarget, tab: BrowserTab, onAction: (PressAction) -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val link = target.link
    val image = target.image
    val web = { u: String -> u.startsWith("http://") || u.startsWith("https://") }
    val hideAddress = tab.isWork
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)) {
                IconTile(if (link != null) Icons.Rounded.Link else Icons.Rounded.Image, container = cs.surfaceContainerHigh, content = cs.onSurfaceVariant, size = 48.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    val title = target.linkText ?: if (link != null) "Link" else imageName(image.orEmpty())
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = when {
                        hideAddress -> "On ${tab.workName ?: "a SyncUp link"} · address hidden"
                        link != null && web(link) -> link
                        image != null && web(image) -> UrlInput.display(image).substringBefore('/')
                        else -> "From this page"
                    }
                    Text(sub, fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            SheetDivider()
            if (link != null) {
                if (web(link)) {
                    MenuRow(Icons.Rounded.AddBox, "Open in new tab") { onAction(PressAction.OPEN_NEW) }
                    if (!hideAddress && tab.section != Section.INCOGNITO) {
                        MenuRow(Icons.Rounded.VisibilityOff, "Open in Incognito tab") { onAction(PressAction.OPEN_INCOGNITO) }
                    }
                    if (!hideAddress) {
                        MenuRow(Icons.Rounded.ContentCopy, "Copy link") { onAction(PressAction.COPY_LINK) }
                        MenuRow(Icons.Rounded.Share, "Share link") { onAction(PressAction.SHARE_LINK) }
                    }
                }
                MenuRow(Icons.Rounded.Download, "Download link", trailing = "SyncUp Downloads", tint = cs.primary, textColor = cs.primary) {
                    onAction(PressAction.DOWNLOAD_LINK)
                }
            }
            if (image != null) {
                if (link != null) SheetDivider()
                MenuRow(Icons.Rounded.Download, "Download image", tint = cs.primary, textColor = cs.primary) { onAction(PressAction.DOWNLOAD_IMAGE) }
                MenuRow(Icons.Rounded.Share, "Share image") { onAction(PressAction.SHARE_IMAGE) }
                if (web(image)) {
                    MenuRow(Icons.Rounded.Image, "Open image in new tab") { onAction(PressAction.OPEN_IMAGE) }
                    if (!hideAddress) MenuRow(Icons.Rounded.ContentCopy, "Copy image link") { onAction(PressAction.COPY_IMAGE_LINK) }
                }
            }
        }
    }
}

private fun imageName(url: String): String =
    if (url.startsWith("http")) Files.cleanName(android.net.Uri.parse(url).lastPathSegment ?: "Image") else "Image"

// ============================================================================ a site asks for a permission
/** "meet.jit.si wants to use your camera and microphone · Block / Allow" over the bottom of the page. */
@Composable
fun SitePermissionCard(prompt: SitePermissions.Prompt, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val perms = prompt.perms
    val what = when {
        SitePerm.CAMERA in perms && SitePerm.MIC in perms -> "use your camera and microphone"
        SitePerm.CAMERA in perms -> "use your camera"
        SitePerm.MIC in perms -> "use your microphone"
        SitePerm.LOCATION in perms -> "know your location"
        else -> "send you notifications"
    }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = cs.surfaceContainerLowest,
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Column(Modifier.padding(start = 18.dp, end = 12.dp, top = 18.dp, bottom = 8.dp)) {
            Row {
                Box(Modifier.size(40.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(perms.first().icon, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f).padding(end = 6.dp)) {
                    Text("${prompt.label} wants to $what", fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                    Text(
                        if (prompt.incognito) "Your choice lasts until you close Incognito."
                        else "Your choice is remembered for this site. Change it any time in Settings → Site settings.",
                        fontSize = 13.sp, lineHeight = 18.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { SitePermissions.answer(false) }) { Text("Block") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { SitePermissions.answer(true) }) { Text("Allow") }
            }
        }
    }
}

// ============================================================================ the media & file detector
/** The ⬇ button with the number of files found on the page. */
@Composable
fun DetectorButton(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(modifier.size(60.dp)) {
        Box(
            Modifier.align(Alignment.Center).size(52.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(cs.primary).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Download, "Videos, music and files on this page", tint = cs.onPrimary, modifier = Modifier.size(26.dp))
        }
        Box(
            Modifier.align(Alignment.TopEnd).heightIn(min = 22.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFFDC2626))
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("$count", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

/** "Found on this page": each file with its type and size, Save one or All. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectedSheet(
    files: List<DetectedFile>,
    requestFor: (String) -> DownloadRequest,
    onSave: (DetectedFile) -> Unit,
    onSaveAll: () -> Unit,
    onDismiss: () -> Unit,
    onPlay: ((DetectedFile) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp, end = 16.dp, bottom = 6.dp)) {
                Text("Found on this page · ${files.size}", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.weight(1f))
                if (files.size > 1) {
                    Button(onClick = onSaveAll, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), modifier = Modifier.height(36.dp)) {
                        Icon(Icons.Rounded.Download, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("All")
                    }
                }
            }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(files, key = { it.url }) { f -> DetectedRow(f, requestFor, onSave, onPlay) }
            }
            Row(
                Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(cs.surfaceContainerHigh).padding(12.dp),
            ) {
                Icon(Icons.Rounded.Info, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp).padding(top = 1.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Only files the site lets you load. YouTube and protected videos (Netflix, Prime, Hotstar…) can't be downloaded.",
                    fontSize = 12.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DetectedRow(f: DetectedFile, requestFor: (String) -> DownloadRequest, onSave: (DetectedFile) -> Unit, onPlay: ((DetectedFile) -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    var size by remember(f.url) { mutableLongStateOf(-2L) } // -2 = still checking
    LaunchedEffect(f.url) {
        val r = requestFor(f.url)
        size = runCatching { Downloads.probe(r.url, r.userAgent, r.referer, r.cookies).size }.getOrDefault(-1L)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        KindTile(f.kind)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sizeText = when (size) {
                -2L -> "Checking size…"
                -1L -> "Size unknown"
                else -> Files.size(size)
            }
            Text(listOfNotNull(f.kind.label, sizeText, if (f.playing) "playing on the page" else null).joinToString(" · "), fontSize = 12.sp, color = cs.onSurfaceVariant)
        }
        // Videos can be watched straight away in SyncUp's player.
        if (onPlay != null && f.kind == com.agani.syncup.downloads.FileKind.VIDEO) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(17.dp)).background(cs.surfaceContainerHighest).clickable { onPlay(f) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.PlayArrow, "Play ${f.name}", tint = cs.onSurface, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(8.dp))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(cs.primaryContainer).clickable { onSave(f) }.padding(horizontal = 14.dp),
        ) {
            Icon(Icons.Rounded.Download, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Save", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onPrimaryContainer)
        }
    }
}
