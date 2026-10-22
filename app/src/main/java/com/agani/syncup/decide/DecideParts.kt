package com.agani.syncup.decide

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.music.ToolTint
import com.agani.syncup.music.ToolTopBar

// Small parts the Decide pages share (docs/mockup/syncup-everyday-tools.html): the page frame, the
// filled text field, the ⋮ menu with "Sound & buzz", the group chip and the line icons Material
// doesn't have.

/** The group's colour: "Together" badges, the "In a group" card and the "N phones" chip. */
internal val DecideGroupTint = ToolTint.INDIGO

/** "1 phone" / "4 phones". */
internal fun phonesText(n: Int): String = if (n == 1) "1 phone" else "$n phones"

/** A Decide page: the top bar, then [content] scrolling, with 16 dp sides. */
@Composable
internal fun DecidePage(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        ToolTopBar(title, onBack, actions = actions)
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            content = content,
        )
    }
}

/**
 * The v7 field: a soft grey box (50 dp, 16 dp corners) that turns white with a blue edge while
 * typing, and an optional small [label] above it.
 */
@Composable
internal fun DecideField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    onDone: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Column(modifier) {
        if (label != null) {
            Text(label, fontSize = 12.5.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 6.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 15.sp, color = cs.onSurface),
            cursorBrush = SolidColor(cs.primary),
            keyboardOptions = KeyboardOptions(
                capitalization = if (keyboardType == KeyboardType.Text) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
                keyboardType = keyboardType,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = {
                focus.clearFocus()
                onDone?.invoke()
            }),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            decorationBox = { field ->
                Box(
                    Modifier.fillMaxWidth().height(50.dp).clip(shape)
                        .background(if (focused) cs.surfaceContainerLowest else cs.surfaceContainer)
                        .then(if (focused) Modifier.border(2.dp, cs.primary, shape) else Modifier)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, fontSize = 15.sp, color = cs.onSurfaceVariant, maxLines = 1)
                    field()
                }
            },
        )
    }
}

/**
 * The ⋮ menu of a Decide tool: the "Sound & buzz" switch (one setting for all of Decide), then any
 * [extra] rows — each gets a `close` to call when it's tapped.
 */
@Composable
internal fun DecideMoreMenu(extra: @Composable ColumnScope.(close: () -> Unit) -> Unit = {}) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    var sound by remember { mutableStateOf(DecideFx.enabled(context)) }
    Box {
        BarIcon(Icons.Rounded.MoreVert, "More", onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = RoundedCornerShape(18.dp), containerColor = cs.surfaceContainerLowest) {
            DropdownMenuItem(
                text = { Text("Sound & buzz") },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.VolumeUp, null) },
                trailingIcon = { Switch(checked = sound, onCheckedChange = null, modifier = Modifier.scale(.8f)) },
                onClick = {
                    sound = !sound
                    DecideFx.setEnabled(context, sound)
                },
            )
            extra { open = false }
        }
    }
}

/** The indigo "4 phones" chip in a tool's top bar while this phone is in a group. */
@Composable
internal fun DecideGroupChip(phones: Int, modifier: Modifier = Modifier) {
    val tint = DecideGroupTint
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.height(30.dp).clip(RoundedCornerShape(15.dp)).background(tint.tile).padding(horizontal = 12.dp),
    ) {
        Icon(Icons.Rounded.Groups, null, tint = tint.icon, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(phonesText(phones), fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = tint.icon, maxLines = 1)
    }
}

/** Line icons from the design (24 × 24, 2-wide round strokes) for the ones Material doesn't have. */
internal object DecideIcons {
    /** Decide's own tile: a die. */
    val Dice: ImageVector by lazy {
        lineIcon(
            "Dice", "M8 4h8a4 4 0 0 1 4 4v8a4 4 0 0 1-4 4H8a4 4 0 0 1-4-4V8a4 4 0 0 1 4-4z",
            dot(9f, 9f), dot(15f, 15f), dot(15f, 9f), dot(9f, 15f),
        )
    }

    /** A coin: a disc with its inner ring. */
    val Coin: ImageVector by lazy {
        lineIcon("Coin", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0", "M6.5 12a5.5 5.5 0 1 0 11 0a5.5 5.5 0 1 0-11 0")
    }

    /** A wheel with its spokes. */
    val Wheel: ImageVector by lazy {
        lineIcon("Wheel", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0", "M12 3v18M3 12h18M5.6 5.6l12.8 12.8M18.4 5.6 5.6 18.4")
    }

    /** A phone being shaken. */
    val Shake: ImageVector by lazy {
        lineIcon("Shake", "M10 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M4 8v8M20 8v8")
    }

    /** A tiny filled dot, drawn as a stroked circle (the stroke fills it). */
    private fun dot(x: Float, y: Float) = "M${x - .3f} ${y}a.3 .3 0 1 0 .6 0a.3 .3 0 1 0-.6 0"

    private fun lineIcon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach {
                addPath(
                    addPathNodes(it), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}
