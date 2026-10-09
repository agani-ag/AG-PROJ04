package com.agani.syncup.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.BarIcon

/** Shared selection/paste UI for anything that browses [ClipItem]s — local folders and network shares alike. */

@Composable
internal fun SelectionBar(count: Int, onClose: () -> Unit, onSelectAll: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(64.dp).background(cs.primaryContainer).padding(start = 4.dp, end = 16.dp),
    ) {
        BarIcon(Icons.Rounded.Close, "Close selection", tint = cs.onPrimaryContainer, onClick = onClose)
        Text("$count selected", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = cs.onPrimaryContainer, modifier = Modifier.weight(1f))
        Text("All", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onPrimaryContainer, modifier = Modifier.clickable(onClick = onSelectAll).padding(8.dp))
    }
}

@Composable
internal fun PasteBar(count: Int, move: Boolean, busy: Boolean, onPaste: () -> Unit, onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(cs.secondaryContainer).clickable(enabled = !busy, onClick = onPaste).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(if (move) Icons.Rounded.ContentCut else Icons.Rounded.ContentCopy, null, tint = cs.onSecondaryContainer, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            if (busy) "Pasting…" else "$count item${if (count == 1) "" else "s"} ${if (move) "to move" else "copied"} · Paste here",
            fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSecondaryContainer, modifier = Modifier.weight(1f),
        )
        if (!busy) Text("Cancel", fontSize = 13.sp, color = cs.onSecondaryContainer, modifier = Modifier.clickable(onClick = onCancel).padding(6.dp))
    }
}

@Composable
internal fun ActionButton(icon: ImageVector, label: String, onClick: () -> Unit, danger: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick).padding(8.dp)) {
        Icon(icon, null, tint = if (danger) cs.error else cs.onSurface, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.5.sp, color = if (danger) cs.error else cs.onSurface)
    }
}
