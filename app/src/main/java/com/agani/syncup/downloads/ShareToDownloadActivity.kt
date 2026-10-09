package com.agani.syncup.downloads

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.agani.syncup.MainActivity

/**
 * "Share → Download with SyncUp" from any app: hands the shared link to SyncUp's Downloads
 * ("Add link", already filled in) and gets out of the way. Kept separate so a share never opens a
 * second copy of the browser inside the other app.
 */
class ShareToDownloadActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: intent?.dataString
        val link = firstLink(text)
        if (link == null) {
            Toast.makeText(this, "No link to download in what was shared", Toast.LENGTH_SHORT).show()
        } else {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_DOWNLOAD_LINK, link)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
        finish()
    }
}
