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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.rounded.CheckCircle
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.IconTile
import com.agani.syncup.browser.ui.TonalRow

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
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            OutlinedTextField(
                value = value,
                onValueChange = { onValue(it.take(QuickShare.MAX_TEXT)) },
                label = { Text("Text or link") },
                placeholder = { Text("A Wi-Fi password, an address, a link, a phone number…") },
                minLines = 6,
                maxLines = 12,
                supportingText = {
                    Text(
                        "${value.length.withCommas()} / ${QuickShare.MAX_TEXT.withCommas()}",
                        textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth(),
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                AssistChip(
                    onClick = {
                        // The clipboard is read only here, on this tap.
                        val clip = readClipboard(context)
                        if (clip.isNullOrEmpty()) {
                            Toast.makeText(context, "Nothing copied to paste", Toast.LENGTH_SHORT).show()
                        } else {
                            val joined = if (value.isEmpty() || value.last().isWhitespace()) value + clip else "$value\n$clip"
                            onValue(joined.take(QuickShare.MAX_TEXT))
                        }
                    },
                    label = { Text("Paste") },
                    leadingIcon = { Icon(Icons.Rounded.ContentPaste, null, Modifier.size(18.dp)) },
                )
                AssistChip(
                    onClick = { onValue("") },
                    enabled = value.isNotEmpty(),
                    label = { Text("Clear") },
                    leadingIcon = { Icon(Icons.Rounded.Clear, null, Modifier.size(18.dp)) },
                )
            }
            spottedNote(spotted.kind)?.let { note ->
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(cs.primaryContainer).padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.Rounded.Info, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(note, fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onPrimaryContainer)
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(cs.surfaceContainerLow).clickable { onSecret(!secret) }.padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Icon(Icons.Rounded.Lock, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Secret", fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                    Text("Hidden on their screen until they tap · not kept in Recent", fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = secret, onCheckedChange = onSecret)
            }
            Text(
                "It goes straight to the other phone — nothing is uploaded. They tap Accept first, like with files.",
                fontSize = 12.5.sp, lineHeight = 18.sp, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
            Button(onClick = onChoosePhone, enabled = value.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Choose a phone") }
        }
    }
}

// ============================================================================ send: the card above "Nearby phones"
@Composable
internal fun TextOfferCard(text: String, secret: Boolean, onChange: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(cs.surfaceContainerLow).padding(vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 8.dp)) {
            Text(
                "${if (secret) "Secret" else "Text"} · ${text.length.withCommas()} character${if (text.length == 1) "" else "s"}",
                fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onChange) { Text("Change") }
        }
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
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
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (sending) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = cs.primary, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "${if (secret) "Secret" else "Text"} sent to ${QuickShare.peerName}",
                        fontSize = 18.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, textAlign = TextAlign.Center,
                    )
                }
            }
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp))
                    .background(cs.surfaceContainerLowest).border(1.dp, cs.outlineVariant, RoundedCornerShape(22.dp)).padding(18.dp),
            ) {
                if (!sending) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                            Text(QuickShare.peerName.trim().take(1).uppercase().ifBlank { "?" }, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = cs.onPrimaryContainer)
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (secret && !shown) {
                        TextAction(Icons.Rounded.Visibility, "Tap to show", main = false, Modifier.weight(1f)) { shown = true }
                        TextAction(Icons.Rounded.ContentCopy, "Copy without showing", main = true, Modifier.weight(1f)) { copyText(context, text, secret = true) }
                    } else {
                        TextAction(Icons.Rounded.ContentCopy, "Copy", main = true, Modifier.weight(1f)) { copyText(context, text, secret) }
                        when (spotted.kind) {
                            TextKind.LINK -> TextAction(Icons.Rounded.OpenInBrowser, "Open link", main = false, Modifier.weight(1f)) { onOpenLink(spotted.value) }
                            TextKind.PHONE -> {
                                TextAction(Icons.Rounded.Call, "Call", main = false, Modifier.weight(1f)) { dial(context, spotted.value) }
                                TextAction(Icons.Rounded.PersonAdd, "Save contact", main = false, Modifier.weight(1f)) { saveContact(context, spotted.value, text) }
                            }
                            TextKind.EMAIL -> TextAction(Icons.Rounded.Email, "Write email", main = false, Modifier.weight(1f)) { email(context, spotted.value) }
                            TextKind.PLAIN -> Unit
                        }
                        if (spotted.kind != TextKind.PHONE) {
                            TextAction(Icons.Rounded.Share, "Share", main = false, Modifier.weight(1f)) { shareText(context, text) }
                        }
                    }
                }
            }
            Text(
                if (secret) "Secrets aren't kept on this phone." else "Kept under Recent on this phone, to copy again later.",
                fontSize = 12.5.sp, lineHeight = 18.sp, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
            OutlinedButton(onClick = onAgain, modifier = Modifier.weight(1f)) { Text(if (sending) "Send more" else "Receive more") }
            Button(onClick = onDone, modifier = Modifier.weight(1f)) { Text("Done") }
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

@Composable
private fun TextAction(icon: ImageVector, label: String, main: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val fg = if (main) cs.onPrimary else cs.onSurface
    Column(
        modifier.heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp)).background(if (main) cs.primary else cs.surfaceContainerLow)
            .clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.5.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, color = fg, textAlign = TextAlign.Center)
    }
}

// ============================================================================ Recent: a text row
@Composable
internal fun TextRecentRow(r: QsRecord, ago: String) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val what = if (r.secret) "Secret" else "Text"
    TonalRow(
        title = if (r.incoming) "$what from ${r.peer}" else "$what to ${r.peer}",
        subtitle = if (r.text == null) "Not kept · $ago" else "“${r.text.replace('\n', ' ').take(80)}” · $ago",
        leading = {
            IconTile(if (r.secret) Icons.Rounded.Lock else Icons.Rounded.ContentCopy, container = cs.secondaryContainer, content = cs.onSecondaryContainer)
        },
        trailing = { r.text?.let { t -> TextButton(onClick = { copyText(context, t, secret = false) }) { Text("Copy") } } },
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

