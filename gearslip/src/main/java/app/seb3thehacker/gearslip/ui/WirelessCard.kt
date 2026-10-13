package app.seb3thehacker.gearslip.ui

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.seb3thehacker.gearslip.wireless.WirelessLink

/** What the wireless start-up needs: Bluetooth to reach the car, nearby Wi-Fi to join its network, local network to talk to it. */
private val WIRELESS_PERMISSIONS = buildList {
    add(Manifest.permission.BLUETOOTH_CONNECT)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    // Android 17 silently drops traffic to local addresses, the car included, without it.
    if (Build.VERSION.SDK_INT >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
    add(Manifest.permission.ACCESS_FINE_LOCATION)
}.toTypedArray()

/**
 * Test entry point for wireless Android Auto: pick the paired car, and Gearslip connects to it
 * over Bluetooth and Wi-Fi. Automatic start comes later.
 */
@Composable
internal fun WirelessCard() {
    val context = LocalContext.current
    var devices by remember { mutableStateOf<List<BluetoothDevice>?>(null) }
    var denied by remember { mutableStateOf(false) }
    fun granted() = WIRELESS_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (granted()) devices = WirelessLink.pairedDevices(context) else denied = true
    }

    SettingsCard {
        SettingsRow(
            "Connect wirelessly (test)",
            Modifier.clickable {
                denied = false
                if (granted()) devices = WirelessLink.pairedDevices(context) else ask.launch(WIRELESS_PERMISSIONS)
            },
            subtitle = if (denied) "Gearslip needs Bluetooth and nearby Wi-Fi access for this"
            else "Pick your car from the phone's paired devices",
            icon = Icons.Filled.Info,
        )
    }

    devices?.let { list ->
        AlertDialog(
            onDismissRequest = { devices = null },
            title = { Text("Which car?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (list.isEmpty()) {
                        Text("No paired devices. Pair the phone with the car over Bluetooth first.")
                    }
                    list.forEach { device ->
                        Text(
                            WirelessLink.name(device),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    devices = null
                                    WirelessLink.start(context, device)
                                }
                                .padding(vertical = 14.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { devices = null }) { Text("Cancel") } },
        )
    }
    Spacer(Modifier.height(16.dp))
}
