package com.agani.syncup.browser

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.auth.api.identity.GetPhoneNumberHintIntentRequest
import com.google.android.gms.auth.api.identity.Identity

/**
 * The phone's own numbers on the sign-in / sign-up Phone field: Google's Phone Number Hint (Play
 * services) shows its own "Choose a phone number" sheet with the SIM numbers — one SIM shows one, two
 * SIMs show two. No permission is needed, and SyncUp only gets a number when the person taps it.
 * Many Indian SIMs don't carry their own number: then nothing appears and the field works as before.
 *
 * Returns what to call to show the sheet; [onNumber] gets the picked number as 10 digits (+91 dropped).
 */
@Composable
internal fun rememberPhoneHint(onNumber: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val deliver by rememberUpdatedState(onNumber)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        runCatching { Identity.getSignInClient(context).getPhoneNumberFromIntent(result.data) }
            .getOrNull()?.let(::tenDigits)?.let(deliver)
    }
    return {
        runCatching {
            Identity.getSignInClient(context)
                .getPhoneNumberHintIntent(GetPhoneNumberHintIntentRequest.builder().build())
                .addOnSuccessListener { pending ->
                    runCatching { launcher.launch(IntentSenderRequest.Builder(pending.intentSender).build()) }
                }
            // No numbers on the SIMs, or no Play services: nothing to offer — the field is still there to type in.
        }
    }
}

/** "+91 88259 75787" → "8825975787" (the field shows +91 itself). Null if it isn't an Indian mobile. */
private fun tenDigits(raw: String): String? {
    var d = raw.filter { it.isDigit() }
    if (d.length == 12 && d.startsWith("91")) d = d.drop(2)
    if (d.length == 11 && d.startsWith("0")) d = d.drop(1)
    return d.takeIf { it.length == 10 && it[0] in '6'..'9' }
}
