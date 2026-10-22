package com.agani.syncup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Full-screen announcement, styled like [ForceUpdateScreen]. Shown when the server config marks
 * the announcement as full-screen. When [blocking] is true there is no dismiss button (the screen
 * stays until the admin turns the announcement off — used for maintenance/outage notices);
 * otherwise a "Got it" button lets the user continue.
 */
@Composable
fun AnnouncementScreen(
    title: String,
    message: String,
    blocking: Boolean,
    onDismiss: () -> Unit,
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NoticeIcon(Icons.Rounded.Campaign)
            if (title.isNotBlank()) {
                Text(
                    title,
                    fontSize = 24.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 20.dp),
                )
            }
            if (message.isNotBlank()) {
                Text(
                    message,
                    fontSize = 14.5.sp,
                    lineHeight = 21.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (!blocking) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .padding(top = 28.dp)
                        .fillMaxWidth()
                        .widthIn(max = 420.dp)
                        .height(48.dp),
                ) {
                    Text("Got it", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** The big soft-blue circle at the top of a full-screen notice (and Update required). */
@Composable
internal fun NoticeIcon(icon: ImageVector) {
    Box(
        Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
    }
}
