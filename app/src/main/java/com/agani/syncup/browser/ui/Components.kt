package com.agani.syncup.browser.ui

import android.app.Activity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.vectorResource
import com.agani.syncup.R
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.agani.syncup.browser.Section
import com.agani.syncup.data.User
import com.agani.syncup.ui.theme.IncognitoScheme
import com.agani.syncup.ui.theme.workScheme
import kotlinx.coroutines.delay

// Shared building blocks of the v2 browser design (docs/mockup/v2/mockup.css).

/** Normal = app theme · Work = teal accent on the same surfaces · Incognito = fixed dark violet. */
@Composable
fun SectionTheme(section: Section, content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val scheme = when (section) {
        Section.NORMAL -> base
        Section.WORK -> workScheme(base)
        Section.INCOGNITO -> IncognitoScheme
    }
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes, content = content)
}

/** The SyncUp mark (the app's two-arrow logo): the icon of the SyncUp section — links SyncUp provides. */
val SyncUpMark: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.ic_syncup_mark)

@Composable
fun sectionIcon(s: Section): ImageVector = when (s) {
    Section.NORMAL -> Icons.Rounded.Public
    Section.WORK -> SyncUpMark
    Section.INCOGNITO -> Icons.Rounded.VisibilityOff
}

fun sectionName(s: Section) = when (s) {
    Section.NORMAL -> "Normal"
    Section.WORK -> "SyncUp"
    Section.INCOGNITO -> "Incognito"
}

fun setBarIcons(activity: Activity?, lightStatus: Boolean, lightNav: Boolean = lightStatus) {
    activity?.window?.let { w ->
        WindowCompat.getInsetsController(w, w.decorView).apply {
            isAppearanceLightStatusBars = lightStatus
            isAppearanceLightNavigationBars = lightNav
        }
    }
}

/** Rounded-full text-entry shape used for search (56), filter (48) and the address bar (44). */
@Composable
fun SyncPill(
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    focused: Boolean = false,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    startPadding: Dp = if (height >= 48.dp) 16.dp else 14.dp,
    endPadding: Dp = if (height >= 48.dp) 4.dp else 5.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (height >= 48.dp) 12.dp else 8.dp),
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(if (focused) cs.surfaceContainerLowest else container)
            .then(if (focused) Modifier.border(2.dp, cs.primary, shape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = startPadding, end = endPadding),
        content = content,
    )
}

/** 34–40 dp round icon button that lives inside a pill (reload, stop, clear, mic). */
@Composable
fun PillButton(icon: ImageVector, desc: String, size: Dp = 34.dp, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** The one filled control in the bottom bar: section glyph + name, in the section colour. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SectionKey(section: Section, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(cs.primary, tween(350), label = "key")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        modifier = Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .widthIn(min = 104.dp)
            .padding(start = 12.dp, end = 16.dp),
    ) {
        Icon(sectionIcon(section), null, tint = cs.onPrimary, modifier = Modifier.size(20.dp))
        Text(sectionName(section), color = cs.onPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = .1.sp, maxLines = 1)
    }
}

/** 48 dp icon button used in bars and app bars. */
@Composable
fun BarIcon(icon: ImageVector, desc: String, enabled: Boolean = true, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = tint.copy(alpha = if (enabled) tint.alpha else .38f))
    }
}

/**
 * 3 dp loading line. Tweens to the reported progress, fills to 100 % when loading ends and fades
 * out; pulses gently if progress stalls for more than a second.
 */
@Composable
fun ProgressLine(loading: Boolean, progress: Int, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    var visible by remember { mutableStateOf(false) }
    var stalled by remember { mutableStateOf(false) }
    LaunchedEffect(loading) {
        if (loading) visible = true else {
            delay(300)
            visible = false
        }
    }
    LaunchedEffect(progress, loading) {
        stalled = false
        if (loading) {
            delay(1000)
            stalled = true
        }
    }
    val target = when {
        !loading -> 1f
        else -> (progress.coerceIn(5, 100)) / 100f
    }
    val width by animateFloatAsState(target, tween(if (loading) 150 else 200), label = "progress")
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(200), label = "progressAlpha")
    val pulse = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by pulse.animateFloat(1f, .6f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pa")
    Box(modifier.fillMaxWidth().height(3.dp)) {
        Box(
            Modifier
                .fillMaxWidth(width)
                .fillMaxHeight()
                .alpha(alpha * if (stalled && loading) pulseAlpha else 1f)
                .clip(RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
                .background(cs.primary),
        )
    }
}

/** 40 dp rounded icon tile (container + glyph). */
@Composable
fun IconTile(
    icon: ImageVector,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    size: Dp = 40.dp,
) {
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(container), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = content, modifier = Modifier.size(if (size >= 44.dp) 24.dp else 22.dp))
    }
}

/** A 64 dp list row: leading slot, title + optional subtitle, trailing slot. No dividers. */
@Composable
fun TonalRow(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    selected: Boolean = false,
    minHeight: Dp = 64.dp,
    onClick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(20.dp)).background(cs.primaryContainer) else Modifier)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .heightIn(min = minHeight)
            .padding(start = if (selected) 12.dp else 20.dp, end = if (selected) 8.dp else 12.dp, top = 8.dp, bottom = 8.dp)
            .alpha(if (enabled) 1f else .45f),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(
                title, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = .1.sp,
                color = if (selected) cs.onPrimaryContainer else titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = .3.sp,
                    color = if (selected) cs.onPrimaryContainer.copy(alpha = .8f) else cs.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (trailing != null) Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/** Compact menu row for sheets: icon · label · optional trailing text. */
@Composable
fun MenuRow(
    icon: ImageVector,
    title: String,
    trailing: String? = null,
    tint: Color? = null,
    textColor: Color? = null,
    enabled: Boolean = true,
    badge: String? = null,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .height(52.dp)
            .padding(horizontal = 24.dp)
            .alpha(if (enabled) 1f else .4f),
    ) {
        Icon(icon, null, tint = tint ?: cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Text(title, fontSize = 15.sp, letterSpacing = .1.sp, color = textColor ?: cs.onSurface, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (badge != null) CountBadge(badge)
        if (trailing != null) Text(trailing, fontSize = 12.sp, letterSpacing = .3.sp, color = cs.onSurfaceVariant)
    }
}

/** Group header for link lists: 24 dp source mark · name · trailing count. */
@Composable
fun GroupHeader(name: String, count: String? = null, mark: ImageVector? = null, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxWidth().height(44.dp).padding(start = 20.dp, end = 20.dp, top = 12.dp),
    ) {
        if (mark != null) {
            Box(Modifier.size(24.dp).clip(RoundedCornerShape(8.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(mark, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(16.dp))
            }
        }
        Text(name, fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = .1.sp, color = cs.onSurface, modifier = Modifier.weight(1f))
        if (count != null) Text(count, fontSize = 12.sp, letterSpacing = .3.sp, color = cs.onSurfaceVariant)
    }
}

/** Sentence-case section header ("Shortcuts", "Search", "History"). */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = .1.sp, color = cs.onSurface, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(
                action, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = .4.sp, color = cs.primary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(horizontal = 6.dp, vertical = 6.dp),
            )
        }
    }
}

/** Small tonal status chip ("2 open", "SyncUp"). */
@Composable
fun StatusChip(text: String, icon: ImageVector? = null, container: Color = MaterialTheme.colorScheme.primaryContainer, content: Color = MaterialTheme.colorScheme.onPrimaryContainer) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.height(24.dp).clip(RoundedCornerShape(6.dp)).background(container).padding(horizontal = 8.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = content, modifier = Modifier.size(14.dp))
        Text(text, fontSize = 12.sp, letterSpacing = .3.sp, fontWeight = FontWeight.Medium, color = content, maxLines = 1)
    }
}

/** Red "N new" badge. */
@Composable
fun CountBadge(text: String) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.height(20.dp).clip(RoundedCornerShape(10.dp)).background(cs.error).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .3.sp, color = cs.onError)
    }
}

/** Initial-letter avatar with the optional unread dot. */
@Composable
fun Avatar(user: User, unread: Boolean = false, size: Dp = 40.dp, onClick: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(if (onClick != null) 48.dp else size).then(if (onClick != null) Modifier.clip(CircleShape).clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
            Text(user.name.take(1).uppercase(), color = cs.onPrimaryContainer, fontWeight = FontWeight.Medium, fontSize = (size.value * 0.4f).sp)
        }
        if (unread) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = if (onClick != null) 4.dp else 0.dp, end = if (onClick != null) 4.dp else 0.dp)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(cs.surface)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(cs.error),
            )
        }
    }
}

/** Centred empty / error state: glyph in a 96 dp tonal ring, title, one line, optional action. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String? = null,
    action: String? = null,
    actionIcon: ImageVector? = null,
    modifier: Modifier = Modifier,
    onAction: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(96.dp).clip(CircleShape).background(cs.surfaceContainerLow), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(title, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(4.dp))
            Text(body, fontSize = 14.sp, lineHeight = 20.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction, contentPadding = PaddingValues(horizontal = 24.dp)) {
                if (actionIcon != null) {
                    Icon(actionIcon, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(action)
            }
        }
    }
}

/** Card on the lowest-but-one tier (no border, radius 20). */
@Composable
fun TonalCard(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surfaceContainerLow, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        content = content,
    )
}

/** Scaffold for a sheet's content: title + optional subtitle, 24 dp gutters. */
@Composable
fun SheetHeader(title: String, subtitle: String? = null) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 12.dp)) {
        Text(title, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, letterSpacing = .15.sp, color = cs.onSurface)
        if (subtitle != null) Text(subtitle, fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant)
    }
}

@Composable
fun SheetDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}
