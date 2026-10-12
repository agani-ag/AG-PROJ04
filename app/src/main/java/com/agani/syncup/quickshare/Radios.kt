package com.agani.syncup.quickshare

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority

/**
 * What SyncUp Share needs switched on (besides its permissions): Bluetooth to find the other phone,
 * Wi-Fi to send the files fast (Wi-Fi only has to be on — no network needed), and, on Android 12
 * and older, Location, which those versions need to find nearby phones at all.
 */
internal enum class Radio { BLUETOOTH, WIFI, LOCATION }

internal object Radios {
    /** The switches this phone needs (Location only on Android 12 and older). */
    fun needed(): List<Radio> = buildList {
        add(Radio.BLUETOOTH)
        add(Radio.WIFI)
        if (Build.VERSION.SDK_INT <= 32) add(Radio.LOCATION)
    }

    fun bluetoothSupported(context: Context): Boolean = bluetooth(context) != null

    fun isOn(context: Context, radio: Radio): Boolean = runCatching {
        when (radio) {
            Radio.BLUETOOTH -> bluetooth(context)?.isEnabled == true
            // No Wi-Fi at all (very rare): nothing to ask for — Nearby uses what there is.
            Radio.WIFI -> (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled ?: true
            Radio.LOCATION -> (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)
                ?.let { LocationManagerCompat.isLocationEnabled(it) } ?: true
        }
    }.getOrDefault(true)

    /** The switches still off. */
    fun missing(context: Context): List<Radio> = needed().filterNot { isOn(context, it) }

    private fun bluetooth(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
}

/**
 * "Turn these on" before sending / receiving: each switch with its state and a Turn on button,
 * updated live as the user flips them (here, in the system panel or in Settings). As soon as all
 * are on, [onReady] runs — the send / receive carries on by itself.
 */
@Composable
internal fun RadiosDialog(receiving: Boolean, onReady: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var tick by remember { mutableIntStateOf(0) }

    // The switches announce their changes; coming back from a settings screen re-checks too.
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                tick++
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }

    val radios = Radios.needed()
    val on = remember(tick) { radios.associateWith { Radios.isOn(context, it) } }
    LaunchedEffect(on) { if (on.values.all { it }) onReady() }

    val afterSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    val afterLocation = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { tick++ }

    fun open(intent: Intent) {
        runCatching { afterSettings.launch(intent) }
    }

    fun turnOn(radio: Radio) {
        when (radio) {
            // Android's own "SyncUp wants to turn on Bluetooth" — Allow.
            Radio.BLUETOOTH -> runCatching { afterSettings.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                .onFailure { open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            // Apps can't switch Wi-Fi on themselves: the Wi-Fi panel (Android 10+) or Wi-Fi settings.
            Radio.WIFI -> open(Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS))
            // Google's one-tap "Turn on location" dialog, else Location settings.
            Radio.LOCATION -> {
                val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10_000L).build()
                LocationServices.getSettingsClient(context)
                    .checkLocationSettings(LocationSettingsRequest.Builder().addLocationRequest(request).setAlwaysShow(true).build())
                    .addOnSuccessListener { tick++ }
                    .addOnFailureListener { e ->
                        val shown = e is ResolvableApiException &&
                            runCatching { afterLocation.launch(IntentSenderRequest.Builder(e.resolution).build()) }.isSuccess
                        if (!shown) open(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }
            }
        }
    }

    if (!Radios.bluetoothSupported(context)) {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("SyncUp Share can't work here") },
            text = { Text("This phone has no Bluetooth, which SyncUp Share uses to find the other phone.") },
            confirmButton = { TextButton(onClick = onCancel) { Text("OK") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (receiving) "Turn on to receive" else "Turn on to send") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "SyncUp Share uses Bluetooth to find the other phone and Wi-Fi to send the files fast. " +
                        "You don't need to join a Wi-Fi network, and no mobile data is used.",
                    fontSize = 14.sp, lineHeight = 20.sp,
                )
                radios.forEach { radio ->
                    RadioRow(radio, on[radio] == true) { turnOn(radio) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        containerColor = cs.surfaceContainerHigh,
    )
}

@Composable
private fun RadioRow(radio: Radio, isOn: Boolean, onTurnOn: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val (icon, name, why) = when (radio) {
        Radio.BLUETOOTH -> Triple(Icons.Rounded.Bluetooth, "Bluetooth", "Finds the other phone")
        Radio.WIFI -> Triple(Icons.Rounded.Wifi, "Wi-Fi", "Sends the files fast")
        Radio.LOCATION -> Triple(Icons.Rounded.LocationOn, "Location", "This Android version needs it to find phones")
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, null, tint = if (isOn) cs.primary else cs.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
            Text(why, fontSize = 12.5.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant)
        }
        if (isOn) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
                Icon(Icons.Rounded.CheckCircle, null, tint = cs.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("On", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.primary)
            }
        } else {
            Button(onClick = onTurnOn, modifier = Modifier.padding(start = 8.dp)) { Text("Turn on") }
        }
    }
}
