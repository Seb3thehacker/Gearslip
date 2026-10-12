package app.seb3thehacker.gearslip.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.BuildConfig

/**
 * Shown once, the first time the driver turns down Android's screen-sharing dialog: that dialog
 * is the price of sending music over USB, and most people who refuse it would rather not see it
 * again. Offers the two ways out: Bluetooth, or the one-time adb command.
 */
@Composable
fun AudioChoiceDialog(onUseBluetooth: () -> Unit, onKeepAsking: () -> Unit) {
    var showCommand by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onKeepAsking,
        title = { Text("Play car audio over Bluetooth?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Android treats music sent over USB like sharing your screen, so it asks every " +
                        "drive. Over Bluetooth it never asks. You can change this later in Settings.",
                )
                if (showCommand) {
                    Text(
                        "Or keep USB and stop the question for good: plug the phone into a " +
                            "computer with USB debugging on, and run this once.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    CopyableCommand("adb shell appops set ${BuildConfig.APPLICATION_ID} PROJECT_MEDIA allow")
                } else {
                    TextButton(onClick = { showCommand = true }) { Text("Never ask, using a computer") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onUseBluetooth) { Text("Use Bluetooth") } },
        dismissButton = { TextButton(onClick = onKeepAsking) { Text("Keep asking") } },
    )
}
