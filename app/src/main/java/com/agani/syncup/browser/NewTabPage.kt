package com.agani.syncup.browser

import com.agani.syncup.browser.ui.SyncUpMark
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cookie
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NoPhotography
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.R
import com.agani.syncup.browser.ui.Avatar
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.browser.ui.SectionHeader
import com.agani.syncup.browser.ui.SectionTheme
import com.agani.syncup.browser.ui.SyncPill
import com.agani.syncup.ui.theme.dialogSurface
import java.util.Calendar

/** Built-in new-tab shortcuts; users can hide these and add their own after them. */
private enum class Builtin(val label: String, val url: String) {
    GOOGLE("Google", "https://www.google.com"),
    YOUTUBE("YouTube", "https://m.youtube.com"),
    WHATSAPP("WhatsApp", "https://web.whatsapp.com"),
    LINKEDIN("LinkedIn", "https://www.linkedin.com"),
    X("X", "https://x.com"),
    INSTAGRAM("Instagram", "https://www.instagram.com"),
    WIKIPEDIA("Wikipedia", "https://en.m.wikipedia.org"),
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 0..11 -> "Good morning,"
    in 12..16 -> "Good afternoon,"
    else -> "Good evening,"
}

// ============================================================================ Normal new-tab page
/**
 * No address bar. Signed out: account icon, SyncUp mark, search, shortcuts. Signed in: greeting +
 * avatar, search, admin announcement (strip), Work card (only when the user has work links),
 * shortcuts; the Radio mini-player floats above the bottom bar with space reserved for it.
 */
@Composable
internal fun NormalHome(
    account: BrowserAccount,
    hasWork: Boolean,
    workOpenTabs: Int,
    onSearch: () -> Unit,
    onVoice: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onAvatar: () -> Unit,
    onOpenWork: () -> Unit,
    onOpenRadio: () -> Unit,
    onUndo: (message: String, undo: () -> Unit) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val user = account.user
    var editing by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = if (user != null && account.radioEnabled) 96.dp else 20.dp),
        ) {
            if (user == null) {
                Row(Modifier.fillMaxWidth().padding(end = 0.dp), horizontalArrangement = Arrangement.End) {
                    Box(Modifier.offset(x = 8.dp)) {
                        BarIcon(Icons.Rounded.AccountCircle, "Sign in to SyncUp", tint = cs.onSurfaceVariant, onClick = onAvatar)
                    }
                }
                Column(
                    Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(cs.primary), contentAlignment = Alignment.Center) {
                        Image(painterResource(R.drawable.ic_sync), null, colorFilter = ColorFilter.tint(cs.onPrimary), modifier = Modifier.size(34.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("SyncUp", fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(64.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(greeting(), fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = .2.sp, color = cs.onSurfaceVariant)
                        Text(
                            user.name.substringBefore(' '), fontSize = 24.sp, lineHeight = 30.sp, color = cs.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(Modifier.offset(x = 4.dp)) { Avatar(user, unread = account.chatUnread > 0 || account.partnersWaiting > 0, onClick = onAvatar) }
                }
                Spacer(Modifier.height(12.dp))
            }

            SyncPill(modifier = Modifier.fillMaxWidth(), height = 56.dp, onClick = onSearch) {
                Icon(Icons.Rounded.Search, null, tint = cs.onSurfaceVariant)
                Text("Search or type web address", fontSize = 15.sp, letterSpacing = .15.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1)
                BarIcon(Icons.Rounded.Mic, "Voice search", tint = cs.onSurfaceVariant, onClick = onVoice)
            }

            val ann = account.announcement
            if (user != null && ann != null && ann.active && !ann.fullscreen && (ann.title.isNotBlank() || ann.message.isNotBlank())) {
                Spacer(Modifier.height(20.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(cs.secondaryContainer)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Icon(Icons.Rounded.Campaign, null, tint = cs.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        if (ann.title.isNotBlank()) Text(ann.title, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = cs.onSecondaryContainer)
                        if (ann.message.isNotBlank()) Text(ann.message, fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSecondaryContainer.copy(alpha = .85f))
                    }
                }
            }

            if (user != null && hasWork) {
                Spacer(Modifier.height(12.dp))
                SectionTheme(Section.WORK) {
                    val w = MaterialTheme.colorScheme
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(w.primaryContainer)
                            .clickable(onClick = onOpenWork)
                            .padding(16.dp),
                    ) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(w.primary), contentAlignment = Alignment.Center) {
                            Icon(SyncUpMark, null, tint = w.onPrimary)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("SyncUp", fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = w.onPrimaryContainer)
                            val n = account.links.size
                            Text(
                                "$n link${if (n == 1) "" else "s"} · $workOpenTabs open tab${if (workOpenTabs == 1) "" else "s"}",
                                fontSize = 12.sp, lineHeight = 16.sp, color = w.onPrimaryContainer.copy(alpha = .85f),
                            )
                        }
                        Button(onClick = onOpenWork, contentPadding = PaddingValues(horizontal = 18.dp), modifier = Modifier.height(36.dp)) { Text("Open") }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            if (editing) {
                SectionHeader("Edit shortcuts", action = "Done", onAction = { editing = false })
                Text("Tap × to remove a shortcut", fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            } else {
                SectionHeader("Shortcuts", action = "Edit", onAction = { editing = true })
            }
            Spacer(Modifier.height(8.dp))
            ShortcutGrid(
                editing = editing,
                onOpen = onOpenUrl,
                onAdd = { showAdd = true },
                onRemoveBuiltin = { b ->
                    BrowserSettings.hideBuiltin(b.url)
                    onUndo("${b.label} removed") { BrowserSettings.unhideBuiltin(b.url) }
                },
                onRemoveUser = { s ->
                    val index = BrowserSettings.shortcuts.indexOf(s)
                    BrowserSettings.removeShortcut(s)
                    onUndo("${s.name} removed") { BrowserSettings.restoreShortcut(s, index) }
                },
                onLongPress = { editing = true },
            )
        }
        if (user != null && account.radioEnabled) {
            RadioMiniPlayer(onOpen = onOpenRadio, modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 12.dp))
        }
    }

    if (showAdd) AddShortcutDialog(onDismiss = { showAdd = false })
}

@Composable
private fun ShortcutGrid(
    editing: Boolean,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onRemoveBuiltin: (Builtin) -> Unit,
    onRemoveUser: (UserShortcut) -> Unit,
    onLongPress: () -> Unit,
) {
    val builtins = Builtin.entries.filter { it.url !in BrowserSettings.hiddenBuiltins }
    val cells = buildList<@Composable RowScope.() -> Unit> {
        builtins.forEach { b ->
            add {
                ShortcutTile(b.label, editing, onClick = { if (!editing) onOpen(b.url) }, onRemove = { onRemoveBuiltin(b) }, onLongPress = onLongPress) {
                    BrandMark(b)
                }
            }
        }
        BrowserSettings.shortcuts.toList().forEach { s ->
            add {
                ShortcutTile(s.name, editing, onClick = { if (!editing) onOpen(s.url) }, onRemove = { onRemoveUser(s) }, onLongPress = onLongPress) {
                    Text(s.name.take(1).uppercase(), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        if (!editing) add { AddTile(onAdd) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        cells.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { cell -> cell() }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.ShortcutTile(
    label: String,
    editing: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onLongPress: () -> Unit,
    mark: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(vertical = 4.dp),
    ) {
        Box {
            Box(Modifier.size(56.dp).clip(CircleShape).background(cs.surfaceContainerHigh), contentAlignment = Alignment.Center) { mark() }
            if (editing) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 6.dp, y = (-4).dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(cs.onSurface)
                        .clickable(onClick = onRemove),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Close, "Remove $label", tint = cs.surface, modifier = Modifier.size(14.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(label, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = .3.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RowScope.AddTile(onAdd: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onAdd).padding(vertical = 4.dp),
    ) {
        Box(Modifier.size(56.dp).clip(CircleShape).border(1.5.dp, cs.outline, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Add, null, tint = cs.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Text("Add", fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = .3.sp, color = cs.onSurfaceVariant)
    }
}

/** Simplified brand marks for the built-in shortcuts (28 dp, drawn on the tile's tonal circle). */
@Composable
private fun BrandMark(b: Builtin) {
    when (b) {
        Builtin.GOOGLE -> Image(painterResource(R.drawable.ic_brand_google), null, modifier = Modifier.size(28.dp))
        Builtin.YOUTUBE -> Image(painterResource(R.drawable.ic_brand_youtube), null, modifier = Modifier.size(28.dp))
        Builtin.WHATSAPP -> Box(Modifier.size(28.dp).clip(CircleShape).background(Color(0xFF25D366)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Call, null, tint = Color.White, modifier = Modifier.size(17.dp))
        }
        Builtin.LINKEDIN -> LetterSquare("in", Color(0xFF0A66C2), 14)
        Builtin.X -> LetterSquare("X", Color(0xFF111111), 15)
        Builtin.INSTAGRAM -> Canvas(Modifier.size(28.dp)) {
            val s = size.minDimension
            drawRoundRect(
                brush = Brush.linearGradient(listOf(Color(0xFFF58529), Color(0xFFDD2A7B), Color(0xFF8134AF)), start = Offset(0f, s), end = Offset(s, 0f)),
                cornerRadius = CornerRadius(s * .28f),
            )
            val stroke = Stroke(width = s * .075f)
            drawRoundRect(Color.White, topLeft = Offset(s * .2f, s * .2f), size = Size(s * .6f, s * .6f), cornerRadius = CornerRadius(s * .18f), style = stroke)
            drawCircle(Color.White, radius = s * .14f, center = Offset(s / 2, s / 2), style = stroke)
            drawCircle(Color.White, radius = s * .035f, center = Offset(s * .66f, s * .34f))
        }
        Builtin.WIKIPEDIA -> Text("W", fontSize = 26.sp, lineHeight = 26.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun LetterSquare(text: String, color: Color, fontSize: Int) {
    Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(color), contentAlignment = Alignment.Center) {
        Text(text, fontSize = fontSize.sp, lineHeight = fontSize.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
private fun AddShortcutDialog(onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.dialogSurface,
        title = { Text("Add shortcut") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    url, { url = it }, label = { Text("Web address") }, singleLine = true, shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val target = UrlInput.toUrl(url)
                if (name.isBlank() || target.isBlank() || UrlInput.isSearchPage(target)) {
                    error = "Enter a name and a web address, like example.com"
                } else {
                    BrowserSettings.addShortcut(name.trim(), target)
                    onDismiss()
                }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ============================================================================ Incognito home
@Composable
internal fun IncognitoHome(onSearch: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))
            Box(Modifier.size(72.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.VisibilityOff, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text("You've gone incognito", fontSize = 24.sp, lineHeight = 32.sp, color = cs.onSurface, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            IncognitoPoint(Icons.Rounded.History, "Pages you view won't be kept in history")
            IncognitoPoint(Icons.Rounded.Cookie, "Cookies and site data are deleted when you close all incognito tabs")
            IncognitoPoint(Icons.Rounded.Download, "Downloads and bookmarks are kept")
            IncognitoPoint(Icons.Rounded.NoPhotography, "Screenshots are blocked")
            Spacer(Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerLow).padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Icon(Icons.Rounded.Info, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(12.dp))
                Text("SyncUp links, Chat and Radio aren't available in Incognito", fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
        }
        SyncPill(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            height = 56.dp,
            onClick = onSearch,
        ) {
            Icon(Icons.Rounded.Search, null, tint = cs.onSurfaceVariant)
            Text("Search privately", fontSize = 15.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun IncognitoPoint(icon: ImageVector, text: String) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Icon(icon, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, fontSize = 14.sp, lineHeight = 20.sp, color = cs.onSurface)
    }
}
