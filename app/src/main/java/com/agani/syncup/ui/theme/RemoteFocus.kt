package com.agani.syncup.ui.theme

import android.view.View
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.onFocusedBoundsChanged
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * Where the remote is, on every page (user, 19 Nov 2026: "with the remote I can't find where I am, except on the
 * TV home — I want proper highlights in Light and Dark"). Whatever a remote or a keyboard has selected gets a thick
 * ring in the accent colour, a little outside it — buttons, rows, tabs, switches, text boxes, everything — in Light
 * and Dark. Touch never shows it.
 *
 * How: the window watches which element has focus ([onFocusedBoundsChanged], which every clickable / focusable
 * reports to) and draws the ring over everything, so even Material buttons get it (they draw their own highlight
 * and ignore [LocalIndication]). Pop-ups (sheets, dialogs, menus) are separate windows the watcher can't see, so
 * there our own clickables draw the ring themselves ([FocusRingIndication]) and Material buttons get a much stronger
 * selected tint on a TV.
 *
 * A screen with its own selected look (the TV main page) turns the window's ring off with [OwnFocusLook].
 *
 * TV only (user, 26 Nov 2026: "the TV's highlight is too bold on the phone — TV mode only"): on a phone or a tablet
 * nothing changes — Android's own look, even with a keyboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteFocus(tv: Boolean, content: @Composable () -> Unit) {
    if (!tv) {
        content()
        return
    }
    val ring = MaterialTheme.colorScheme.primary
    val view = LocalView.current
    val outer = LocalRingHost.current
    // Already inside one in this window: that one draws the ring.
    val host = if (outer?.view === view) outer else remember(view) { RingHost(view) }
    val indication = remember(ring) { FocusRingIndication(ring, ring.copy(alpha = .14f), ripple()) }
    val ripples = if (tv) {
        RippleConfiguration(
            color = ring,
            rippleAlpha = RippleAlpha(draggedAlpha = .16f, focusedAlpha = .34f, hoveredAlpha = .08f, pressedAlpha = .14f),
        )
    } else {
        LocalRippleConfiguration.current
    }
    CompositionLocalProvider(
        LocalIndication provides indication,
        LocalRippleConfiguration provides ripples,
        LocalRingHost provides host,
    ) {
        if (host === outer) content() else RingLayer(host, ring, content = content)
    }
}

/** Put on a screen that shows the selected element its own way: no window ring while it's showing. */
@Composable
fun OwnFocusLook() {
    val host = LocalRingHost.current ?: return
    DisposableEffect(host) {
        host.ownLook++
        onDispose { host.ownLook-- }
    }
}

/** One per window: where the selected element is (in the window's coordinates), and whether to ring it. */
private class RingHost(val view: View) {
    var focused by mutableStateOf<Rect?>(null)
    var ownLook by mutableIntStateOf(0)

    /** The wide row → went into (see [rowButtons]), while the remote is on one of its buttons. */
    var enteredRow: Rect? = null
}

/**
 * Buttons inside a row (its ⋮, a play or a delete button): the remote never goes into something it has selected,
 * so they couldn't be reached at all. On a wide row (half the window or more — nothing beside it to go to), → goes
 * into the row's own buttons, and ← from one of them comes back to the row. Runs only when the selected element
 * didn't use the key itself (a text box moving its cursor, a slider).
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun rowButtons(host: RingHost, focus: FocusManager, event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val f = host.focused ?: return false
    return when (event.key) {
        Key.DirectionRight -> {
            if (host.view.width <= 0 || f.width < host.view.width * .5f) return false
            focus.moveFocus(FocusDirection.Enter).also { if (it) host.enteredRow = f }
        }
        Key.DirectionLeft -> {
            val row = host.enteredRow ?: return false
            host.enteredRow = null
            row.contains(f.center) && focus.moveFocus(FocusDirection.Exit)
        }
        else -> false
    }
}

private val LocalRingHost = staticCompositionLocalOf<RingHost?> { null }

/**
 * The same ring inside a pop-up (its own window, which the page's ring can't reach): wraps a sheet's, a menu's or a
 * dialog part's content — see RemotePopups.kt. [keepInside]: the ring stays within this area (a sheet or a menu,
 * whose edges cut off anything outside), rather than only within the window (a dialog's button).
 */
@Composable
internal fun RingArea(keepInside: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // Not on a TV (no ring host from RemoteFocus): just the content, as Android draws it.
    if (LocalRingHost.current == null) {
        Box(modifier, propagateMinConstraints = true) { content() }
        return
    }
    val view = LocalView.current
    val host = remember(view) { RingHost(view) }
    CompositionLocalProvider(LocalRingHost provides host) {
        RingLayer(host, MaterialTheme.colorScheme.primary, keepInside, modifier, content)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RingLayer(
    host: RingHost,
    ring: Color,
    keepInside: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val input = LocalInputModeManager.current
    val window = LocalWindowInfo.current
    val focus = LocalFocusManager.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        propagateMinConstraints = true,
        modifier = modifier
            .onKeyEvent { rowButtons(host, focus, it) }
            .onGloballyPositioned { origin = it.positionInRoot() }
            .onFocusedBoundsChanged { c -> host.focused = c?.takeIf { it.isAttached }?.boundsInRoot() }
            .drawWithContent {
                drawContent()
                val f = host.focused ?: return@drawWithContent
                if (host.ownLook > 0 || input.inputMode != InputMode.Keyboard) return@drawWithContent
                // A pop-up over this window has the remote now: only the pop-up shows where it is.
                if (!window.isWindowFocused) return@drawWithContent
                if (f.width <= 0f || f.height <= 0f) return@drawWithContent
                // Something filling the screen (a web page, the video) needs no ring round the whole screen.
                val screen = host.view.resources.displayMetrics
                if (f.width >= screen.widthPixels * .9f && f.height >= screen.heightPixels * .6f) return@drawWithContent
                val w = 3.dp.toPx()
                val out = 2.dp.toPx() + w / 2 // a small gap, so it shows even round a button of the same colour
                // Kept on screen (or in the sheet / menu), so an edge-to-edge row still gets all four sides.
                val box = if (keepInside) Rect(origin, size) else Rect(0f, 0f, host.view.width.toFloat(), host.view.height.toFloat())
                val b = f.inflate(out)
                val r = Rect(
                    max(b.left, box.left + w / 2), max(b.top, box.top + w / 2),
                    min(b.right, box.right - w / 2), min(b.bottom, box.bottom - w / 2),
                ).translate(-origin)
                // Buttons, chips and round icons (48 dp tall or less) get a pill / circle; taller things (rows,
                // text boxes, tiles) rounded corners like the cards'.
                val short = min(f.width, f.height)
                val radius = (if (short <= 50.dp.toPx()) short / 2 else 14.dp.toPx()) + out
                // A soft glow round the ring: the remote's place never looks like a "chosen" outline (Appearance's
                // Light / Dark cards, chips), which are flat lines in the same colour.
                val glow = 6.dp.toPx()
                val g = r.inflate(w / 2 + glow / 2)
                drawRoundRect(ring.copy(alpha = .28f), g.topLeft, g.size, CornerRadius(radius + w / 2 + glow / 2), style = Stroke(glow))
                drawRoundRect(ring, r.topLeft, r.size, CornerRadius(radius), style = Stroke(w))
            },
    ) { content() }
}

/** The usual tap ripple ([base]), plus the selected tint — and the ring too where the window can't draw it. */
private data class FocusRingIndication(
    val ring: Color,
    val fill: Color,
    val base: IndicationNodeFactory,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        FocusRingNode(interactionSource, ring, fill, base.create(interactionSource))
}

private class FocusRingNode(
    private val source: InteractionSource,
    private val ring: Color,
    private val fill: Color,
    base: DelegatableNode,
) : DelegatingNode(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private var selected = false

    init {
        delegate(base)
    }

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { i ->
                when (i) {
                    is FocusInteraction.Focus -> if (!selected) {
                        selected = true
                        invalidateDraw()
                    }
                    is FocusInteraction.Unfocus -> if (selected) {
                        selected = false
                        invalidateDraw()
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (!selected) return
        val w = 3.dp.toPx()
        val r = CornerRadius(12.dp.toPx())
        drawRoundRect(fill, cornerRadius = r)
        // In this window the window's ring layer already rings it.
        val host = currentValueOf(LocalRingHost)
        if (host != null && host.view === currentValueOf(LocalView) && host.ownLook == 0) return
        // Inside the edges, so a clipped (rounded) row still shows all of it but its corners. A small square
        // thing (a round icon button) gets a round ring.
        val round = size.width <= 64.dp.toPx() && size.height <= 64.dp.toPx()
        drawRoundRect(
            ring, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
            cornerRadius = if (round) CornerRadius(min(size.width, size.height) / 2) else r, style = Stroke(w),
        )
    }
}
