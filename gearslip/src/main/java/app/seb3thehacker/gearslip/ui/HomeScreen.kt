package app.seb3thehacker.gearslip.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import android.content.Context
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.seb3thehacker.gearslip.BuildConfig
import app.seb3thehacker.gearslip.SessionStatus
import app.seb3thehacker.gearslip.notify.TestMessage
import app.seb3thehacker.gearslip.stats.WorkedPrompt

@Composable
fun HomeScreen(
    onOpenLogs: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCarPreview: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenUsageNotes: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val status by SessionStatus.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val workedCar by remember { WorkedPrompt.pending(context) }.collectAsStateWithLifecycle()
    val live = status.phase == SessionStatus.Phase.CONNECTING ||
        status.phase == SessionStatus.Phase.PROJECTING

    val celebrate by WorkedPrompt.celebrate.collectAsStateWithLifecycle()
    Box {
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp),
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Spacer(Modifier.height(24.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "Gearslip",
                            style = MaterialTheme.typography.displayLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        IconButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.size(56.dp),
                        ) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings", modifier = Modifier.size(40.dp))
                        }
                    }
                    Text(
                        "v${LocalContext.current.appVersionName()}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    StatusCard(status)
                    // The first drive that worked asks once to share it, right under the result.
                    val car = workedCar
                    if (car != null && !live) {
                        Spacer(Modifier.height(16.dp))
                        WorkedCard(car, onSeeWhatsSent = onOpenUsageNotes)
                    }
                    Spacer(Modifier.height(16.dp))
                    UpdateCard()
                    if (status.phase != SessionStatus.Phase.PROJECTING) {
                        Spacer(Modifier.height(16.dp))
                        WirelessCard()
                    }
                }

                // Sits at the bottom, where a thumb reaches it.
                Column(Modifier.padding(vertical = 16.dp)) {
                    if (live) {
                        Button(onClick = onDisconnect, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                            Text("Disconnect")
                        }
                        Spacer(Modifier.height(16.dp))
                    }

                    // Not while the card above is asking the same thing.
                    if (workedCar == null || live) {
                        UsageNotesHomeCard(onOpen = onOpenUsageNotes)
                        Spacer(Modifier.height(16.dp))
                    }

                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.extraLarge) {
                        Column {
                            if (!live) {
                                ActionRow(Icons.Filled.PlayArrow, "Car preview", onOpenCarPreview)
                                RowDivider()
                            }
                            ActionRow(Icons.Filled.List, "Live logs", onOpenLogs)
                            RowDivider()
                            ActionRow(Icons.Filled.Info, "Connection help", onOpenHelp)
                            if (BuildConfig.DEBUG) {
                                RowDivider()
                                ActionRow(Icons.Filled.Send, "Send a test message") { TestMessage.post(context) }
                            }
                        }
                    }
                }
            }
        }
        // Sharing just went on from the "it worked" ask: a little thanks.
        if (celebrate) ConfettiFall(onDone = WorkedPrompt::celebrated)
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun StatusCard(status: SessionStatus.Snapshot) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (status.phase) {
        SessionStatus.Phase.PROJECTING -> scheme.primaryContainer to scheme.onPrimaryContainer
        SessionStatus.Phase.CONNECTING -> scheme.secondaryContainer to scheme.onSecondaryContainer
        SessionStatus.Phase.FAILED -> scheme.errorContainer to scheme.onErrorContainer
        SessionStatus.Phase.IDLE, SessionStatus.Phase.DISCONNECTED ->
            scheme.surfaceContainerHigh to scheme.onSurface
    }

    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.extraLarge) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (status.phase == SessionStatus.Phase.CONNECTING) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = content)
            } else {
                Surface(
                    modifier = Modifier.size(14.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = if (status.phase == SessionStatus.Phase.IDLE ||
                        status.phase == SessionStatus.Phase.DISCONNECTED
                    ) content.copy(alpha = 0.4f) else content,
                ) {}
            }
            Column {
                Text(status.headline, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(status.detail, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** The version shown on screen, straight from the manifest - one place to bump per release. */
private fun Context.appVersionName(): String =
    runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

