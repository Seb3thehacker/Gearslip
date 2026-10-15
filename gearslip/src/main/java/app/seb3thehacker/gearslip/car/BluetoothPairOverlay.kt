package app.seb3thehacker.gearslip.car

import app.seb3thehacker.gearslip.car.theme.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Asks once per car whether to pair it over Bluetooth, then waits while the phone shows its
 * pairing dialog. See [CarBluetooth]. With no pairing to do, says when Bluetooth audio isn't
 * connected (see [BluetoothAudioCheck]), or else when the phone's location is off (see
 * [LocationCheck]). Unlike the call prompt, a tap outside the card does nothing:
 * the driver picks Pair or Not now.
 */
@Composable
internal fun BluetoothPairOverlay() {
    val prompt by CarBluetooth.prompt.collectAsState()
    val bluetoothAudio = !CarSettings.pipeAudio.collectAsState().value
    when (prompt) {
        CarBluetooth.Prompt.NONE -> {
            val missing by BluetoothAudioCheck.missing.collectAsState()
            val dismissed by BluetoothAudioCheck.carDismissed.collectAsState()
            val warn by CarSettings.bluetoothWarning.collectAsState()
            val locationOff by LocationCheck.off.collectAsState()
            val locationDismissed by LocationCheck.carDismissed.collectAsState()
            if (missing && bluetoothAudio && warn && !dismissed) PairCard(
                title = "Bluetooth isn't connected",
                body = "Music plays over Bluetooth, but your phone isn't connected to the car. " +
                    "Connect it from the car's Bluetooth menu or your phone's.",
                secondary = "Don't show again" to BluetoothAudioCheck::neverAgain,
                primary = "OK" to BluetoothAudioCheck::dismissOnCar,
            ) else if (locationOff && !locationDismissed) PairCard(
                title = "Location is off",
                body = "Map apps can't show where you are or give directions. Turn on location " +
                    "in your phone's quick settings when you're parked.",
                secondary = "Don't show again" to LocationCheck::neverAgain,
                primary = "OK" to LocationCheck::dismissOnCar,
            )
        }
        CarBluetooth.Prompt.ASK -> PairCard(
            title = "Pair with this car?",
            body = (if (bluetoothAudio) "Calls and music play" else "Calls play") +
                " through the car's speakers once your phone and the car are paired. Your phone " +
                "then asks you to confirm a code.",
            secondary = "Not now" to CarBluetooth::notNow,
            primary = "Pair" to CarBluetooth::pair,
        )
        CarBluetooth.Prompt.PAIRING -> PairCard(
            title = "Confirm on your phone",
            body = "Check the code on your phone and tap Pair. The car may ask too.",
            secondary = null,
            primary = "Hide" to CarBluetooth::hide,
        )
    }
}

@Composable
private fun PairCard(title: String, body: String, secondary: Pair<String, () -> Unit>?, primary: Pair<String, () -> Unit>) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() },
            onClick = {},
        ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(0.85f).padding(16.dp),
        ) {
            Column(
                Modifier.padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    body,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    secondary?.let { (label, onClick) ->
                        GsButton(onClick = onClick, tone = GsTone.Outline, modifier = Modifier.weight(1f).height(56.dp)) {
                            Text(label, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    GsButton(onClick = primary.second, modifier = Modifier.weight(1f).height(56.dp)) {
                        Text(primary.first, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}
