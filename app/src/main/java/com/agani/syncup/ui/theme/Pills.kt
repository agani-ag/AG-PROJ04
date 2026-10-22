package com.agani.syncup.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One choice in a [PillTabs]: its label, and optionally an icon, a small green "something's on" dot,
 * or a count shown as a small blue chip (0 = none). */
data class PillTab(val label: String, val icon: ImageVector? = null, val dot: Boolean = false, val count: Int = 0)

/**
 * The v7 pill switch — a soft grey track with the chosen option on a white pill (Tools' tabs,
 * Calendar / To-do, Before / On a date…). Short animation only; no sliding layers.
 */
@Composable
fun PillTabs(tabs: List<PillTab>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(cs.surfaceContainer).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { i, tab ->
            val on = i == selected
            val bg by animateColorAsState(if (on) cs.surfaceContainerLowest else Color.Transparent, tween(150), label = "pill")
            Row(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).height(38.dp)
                    .then(if (on && cs.isLight) Modifier.shadow(1.dp, RoundedCornerShape(50), clip = false) else Modifier)
                    .clip(RoundedCornerShape(50)).background(bg)
                    .clickable(onClickLabel = tab.label) { onSelect(i) }.padding(horizontal = 6.dp),
            ) {
                if (tab.icon != null) {
                    Icon(tab.icon, null, tint = if (on) cs.onSurface else cs.onSurfaceVariant, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    tab.label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                    color = if (on) cs.onSurface else cs.onSurfaceVariant,
                )
                if (tab.dot) {
                    Spacer(Modifier.width(5.dp))
                    Box(Modifier.size(7.dp).clip(CircleShape).background(cs.success))
                }
                if (tab.count > 0) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.height(20.dp).clip(RoundedCornerShape(10.dp)).background(cs.primary).padding(horizontal = 6.dp),
                    ) {
                        Text(if (tab.count > 99) "99+" else "${tab.count}", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = cs.onPrimary)
                    }
                }
            }
        }
    }
}

/** [PillTabs] for plain labels. */
@JvmName("PillTabsLabels")
@Composable
fun PillTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) =
    PillTabs(labels.map { PillTab(it) }, selected, onSelect, modifier)
