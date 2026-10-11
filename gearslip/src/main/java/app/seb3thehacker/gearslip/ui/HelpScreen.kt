package app.seb3thehacker.gearslip.ui

import app.seb3thehacker.gearslip.BuildConfig
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * One step of the setup list: a sentence that says what to do, and optionally how.
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

private fun setupSteps(onRequestCallScreening: () -> Unit) = listOf(
    Step("Use a car from 2020 or earlier.", "Newer head units reject the certificate."),
    Step("Disable or uninstall the Android Auto app."),
    Step("Load a certificate.", "Open Settings, then download or import a certificate."),
    Step(
        "Allow the microphone, so music can reach the car.",
        "Android asks for the microphone before it will let any app pass music along. " +
            "Gearslip listens only when you tap Reply or press the voice button. The rest of the " +
            "time it copies what a music app is playing, and nothing else.",
    ),
    Step(
        "Optional: stop Android asking about audio every drive.",
        "Connect the phone to a computer with USB debugging turned on, and run this command. " +
            "It tells Android to trust Gearslip with audio from now on. Skip it and everything " +
            "still works, but you have to tap Start once each time you set off.",
        command = "adb shell appops set ${BuildConfig.APPLICATION_ID} PROJECT_MEDIA allow",
    ),
    Step(
        "Optional: keep notifications readable while music plays.",
        "Android treats sending audio to the car like sharing your screen, and hides what your " +
            "notifications say. Run this command too, and Gearslip switches that off only while " +
            "music is going to the car, then puts it back.",
        command = "adb shell pm grant ${BuildConfig.APPLICATION_ID} android.permission.WRITE_SECURE_SETTINGS",
    ),
    Step(
        "Optional: let the car screen answer and decline calls.",
        "This sets Gearslip as your phone's caller ID and spam-blocking app, the same kind of " +
            "consent a call-blocking app asks for. Without it, calls still ring, but only the " +
            "phone itself can answer or decline them.",
        buttonLabel = "Set up",
        onClick = onRequestCallScreening,
    ),
    Step("Plug the phone into the car.", "Gearslip opens by itself when the head unit connects."),
)

/** The full setup reference, behind the help button: what the one-time guide walks through, kept
 * here too so it can be reread without repeating the guide itself. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit, onRequestCallScreening: () -> Unit) {
    val steps = remember(onRequestCallScreening) { setupSteps(onRequestCallScreening) }

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
        ) {
            Spacer(Modifier.height(8.dp))
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                steps.forEachIndexed { index, step ->
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
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
            Spacer(Modifier.height(32.dp))
        }
    }
}
