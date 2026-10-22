package com.agani.syncup.forest

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.music.SectionHead
import com.agani.syncup.music.ToolTopBar
import com.agani.syncup.ui.theme.appCard
import com.agani.syncup.ui.theme.isLight

/** Forest → This month: the month as days (what came, what's coming, the month's end), then what's coming up. */
@Composable
internal fun ForestMonth(now: ForestNow, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val days = remember(now.date, now.day) { ForestRules.monthDays(now) }
    val coming = remember(now.date, now.day) { ForestRules.comingUp(now) }
    Column(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        ToolTopBar("This month", onBack, subtitle = ForestRules.monthTitle(now))
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            SectionHead(ForestRules.MONTHS[now.date.month - 1])
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (w in "MTWTFSS") {
                    Text("$w", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                }
            }
            // Monday first: blanks before the 1st.
            val cells: List<ForestMonthDay?> = List(days.first().date.weekday - 1) { null } + days
            for (week in cells.chunked(7)) {
                Row(Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (i in 0 until 7) {
                        val d = week.getOrNull(i)
                        if (d == null) Spacer(Modifier.weight(1f)) else DayCell(d, now.monthLength, Modifier.weight(1f))
                    }
                }
            }
            SectionHead("Coming up")
            Column(Modifier.fillMaxWidth().appCard().padding(vertical = 6.dp)) {
                for (c in coming) ComingRow(c, now.monthLength)
            }
        }
    }
}

@Composable
private fun DayCell(d: ForestMonthDay, n: Int, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val light = cs.isLight
    val shape = RoundedCornerShape(12.dp)
    val bg = when (d.phase) {
        ForestPhase.PICNIC -> if (light) Color(0xFFDCE7FF) else Color(0xFF1A2540)
        ForestPhase.DRY, ForestPhase.DESERT -> if (light) Color(0xFFF3E3C0) else Color(0xFF3A2E18)
        ForestPhase.STORM -> if (light) Color(0xFFD5DBE3) else Color(0xFF232A35)
        ForestPhase.GREEN -> Color.Unspecified
    }
    Box(
        modifier.aspectRatio(1f / 1.1f).alpha(if (d.past) .55f else 1f).appCard(shape, bg)
            .then(if (d.today) Modifier.border(2.dp, if (light) Color(0xFF2F7A3A) else Color(0xFF74C77F), shape) else Modifier),
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            DayIcon(d.day, n, Modifier.size(22.dp))
            Text(
                "${d.day}", fontSize = 11.sp, lineHeight = 13.sp,
                fontWeight = if (d.today) FontWeight.SemiBold else FontWeight.Normal,
                color = if (d.today) cs.onSurface else cs.onSurfaceVariant,
            )
        }
        // Sundays: the friendly T. rex (and the dragon) visit.
        if (d.sunday) SpriteIcon("a-trex", Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 2.dp).size(12.dp))
    }
}

@Composable
private fun ComingRow(c: ForestComing, n: Int) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp)) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(cs.surfaceContainer), contentAlignment = Alignment.Center) {
            val m = Modifier.size(26.dp)
            when (c.icon) {
                "🧺" -> Drawn(DrawnIcon.PICNIC, m)
                "🍂" -> Drawn(DrawnIcon.DRY, m)
                "🏜" -> Drawn(DrawnIcon.DESERT, m)
                "⛈" -> Drawn(DrawnIcon.STORM, m)
                "🌈" -> Drawn(DrawnIcon.RAINBOW, m)
                "🦖" -> SpriteIcon("a-trex", m)
                else -> c.label.removePrefix("Day ").toIntOrNull()?.let { DayIcon(it, n, m) } ?: Text(c.icon, fontSize = 18.sp)
            }
        }
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = cs.onSurface)) { append(c.label) }
                append(" · ")
                append(c.text)
            },
            fontSize = 13.5.sp, lineHeight = 19.sp, color = cs.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
    }
}

/** What each green day brings, as its picture (days 1 and 2 have drawn ones: a rainbow, a butterfly). */
private val ARRIVAL_SPRITES: Map<Int, String> = mapOf(
    3 to "a-sparrow", 4 to "a-squirrel", 5 to "a-rabbit", 6 to "a-owl", 7 to "a-frog", 8 to "a-dragonfly", 9 to "o-mushrooms",
    10 to "a-peacock", 11 to "a-nest-eggs", 12 to "a-duck", 13 to "a-nest-chicks", 14 to "a-deer", 15 to "a-tortoise", 16 to "a-monkey",
    17 to "o-beehive", 18 to "o-bench", 19 to "a-kingfisher", 20 to "a-parrot", 21 to "o-stream", 22 to "a-mongoose", 23 to "a-fawn",
    24 to "a-elephant", 25 to "a-hornbill", 26 to "a-peahen", 27 to "a-tiger",
)

/** A day's picture: its arrival, or the month's end (picnic, dry spell, desert, storm). */
@Composable
internal fun DayIcon(day: Int, n: Int, modifier: Modifier) {
    when (ForestRules.phaseOf(day, n)) {
        ForestPhase.PICNIC -> Drawn(DrawnIcon.PICNIC, modifier)
        ForestPhase.DRY -> SpriteIcon("o-tornado", modifier)
        ForestPhase.DESERT -> SpriteIcon("o-volcano", modifier)
        ForestPhase.STORM -> Drawn(DrawnIcon.STORM, modifier)
        ForestPhase.GREEN -> when (day) {
            1 -> Drawn(DrawnIcon.RAINBOW, modifier)
            2 -> Drawn(DrawnIcon.BUTTERFLY, modifier)
            else -> ARRIVAL_SPRITES[day]?.let { SpriteIcon(it, modifier) } ?: Drawn(DrawnIcon.LEAF, modifier)
        }
    }
}

internal enum class DrawnIcon { RAINBOW, BUTTERFLY, PICNIC, DRY, DESERT, STORM, LEAF }

/** Small drawn pictures for the days without a sprite, on a 24-unit grid. */
@Composable
internal fun Drawn(icon: DrawnIcon, modifier: Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / 24f
        val ox = (size.width - 24f * u) / 2
        val oy = (size.height - 24f * u) / 2
        fun o(x: Float, y: Float) = Offset(ox + x * u, oy + y * u)
        when (icon) {
            DrawnIcon.RAINBOW -> rainbow(::o, u)
            DrawnIcon.BUTTERFLY -> butterfly(::o, u)
            DrawnIcon.PICNIC -> picnic(::o, u)
            DrawnIcon.DRY -> leaf(::o, u, Color(0xFFD97706), Color(0xFF92400E))
            DrawnIcon.DESERT -> desert(::o, u)
            DrawnIcon.STORM -> storm(::o, u)
            DrawnIcon.LEAF -> leaf(::o, u, Color(0xFF65A30D), Color(0xFF3F6212))
        }
    }
}

private fun path(o: (Float, Float) -> Offset, vararg pts: Float): Path = Path().apply {
    val a = o(pts[0], pts[1])
    moveTo(a.x, a.y)
    var i = 2
    while (i < pts.size) {
        val p = o(pts[i], pts[i + 1])
        lineTo(p.x, p.y)
        i += 2
    }
    close()
}

private fun DrawScope.rainbow(o: (Float, Float) -> Offset, u: Float) {
    val bands = listOf(Color(0xFFEF4444), Color(0xFFF59E0B), Color(0xFF22C55E), Color(0xFF3B82F6))
    bands.forEachIndexed { i, c ->
        val r = (10f - i * 2.1f) * u
        val ctr = o(12f, 18f)
        drawArc(c, 180f, 180f, false, Offset(ctr.x - r, ctr.y - r), Size(2 * r, 2 * r), style = Stroke(2f * u, cap = StrokeCap.Round))
    }
    // A little cloud at each foot.
    for ((x, y) in listOf(3f to 18.5f, 21f to 18.5f)) {
        drawCircle(Color(0xFFF1F5F9), 2.2f * u, o(x, y))
        drawCircle(Color(0xFFE2E8F0), 1.6f * u, o(x + 1.6f * (if (x < 12) 1 else -1), y + .6f))
    }
}

private fun DrawScope.butterfly(o: (Float, Float) -> Offset, u: Float) {
    val wing = Color(0xFFF59E0B)
    val low = Color(0xFFEA580C)
    val edge = Color(0xFF3F2A14)
    fun oval(cx: Float, cy: Float, rx: Float, ry: Float, deg: Float, c: Color) {
        val ctr = o(cx, cy)
        rotate(deg, ctr) { drawOval(c, Offset(ctr.x - rx * u, ctr.y - ry * u), Size(2 * rx * u, 2 * ry * u)) }
    }
    oval(7.6f, 9.6f, 4.6f, 3.6f, -25f, wing)
    oval(16.4f, 9.6f, 4.6f, 3.6f, 25f, wing)
    oval(8.8f, 15.4f, 3.2f, 2.6f, 20f, low)
    oval(15.2f, 15.4f, 3.2f, 2.6f, -20f, low)
    drawCircle(Color.White.copy(alpha = .85f), .7f * u, o(5.4f, 8.6f))
    drawCircle(Color.White.copy(alpha = .85f), .7f * u, o(18.6f, 8.6f))
    drawLine(edge, o(12f, 7f), o(12f, 18.5f), 1.8f * u, StrokeCap.Round)
    drawLine(edge, o(12f, 7.4f), o(9.8f, 3.6f), .8f * u, StrokeCap.Round)
    drawLine(edge, o(12f, 7.4f), o(14.2f, 3.6f), .8f * u, StrokeCap.Round)
}

private fun DrawScope.picnic(o: (Float, Float) -> Offset, u: Float) {
    val wicker = Color(0xFFC2833A)
    val dark = Color(0xFF8A5523)
    // The handle.
    val h0 = o(7f, 5.5f)
    drawArc(dark, 180f, 180f, false, h0, Size(10f * u, 13f * u), style = Stroke(1.6f * u))
    // The basket.
    drawPath(path(o, 4.5f, 12f, 19.5f, 12f, 17.6f, 21f, 6.4f, 21f), wicker)
    drawLine(dark, o(5.3f, 15.5f), o(18.7f, 15.5f), .8f * u)
    drawLine(dark, o(5.9f, 18.3f), o(18.1f, 18.3f), .8f * u)
    // The checked cloth on top.
    drawPath(path(o, 4f, 10.6f, 20f, 10.6f, 20f, 12.8f, 4f, 12.8f), Color(0xFFEF4444))
    for (i in 0 until 4) drawRect(Color.White, o(5f + i * 4f, 10.6f), Size(2f * u, 2.2f * u))
}

private fun DrawScope.leaf(o: (Float, Float) -> Offset, u: Float, fill: Color, rib: Color) {
    val p = Path().apply {
        val a = o(5f, 19f)
        moveTo(a.x, a.y)
        val c1 = o(4f, 8f)
        val e = o(19f, 5f)
        quadraticTo(c1.x, c1.y, e.x, e.y)
        val c2 = o(16f, 20f)
        quadraticTo(c2.x, c2.y, a.x, a.y)
        close()
    }
    drawPath(p, fill)
    drawLine(rib, o(3f, 21f), o(16.5f, 7.5f), 1.1f * u, StrokeCap.Round)
}

private fun DrawScope.desert(o: (Float, Float) -> Offset, u: Float) {
    drawCircle(Color(0xFFF59E0B), 3.6f * u, o(16.5f, 7.5f))
    val back = Path().apply {
        val s = o(1f, 18f); moveTo(s.x, s.y)
        val c = o(7f, 11.5f); val e = o(13f, 16f); quadraticTo(c.x, c.y, e.x, e.y)
        val c2 = o(18f, 12.5f); val e2 = o(23f, 15.5f); quadraticTo(c2.x, c2.y, e2.x, e2.y)
        val b = o(23f, 22f); lineTo(b.x, b.y)
        val b2 = o(1f, 22f); lineTo(b2.x, b2.y)
        close()
    }
    drawPath(back, Color(0xFFE9B872))
    val front = Path().apply {
        val s = o(1f, 21f); moveTo(s.x, s.y)
        val c = o(10f, 15.5f); val e = o(23f, 20f); quadraticTo(c.x, c.y, e.x, e.y)
        val b = o(23f, 22f); lineTo(b.x, b.y)
        val b2 = o(1f, 22f); lineTo(b2.x, b2.y)
        close()
    }
    drawPath(front, Color(0xFFD69E4F))
}

private fun DrawScope.storm(o: (Float, Float) -> Offset, u: Float) {
    val cloud = Color(0xFF64748B)
    drawCircle(cloud, 4f * u, o(8.5f, 10f))
    drawCircle(cloud, 5f * u, o(13.5f, 8.5f))
    drawCircle(cloud, 3.6f * u, o(18f, 11f))
    drawRect(cloud, o(6f, 10.5f), Size(14f * u, 4f * u))
    drawPath(path(o, 13.6f, 13f, 9.6f, 18.6f, 12.4f, 18.6f, 10.8f, 23f, 16f, 16.6f, 13.2f, 16.6f, 15f, 13f), Color(0xFFFACC15))
    val rain = Color(0xFF60A5FA)
    drawLine(rain, o(7f, 17f), o(6f, 20f), u, StrokeCap.Round)
    drawLine(rain, o(18f, 17f), o(17f, 20f), u, StrokeCap.Round)
}
