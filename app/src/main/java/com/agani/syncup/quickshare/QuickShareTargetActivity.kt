package com.agani.syncup.quickshare

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.IntentCompat
import com.agani.syncup.MainActivity

/**
 * "Share → SyncUp Share" from any app (gallery, file manager…): hands the shared files to
 * SyncUp Share, ready to send, and gets out of the way. Shared text (a PNR, an address, a link) opens
 * Send text with it filled in. Kept separate so a share never opens a second copy of the browser
 * inside the other app (same reason as ShareToDownloadActivity).
 */
class QuickShareTargetActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        val text = intent?.takeIf { it.action == Intent.ACTION_SEND && uris.isEmpty() }
            ?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()
        if (!text.isNullOrEmpty()) {
            if (text.length > QuickShare.MAX_TEXT) {
                Toast.makeText(this, "Only the first 5,000 characters will be sent", Toast.LENGTH_SHORT).show()
            }
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_QUICK_SHARE, true)
                    .putExtra(MainActivity.EXTRA_QUICK_SHARE_TEXT, text.take(QuickShare.MAX_TEXT))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            finish()
            return
        }
        // Only files we can really open: passing on a grant we were never given throws and would
        // crash SyncUp (some apps share links to files without letting the receiver read them).
        val readable = uris.filter(::canRead)
        if (readable.isEmpty()) {
            Toast.makeText(this, if (uris.isEmpty()) "Nothing to send in what was shared" else "SyncUp can't open what was shared", Toast.LENGTH_SHORT).show()
        } else {
            if (readable.size < uris.size) {
                val skipped = uris.size - readable.size
                Toast.makeText(this, "$skipped file${if (skipped == 1) "" else "s"} couldn't be opened and won't be sent", Toast.LENGTH_SHORT).show()
            }
            // Pass the read permission on with the files (ClipData + the grant flag).
            val clip = ClipData.newRawUri("files", readable.first()).apply { readable.drop(1).forEach { addItem(ClipData.Item(it)) } }
            try {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_QUICK_SHARE, true)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                        .also { it.clipData = clip },
                )
            } catch (_: SecurityException) {
                Toast.makeText(this, "SyncUp can't open what was shared", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }

    private fun canRead(uri: Uri): Boolean =
        runCatching { contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
}
