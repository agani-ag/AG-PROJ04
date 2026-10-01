package com.agani.syncup.browser

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.browser.ui.SectionKey
import com.agani.syncup.browser.ui.chrome
import com.agani.syncup.browser.ui.sectionName

/**
 * Back · Forward · section key · Tabs · Menu — the same five buttons, evenly spaced, on every page.
 * Back / Forward stay in place and are greyed out when there's nowhere to go.
 */
@Composable
internal fun BottomBar(
    divider: Boolean,
    tab: BrowserTab,
    section: Section,
    tabCount: Int,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onSection: () -> Unit,
    onSectionLong: () -> Unit,
    onTabs: () -> Unit,
    onMenu: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(cs.chrome)) {
        if (divider) HorizontalDivider(thickness = 1.dp, color = cs.outlineVariant)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceAround,
            modifier = Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp),
        ) {
            BarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", enabled = tab.canGoBack || !tab.isHome, onClick = onBack)
            BarIcon(Icons.AutoMirrored.Rounded.ArrowForward, "Forward", enabled = tab.canGoForward, onClick = onForward)
            SectionKey(section, onClick = onSection, onLongClick = onSectionLong)
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onTabs)
                    .semantics { contentDescription = "$tabCount tab${if (tabCount == 1) "" else "s"} — ${sectionName(section)}" },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(22.dp).border(2.dp, cs.onSurface, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    // The count rolls when tabs open or close.
                    AnimatedContent(
                        targetState = tabCount,
                        transitionSpec = {
                            val up = targetState > initialState
                            (slideInVertically(tween(150)) { if (up) it else -it } + fadeIn(tween(150))) togetherWith
                                (slideOutVertically(tween(150)) { if (up) -it else it } + fadeOut(tween(150)))
                        },
                        label = "tabCount",
                    ) { n ->
                        Text(if (n > 99) "∞" else n.toString(), fontSize = 11.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                    }
                }
            }
            BarIcon(Icons.Rounded.MoreVert, "Menu — ${sectionName(section)}", onClick = onMenu)
        }
    }
}
