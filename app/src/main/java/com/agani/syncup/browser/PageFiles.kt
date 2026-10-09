package com.agani.syncup.browser

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebView
import android.widget.Toast
import com.agani.syncup.downloads.Downloads
import com.agani.syncup.downloads.FileKind
import com.agani.syncup.downloads.Files
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Saves files a page makes itself — blob: and data: links, like an invoice or a report built in the
 * browser — which can't be fetched again from a server because they exist only inside the page.
 *
 * A blob is read by the page and streamed over a JavaScript bridge in base64 chunks into a temp
 * file; a data: link is decoded here. The file then goes into Download / SyncUp / <kind> and the
 * Download Manager's list, like any other download.
 *
 * Every page can see the bridge, so it only accepts files the browser asked for: a blob link the
 * page tried to download ([save]). Pages can't drop files into Downloads on their own.
 */
internal class PageFiles(private val context: Context, private val main: Handler) {

    /** Names pages gave their download links (<a download="name">), by link (first [KEY_LENGTH] chars). */
    private val names: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 50
        },
    )

    /** Blob links the browser asked a page to hand over, with the type the download reported. */
    private val requested = ConcurrentHashMap<String, String>()
    private val incoming = ConcurrentHashMap<Int, Incoming>()
    private val nextId = AtomicInteger(1)
    private val io = Executors.newSingleThreadExecutor()

    private class Incoming(
        val file: File,
        val out: OutputStream,
        val name: String,
        val mime: String,
        val tab: BrowserTab,
    )

    /** The page-side bridge for [tab]'s WebView. */
    fun bridge(tab: BrowserTab): Any = Bridge(tab)

    private inner class Bridge(private val tab: BrowserTab) {
        @JavascriptInterface
        fun name(link: String?, name: String?) {
            if (!link.isNullOrBlank() && !name.isNullOrBlank()) names[link.take(KEY_LENGTH)] = name
        }

        /** Start receiving the blob behind [link]; returns 0 when it wasn't asked for. */
        @JavascriptInterface
        fun begin(link: String?, type: String?): Int {
            val reported = link?.let { requested.remove(it) } ?: return 0
            val mime = type?.takeIf { it.isNotBlank() } ?: reported
            val id = nextId.getAndIncrement()
            return runCatching {
                val dir = File(context.cacheDir, "page-files").apply { mkdirs() }
                val file = File(dir, "$id.part")
                incoming[id] = Incoming(file, file.outputStream().buffered(), fileName(link, mime), mime, tab)
                id
            }.getOrDefault(0)
        }

        @JavascriptInterface
        fun chunk(id: Int, base64: String?) {
            val inc = incoming[id] ?: return
            val ok = runCatching { inc.out.write(Base64.decode(base64.orEmpty(), Base64.DEFAULT)) }.isSuccess
            if (!ok) abort(id)
        }

        @JavascriptInterface
        fun end(id: Int) {
            val inc = incoming.remove(id) ?: return
            runCatching { inc.out.close() }
            io.execute { publish(inc.file, inc.name, inc.mime, inc.tab) }
        }

        @JavascriptInterface
        fun fail(id: Int) {
            abort(id)
        }
    }

    private fun abort(id: Int) {
        incoming.remove(id)?.let { inc ->
            runCatching { inc.out.close() }
            inc.file.delete()
        }
        main.post { toast("Couldn't save this file") }
    }

    /** Save the file behind a blob: or data: link the page tried to download. Main thread. */
    fun save(webView: WebView, tab: BrowserTab, url: String, mimeType: String?) {
        val reported = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        if (url.startsWith("data:")) {
            io.execute { saveDataUrl(url, reported, tab) }
            return
        }
        requested[url] = reported
        webView.evaluateJavascript(blobReader(url), null)
    }

    private fun saveDataUrl(url: String, reported: String, tab: BrowserTab) {
        val comma = url.indexOf(',')
        if (comma < 0) {
            main.post { toast("Couldn't save this file") }
            return
        }
        val meta = url.substring("data:".length, comma)
        val mime = meta.substringBefore(';').trim().ifBlank { reported }
        val payload = url.substring(comma + 1)
        val bytes = runCatching {
            if (meta.endsWith(";base64", ignoreCase = true)) Base64.decode(payload, Base64.DEFAULT)
            else Uri.decode(payload).toByteArray()
        }.getOrNull()
        if (bytes == null) {
            main.post { toast("Couldn't save this file") }
            return
        }
        val file = File(File(context.cacheDir, "page-files").apply { mkdirs() }, "${nextId.getAndIncrement()}.part")
        if (runCatching { file.writeBytes(bytes) }.isFailure) {
            main.post { toast("Couldn't save this file") }
            return
        }
        publish(file, fileName(url, mime), mime, tab)
    }

    /** Move a finished temp file into Download / SyncUp / <kind> and list it with the other downloads. Background thread. */
    private fun publish(temp: File, rawName: String, rawMime: String, tab: BrowserTab) {
        val mime = Files.mimeFor(rawName, rawMime)
        val kind = FileKind.of(rawName, mime)
        var name = rawName
        val size = temp.length()
        val saved = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/SyncUp/${kind.folder}")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no row")
                try {
                    resolver.openOutputStream(uri)!!.use { out -> temp.inputStream().use { it.copyTo(out) } }
                    resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { name = it }
                }
                uri.toString()
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SyncUp/${kind.folder}").apply { mkdirs() }
                val dest = unusedFile(dir, name)
                temp.copyTo(dest)
                name = dest.name
                MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
                dest.absolutePath
            }
        }.getOrNull()
        temp.delete()
        main.post {
            if (saved == null) {
                toast("Couldn't save this file")
            } else {
                Downloads.recordSaved(name, mime, size, saved, tab.downloadSource, tab.isWork, tab.section == Section.INCOGNITO)
                toast("Saved $name to Downloads")
            }
        }
    }

    /** The page's own name for the link, else "download", with an extension that matches [mime]. */
    private fun fileName(link: String, mime: String): String {
        val given = names.remove(link.take(KEY_LENGTH))
            ?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.replace(Regex("""[\x00-\x1f:*?"<>|]"""), "_")
            ?.trim()?.trim('.')
            ?.take(120)
            .orEmpty()
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime.substringBefore(';').trim().lowercase())
        val base = given.ifBlank { "download" }
        return if (ext != null && !base.contains('.')) "$base.$ext" else base
    }

    private fun unusedFile(dir: File, name: String): File {
        var f = File(dir, name)
        var n = 1
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        while (f.exists()) f = File(dir, "$stem (${n++})$ext")
        return f
    }

    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    companion object {
        const val BRIDGE = "AndroidFileBridge"
        private const val KEY_LENGTH = 256

        /** Reads the blob behind a link in the page and streams it to the bridge (3-byte-aligned chunks). */
        private fun blobReader(url: String) = """
            (function (u) {
              var B = window.$BRIDGE;
              if (!B) return;
              var held = window.__syncupBlobs && window.__syncupBlobs[u];
              if (held) delete window.__syncupBlobs[u];
              (held || fetch(u).then(function (r) { return r.blob(); })).then(function (b) {
                var id = B.begin(u, b.type || '');
                if (!id) return;
                var size = 393216, at = 0;
                (function next() {
                  if (at >= b.size) { B.end(id); return; }
                  b.slice(at, at + size).arrayBuffer().then(function (buf) {
                    var a = new Uint8Array(buf), s = '';
                    for (var i = 0; i < a.length; i += 32768) s += String.fromCharCode.apply(null, a.subarray(i, i + 32768));
                    B.chunk(id, btoa(s));
                    at += size;
                    next();
                  }).catch(function () { B.fail(id); });
                })();
              }).catch(function () { B.fail(0); });
            })(${JSONObject.quote(url)});
        """.trimIndent()

        /**
         * Notes the name a page gives a download link (<a download="name">), including links that
         * are only clicked from script, and grabs a blob link's file at once — pages often revoke it
         * right after the click, before the download starts.
         */
        val NAME_HINT_JS = """
            (function () {
              if (window.__syncupFileHints) return;
              window.__syncupFileHints = true;
              function note(a) {
                try {
                  if (!a || !a.hasAttribute || !a.hasAttribute('download') || !a.href) return;
                  var h = a.href;
                  window.$BRIDGE.name(h.slice(0, $KEY_LENGTH), a.getAttribute('download') || '');
                  if (h.indexOf('blob:') === 0) {
                    var held = window.__syncupBlobs = window.__syncupBlobs || {};
                    held[h] = fetch(h).then(function (r) { return r.blob(); });
                    setTimeout(function () { delete held[h]; }, 60000);
                  }
                } catch (e) {}
              }
              document.addEventListener('click', function (e) {
                var t = e.target;
                note(t && t.closest ? t.closest('a') : null);
              }, true);
              // Links on the page are seen by the listener above; these catch ones never added to it.
              var click = HTMLAnchorElement.prototype.click;
              HTMLAnchorElement.prototype.click = function () {
                if (!this.isConnected) note(this);
                return click.apply(this, arguments);
              };
              var dispatch = EventTarget.prototype.dispatchEvent;
              EventTarget.prototype.dispatchEvent = function (ev) {
                if (ev && ev.type === 'click' && this instanceof HTMLAnchorElement && !this.isConnected) note(this);
                return dispatch.apply(this, arguments);
              };
            })();
        """.trimIndent()
    }
}
