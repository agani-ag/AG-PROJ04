package com.agani.syncup.decide

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import kotlin.math.PI
import kotlin.math.hypot

// The wheel itself, drawn the way the mockup's wheel_svg does: slices from the pointer on the right
// (3 o'clock), names along the radius ending near the rim, a white rim and hub with SPIN, and a dark
// pointer on the right edge pointing in. Sizes are the mockup's, scaled from its 290 px wheel.

private const val MOCK = 290f
private val HUB_TEXT = Color(0xFF6D28D9)
private val POINTER = Color(0xFF0F172A)

/**
 * The wheel at [size]. [rotation] (degrees, clockwise) is read only while drawing, so a spin redraws
 * without recomposing. [highlight] keeps one slice bright and dims the rest (the winner, a knock-out).
 */
@Composable
internal fun WheelCanvas(
    slices: List<WheelOption>,
    rotation: () -> Float,
    highlight: Int?,
    size: Dp,
    onHubTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val tap by rememberUpdatedState(onHubTap)
    val shadowTint = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f)
    val hubLine = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier
            .size(size)
            .semantics {
                role = Role.Button
                contentDescription = "Spin the wheel"
                onClick { tap(); true }
            }
            .pointerInput(Unit) {
                detectTapGestures { p ->
                    val c = this.size.width / 2f
                    val hub = 34f * this.size.width / MOCK
                    // The hub, with a little room around it for a finger.
                    if (hypot(p.x - c, p.y - c) <= hub * 1.35f) tap()
                }
            }
            .drawWithCache {
                val k = this.size.width / MOCK
                val c = this.size.width / 2f
                val r = c - 10f * k
                val hubR = 34f * k
                val n = slices.size
                val sweep = if (n > 0) 360f / n else 360f
                // Names: 15 px at the mockup's size, smaller when the slices get thin.
                val textEnd = r - 16f * k
                val arc = (2 * PI * textEnd / n.coerceAtLeast(1)).toFloat()
                val fontPx = minOf(15f * k, arc * .62f).coerceAtLeast(6f * k)
                val room = (textEnd - hubR - 8f * k).coerceAtLeast(1f)
                val style = TextStyle(
                    color = Color.White, fontSize = fontPx.toSp(), fontWeight = FontWeight.Bold,
                    shadow = Shadow(Color(0x40000000), Offset(0f, 1f * k), 2f * k),
                )
                val names = slices.map {
                    measurer.measure(
                        it.name, style, overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1,
                        constraints = Constraints(maxWidth = room.toInt()),
                    )
                }
                val spin = measurer.measure(
                    "SPIN",
                    TextStyle(color = HUB_TEXT, fontSize = (13f * k).toSp(), fontWeight = FontWeight.Bold, letterSpacing = (1f * k).toSp()),
                )
                val pointer = Path().apply {
                    moveTo(this@drawWithCache.size.width - 2f * k, c - 13f * k)
                    lineTo(this@drawWithCache.size.width - 2f * k, c + 13f * k)
                    lineTo(this@drawWithCache.size.width - 26f * k, c)
                    close()
                }
                val center = Offset(c, c)
                onDrawBehind {
                    val rot = rotation()
                    drawCircle(shadowTint, r + 4f * k, Offset(c, c + 4f * k))
                    for (i in 0 until n) {
                        val alpha = if (highlight != null && i != highlight) .35f else 1f
                        val col = wheelColor(slices[i].color)
                        if (n == 1) drawCircle(col, r, center, alpha = alpha)
                        else drawArc(col, rot + i * sweep, sweep, true, Offset(c - r, c - r), Size(r * 2, r * 2), alpha = alpha)
                    }
                    for (i in 0 until n) {
                        val alpha = if (highlight != null && i != highlight) .35f else 1f
                        val t = names[i]
                        rotate(rot + (i + .5f) * sweep, center) {
                            drawText(t, topLeft = Offset(c + textEnd - t.size.width, c - t.size.height / 2f), alpha = alpha)
                        }
                    }
                    drawCircle(Color.White, r, center, style = Stroke(4f * k))
                    drawCircle(Color.White, hubR, center)
                    drawCircle(hubLine, hubR, center, style = Stroke(2f * k))
                    drawText(spin, topLeft = Offset(c - spin.size.width / 2f, c - spin.size.height / 2f))
                    drawPath(pointer, POINTER)
                    drawPath(pointer, Color.White, style = Stroke(2.5f * k, join = StrokeJoin.Round))
                }
            },
    )
}
