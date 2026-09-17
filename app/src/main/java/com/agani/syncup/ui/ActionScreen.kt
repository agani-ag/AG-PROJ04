package com.agani.syncup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.data.ActionDto
import kotlinx.coroutines.launch

/**
 * Full-screen partner verification prompt (OTP shown / code entered / number selected / notice
 * read + acknowledged). Push-only and one-time — closing or completing dismisses it; if the user
 * missed the push, the partner simply re-requests.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ActionScreen(
    action: ActionDto,
    onSubmit: suspend (value: String) -> Result<Unit>,
    onClose: () -> Unit,
    onOpenLink: (String) -> Unit = {},
) {
    var submitting by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var codeInput by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    fun submit(value: String) {
        if (submitting) return
        submitting = true
        error = null
        scope.launch {
            onSubmit(value).fold(
                onSuccess = { submitting = false; done = true },
                onFailure = { submitting = false; error = it.message ?: "Couldn't submit. Try again." },
            )
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        // A "notice" (info + acknowledge) gets its own top-aligned, scrollable layout — the user
        // must read to the bottom before the Acknowledge button enables.
        if (action.type == "notice" && !done) {
            NoticeContent(
                action = action,
                padding = padding,
                submitting = submitting,
                error = error,
                onOpenLink = onOpenLink,
                onAcknowledge = { submit("") },
                onClose = onClose,
            )
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .then(Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (done) Icons.Rounded.CheckCircle else Icons.Rounded.VerifiedUser,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(56.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                if (done) "Done" else action.title.ifBlank { "Verification" },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            if (!done && action.message.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    action.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(28.dp))

            when {
                done -> {
                    Text(
                        "You can return to where you started.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onClose, modifier = Modifier.fillMaxWidth().widthIn(max = 320.dp)) {
                        Text("Close")
                    }
                }

                action.type == "otp" -> {
                    val code = action.params.code.orEmpty()
                    Text(
                        code,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 8.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString(code))
                        android.widget.Toast.makeText(context, "Code copied", android.widget.Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Copy code")
                    }
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = { submit("") },
                        enabled = !submitting,
                        modifier = Modifier.fillMaxWidth().widthIn(max = 320.dp),
                    ) { Text(if (submitting) "…" else "Done") }
                }

                action.type == "code" -> {
                    val len = action.params.length ?: 6
                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = { v -> if (v.length <= len && v.all { it.isDigit() }) codeInput = v },
                        label = { Text("Enter the $len-digit code") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth().widthIn(max = 320.dp),
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { submit(codeInput) },
                        enabled = !submitting && codeInput.length == len,
                        modifier = Modifier.fillMaxWidth().widthIn(max = 320.dp),
                    ) { Text(if (submitting) "Submitting…" else "Submit") }
                }

                action.type == "number" -> {
                    Text(
                        "Tap the number shown where you started",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        (action.params.numbers ?: emptyList()).forEach { n ->
                            OutlinedButton(onClick = { submit(n) }, enabled = !submitting) {
                                Text(n, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                action.type == "approve" -> {
                    Button(
                        onClick = { submit("approved") },
                        enabled = !submitting,
                        modifier = Modifier.fillMaxWidth().widthIn(max = 320.dp),
                    ) { Text(action.params.approveLabel?.ifBlank { "Approve" } ?: "Approve") }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { submit("rejected") },
                        enabled = !submitting,
                        modifier = Modifier.fillMaxWidth().widthIn(max = 320.dp),
                    ) {
                        Text(
                            action.params.rejectLabel?.ifBlank { "Reject" } ?: "Reject",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                else -> {
                    Text("Unsupported verification.", color = MaterialTheme.colorScheme.error)
                }
            }

            if (submitting) {
                Spacer(Modifier.height(20.dp))
                CircularProgressIndicator()
            }
            if (error != null) {
                Spacer(Modifier.height(14.dp))
                Text(error!!, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
            if (!done) {
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onClose) { Text("Cancel", color = Color.Gray) }
            }
        }
    }
}

/**
 * "Info + acknowledge" notice — a scrollable body the user must read to the bottom, an optional
 * CTA button that opens a link in the in-app browser, then an "I Acknowledge" button that stays
 * disabled until the body has been scrolled through.
 */
@Composable
private fun NoticeContent(
    action: ActionDto,
    padding: androidx.compose.foundation.layout.PaddingValues,
    submitting: Boolean,
    error: String?,
    onOpenLink: (String) -> Unit,
    onAcknowledge: () -> Unit,
    onClose: () -> Unit,
) {
    val scroll = rememberScrollState()
    // A short notice fits without overflowing (maxValue == 0) — we present it as a compact,
    // centered card and enable Acknowledge right away. A long one becomes a top-aligned scroll
    // and Acknowledge stays disabled until the body is scrolled to the end.
    val scrollable = scroll.maxValue > 0
    val atBottom = !scrollable || scroll.value >= scroll.maxValue - 4
    val bodyAlign = if (scrollable) TextAlign.Start else TextAlign.Center

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        // Body area — vertically centered when short (feels like a notification), scrollable when long.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (scrollable) Arrangement.Top else Arrangement.Center,
        ) {
            Icon(
                Icons.Rounded.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(if (scrollable) 40.dp else 52.dp),
            )
            Spacer(Modifier.height(16.dp))
            if (action.title.isNotBlank()) {
                Text(
                    action.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
            }
            Text(
                action.params.body.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = bodyAlign,
                modifier = Modifier.fillMaxWidth(),
            )
            if (action.message.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    action.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = bodyAlign,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val cta = action.params.ctaUrl
            if (!cta.isNullOrBlank()) {
                Spacer(Modifier.height(20.dp))
                OutlinedButton(onClick = { onOpenLink(cta) }, modifier = Modifier.fillMaxWidth()) {
                    Text(action.params.ctaLabel?.ifBlank { "View details" } ?: "View details")
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (scrollable && !atBottom) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Scroll down to read it all before you can acknowledge",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onAcknowledge,
            enabled = atBottom && !submitting,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (submitting) "…" else "I Acknowledge") }
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("Close", color = Color.Gray)
        }
    }
}
