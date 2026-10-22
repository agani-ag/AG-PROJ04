package com.agani.syncup.forest

import android.app.Activity
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.agani.syncup.ui.theme.isLight

/** Android's "Remove animations": the forest stands still. */
@Composable
internal fun animationsOn(): Boolean {
    val ctx = LocalContext.current
    return remember { runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) > 0f }
}

/** Seconds since this was first shown, ticking at a gentle 30 frames a second (only while on screen). */
@Composable
internal fun rememberClock(animate: Boolean = animationsOn()): State<Float> {
    val t = remember { mutableFloatStateOf(0f) }
    if (animate) {
        LaunchedEffect(Unit) {
            val start = withFrameNanos { it }
            var last = 0L
            while (true) {
                withFrameNanos { n ->
                    if (n - last >= 33_000_000L) {
                        last = n
                        t.floatValue = (n - start) / 1e9f
                    }
                }
            }
        }
    }
    return t
}

/**
 * Status-bar icons for a page drawn under the bar (dark icons on a light sky); back to the app's own after.
 * [nav] also sets the navigation bar's icons (null leaves them as they are).
 */
@Composable
internal fun BarIcons(light: Boolean, nav: Boolean? = null) {
    val view = LocalView.current
    val appLight = MaterialTheme.colorScheme.isLight
    DisposableEffect(light, nav) {
        val w = (view.context as? Activity)?.window
        if (w != null) {
            val c = WindowCompat.getInsetsController(w, view)
            c.isAppearanceLightStatusBars = light
            if (nav != null) c.isAppearanceLightNavigationBars = nav
        }
        onDispose {
            if (w != null) {
                val c = WindowCompat.getInsetsController(w, view)
                c.isAppearanceLightStatusBars = appLight
                if (nav != null) c.isAppearanceLightNavigationBars = appLight
            }
        }
    }
}
