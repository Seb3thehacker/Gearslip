package app.seb3thehacker.gearslip.ui

import android.Manifest
import android.content.Intent
import android.provider.Settings
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontWeight
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
 * Wireless Android Auto, in Home's status card: pick the paired car, and Gearslip connects to
 * it over Bluetooth and Wi-Fi.
 */
@Composable
internal fun WirelessButton() {
    val context = LocalContext.current
    var devices by remember { mutableStateOf<List<BluetoothDevice>?>(null) }
    var denied by remember { mutableStateOf(false) }
    fun granted() = WIRELESS_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (granted()) devices = WirelessLink.pairedDevices(context) else denied = true
    }

    if (denied) {
        Text(
            "Gearslip needs Bluetooth and nearby Wi-Fi access for this.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
    Button(
        onClick = {
            denied = false
            if (granted()) devices = WirelessLink.pairedDevices(context) else ask.launch(WIRELESS_PERMISSIONS)
        },
        modifier = Modifier.fillMaxWidth().height(64.dp),
    ) {
        Box(Modifier.size(28.dp)) { WifiIcon(MaterialTheme.colorScheme.onPrimary) }
        Spacer(Modifier.width(12.dp))
        Text("Connect wirelessly", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }

    devices?.let { list ->
        AlertDialog(
            onDismissRequest = { devices = null },
            title = { Text("Which car?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (list.isEmpty()) {
                        Text("Your phone isn't paired with anything yet. Pair it with the car first.")
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
                    // The car has to be paired before it shows up here, and pairing happens in Android's settings.
                    if (list.isNotEmpty()) {
                        Text(
                            "Car not listed? Pair it with your phone first.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    devices = null
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }) { Text("Bluetooth settings") }
            },
            confirmButton = { TextButton(onClick = { devices = null }) { Text("Cancel") } },
        )
    }
}
