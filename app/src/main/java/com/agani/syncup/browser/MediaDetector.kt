package com.agani.syncup.browser

import android.net.Uri
import com.agani.syncup.downloads.FileKind
import com.agani.syncup.downloads.Files

/** A file the page plays or links that can be saved as a whole file. */
data class DetectedFile(val url: String, val name: String, val kind: FileKind, val playing: Boolean)

/**
 * Finds videos, music and files on a page that can be downloaded as whole files:
 * - media the page plays straight from a file (what a `<video>` / `<audio>` element loads, seen on
 *   the network or in the page), and
 * - links to files (pdf, zip, apk, mp3, mp4 …).
 *
 * By design it never offers: YouTube (Play policy), streamed video (players that feed `blob:`
 * sources — which is also how DRM-protected video plays), or pieces of a stream (.ts / .m4s / .m3u8).
 */
object MediaDetector {
    private val MEDIA_EXT = setOf("mp4", "m4v", "webm", "mov", "mkv", "3gp", "mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac")
    private val FILE_EXT = MEDIA_EXT + setOf(
        "pdf", "zip", "rar", "7z", "apk", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "epub", "iso", "tar", "gz",
    )
    private val NEVER = listOf("youtube.com", "youtu.be", "youtube-nocookie.com", "googlevideo.com", "ytimg.com")
    private const val MAX_PER_PAGE = 60

    /** May the detector work on a page from [pageHost]? Never on YouTube. */
    fun allowedOn(pageHost: String): Boolean = NEVER.none { pageHost == it || pageHost.endsWith(".$it") }

    /**
     * A request the page made (WebView thread). Media elements ask for `Range: bytes=0-` first —
     * that tells a played file apart from a player fetching pieces of a stream.
     */
    fun fromRequest(url: String, range: String?): DetectedFile? {
        if (range == null || !range.trim().lowercase().startsWith("bytes=0-")) return null
        val file = check(url, playing = true, name = null) ?: return null
        return file.takeIf { it.kind == FileKind.VIDEO || it.kind == FileKind.MUSIC }
    }

    /** Something the page script found (a playing media element, or a link to a file). */
    fun fromPage(url: String, name: String?, playing: Boolean): DetectedFile? = check(url, playing, name)

    private fun check(url: String, playing: Boolean, name: String?): DetectedFile? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (!allowedOn(host)) return null
        val last = uri.lastPathSegment.orEmpty()
        val ext = last.substringAfterLast('.', "").lowercase()
        // A playing element's file may have no extension; a link must end in a file type.
        if (ext !in FILE_EXT && !(playing && ext.isEmpty())) return null
        val fileName = Files.cleanName(
            name?.takeIf { it.isNotBlank() && it.contains('.') } ?: last.ifBlank { "media" }.let { if (it.contains('.')) it else "$it.mp4" },
        )
        return DetectedFile(url, fileName, FileKind.of(fileName, ""), playing)
    }

    /** Add [file] to [tab]'s list (main thread). */
    fun add(tab: BrowserTab, file: DetectedFile) {
        if (!allowedOn(tab.pageHost)) return
        val i = tab.detected.indexOfFirst { it.url == file.url }
        when {
            i >= 0 -> if (file.playing && !tab.detected[i].playing) tab.detected[i] = file
            tab.detected.size < MAX_PER_PAGE -> if (file.playing) tab.detected.add(0, file) else tab.detected.add(file)
        }
    }

    /** Runs in every page: reports playing media and links to files to [BRIDGE]. */
    const val BRIDGE = "SyncUpDetect"

    val SCAN_JS = """
        (function () {
          if (window.__syncupDetect) return;
          var port = window.$BRIDGE;
          if (!port) return;
          window.__syncupDetect = true;
          var sent = {};
          var EXT = /\.(mp4|m4v|webm|mov|mkv|3gp|mp3|m4a|aac|ogg|oga|opus|wav|flac|pdf|zip|rar|7z|apk|docx?|xlsx?|pptx?|csv|epub|iso|tar|gz)$/i;
          function send(u, name, playing) {
            if (!u || u.indexOf('http') !== 0 || sent[u]) return;
            sent[u] = 1;
            try { port.postMessage(JSON.stringify({ url: u, name: name || '', playing: !!playing })); } catch (e) {}
          }
          function scan() {
            try {
              var media = document.querySelectorAll('video, audio');
              for (var i = 0; i < media.length; i++) {
                var m = media[i];
                send(m.currentSrc || m.src, '', true);
                var srcs = m.querySelectorAll('source');
                for (var j = 0; j < srcs.length; j++) send(srcs[j].src, '', true);
              }
              var links = document.querySelectorAll('a[href]');
              for (var k = 0; k < links.length && k < 3000; k++) {
                var a = links[k], h = a.href.split('#')[0], path = h.split('?')[0];
                if (EXT.test(path)) send(h, a.getAttribute('download') || '', false);
              }
            } catch (e) {}
          }
          var timer = null;
          function soon() {
            if (timer) return;
            timer = setTimeout(function () { timer = null; scan(); }, 1500);
          }
          document.addEventListener('DOMContentLoaded', scan);
          window.addEventListener('load', scan);
          document.addEventListener('play', soon, true);
          document.addEventListener('loadedmetadata', soon, true);
          try { new MutationObserver(soon).observe(document.documentElement, { childList: true, subtree: true }); } catch (e) {}
        })();
    """.trimIndent()
}
