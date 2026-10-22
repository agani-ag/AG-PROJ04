package com.agani.syncup.party

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.group.GroupLink
import com.agani.syncup.group.GroupMode
import com.agani.syncup.music.LocalPlayer
import com.agani.syncup.music.Song
import com.agani.syncup.quickshare.QsPhase
import com.agani.syncup.quickshare.QuickShare
import com.agani.syncup.quickshare.Radios
import com.agani.syncup.quickshare.RadiosDialog
import com.agani.syncup.ui.theme.dialogSurface
import com.agani.syncup.walkie.Walkie

/**
 * Start a Radio → Party from somewhere else (the Music player): Nearby permission, Bluetooth / Wi-Fi
 * on, a party name, then this phone hosts [songs] — the first from `fromMs` — and [onStarted] goes to
 * the host screen. Returns the "start" function; its dialogs show where this is called.
 */
@Composable
fun rememberPartyStarter(onStarted: () -> Unit): (songs: List<Song>, fromMs: Long) -> Unit {
    val context = LocalContext.current
    remember { Party.init(context) }
    val musicPlayer = LocalPlayer.current
    val started by rememberUpdatedState(onStarted)
    var pending by remember { mutableStateOf<Pair<List<Song>, Long>?>(null) }
    var naming by remember { mutableStateOf(false) }
    var waitingForRadios by remember { mutableStateOf(false) }

    fun afterPermissions() {
        if (Radios.missing(context).isEmpty()) naming = true else waitingForRadios = true
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (QuickShare.missingPermissions(receiving = false).isEmpty()) afterPermissions() else {
            pending = null
            Toast.makeText(context, "Party needs the Nearby devices permission to find other phones.", Toast.LENGTH_LONG).show()
        }
    }

    if (naming) {
        val (songs, from) = pending ?: (emptyList<Song>() to 0L)
        var name by remember { mutableStateOf("${QuickShare.myName.ifBlank { "My" }}'s Party") }
        AlertDialog(
            onDismissRequest = { naming = false; pending = null },
            title = { Text("Start a party") },
            text = {
                Column {
                    val first = songs.firstOrNull()?.title.orEmpty()
                    Text(
                        if (songs.size <= 1) "“$first” plays on every phone that joins. Phones nearby see this name."
                        else "“$first” and ${songs.size - 1} more songs play on every phone that joins. Phones nearby see this name.",
                        fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = name, onValueChange = { name = it.take(30) }, singleLine = true, label = { Text("Party name") }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    naming = false
                    pending = null
                    // "Play at a party": the song kept playing while this was open — carry on from where it is now.
                    val at = if (from > 0 && musicPlayer?.songId == songs.firstOrNull()?.id) musicPlayer?.position ?: from else from
                    musicPlayer?.stop()
                    Party.startHosting(name)
                    Party.addSongs(songs)
                    if (at > 0) Party.seekTo(at)
                    started()
                }) { Text("Start") }
            },
            dismissButton = { TextButton(onClick = { naming = false; pending = null }) { Text("Cancel") } },
            containerColor = MaterialTheme.colorScheme.dialogSurface,
        )
    }
    if (waitingForRadios) {
        RadiosDialog(
            receiving = false,
            onReady = { waitingForRadios = false; naming = true },
            onCancel = { waitingForRadios = false; pending = null },
        )
    }

    return remember {
        { songs, fromMs ->
            val busy = when {
                songs.isEmpty() -> "No songs to play"
                QuickShare.phase != QsPhase.IDLE -> "Finish SyncUp Share first"
                Walkie.active -> "Leave Walkie Talkie first"
                Party.active -> "You're already in a party"
                GroupLink.inGroup && !GroupLink.isHost -> "You're in ${GroupLink.groupName} — ${GroupLink.hostName} chooses what plays"
                else -> null
            }
            if (busy != null) {
                Toast.makeText(context, busy, Toast.LENGTH_SHORT).show()
            } else if (GroupLink.inGroup) {
                // Hosting a group doing something else: switch it to these songs.
                musicPlayer?.stop()
                GroupLink.switchTo(GroupMode.MUSIC)
                Party.addSongs(songs)
                if (fromMs > 0 && songs.size == Party.queue.size) Party.seekTo(fromMs)
                started()
            } else {
                pending = songs to fromMs
                val missing = QuickShare.missingPermissions(receiving = false)
                if (missing.isEmpty()) afterPermissions() else permissions.launch(missing.toTypedArray())
            }
        }
    }
}
