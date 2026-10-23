package com.agani.syncup.decide

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.music.CardDivider
import com.agani.syncup.music.ToolTint
import com.agani.syncup.ui.theme.CardShape
import com.agani.syncup.ui.theme.appCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// Decide → Yes / No (docs/mockup/syncup-everyday-tools.html): type a question if you like, tap Ask —
// Yes, No or Maybe, each as likely. Earlier answers stay underneath.

private const val K_HISTORY = "yn_history"

/** Earlier answers listed under the circle. */
private const val HISTORY_SHOWN = 5

private const val MAX_QUESTION = 200

private enum class Answer(val word: String, val short: String, val line: String, val tint: ToolTint) {
    YES("Yes!", "Yes", "Go for it", ToolTint.GREEN),
    NO("No", "No", "Better not", ToolTint.ROSE),
    MAYBE("Maybe", "Maybe", "Ask again later", ToolTint.AMBER),
}

@Immutable
private data class Asked(val question: String, val answer: Answer)

/** Decide → Yes / No. */
@Composable
internal fun YesNoScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val prefs = remember { context.getSharedPreferences(DECIDE_PREFS, Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    var question by rememberSaveable { mutableStateOf("") }
    // Newest first: the one in the circle (if asked on this visit) and the earlier ones.
    var history by remember { mutableStateOf(readHistory(prefs.getString(K_HISTORY, null))) }
    // The answer in the circle; null shows "?" (just opened, nothing asked yet).
    var current by remember { mutableStateOf<Answer?>(null) }
    // True once this visit's answer has landed — it's then history[0], shown in the circle, not the list.
    var answered by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    val pop = remember { Animatable(1f) }

    fun save(list: List<Asked>) {
        history = list
        prefs.edit().putString(K_HISTORY, writeHistory(list)).apply()
    }

    fun ask() {
        if (asking) return
        focus.clearFocus()
        val result = Answer.entries.random()
        val q = question.trim()
        scope.launch {
            asking = true
            // The last answer leaves the circle: it joins the earlier ones while this one shuffles.
            answered = false
            try {
                // A short shuffle through the three answers, then the real one pops in.
                var a = current ?: Answer.MAYBE
                for (i in 0 until 7) {
                    a = Answer.entries[(a.ordinal + 1) % Answer.entries.size]
                    current = a
                    DecideFx.tick(context)
                    pop.snapTo(.92f)
                    delay(55L + i * 8L)
                }
                current = result
                answered = true
                save((listOf(Asked(q, result)) + history).take(HISTORY_SHOWN + 1))
                DecideFx.land(context)
                DecideFx.buzz(context, 25, 150)
            } finally {
                asking = false
            }
            pop.animateTo(1f, spring(dampingRatio = .38f, stiffness = 450f), initialVelocity = 3f)
        }
    }

    DecidePage("Yes / No", onBack, actions = {
        DecideMoreMenu { close ->
            if (history.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text("Clear answers") },
                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                    onClick = {
                        close()
                        save(emptyList())
                        current = null
                        answered = false
                    },
                )
            }
        }
    }) {
        DecideField(
            question, { question = it.take(MAX_QUESTION) },
            label = "Your question (optional)", placeholder = "Should I…?", onDone = ::ask,
        )

        val shown = current
        val bg by animateColorAsState(shown?.tint?.tile ?: cs.surfaceContainer, tween(120), label = "answer")
        val fg by animateColorAsState(shown?.tint?.icon ?: cs.onSurfaceVariant, tween(120), label = "answerText")
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 22.dp)
                .graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                }
                .size(200.dp).clip(CircleShape).background(bg),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(shown?.word ?: "?", fontSize = 52.sp, lineHeight = 58.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
                Text(shown?.line ?: "Tap Ask", fontSize = 13.sp, color = fg.copy(alpha = .85f), modifier = Modifier.padding(top = 2.dp))
            }
        }
        Text(
            "Yes, No or Maybe — at random", fontSize = 13.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
        Box(Modifier.padding(top = 16.dp)) {
            DecideButton("Ask", DecideTint.YES_NO, onClick = ::ask)
        }

        // Earlier answers: everything but the one in the circle.
        val earlier = (if (answered) history.drop(1) else history).take(HISTORY_SHOWN)
        if (earlier.isNotEmpty()) {
            Column(Modifier.padding(top = 22.dp).fillMaxWidth().appCard(CardShape)) {
                earlier.forEachIndexed { i, asked ->
                    if (i > 0) CardDivider()
                    HistoryRow(asked)
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(asked: Asked) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Icon(Icons.Rounded.History, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            asked.question.ifEmpty { "No question" }, fontSize = 15.sp,
            color = if (asked.question.isEmpty()) cs.onSurfaceVariant else cs.onSurface,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Text(asked.answer.short, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = asked.answer.tint.icon)
    }
}

private fun readHistory(text: String?): List<Asked> = runCatching {
    val arr = JSONArray(text ?: return emptyList())
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val answer = Answer.entries.firstOrNull { it.name == o.optString("a") } ?: return@mapNotNull null
        Asked(o.optString("q"), answer)
    }
}.getOrDefault(emptyList())

private fun writeHistory(list: List<Asked>): String =
    JSONArray().apply { list.forEach { put(JSONObject().put("q", it.question).put("a", it.answer.name)) } }.toString()
