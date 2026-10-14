package app.seb3thehacker.gearslip.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.AppSettings
import app.seb3thehacker.gearslip.BuildConfig
import app.seb3thehacker.gearslip.CertProvider
import app.seb3thehacker.gearslip.SessionStatus
import app.seb3thehacker.gearslip.notify.TestMessage
import app.seb3thehacker.gearslip.car.CarSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenRecommendedApps: () -> Unit,
    onOpenWhatsNew: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenCarPreview: () -> Unit,
    onOpenLogs: () -> Unit,
    onReplayTutorial: () -> Unit,
    onReplayIntro: () -> Unit,
    atUsageNotes: Boolean = false,
) {
    val scroll = rememberScrollState()
    // Opened from Home's "it worked" card: start at usage notes.
    var usageNotesY by remember { mutableIntStateOf(-1) }
    if (atUsageNotes) {
        LaunchedEffect(Unit) {
            val y = snapshotFlow { usageNotesY }.first { it >= 0 }
            snapshotFlow { scroll.maxValue }.first { it > 0 && it < Int.MAX_VALUE }
            scroll.scrollTo(y)
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Spacer(Modifier.height(0.dp))
            SettingsSection(
                "Support Gearslip",
                footer = "Gearslip is free, with no ads and no tracking. Donations keep it going.",
            ) { DonateRows() }
            ConnectionSection()
            HelpSection(onOpenHelp, onOpenCarPreview, onOpenLogs)
            CarAudioSection()
            VoiceModelSection()
            if (SHOW_RECOMMENDED_APPS) AppsSection(onOpenRecommendedApps)
            SettingsSection(
                "Usage notes",
                Modifier.onGloballyPositioned { usageNotesY = it.positionInParent().y.toInt() },
                header = USAGE_STATS_INTRO,
            ) { UsageStatsRows() }
            SettingsSection("Updates") { UpdateRow() }
            CertificateSection()
            AboutSection(onOpenWhatsNew, onReplayTutorial, onReplayIntro)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Arrow() = Icon(
    Icons.AutoMirrored.Filled.KeyboardArrowRight,
    contentDescription = null,
    tint = MaterialTheme.colorScheme.onSurfaceVariant,
)

/** What's new, the replays, and where Gearslip lives outside the app. */
@Composable
private fun AboutSection(onOpenWhatsNew: () -> Unit, onReplayTutorial: () -> Unit, onReplayIntro: () -> Unit) {
    SettingsSection("About") {
        SettingsRow(
            "What's new",
            Modifier.clickable(onClick = onOpenWhatsNew),
            subtitle = "Version ${BuildConfig.VERSION_NAME}",
            icon = Icons.Filled.Star,
            trailing = { Arrow() },
        )
        SettingsDivider()
        SettingsRow(
            "Replay the ${Intro.release} intro",
            Modifier.clickable(onClick = onReplayIntro),
            subtitle = "What's in this release, in a few taps",
            icon = Icons.Filled.Star,
            trailing = { Arrow() },
        )
        SettingsDivider()
        SettingsRow(
            "Replay the setup guide",
            Modifier.clickable(onClick = onReplayTutorial),
            subtitle = "Go through the first-run steps again",
            icon = Icons.Filled.Refresh,
            trailing = { Arrow() },
        )
        SettingsDivider()
        AboutLinkRows()
    }
}

/** Off, a stock Android Auto app or head unit answers the USB connection instead of Gearslip. */
@Composable
private fun ConnectionSection() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AppSettings.gearslipEnabled(context)) }
    SettingsSection("Connection") {
        SettingsRow(
            "Use Gearslip in the car",
            Modifier.toggleable(enabled, role = Role.Switch) {
                enabled = it
                AppSettings.setGearslipEnabled(context, it)
            },
            subtitle = if (enabled) "Gearslip answers when you plug into a car" else "Off: Android Auto can take the connection instead",
            icon = Icons.Filled.Settings,
            trailing = { Switch(checked = enabled, onCheckedChange = null) },
        )
    }
}

/** Connection help, and the tools for checking what Gearslip does. */
@Composable
private fun HelpSection(onOpenHelp: () -> Unit, onOpenCarPreview: () -> Unit, onOpenLogs: () -> Unit) {
    val context = LocalContext.current
    val status by SessionStatus.state.collectAsState()
    val live = status.phase == SessionStatus.Phase.CONNECTING || status.phase == SessionStatus.Phase.PROJECTING
    SettingsSection("Help and tools") {
        SettingsRow(
            "Connection help",
            Modifier.clickable(onClick = onOpenHelp),
            subtitle = "Which cars work, and what to set up first",
            icon = Icons.Filled.Info,
            trailing = { Arrow() },
        )
        // The preview draws the car screen on the phone, so it waits until no car is using it.
        if (!live) {
            SettingsDivider()
            SettingsRow(
                "Car preview",
                Modifier.clickable(onClick = onOpenCarPreview),
                subtitle = "See the car screen on your phone",
                icon = Icons.Filled.PlayArrow,
                trailing = { Arrow() },
            )
        }
        SettingsDivider()
        SettingsRow(
            "Live logs",
            Modifier.clickable(onClick = onOpenLogs),
            subtitle = "What Gearslip and the car are saying to each other",
            icon = Icons.AutoMirrored.Filled.List,
            trailing = { Arrow() },
        )
        if (BuildConfig.DEBUG) {
            SettingsDivider()
            SettingsRow(
                "Send a test message",
                Modifier.clickable { TestMessage.post(context) },
                subtitle = "Dev builds only. Posts a message notification to read on the car",
                icon = Icons.AutoMirrored.Filled.Send,
            )
        }
    }
}

/** How music reaches the car: by Bluetooth as usual, or through Gearslip over USB. Same choice as setup. */
@Composable
private fun CarAudioSection() {
    val usb by CarSettings.pipeAudio.collectAsState()
    SettingsSection(
        "Car audio",
        footer = if (usb) "Run the command in Help once, and USB stops asking." else "Calls go over Bluetooth either way.",
    ) {
        SettingsRow(
            "Bluetooth (Recommended)",
            Modifier.selectable(!usb, role = Role.RadioButton) { CarSettings.setPipeAudio(false) },
            subtitle = "No prompt each drive, and your map app's voice reaches the car",
            trailing = { RadioButton(selected = !usb, onClick = null) },
        )
        SettingsDivider()
        SettingsRow(
            "USB",
            Modifier.selectable(usb, role = Role.RadioButton) { CarSettings.setPipeAudio(true) },
            subtitle = "Clearer sound with no delay. Android asks to share audio once each drive",
            trailing = { RadioButton(selected = usb, onClick = null) },
        )
    }
}

@Composable
private fun AppsSection(onOpenRecommendedApps: () -> Unit) {
    SettingsSection("Apps") {
        SettingsRow(
            "Recommended apps",
            Modifier.clickable(onClick = onOpenRecommendedApps),
            icon = Icons.Filled.Star,
            trailing = { Arrow() },
        )
    }
}

@Composable
private fun CertificateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var refresh by remember { mutableIntStateOf(0) }
    // The cached summary shows at once; the read still runs, in case the cert changed since.
    val summary by produceState(CertSummary.cached, refresh) {
        value = withContext(Dispatchers.Default) { CertSummary.read(context) }
    }

    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var detailsOpen by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingUri = uri
    }

    SettingsSection("Certificate", footer = notice ?: "Changes take effect on the next connection.") {
        val s = summary
        val loaded = s != null && s.kind != CertProvider.Kind.SELF_SIGNED
        val hasDetails = s?.subject != null || s?.issuer != null
        SettingsRow(
            s?.headline ?: "Checking…",
            if (hasDetails) Modifier.clickable { detailsOpen = !detailsOpen } else Modifier,
            subtitle = s?.validUntil?.let { "Valid until $it" },
            icon = if (s == null || loaded) Icons.Filled.Lock else Icons.Filled.Warning,
            tint = if (s == null || loaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            trailing = if (hasDetails) {
                {
                    Icon(
                        if (detailsOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (detailsOpen) "Hide details" else "Show details",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else null,
        )
        AnimatedVisibility(detailsOpen && hasDetails) {
            Column(
                Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                s?.subject?.let { Detail("Subject", it) }
                s?.issuer?.let { Detail("Issuer", it) }
            }
        }
        SettingsDivider()
        SettingsRow(
            if (downloading) "Downloading…" else "Download from aasdk",
            Modifier.clickable(enabled = !downloading) {
                downloading = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { CertProvider.downloadAndImport(context) } }
                    downloading = false
                    result.onSuccess {
                        notice = "Certificate downloaded."
                        refresh++
                    }.onFailure {
                        notice = "Download failed: ${it.message}"
                    }
                }
            },
            subtitle = "The certificate the open-source aasdk project publishes on GitHub. Gearslip doesn't include one",
            icon = Icons.Filled.Refresh,
        )
        SettingsDivider()
        SettingsRow(
            "Import my own file",
            Modifier.clickable { picker.launch(arrayOf("*/*")) },
            subtitle = "A .p12 file with its private key, from wherever you choose",
            icon = Icons.Filled.Add,
        )
        val removable = when (s?.kind) {
            CertProvider.Kind.IMPORTED -> "Remove imported certificate"
            CertProvider.Kind.DOWNLOADED -> "Remove downloaded certificate"
            else -> null
        }
        if (removable != null) {
            SettingsDivider()
            SettingsRow(
                removable,
                Modifier.clickable {
                    if (s?.kind == CertProvider.Kind.IMPORTED) CertProvider.removeImported(context) else CertProvider.removeDownloaded(context)
                    notice = if (s?.kind == CertProvider.Kind.IMPORTED) "Removed the imported certificate." else "Removed the downloaded certificate."
                    refresh++
                },
                icon = Icons.Filled.Delete,
                tint = MaterialTheme.colorScheme.error,
                titleColor = MaterialTheme.colorScheme.error,
            )
        }
    }

    pendingUri?.let { uri ->
        PasswordDialog(
            onDismiss = { pendingUri = null },
            onConfirm = { password, reportError ->
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { CertProvider.importFrom(context, uri, password) }
                    }
                    result.onSuccess {
                        pendingUri = null
                        notice = "Certificate imported."
                        refresh++
                    }.onFailure {
                        reportError("Could not import: wrong password, or not a PKCS#12 file with a private key.")
                    }
                }
            },
        )
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PasswordDialog(onDismiss: () -> Unit, onConfirm: (String, (String) -> Unit) -> Unit) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Certificate password") },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; error = null },
                singleLine = true,
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = error != null,
                supportingText = { Text(error ?: "Leave empty if the file has none.") },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(password) { error = it } }) { Text("Import") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
