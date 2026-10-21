package com.agani.syncup.browser

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.agani.syncup.data.ApiClient
import com.agani.syncup.data.GeneralLinksResponse
import com.agani.syncup.data.UrlItem
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The admin's general links — for everyone, signed in or not. Signed in, they come after the user's
 * own links in /sync; signed out, they are the SyncUp section (GET /app/v1/general-links, no sign-in).
 * A copy is kept on the phone, so SyncUp works offline and opens instantly; it's refreshed when the
 * app starts and comes back, and the backend answers "unchanged" (304) when nothing changed.
 */
object GeneralLinks {
    private var file: File? = null
    private var version: String = ""
    private var loading = false

    var links by mutableStateOf<List<UrlItem>>(emptyList())
        private set

    /** Pull-to-refresh on the signed-out SyncUp page is running. */
    var refreshing by mutableStateOf(false)
        private set

    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "general_links.json")
        file = f
        runCatching {
            if (f.exists()) {
                val r = Gson().fromJson(f.readText(), GeneralLinksResponse::class.java)
                version = r.version
                links = toLinks(r)
            }
        }
    }

    /** Fetch the latest list ([spinner] = the user pulled to refresh). Main thread. */
    suspend fun refresh(spinner: Boolean = false) {
        if (loading) return
        loading = true
        if (spinner) refreshing = true
        try {
            val resp = withContext(Dispatchers.IO) { runCatching { ApiClient.service.generalLinks(version.ifBlank { null }) }.getOrNull() } ?: return
            if (resp.code() == 304) return
            val body = resp.body() ?: return
            version = body.version
            links = toLinks(body)
            withContext(Dispatchers.IO) { runCatching { file?.writeText(Gson().toJson(body)) } }
        } finally {
            loading = false
            refreshing = false
        }
    }

    private fun toLinks(r: GeneralLinksResponse): List<UrlItem> =
        r.links.filter { it.url.startsWith("http") }.map { it.copy(general = true) }
}
