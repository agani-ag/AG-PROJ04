package com.agani.syncup.browser

import com.agani.syncup.browser.ui.chrome

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.browser.ui.PillButton
import com.agani.syncup.browser.ui.SyncPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ============================================================================ page address bar
/**
 * Normal / Incognito web pages: 60 dp strip, 44 dp tonal pill with lock · host + path · reload/stop
 * inside; the bookmark star sits outside (Normal only). Work pages never get this bar.
 */
@Composable
internal fun PageBar(tab: BrowserTab, db: BrowserDb, atBottom: Boolean, onTap: () -> Unit, onReload: () -> Unit, onStop: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val normal = tab.section == Section.NORMAL
    var bookmarked by remember { mutableStateOf(false) }
    LaunchedEffect(tab.url) { bookmarked = normal && withContext(Dispatchers.IO) { db.isBookmarked(tab.url) } }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .background(cs.chrome)
            .padding(start = 12.dp, end = if (normal) 4.dp else 12.dp),
    ) {
        SyncPill(
            modifier = Modifier.weight(1f),
            height = 44.dp,
            container = cs.surfaceContainerHigh,
            onClick = onTap,
        ) {
            val icon = when {
                tab.section == Section.INCOGNITO -> Icons.Rounded.VisibilityOff
                UrlInput.isSecure(tab.url) -> Icons.Rounded.Lock
                else -> Icons.Rounded.Info
            }
            Icon(icon, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
            val (host, path) = UrlInput.hostAndPath(tab.url)
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = cs.onSurface)) { append(host) }
                    withStyle(SpanStyle(color = cs.onSurfaceVariant)) { append(path) }
                },
                fontSize = 15.sp, letterSpacing = .2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (tab.loading) {
                PillButton(Icons.Rounded.Close, "Stop", onClick = onStop)
            } else {
                PillButton(Icons.Rounded.Refresh, "Reload", onClick = onReload)
            }
        }
        if (normal) {
            BarIcon(
                if (bookmarked) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                if (bookmarked) "Remove bookmark" else "Add bookmark",
                tint = if (bookmarked) cs.primary else cs.onSurfaceVariant,
            ) {
                scope.launch {
                    bookmarked = withContext(Dispatchers.IO) { db.toggleBookmark(tab.url, tab.title) }
                    Toast.makeText(context, if (bookmarked) "Bookmarked" else "Bookmark removed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

// ============================================================================ typing
@Composable
internal fun EditBar(
    value: TextFieldValue,
    incognito: Boolean,
    onValueChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().height(60.dp).background(cs.surface).padding(start = 4.dp, end = 12.dp),
    ) {
        BarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Cancel", onClick = onCancel)
        SyncPill(modifier = Modifier.weight(1f), height = 44.dp, focused = true) {
            if (incognito) Icon(Icons.Rounded.VisibilityOff, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 15.sp, letterSpacing = .2.sp, color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.text.isEmpty()) {
                            Text(
                                if (incognito) "Search privately" else "Search or type web address",
                                color = cs.onSurfaceVariant, fontSize = 15.sp, maxLines = 1,
                            )
                        }
                        inner()
                    }
                },
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            if (value.text.isNotEmpty()) PillButton(Icons.Rounded.Close, "Clear") { onValueChange(TextFieldValue("")) }
        }
    }
}

// ============================================================================ find in page
@Composable
internal fun FindBar(webViewProvider: () -> android.webkit.WebView, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var current by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        webViewProvider().setFindListener { active, count, _ ->
            current = if (count == 0) 0 else active + 1
            total = count
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(60.dp).background(cs.chrome).padding(start = 12.dp, end = 4.dp),
    ) {
        SyncPill(modifier = Modifier.weight(1f), height = 44.dp, endPadding = 12.dp) {
            Icon(Icons.Rounded.Search, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
            BasicTextField(
                value = query,
                onValueChange = {
                    query = it
                    webViewProvider().findAllAsync(it)
                },
                singleLine = true,
                textStyle = TextStyle(fontSize = 15.sp, color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { webViewProvider().findNext(true) }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text("Find in page", color = cs.onSurfaceVariant, fontSize = 15.sp)
                        inner()
                    }
                },
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            if (query.isNotEmpty()) Text("$current/$total", fontSize = 12.sp, color = cs.onSurfaceVariant)
        }
        BarIcon(Icons.Rounded.KeyboardArrowUp, "Previous match", enabled = total > 0) { webViewProvider().findNext(false) }
        BarIcon(Icons.Rounded.KeyboardArrowDown, "Next match", enabled = total > 0) { webViewProvider().findNext(true) }
        BarIcon(Icons.Rounded.Close, "Close find", onClick = onClose)
    }
}

// ============================================================================ suggestions
private class Suggestion(val icon: ImageVector, val title: String, val subtitle: String?, val url: String, val fill: String?)

private const val SUGGEST_URL = "https://suggestqueries.google.com/complete/search?client=firefox&q="

/** Live query suggestions from Google's public suggest endpoint (Normal section only). */
private suspend fun remoteSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
    runCatching {
        val conn = (URL(SUGGEST_URL + URLEncoder.encode(query, "UTF-8")).openConnection() as HttpURLConnection).apply {
            connectTimeout = 2500
            readTimeout = 2500
        }
        try {
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONArray(body).getJSONArray(1)
            (0 until arr.length()).map { arr.getString(it) }
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(emptyList())
}

/**
 * The list under the typing pill: "Search" (the typed text + live suggestions, Normal only) and
 * "History" (bookmarks + history, Normal only). Incognito and Work never send or show anything.
 */
@Composable
internal fun Suggestions(query: String, section: Section, db: BrowserDb, onPick: (String) -> Unit, onFill: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var search by remember { mutableStateOf<List<Suggestion>>(emptyList()) }
    var history by remember { mutableStateOf<List<Suggestion>>(emptyList()) }
    LaunchedEffect(query, section) {
        val q = query.trim()
        if (q.isEmpty()) {
            search = emptyList()
            history = emptyList()
            return@LaunchedEffect
        }
        val target = UrlInput.toUrl(q)
        val isSearch = target.startsWith(BrowserSettings.searchEngine.searchUrl)
        val direct = if (isSearch) {
            Suggestion(Icons.Rounded.Search, q, "${BrowserSettings.searchEngine.label} search", target, null)
        } else {
            Suggestion(Icons.Rounded.Public, UrlInput.display(target), "Go to address", target, null)
        }
        search = listOf(direct)
        if (section != Section.NORMAL) {
            history = emptyList()
            return@LaunchedEffect
        }
        history = withContext(Dispatchers.IO) {
            val b = db.bookmarks(q).take(3).map { Suggestion(Icons.Rounded.Star, it.title.ifBlank { UrlInput.display(it.url) }, UrlInput.display(it.url), it.url, null) }
            val h = db.history(q, 8).map { Suggestion(Icons.Rounded.History, it.title.ifBlank { UrlInput.display(it.url) }, UrlInput.display(it.url), it.url, null) }
            (b + h).distinctBy { it.url }.take(5)
        }
        if (BrowserSettings.searchEngine == SearchEngine.GOOGLE) {
            delay(150) // debounce: a newer keystroke cancels this effect
            val remote = remoteSuggestions(q).filter { !it.equals(q, ignoreCase = true) }.take(5)
            search = listOf(direct) + remote.map { Suggestion(Icons.Rounded.Search, it, null, UrlInput.toUrl(it), it) }
        }
    }
    Box(Modifier.fillMaxSize().background(cs.surface)) {
        LazyColumn(contentPadding = PaddingValues(bottom = 8.dp)) {
            if (search.isNotEmpty()) {
                item { SuggestionHeader("Search") }
                items(search) { s -> SuggestionRow(s, query, onPick, onFill) }
            }
            if (history.isNotEmpty()) {
                item { SuggestionHeader("History") }
                items(history) { s -> SuggestionRow(s, query, onPick, onFill) }
            }
            if (section == Section.INCOGNITO && query.isNotBlank()) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(cs.surfaceContainerLow)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Icon(Icons.Rounded.VisibilityOff, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Incognito doesn't send what you type for suggestions or show your history",
                            fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionHeader(text: String) {
    Text(
        text, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = .1.sp,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
    )
}

@Composable
private fun SuggestionRow(s: Suggestion, query: String, onPick: (String) -> Unit, onFill: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onPick(s.url) }.heightIn(min = 56.dp).padding(start = 20.dp, end = 4.dp),
    ) {
        Icon(s.icon, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            // Emphasise what the suggestion adds to the typed text.
            val typed = query.trim()
            val t = if (s.fill != null && typed.isNotEmpty() && s.title.startsWith(typed, ignoreCase = true)) {
                buildAnnotatedString {
                    append(s.title.substring(0, typed.length))
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(s.title.substring(typed.length)) }
                }
            } else {
                buildAnnotatedString { append(s.title) }
            }
            Text(t, fontSize = 15.sp, lineHeight = 20.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!s.subtitle.isNullOrBlank()) Text(s.subtitle, fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (s.fill != null) {
            BarIcon(Icons.Rounded.NorthWest, "Edit this", tint = cs.onSurfaceVariant) { onFill(s.fill) }
        } else {
            Spacer(Modifier.width(8.dp))
        }
    }
}
