package com.agani.syncup.quickshare

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.agani.syncup.MainActivity
import com.agani.syncup.R
import java.lang.ref.WeakReference

/**
 * The "SyncUp Share" button in the phone's Quick Settings (swipe down from the status bar), like
 * Android's own Quick Share tile. A tap opens SyncUp Share ready to receive — the quick way to let a
 * nearby phone send to you. While something is going on the tile lights up and says what ("Ready to
 * receive", "Receiving · 40%"), and a tap just opens the page. Sending starts from the files
 * themselves: Share → SyncUp Share.
 */
class SyncUpShareTileService : TileService() {

    override fun onTileAdded() {
        QuickShare.setTileAdded(this, true)
        draw()
    }

    override fun onTileRemoved() {
        QuickShare.setTileAdded(this, false)
    }

    override fun onStartListening() {
        // A tile added before this flag existed (or after "Clear data") still counts as added.
        if (!QuickShare.tileAdded) QuickShare.setTileAdded(this, true)
        live = WeakReference(this)
        draw()
    }

    override fun onStopListening() {
        if (live?.get() === this) live = null
    }

    override fun onClick() {
        val receive = QuickShare.phase == QsPhase.IDLE || QuickShare.phase == QsPhase.DONE || QuickShare.phase == QsPhase.FAILED
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_QUICK_SHARE, true)
            .putExtra(MainActivity.EXTRA_QUICK_SHARE_RECEIVE, receive)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (isLocked) unlockAndRun { open(intent) } else open(intent)
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun open(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14+ only takes a PendingIntent here (the Intent version throws).
            startActivityAndCollapse(PendingIntent.getActivity(this, 4202, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun draw() {
        val tile = qsTile ?: return
        val p = QuickShare.phase
        val on = p == QsPhase.VISIBLE || p == QsPhase.SEARCHING || p == QsPhase.CONFIRM || p == QsPhase.CONNECTING || p == QsPhase.TRANSFERRING
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.syncup_share_tile)
        val status = when (p) {
            QsPhase.VISIBLE -> "Ready to receive"
            QsPhase.SEARCHING -> "Looking for phones"
            QsPhase.CONFIRM -> "Check the code"
            QsPhase.CONNECTING -> "Connecting…"
            QsPhase.TRANSFERRING -> {
                val total = QuickShare.totalBytes
                val pct = if (total > 0) " · ${QuickShare.doneBytes * 100 / total}%" else ""
                (if (QuickShare.role == QsRole.SEND) "Sending" else "Receiving") + pct
            }
            else -> "Tap to receive"
        }
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = status
        tile.contentDescription = "${getString(R.string.syncup_share_tile)}, $status"
        tile.updateTile()
    }

    companion object {
        /** The tile while the Quick Settings panel is open (null otherwise). Main thread only. */
        private var live: WeakReference<SyncUpShareTileService>? = null

        /** Something changed (a new phase): have the system redraw the tile next time it's shown, or now if it is. */
        fun refresh(context: Context) {
            redraw()
            runCatching { requestListeningState(context, ComponentName(context, SyncUpShareTileService::class.java)) }
        }

        /** Progress tick: redraw only if the panel is open right now — no binding otherwise. */
        fun redraw() {
            live?.get()?.draw()
        }

        private fun component(context: Context) = ComponentName(context, SyncUpShareTileService::class.java)

        /** The "Show in Quick Settings" switch was turned off before (the tile's service is switched off). */
        fun isTurnedOff(context: Context): Boolean =
            context.packageManager.getComponentEnabledSetting(component(context)) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED

        /**
         * Switch on: make the tile available again and (Android 13+) show the system's "Add tile?"
         * prompt. [onResult] says whether it's in Quick Settings now — always false below Android 13,
         * where people add it themselves from the panel's Edit screen.
         */
        fun turnOn(context: Context, onResult: (Boolean) -> Unit) {
            context.packageManager.setComponentEnabledSetting(
                component(context), PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP,
            )
            val prefs = prefs(context)
            val wasInPanel = prefs.getBoolean(KEY_WAS_IN_PANEL, false)
            prefs.edit().putBoolean(KEY_WAS_IN_PANEL, false).commit()
            if (wasInPanel) {
                // A tile that was switched off keeps its place: with its service back on, Android puts
                // it back by itself — no prompt. (Asking right away would wrongly show the prompt:
                // Android's "already added" check catches up a few seconds later than the tile.)
                QuickShare.setTileAdded(context, true)
                onResult(true)
                // Double-check once that's settled: silent if it's there, the prompt if this phone didn't restore it.
                if (Build.VERSION.SDK_INT >= 33) Handler(Looper.getMainLooper()).postDelayed({ askToAdd(context) {} }, 4_000)
            } else if (Build.VERSION.SDK_INT >= 33) {
                askToAdd(context, onResult)
            } else {
                onResult(false)
            }
        }

        /**
         * Switch off. Android has no "remove my tile" call, but it drops a tile from Quick Settings
         * (and from its Edit list) once the tile's service is switched off — so that's what we do.
         */
        fun turnOff(context: Context) {
            prefs(context).edit().putBoolean(KEY_WAS_IN_PANEL, QuickShare.tileAdded).commit()
            context.packageManager.setComponentEnabledSetting(
                component(context), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP,
            )
            QuickShare.setTileAdded(context, false)
        }

        /** Android 13+: the system's own "Add tile to Quick Settings?" prompt. [onResult] = it's there afterwards. */
        @RequiresApi(33)
        fun askToAdd(context: Context, onResult: (Boolean) -> Unit) {
            val sbm = context.getSystemService(StatusBarManager::class.java) ?: return onResult(false)
            sbm.requestAddTileService(
                component(context),
                context.getString(R.string.syncup_share_tile),
                Icon.createWithResource(context, R.drawable.ic_qs_syncup_share),
                context.mainExecutor,
            ) { result ->
                val added = result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                    result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                // Only a real "Do not add" means it's not there (errors like "app not in front" say nothing).
                if (added || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED) QuickShare.setTileAdded(context, added)
                onResult(added)
            }
        }

        private const val KEY_WAS_IN_PANEL = "was_in_panel"

        /** Remembers, across the switch's off → on, whether the tile was in the panel. */
        private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("syncup_share_tile", Context.MODE_PRIVATE)
    }
}
