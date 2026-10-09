package com.agani.syncup.browser

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector

/** What a site can ask for. */
enum class SitePerm(val label: String, val title: String, val icon: ImageVector) {
    CAMERA("camera", "Camera", Icons.Rounded.CameraAlt),
    MIC("microphone", "Microphone", Icons.Rounded.Mic),
    LOCATION("location", "Location", Icons.Rounded.LocationOn),
    NOTIFICATIONS("notifications", "Notifications", Icons.Rounded.Notifications),
}

/**
 * Each site's answer for camera, microphone, location and notifications, like Chrome: a site asks
 * once, the user's Allow / Block is remembered for that site (its origin), and Settings → Site
 * settings shows and changes them. Incognito answers stay in memory and are forgotten when
 * Incognito closes. A SyncUp link's site is listed by the link's name, never its address.
 */
object SitePermissions {
    private var prefs: SharedPreferences? = null
    private val incognito = HashMap<String, Boolean>()

    /** Bumped on every change, so Compose screens re-read. */
    var version by mutableIntStateOf(0)
        private set

    /** The question on screen now, and the ones waiting behind it. */
    var prompt by mutableStateOf<Prompt?>(null)
        private set
    private val waiting = ArrayDeque<Prompt>()

    class Prompt(
        val origin: String,
        val perms: List<SitePerm>,
        /** What the site is called in the question: its host, or the SyncUp link's name. */
        val label: String,
        val incognito: Boolean,
        internal val onAnswer: (Boolean) -> Unit,
    )

    data class Entry(val origin: String, val perm: SitePerm, val allowed: Boolean, val label: String)

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("site_permissions", Context.MODE_PRIVATE)
    }

    /** "https://meet.jit.si" for any address on that site. */
    fun originOf(url: String): String {
        val u = runCatching { Uri.parse(url) }.getOrNull() ?: return url.trimEnd('/')
        val scheme = u.scheme ?: return url.trimEnd('/')
        val host = u.host ?: return url.trimEnd('/')
        return if (u.port > 0) "$scheme://$host:${u.port}" else "$scheme://$host"
    }

    /** true = allowed, false = blocked, null = not asked yet. */
    fun get(origin: String, perm: SitePerm, inIncognito: Boolean): Boolean? {
        val key = key(perm, origin)
        if (inIncognito) incognito[key]?.let { return it }
        return prefs?.getString(key, null)?.let { it == "allow" }
    }

    fun set(origin: String, perm: SitePerm, allowed: Boolean, inIncognito: Boolean, label: String? = null) {
        val key = key(perm, origin)
        if (inIncognito) {
            incognito[key] = allowed
        } else {
            prefs?.edit()?.let { e ->
                e.putString(key, if (allowed) "allow" else "block")
                if (!label.isNullOrBlank()) e.putString("label|$origin", label)
                e.apply()
            }
        }
        version++
    }

    fun remove(origin: String, perm: SitePerm) {
        prefs?.edit()?.remove(key(perm, origin))?.apply()
        version++
    }

    /** Every saved answer (not Incognito's). */
    fun all(): List<Entry> {
        val p = prefs ?: return emptyList()
        return p.all.mapNotNull { (k, v) ->
            if (k.startsWith("label|")) return@mapNotNull null
            val perm = runCatching { SitePerm.valueOf(k.substringBefore('|')) }.getOrNull() ?: return@mapNotNull null
            val origin = k.substringAfter('|')
            Entry(origin, perm, v == "allow", p.getString("label|$origin", null) ?: displayHost(origin))
        }.sortedBy { it.label }
    }

    /** Incognito closed: forget its answers. */
    fun clearIncognito() {
        incognito.clear()
        version++
    }

    /** Ask the user (or queue the question behind the one on screen). [onAnswer] runs on the main thread. */
    fun ask(origin: String, perms: List<SitePerm>, label: String, inIncognito: Boolean, onAnswer: (Boolean) -> Unit) {
        val p = Prompt(origin, perms, label, inIncognito, onAnswer)
        if (prompt == null) prompt = p else waiting.addLast(p)
    }

    /** The user answered the question on screen: remember it for the site, carry on with the request. */
    fun answer(allowed: Boolean) {
        val p = prompt ?: return
        p.perms.forEach { set(p.origin, it, allowed, p.incognito, p.label.takeIf { l -> l != displayHost(p.origin) }) }
        p.onAnswer(allowed)
        next()
    }

    /** The page stopped asking (it closed, or cancelled the request): drop the question without an answer. */
    fun cancel(origin: String, perms: Collection<SitePerm>) {
        val match: (Prompt) -> Boolean = { it.origin == origin && it.perms.any { p -> p in perms } }
        waiting.removeAll(match)
        if (prompt?.let(match) == true) next()
    }

    private fun next() {
        prompt = waiting.removeFirstOrNull()
    }

    fun displayHost(origin: String): String = runCatching { Uri.parse(origin).host }.getOrNull()?.removePrefix("www.") ?: origin

    private fun key(perm: SitePerm, origin: String) = "${perm.name}|$origin"
}
