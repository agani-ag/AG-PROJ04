package com.agani.syncup.shopping

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.music.ToolTint
import com.agani.syncup.ui.theme.appCard
import com.agani.syncup.wellbeing.RoundButton

/**
 * Split bill: the amount ÷ people, worked out in paise so it adds up exactly — when it doesn't split
 * evenly, the first few people pay one paisa more. No tip, no tax, nothing saved.
 */
internal object Split {
    const val MIN_PEOPLE = 1
    const val MAX_PEOPLE = 50

    /** Each one's share in paise, and how many people pay one paisa more. */
    data class Share(val total: Long, val people: Int, val each: Long, val extra: Int)

    fun of(totalPaise: Long, people: Int): Share {
        val n = people.coerceIn(MIN_PEOPLE, MAX_PEOPLE)
        val t = totalPaise.coerceAtLeast(0)
        return Share(t, n, t / n, (t % n).toInt())
    }

    /** "1,000.5" / "1000.50" → 100050; null when it isn't an amount. */
    fun paise(text: String): Long? {
        val t = text.replace(",", "").trim()
        if (t.isEmpty() || t == ".") return null
        if (!t.all { it.isDigit() || it == '.' } || t.count { it == '.' } > 1) return null
        val dot = t.indexOf('.')
        val rupees = (if (dot >= 0) t.substring(0, dot) else t).ifEmpty { "0" }
        val frac = if (dot >= 0) t.substring(dot + 1) else ""
        if (rupees.length > 12 || frac.length > 2) return null
        return rupees.toLong() * 100 + frac.padEnd(2, '0').toLong()
    }

    /** 100000 → "₹1,000"; 33333 → "₹333.33" (Indian grouping: ₹1,00,000). */
    fun money(paise: Long): String {
        val r = paise / 100
        val p = paise % 100
        return "₹" + group(r.toString()) + if (p == 0L) "" else String.format(java.util.Locale.US, ".%02d", p)
    }

    /** Indian digit grouping: 1234567 → 12,34,567. */
    fun group(digits: String): String {
        if (digits.length <= 3) return digits
        val last = digits.takeLast(3)
        var rest = digits.dropLast(3)
        val parts = ArrayList<String>()
        while (rest.length > 2) {
            parts.add(0, rest.takeLast(2))
            rest = rest.dropLast(2)
        }
        if (rest.isNotEmpty()) parts.add(0, rest)
        return parts.joinToString(",") + "," + last
    }

    /** The small note when it doesn't split evenly; null when it does. */
    fun note(s: Share): String? {
        if (s.extra == 0) return null
        val who = if (s.extra == 1) "1 person pays" else "${s.extra} people pay"
        return "${money(s.total)} doesn't split evenly — $who ${money(s.each + 1)}, so it adds up exactly."
    }

    /** "Bill ₹1,200 ÷ 4 = ₹300 each". */
    fun shareText(s: Share): String {
        val extra = if (s.extra == 0) "" else " (${if (s.extra == 1) "1 person pays" else "${s.extra} people pay"} ${money(s.each + 1)})"
        return "Bill ${money(s.total)} ÷ ${s.people} = ${money(s.each)} each$extra"
    }

    /**
     * What the field may hold after typing [new]: digits with one dot and up to 2 decimals, no leading
     * zeros, at most 9 digits of rupees. Null = ignore that key.
     */
    fun accept(new: String): String? {
        val t = new.replace(",", "").replace(" ", "")
        if (t.isEmpty()) return ""
        if (!t.all { it.isDigit() || it == '.' } || t.count { it == '.' } > 1) return null
        val dot = t.indexOf('.')
        val ip = if (dot >= 0) t.substring(0, dot) else t
        val fp = if (dot >= 0) t.substring(dot + 1) else ""
        if (fp.length > 2) return null
        val whole = ip.trimStart('0').ifEmpty { "0" }
        if (whole.length > 9) return null
        return if (dot >= 0) "$whole.$fp" else whole
    }
}

/** Shows the typed amount with Indian commas (the field itself holds plain digits). */
private object IndianGrouping : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val dot = raw.indexOf('.')
        val ip = if (dot >= 0) raw.substring(0, dot) else raw
        val grouped = Split.group(ip)
        val out = grouped + raw.substring(ip.length)
        val o2t = IntArray(raw.length + 1)
        val t2o = IntArray(out.length + 1)
        var o = 0
        var t = 0
        while (t < out.length) {
            if (t < grouped.length && out[t] == ',') {
                t2o[t] = o
                t++
                continue
            }
            o2t[o] = t
            t2o[t] = o
            o++
            t++
        }
        o2t[raw.length] = out.length
        t2o[out.length] = raw.length
        return TransformedText(
            AnnotatedString(out),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int) = o2t[offset.coerceIn(0, raw.length)]
                override fun transformedToOriginal(offset: Int) = t2o[offset.coerceIn(0, out.length)]
            },
        )
    }
}

@Composable
internal fun SplitBillTab(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val g = ToolTint.GREEN
    var amount by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var people by rememberSaveable { mutableIntStateOf(2) }
    val focus = remember { FocusRequester() }
    val share = Split.of(Split.paise(amount.text) ?: 0L, people)

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        Text("Bill amount", fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, top = 16.dp, bottom = 10.dp))
        // The amount: a big ₹ number.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().appCard()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { runCatching { focus.requestFocus() } }
                .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 14.dp),
        ) {
            Text("₹", fontSize = 26.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(end = 6.dp, top = 4.dp))
            BasicTextField(
                value = amount,
                onValueChange = { v ->
                    val ok = Split.accept(v.text) ?: return@BasicTextField
                    amount = if (ok == v.text) v else TextFieldValue(ok, TextRange(ok.length))
                },
                singleLine = true,
                textStyle = TextStyle(fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                visualTransformation = IndianGrouping,
                modifier = Modifier.weight(1f).focusRequester(focus),
                decorationBox = { field ->
                    Box {
                        if (amount.text.isEmpty()) Text("0", fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant.copy(alpha = .5f))
                        field()
                    }
                },
            )
        }
        // People, including you: − n +.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 12.dp).fillMaxWidth().appCard().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Icon(Icons.Rounded.Groups, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("People", fontSize = 15.sp, color = cs.onSurface)
                Text("Including you", fontSize = 12.5.sp, color = cs.onSurfaceVariant)
            }
            RoundButton("−", "Fewer people", onClick = { people = (people - 1).coerceAtLeast(Split.MIN_PEOPLE) })
            Text(
                "$people", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface, textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 44.dp),
            )
            RoundButton("+", "More people", onClick = { people = (people + 1).coerceAtMost(Split.MAX_PEOPLE) })
        }
        // Each one's share, big.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 14.dp).fillMaxWidth().background(g.tile, RoundedCornerShape(26.dp)).padding(20.dp),
        ) {
            Text(Split.money(share.each), fontSize = 44.sp, lineHeight = 50.sp, fontWeight = FontWeight.SemiBold, color = g.icon, textAlign = TextAlign.Center)
            Text("each", fontSize = 14.sp, color = g.icon)
            Split.note(share)?.let {
                Text(
                    it, fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurface.copy(alpha = .75f), textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        ShopSolidButton(
            "Share", g, icon = Icons.Rounded.Share, enabled = share.total > 0,
            onClick = { shareText(context, Split.shareText(share)) },
        )
    }
}
