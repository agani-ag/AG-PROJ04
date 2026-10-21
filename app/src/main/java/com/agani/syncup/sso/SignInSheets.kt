package com.agani.syncup.sso

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.KnownLinks
import com.agani.syncup.browser.ui.SyncUpMark
import com.agani.syncup.data.ApiClient
import com.agani.syncup.data.SsoSite
import com.agani.syncup.data.TokenStore
import com.agani.syncup.data.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * SyncUp's own "Sign in to SyncHub" sheet — drawn by the app, so a website can't fake it or change
 * what it says. Shown once for all of SyncHub; after a yes, its pages sign in quietly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInConsentHost(user: User?) {
    val p = SyncUpSignIn.prompt ?: return
    val cs = MaterialTheme.colorScheme
    // The tools that use it, by their link names (e.g. "Hibiscus · Pocket Money · J6").
    val tools = KnownLinks.byId.values.filter { SyncUpSignIn.hostOf(it.url) != null }.map { it.title }.distinct()
    ModalBottomSheet(onDismissRequest = { p.answer(false) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(cs.primary), contentAlignment = Alignment.Center) {
                    Icon(SyncUpMark, null, tint = cs.onPrimary, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Sign in to ${p.client}", fontSize = 19.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                    Text(tools.joinToString(" · ").ifBlank { p.host }, fontSize = 13.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (user != null) {
                Spacer(Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerLowest)
                        .border(1.dp, cs.outlineVariant, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Box(Modifier.size(38.dp).clip(CircleShape).background(cs.primary), contentAlignment = Alignment.Center) {
                        Text(user.name.trim().take(1).uppercase().ifBlank { "?" }, color = cs.onPrimary, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(user.name, fontSize = 15.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(user.loginLabel, fontSize = 12.5.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("${p.client} will get", fontSize = 12.5.sp, color = cs.onSurfaceVariant)
            Shares(Icons.Rounded.Person, "Your name")
            Shares(Icons.Rounded.Mail, "Your email")
            Shares(Icons.Rounded.PhoneAndroid, "Your phone number")
            Spacer(Modifier.height(6.dp))
            Text("What you do in ${p.client} stays there — SyncUp never sees it.", fontSize = 12.5.sp, lineHeight = 18.sp, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = { p.answer(false) }, modifier = Modifier.weight(1f).height(46.dp)) { Text("Cancel") }
                Button(onClick = { p.answer(true) }, modifier = Modifier.weight(1f).height(46.dp)) { Text("Continue") }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Asked once. Disconnect any time in You → Signed-in sites.",
                fontSize = 12.5.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Shares(icon: ImageVector, text: String) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        Icon(icon, null, tint = cs.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 14.5.sp, color = cs.onSurface)
    }
}

/** You → Signed-in sites: where SyncUp signs this person in, what each gets, and Disconnect. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignedInSitesSheet(onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sites by remember { mutableStateOf<List<SsoSite>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val auth = remember { TokenStore(context.applicationContext).token()?.let { "Bearer $it" } }
    suspend fun load() {
        val got = withContext(Dispatchers.IO) { runCatching { ApiClient.service.ssoSites(auth ?: return@runCatching null) }.getOrNull() }
        failed = got == null
        sites = got?.sites ?: sites
    }
    LaunchedEffect(Unit) { load() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Signed-in sites", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
            Text("Sites SyncUp signs you in to, and what they get.", fontSize = 13.sp, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            val list = sites
            when {
                list == null && failed -> Text("Couldn't load — check your connection.", fontSize = 14.sp, color = cs.onSurfaceVariant)
                list == null -> Text("Loading…", fontSize = 14.sp, color = cs.onSurfaceVariant)
                list.isEmpty() -> Text(
                    "None yet. When a SyncHub tool asks you to Continue with SyncUp and you say yes, it shows here.",
                    fontSize = 14.sp, lineHeight = 20.sp, color = cs.onSurfaceVariant,
                )
                else -> list.forEach { site ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerLowest)
                            .border(1.dp, cs.outlineVariant, RoundedCornerShape(20.dp)).padding(16.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(cs.inverseSurface), contentAlignment = Alignment.Center) {
                                Text((site.name ?: "S").take(1), color = cs.inverseOnSurface, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(site.name ?: "SyncHub", fontSize = 15.5.sp, color = cs.onSurface)
                                if (site.tools.isNotEmpty()) Text(site.tools.joinToString(" · "), fontSize = 12.5.sp, color = cs.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            listOfNotNull(sinceText(site.since), "gets your " + sharesText(site.shares)).joinToString(" · "),
                            fontSize = 13.sp, lineHeight = 19.sp, color = cs.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    runCatching { ApiClient.service.ssoDisconnect(auth ?: return@runCatching false).success }.getOrDefault(false)
                                }
                                if (ok) SyncUpSignIn.disconnected(context)
                                load()
                                busy = false
                            }
                        }) { Text("Disconnect") }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Disconnect stops SyncUp signing you in there. Your account on that site and everything in it stay — sign in there with a password or a link.",
                fontSize = 12.5.sp, lineHeight = 18.sp, color = cs.onSurfaceVariant,
            )
        }
    }
}

private fun sinceText(iso: String?): String? = runCatching {
    "Since " + OffsetDateTime.parse(iso).format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
}.getOrNull()

private fun sharesText(shares: List<String>): String {
    val words = shares.map { mapOf("name" to "name", "email" to "email", "phone" to "phone number")[it] ?: it }
    return when (words.size) {
        0 -> "nothing"
        1 -> words[0]
        else -> words.dropLast(1).joinToString(", ") + " and " + words.last()
    }
}
