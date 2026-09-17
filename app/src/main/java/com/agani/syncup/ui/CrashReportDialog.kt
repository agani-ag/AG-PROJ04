package com.agani.syncup.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agani.syncup.CrashReporter

/** If the app crashed last run, shows the saved stack trace so it can be screenshotted/shared. */
@Composable
fun CrashReportDialog() {
    val context = LocalContext.current
    val text = remember { CrashReporter.consume(context) }
    var show by remember { mutableStateOf(text != null) }

    if (show && text != null) {
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text("App crash report", fontWeight = FontWeight.Bold) },
            text = {
                Box(Modifier.heightIn(max = 360.dp)) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    copyToClipboard(context, text)
                    Toast.makeText(context, "Crash copied", Toast.LENGTH_SHORT).show()
                    show = false
                }) { Text("Copy") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Close") } },
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText("SyncUp crash", text))
}
