package com.agani.syncup.browser

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.agani.syncup.MainActivity
import com.agani.syncup.ui.theme.appCard

/**
 * SyncUp as the phone's default browser: links tapped in other apps (WhatsApp, Gmail, Messages…) open
 * in a new Normal tab — never Incognito or a SyncUp tab. Android 10+ asks with its own dialog
 * (RoleManager); older phones open the Default apps page instead.
 */
object DefaultBrowser {
    private const val PREFS = "default_browser"
    private const val KEY_NUDGE_DONE = "nudge_done"

    /** Is SyncUp the browser links from other apps go to? */
    fun isDefault(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = context.getSystemService(RoleManager::class.java) ?: return false
            return roles.isRoleAvailable(RoleManager.ROLE_BROWSER) && roles.isRoleHeld(RoleManager.ROLE_BROWSER)
        }
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        val handler = context.packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
        return handler?.activityInfo?.packageName == context.packageName
    }

    /** What "Make default" opens: Android's own "Set SyncUp as your default browser app?" (10+), else Default apps. */
    fun requestIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = context.getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_BROWSER) && !roles.isRoleHeld(RoleManager.ROLE_BROWSER)) {
                return roles.createRequestRoleIntent(RoleManager.ROLE_BROWSER)
            }
        }
        return settingsIntent()
    }

    /** Android's Default apps page ("Change" once SyncUp already is the default). */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)

    /** The Browse page's "Open links in SyncUp?" card: shown until either button is tapped, once. */
    fun nudgeDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_NUDGE_DONE, false)

    fun setNudgeDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_NUDGE_DONE, true).apply()
    }
}

/** Whether SyncUp is the default browser, checked again whenever the screen comes back (after Android's dialog or Settings). */
@Composable
internal fun rememberIsDefaultBrowser(): Boolean {
    val context = LocalContext.current
    var isDefault by remember { mutableStateOf(DefaultBrowser.isDefault(context)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) isDefault = DefaultBrowser.isDefault(context) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return isDefault
}

/** Opens Android's "default browser" choice; returns the launcher's trigger. */
@Composable
internal fun rememberMakeDefaultBrowser(onDone: () -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onDone() }
    return {
        val intent = DefaultBrowser.requestIntent(context)
        runCatching { launcher.launch(intent) }.onFailure { runCatching { launcher.launch(DefaultBrowser.settingsIntent()) } }
    }
}

/**
 * The Browse start page's one-time card: "Open links in SyncUp?" with Make default / Not now. Gone for
 * good after either button (Settings → Browser still has it), and never shown once SyncUp is the default.
 */
@Composable
internal fun DefaultBrowserNudge(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val isDefault = rememberIsDefaultBrowser()
    var done by remember { mutableStateOf(DefaultBrowser.nudgeDone(context)) }
    val makeDefault = rememberMakeDefaultBrowser()
    if (isDefault || done) return
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Top, modifier = modifier.fillMaxWidth().appCard(RoundedCornerShape(24.dp)).padding(16.dp)) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Link, null, tint = cs.onPrimaryContainer)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Open links in SyncUp?", fontSize = 15.5.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
            Text(
                "Links you tap in WhatsApp, Gmail and other apps can open here, in a Normal tab.",
                fontSize = 13.sp, lineHeight = 18.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                Button(
                    onClick = {
                        DefaultBrowser.setNudgeDone(context)
                        done = true
                        makeDefault()
                    },
                    contentPadding = PaddingValues(horizontal = 18.dp), modifier = Modifier.height(38.dp),
                ) { Text("Make default", fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                TextButton(
                    onClick = {
                        DefaultBrowser.setNudgeDone(context)
                        done = true
                    },
                    modifier = Modifier.height(38.dp),
                ) { Text("Not now", fontSize = 14.sp, fontWeight = FontWeight.Medium) }
            }
        }
    }
}

/**
 * A link tapped in another app, with SyncUp as its browser: hands the address to the main screen (a new
 * Normal tab) and gets out of the way. Kept separate, like QuickShareTargetActivity, so a link never
 * opens a second copy of SyncUp inside the other app; Back on that tab's first page returns there.
 */
class OpenLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent?.dataString?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
        if (url != null) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_LINK, url)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
        finish()
    }
}
