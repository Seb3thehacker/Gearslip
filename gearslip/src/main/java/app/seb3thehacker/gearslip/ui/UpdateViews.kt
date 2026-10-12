package app.seb3thehacker.gearslip.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.seb3thehacker.gearslip.BuildConfig
import app.seb3thehacker.gearslip.SessionStatus
import app.seb3thehacker.gearslip.update.UpdateChecker
import app.seb3thehacker.gearslip.update.UpdateChecker.State

private const val UNPLUG_FIRST = "Unplug from the car to install. Installing closes Gearslip."

/** Shown on Home once a newer version is found; hidden otherwise. */
@Composable
fun UpdateCard() {
    val context = LocalContext.current
    val state by UpdateChecker.state.collectAsStateWithLifecycle()
    SessionStatus.state.collectAsStateWithLifecycle().value // redraw when the car comes or goes
    val release = when (val s = state) {
        is State.Available -> s.release
        is State.Downloading -> s.release
        is State.Ready -> s.release
        is State.Failed -> s.release
        else -> null
    } ?: return

    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("Gearslip ${release.version} is out", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            when (val s = state) {
                is State.Downloading -> {
                    Text("Downloading… ${(s.fraction * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { s.fraction }, modifier = Modifier.fillMaxWidth())
                }
                is State.Ready -> {
                    val connected = UpdateChecker.carConnected
                    Text(
                        if (connected) UNPLUG_FIRST else "Downloaded. Android asks before it installs.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { UpdateChecker.install(context) }, enabled = !connected) { Text("Install") }
                }
                else -> {
                    Text(
                        (s as? State.Failed)?.message ?: "Download it now. Android asks before it installs.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { UpdateChecker.download(context, release) }) {
                            Text(if (s is State.Failed) "Try again" else "Update")
                        }
                        if (release.pageUrl.isNotBlank()) {
                            TextButton(onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.pageUrl)))
                            }) { Text("What's new") }
                        }
                    }
                }
            }
        }
    }
    // Room before whatever Home shows next; nothing when the card is hidden.
    Spacer(Modifier.height(16.dp))
}

/** The Settings row: checks on tap, and carries on to download and install once something is found. */
@Composable
internal fun UpdateRow() {
    val context = LocalContext.current
    val state by UpdateChecker.state.collectAsStateWithLifecycle()
    SessionStatus.state.collectAsStateWithLifecycle().value
    val subtitle = when (val s = state) {
        State.Idle -> if (BuildConfig.DEBUG) "Dev builds check only when you tap" else "Gearslip checks once a day"
        State.Checking -> "Checking…"
        State.UpToDate -> "You have the latest version"
        is State.Available -> "Version ${s.release.version} is out. Tap to download."
        is State.Downloading -> "Downloading… ${(s.fraction * 100).toInt()}%"
        is State.Ready -> if (UpdateChecker.carConnected) UNPLUG_FIRST else "Tap to install version ${s.release.version}"
        is State.Failed -> s.message
    }
    SettingsRow(
        "Check for updates",
        Modifier.clickable {
            when (val s = state) {
                is State.Available -> UpdateChecker.download(context, s.release)
                is State.Ready -> UpdateChecker.install(context)
                is State.Failed -> s.release?.let { UpdateChecker.download(context, it) } ?: UpdateChecker.check(context)
                else -> UpdateChecker.check(context)
            }
        },
        subtitle = subtitle,
        icon = Icons.Filled.CheckCircle,
    )
}

/** Whether Gearslip checks for updates on its own. */
@Composable
internal fun UpdateAutoRow() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(UpdateChecker.autoCheckEnabled(context)) }
    SettingsRow(
        "Check automatically",
        Modifier.toggleable(on, role = Role.Switch) {
            on = it
            UpdateChecker.setAutoCheckEnabled(context, it)
        },
        subtitle = if (BuildConfig.DEBUG) "Does nothing in dev builds. They check only when you tap Check for updates"
        else if (on) "Once a day, with a note on the car screen and the phone when one is out"
        else "Gearslip checks only when you tap Check for updates",
        icon = Icons.Filled.Notifications,
        trailing = { Switch(checked = on, onCheckedChange = null) },
    )
}

/** Whether pre-releases count as updates. */
@Composable
internal fun UpdateBetaRow() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(UpdateChecker.betaEnabled(context)) }
    SettingsRow(
        "Beta updates",
        Modifier.toggleable(on, role = Role.Switch) {
            on = it
            UpdateChecker.setBetaEnabled(context, it)
        },
        subtitle = "Get test versions before they're released. They may have more bugs.",
        icon = Icons.Filled.Build,
        trailing = { Switch(checked = on, onCheckedChange = null) },
    )
}
