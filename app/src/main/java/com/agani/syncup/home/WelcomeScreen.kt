package com.agani.syncup.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.R
import com.agani.syncup.ui.theme.CardShape
import com.agani.syncup.ui.theme.appCard

/**
 * The first open after a fresh install, before Home: sign in (a SyncUp password or the one a partner
 * sent — the same sign-in) or create an account. With sign-up off there's nothing to choose, so it's
 * the sign-in form itself ([signInForm]); until the server has said ([configLoaded]), a spinner. Skip
 * goes to Home; it never comes back once skipped or signed in (HomePrefs.welcomePending).
 */
@Composable
fun WelcomeScreen(
    signupEnabled: Boolean,
    configLoaded: Boolean,
    onSignIn: () -> Unit,
    onCreate: () -> Unit,
    onSkip: () -> Unit,
    signInForm: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    if (configLoaded && !signupEnabled) {
        Column(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp, end = 20.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onSkip) { Text("Skip", fontSize = 15.sp, fontWeight = FontWeight.Medium) }
            }
            Box(Modifier.weight(1f).padding(top = 24.dp)) { signInForm() }
        }
        return
    }
    Column(
        Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onSkip) { Text("Skip", fontSize = 15.sp, fontWeight = FontWeight.Medium) }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(top = 32.dp).size(88.dp).clip(RoundedCornerShape(28.dp)).background(cs.primary).align(Alignment.CenterHorizontally),
        ) {
            Image(painterResource(R.drawable.ic_sync), null, colorFilter = ColorFilter.tint(cs.onPrimary), modifier = Modifier.size(46.dp))
        }
        Text(
            "Welcome to SyncUp", fontSize = 25.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium, color = cs.onSurface,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 22.dp),
        )
        Text(
            "Your links, browser and everyday tools — in one app.", fontSize = 14.5.sp, lineHeight = 21.sp, color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 28.dp),
        )
        if (!configLoaded) {
            Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            }
        } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            WelcomeCard(
                Icons.AutoMirrored.Rounded.Login, ToolTint.BLUE, "Sign in",
                "With your SyncUp or partner password",
                highlight = true, onClick = onSignIn,
            )
            if (signupEnabled) WelcomeCard(Icons.Rounded.Add, ToolTint.VIOLET, "Create an account", "Free · takes a minute", onClick = onCreate)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun WelcomeCard(icon: ImageVector, tint: ToolTint, title: String, sub: String, highlight: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().appCard(CardShape)
            .border(if (highlight) 2.dp else 1.dp, if (highlight) cs.primary else Color.Transparent, CardShape)
            .clickable(onClick = onClick).padding(14.dp),
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(tint.tile), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint.icon, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
            Text(sub, fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}
