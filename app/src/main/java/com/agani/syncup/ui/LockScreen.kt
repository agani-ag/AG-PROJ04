package com.agani.syncup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun LockScreen(
    biometricEnabled: Boolean,
    onCheckPin: (String) -> Boolean,
    onUnlock: () -> Unit,
    onBiometric: () -> Unit,
    onForgotPin: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var attempts by remember { mutableStateOf(0) }
    var showForgot by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (biometricEnabled) onBiometric() }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(34.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "SyncUp is locked",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "Enter your 4-digit PIN",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            PinInput(
                value = pin,
                isError = error,
                // Don't grab focus (and pop the keyboard) when biometric is the primary method —
                // the fingerprint/face prompt shows instead. Tapping the field still types a PIN.
                autoFocus = !biometricEnabled,
                onValueChange = { v ->
                    error = false
                    pin = v
                    if (v.length == PIN_LENGTH) {
                        if (onCheckPin(v)) {
                            onUnlock()
                        } else {
                            error = true
                            pin = ""
                            attempts++
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 320.dp),
            )
            if (error) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Incorrect PIN",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (biometricEnabled) {
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onBiometric) {
                    Icon(Icons.Rounded.Fingerprint, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Use biometric")
                }
            }
            if (attempts >= 2) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { showForgot = true }) { Text("Forgot PIN?") }
            }
        }
    }

    if (showForgot) {
        AlertDialog(
            onDismissRequest = { showForgot = false },
            title = { Text("Forgot PIN?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "You'll be signed out and can sign in again with your email and password. " +
                        "Your admin can reset your password if needed.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    showForgot = false
                    onForgotPin()
                }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { showForgot = false }) { Text("Cancel") } },
        )
    }
}
