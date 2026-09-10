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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
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
 * Full-screen partner verification prompt (OTP shown / code entered / number selected).
 * Push-only and one-time — closing or completing dismisses it; if the user missed the push,
 * the partner simply re-requests.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ActionScreen(
    action: ActionDto,
    onSubmit: suspend (value: String) -> Result<Unit>,
    onClose: () -> Unit,
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
