package app.seb3thehacker.gearslip.ui

import app.seb3thehacker.gearslip.BuildConfig
import app.seb3thehacker.gearslip.car.CarSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * One step: a sentence that says what to do, and optionally how.
 *
 * [command] is for a step that cannot be done on the phone; it is shown as something to copy
 * rather than to read, because a mistyped package name fails silently. [onClick] is for a step
 * that can, in one tap, in which case it is shown as a button instead.
 */
private class Step(
    val action: String,
    val detail: String? = null,
    val command: String? = null,
    val buttonLabel: String? = null,
    val onClick: (() -> Unit)? = null,
)

private val FIRST_DRIVE = listOf(
    Step("Turn off Android Auto.", "Disable or uninstall the Android Auto app, so the car talks to Gearslip instead."),
    Step("Load a certificate.", "In Settings, download one or import your own."),
    Step(
        "Allow the microphone.",
        "Android won't let an app pass music to the car without it. Gearslip listens only when " +
            "you tap the mic or press the talk button on your wheel.",
    ),
)

private fun inTheCar(onRequestCallScreening: () -> Unit) = listOf(
    Step(
        "Plug in, or connect wirelessly.",
        "Gearslip opens by itself when the car connects. For wireless, pair the phone with the " +
            "car first, then tap Connect wirelessly on Home.",
    ),
    Step(
        "Pair over Bluetooth for calls.",
        "Calls play through the car once your phone and the car are paired. Gearslip offers to " +
            "pair the first time you plug in.",
    ),
    Step(
        "Answer calls from the car screen.",
        "Set Gearslip as your caller ID and spam app. Without it, calls still ring, but only " +
            "the phone can answer them.",
        buttonLabel = "Set up",
        onClick = onRequestCallScreening,
    ),
)

private val USB_AUDIO = listOf(
    Step(
        "Stop the audio prompt each drive.",
        "Connect the phone to a computer with USB debugging on, and run this once. Skip it and " +
            "you tap Start each time you set off.",
        command = "adb shell appops set ${BuildConfig.APPLICATION_ID} PROJECT_MEDIA allow",
    ),
    Step(
        "Keep notifications readable.",
        "Android hides what notifications say while it shares audio. Run this too, and Gearslip " +
            "lifts that only while music goes to the car.",
        command = "adb shell pm grant ${BuildConfig.APPLICATION_ID} android.permission.WRITE_SECURE_SETTINGS",
    ),
)

/** Which cars work, and the setup the one-time guide walks through, to reread at any time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit, onRequestCallScreening: () -> Unit) {
    val car = remember(onRequestCallScreening) { inTheCar(onRequestCallScreening) }
    val usb by CarSettings.pipeAudio.collectAsState()
    val context = LocalContext.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("How to connect") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Spacer(Modifier.height(0.dp))
            SettingsSection("Which cars work") {
                Text(
                    "Most cars work. Starting around 2020, some cars added newer security " +
                        "standards that reject Gearslip's certificate, and Gearslip can't run in those cars.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                )
                FilledTonalButton(
                    onClick = { GearslipLinks.open(context, GearslipLinks.CARS) },
                    modifier = Modifier.padding(16.dp),
                ) { Text("See the full list") }
            }
            Steps("Before your first drive", FIRST_DRIVE)
            Steps("In the car", car)
            if (usb) Steps("USB audio", USB_AUDIO)
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** A titled card of numbered steps. */
@Composable
private fun Steps(title: String, steps: List<Step>) {
    SettingsSection(title) {
        steps.forEachIndexed { index, step ->
            if (index > 0) SettingsDivider()
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(16.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(step.action, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    step.detail?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    step.command?.let { CopyableCommand(it) }
                    if (step.onClick != null) {
                        FilledTonalButton(onClick = step.onClick, modifier = Modifier.padding(top = 4.dp)) {
                            Text(step.buttonLabel ?: "Open")
                        }
                    }
                }
            }
        }
    }
}
