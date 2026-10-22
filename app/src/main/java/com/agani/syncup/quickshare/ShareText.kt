package com.agani.syncup.quickshare

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.provider.ContactsContract
import android.util.Patterns
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.music.ToolTile
import com.agani.syncup.music.ToolTint
import com.agani.syncup.ui.theme.CardShape
import com.agani.syncup.ui.theme.appCard

// ============================================================================ what the text is
/** What SyncUp spotted in a text, so the other phone can offer the right button. */
internal enum class TextKind { LINK, EMAIL, PHONE, PLAIN }

internal class Spotted(val kind: TextKind, val value: String)

private val linkRegex = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"']+""")
private val phoneRegex = Regex("""\+?\d[\d \-()]{5,17}\d""")

/** The first link, else an email, else a phone number in [text]. Links win: they often carry digits. */
internal fun spot(text: String): Spotted {
    linkRegex.find(text)?.let { m ->
        val url = m.value.trimEnd('.', ',', ')', '!', '?', ';', ':', ']')
        return Spotted(TextKind.LINK, if (url.startsWith("www.", ignoreCase = true)) "https://$url" else url)
    }
    Patterns.EMAIL_ADDRESS.matcher(text).let { if (it.find()) return Spotted(TextKind.EMAIL, it.group()) }
    for (m in phoneRegex.findAll(text)) {
        val raw = m.value.trim()
        if (isPhone(raw)) return Spotted(TextKind.PHONE, raw.filter { it.isDigit() || it == '+' })
    }
    return Spotted(TextKind.PLAIN, "")
}

/**
 * A phone number, not any long number (a PNR, an order id): +country and 8–15 digits, an Indian mobile
 * (10 digits from 6–9, with or without +91 / 0), or a 0-led landline with its area code.
 */
private fun isPhone(raw: String): Boolean {
    val digits = raw.filter { it.isDigit() }
    if (raw.startsWith("+")) return digits.length in 8..15
    val local = when {
        digits.length == 12 && digits.startsWith("91") -> digits.drop(2)
        digits.length == 11 && digits.startsWith("0") -> digits.drop(1)
        else -> digits
    }
    if (local.length == 10 && local[0] in '6'..'9') return true
    return digits.length == 11 && digits.startsWith("0")
}

private fun spottedNote(kind: TextKind): String? = when (kind) {
    TextKind.LINK -> "Has a link — they can open it in SyncUp with one tap."
    TextKind.EMAIL -> "Has an email address — they can write to it with one tap."
    TextKind.PHONE -> "Has a phone number — they can call it or save it as a contact."
    TextKind.PLAIN -> null
}

private fun Int.withCommas(): String = "%,d".format(this)

// ============================================================================ send: write or paste it
/** Tools → SyncUp Share → Send → Text or link. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TextComposer(
    value: String,
    onValue: (String) -> Unit,
    secret: Boolean,
    onSecret: (Boolean) -> Unit,
    onChoosePhone: () -> Unit,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val spotted = remember(value) { spot(value) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = value,
                onValueChange = { onValue(it.take(QuickShare.MAX_TEXT)) },
                label = { Text("Text or link") },
                placeholder = { Text("A Wi-Fi password, an address, a link, a phone number…") },
                minLines = 6,
                maxLines = 12,
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = cs.surfaceContainerLowest, unfocusedContainerColor = cs.surfaceContainerLowest,
                    unfocusedBorderColor = cs.outlineVariant,
                ),
                supportingText = {
                    Text(
                        "${value.length.withCommas()} / ${QuickShare.MAX_TEXT.withCommas()}",
                        textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth(),
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipPill(Icons.Rounded.ContentPaste, "Paste") {
                    // The clipboard is read only here, on this tap.
                    val clip = readClipboard(context)
                    if (clip.isNullOrEmpty()) {
                        Toast.makeText(context, "Nothing copied to paste", Toast.LENGTH_SHORT).show()
                    } else {
                        val joined = if (value.isEmpty() || value.last().isWhitespace()) value + clip else "$value\n$clip"
                        onValue(joined.take(QuickShare.MAX_TEXT))
                    }
                }
                ChipPill(Icons.Rounded.Clear, "Clear", enabled = value.isNotEmpty()) { onValue("") }
            }
            spottedNote(spotted.kind)?.let { note ->
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(ToolTint.BLUE.tile).padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Icon(Icons.Rounded.Info, null, tint = ToolTint.BLUE.icon, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(note, fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurface)
                }
            }
            ShareRow(
                title = "Secret",
                subtitle = "Hidden on their screen until they tap · not kept in Recent",
                modifier = Modifier.padding(top = 12.dp).appCard(CardShape),
                leading = { ToolTile(Icons.Rounded.Lock, ToolTint.SLATE) },
                trailing = { Switch(checked = secret, onCheckedChange = onSecret) },
                onClick = { onSecret(!secret) },
            )
            Text(
                "It goes straight to the other phone — nothing is uploaded. They tap Accept first, like with files.",
                fontSize = 12.5.sp, lineHeight = 18.sp, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
            Button(onClick = onChoosePhone, enabled = value.isNotBlank(), modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Choose a phone", fontSize = 15.sp) }
        }
    }
}

/** A small outlined pill on the card colour (Paste, Clear). */
@Composable
private fun ChipPill(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(36.dp).clip(RoundedCornerShape(50)).background(cs.surfaceContainerLowest)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(50)).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp).alpha(if (enabled) 1f else .45f),
    ) {
        Icon(icon, null, tint = cs.onSurface, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.5.sp, color = cs.onSurface)
    }
}

// ============================================================================ send: the card above "Nearby phones"
@Composable
internal fun TextOfferCard(text: String, secret: Boolean, onChange: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().appCard(CardShape).padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${if (secret) "Secret" else "Text"} · ${text.length.withCommas()} character${if (text.length == 1) "" else "s"}",
                fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onChange) { Text("Change") }
        }
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(end = 8.dp, top = 4.dp)) {
            Icon(if (secret) Icons.Rounded.Lock else Icons.Rounded.ContentCopy, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                if (secret) "Hidden on their screen until they tap" else text,
                fontSize = 13.5.sp, lineHeight = 19.sp, color = cs.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ============================================================================ finished: the text, and what to do with it
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TextFinished(onOpenLink: (String) -> Unit, onAgain: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val sending = QuickShare.role == QsRole.SEND
    val text = QuickShare.text
    val secret = QuickShare.secret
    val spotted = remember(text) { spot(text) }
    var shown by remember(text) { mutableStateOf(!secret) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            if (sending) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    DoneMark()
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "${if (secret) "Secret" else "Text"} sent to ${QuickShare.peerName}",
                        fontSize = 18.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, textAlign = TextAlign.Center,
                    )
                }
            }
            Column(
                Modifier.padding(top = 8.dp).fillMaxWidth().appCard(CardShape).padding(18.dp),
            ) {
                if (!sending) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(ToolTint.BLUE.tile), contentAlignment = Alignment.Center) {
                            Text(QuickShare.peerName.trim().take(1).uppercase().ifBlank { "?" }, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = ToolTint.BLUE.icon)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "${if (secret) "Secret" else "Text"} from ${QuickShare.peerName}",
                                fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface,
                            )
                            Text(
                                when {
                                    secret && !shown -> "hidden until you tap"
                                    spotted.kind == TextKind.LINK -> "a link"
                                    spotted.kind == TextKind.PHONE -> "a phone number"
                                    spotted.kind == TextKind.EMAIL -> "an email address"
                                    else -> "just now"
                                },
                                fontSize = 12.5.sp, color = cs.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
                if (shown) {
                    SelectionContainer {
                        Text(text, fontSize = 17.sp, lineHeight = 25.sp, color = cs.onSurface)
                    }
                } else {
                    HiddenText(text, onShow = { shown = true })
                }
                Spacer(Modifier.height(16.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (secret && !shown) {
                        TextAction(Icons.Rounded.Visibility, "Tap to show", main = false) { shown = true }
                        TextAction(Icons.Rounded.ContentCopy, "Copy without showing", main = true) { copyText(context, text, secret = true) }
                    } else {
                        TextAction(Icons.Rounded.ContentCopy, "Copy", main = true) { copyText(context, text, secret) }
                        when (spotted.kind) {
                            TextKind.LINK -> TextAction(Icons.Rounded.OpenInBrowser, "Open link", main = false) { onOpenLink(spotted.value) }
                            TextKind.PHONE -> {
                                TextAction(Icons.Rounded.Call, "Call", main = false) { dial(context, spotted.value) }
                                TextAction(Icons.Rounded.PersonAdd, "Save contact", main = false) { saveContact(context, spotted.value, text) }
                            }
                            TextKind.EMAIL -> TextAction(Icons.Rounded.Email, "Write email", main = false) { email(context, spotted.value) }
                            TextKind.PLAIN -> Unit
                        }
                        if (spotted.kind != TextKind.PHONE) {
                            TextAction(Icons.Rounded.Share, "Share", main = false) { shareText(context, text) }
                        }
                    }
                }
            }
            Text(
                if (secret) "Secrets aren't kept on this phone." else "Kept under Recent on this phone, to copy again later.",
                fontSize = 12.5.sp, lineHeight = 18.sp, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
            OutlinePill(if (sending) "Send more" else "Receive more", Modifier.weight(1f), onAgain)
            Button(onClick = onDone, modifier = Modifier.weight(1f).height(48.dp)) { Text("Done", fontSize = 15.sp) }
        }
    }
}

/** A secret, not readable until tapped: blurred where the phone can (Android 12+), dots before. */
@Composable
private fun HiddenText(text: String, onShow: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onShow)) {
        if (Build.VERSION.SDK_INT >= 31) {
            Text(text.take(200), fontSize = 17.sp, lineHeight = 25.sp, color = cs.onSurface, maxLines = 3, modifier = Modifier.blur(10.dp))
        } else {
            Text("•".repeat(text.length.coerceIn(6, 24)), fontSize = 17.sp, lineHeight = 25.sp, color = cs.onSurfaceVariant)
        }
    }
}

/** One thing to do with the text, as a pill: the main one filled blue, the rest soft blue. */
@Composable
private fun TextAction(icon: ImageVector, label: String, main: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val fg = if (main) cs.onPrimary else cs.onPrimaryContainer
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(40.dp).clip(RoundedCornerShape(50)).background(if (main) cs.primary else cs.primaryContainer)
            .clickable(onClick = onClick).padding(start = 14.dp, end = 16.dp),
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = fg, maxLines = 1)
    }
}

// ============================================================================ Recent: a text row
@Composable
internal fun TextRecentRow(r: QsRecord, ago: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val what = if (r.secret) "Secret" else "Text"
    ShareRow(
        title = if (r.incoming) "$what from ${r.peer}" else "$what to ${r.peer}",
        subtitle = if (r.text == null) "Not kept · $ago" else "“${r.text.replace('\n', ' ').take(80)}” · $ago",
        modifier = modifier,
        leading = { ToolTile(if (r.secret) Icons.Rounded.Lock else Icons.Rounded.ContentCopy, ToolTint.BLUE) },
        trailing = { r.text?.let { t -> TextButton(onClick = { copyText(context, t, secret = false) }) { Text("Copy") } } },
        endPadding = if (r.text != null) 6.dp else 16.dp,
    )
}

// ============================================================================ actions (Android's own screens, no permissions)
private fun readClipboard(context: Context): String? {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return null
    val clip = runCatching { cm.primaryClip }.getOrNull() ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}

internal fun copyText(context: Context, text: String, secret: Boolean) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(if (secret) "Secret" else "Text", text)
    // A secret is marked sensitive, so Android doesn't show it in its "Copied" preview.
    if (secret) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
        }
    }
    cm.setPrimaryClip(clip)
    // Android 13+ shows its own "Copied" note.
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

private fun start(context: Context, intent: Intent, fail: String) {
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(context, fail, Toast.LENGTH_SHORT).show() }
}

private fun dial(context: Context, number: String) =
    start(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))), "No phone app here")

/** Android's own "new contact" screen with the number (and the rest of a short text as the name) filled in. */
private fun saveContact(context: Context, number: String, text: String) {
    val name = text.replace(Regex("""[+\d \-()·:,]{7,}"""), " ").replace(Regex("""\s+"""), " ").trim().take(60)
    val intent = Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
        .putExtra(ContactsContract.Intents.Insert.PHONE, number)
    if (name.isNotEmpty() && text.length <= 120) intent.putExtra(ContactsContract.Intents.Insert.NAME, name)
    start(context, intent, "No contacts app here")
}

private fun email(context: Context, address: String) =
    start(context, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + address)), "No email app here")

private fun shareText(context: Context, text: String) =
    start(context, Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null), "No app here can share this")

