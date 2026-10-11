package app.seb3thehacker.gearslip.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.AppSettings
import app.seb3thehacker.gearslip.BuildConfig
import app.seb3thehacker.gearslip.CertProvider
import app.seb3thehacker.gearslip.ProjectionCertificates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onCertificateSetup: () -> Unit,
    onBack: () -> Unit,
    onOpenRecommendedApps: () -> Unit,
    onOpenWhatsNew: () -> Unit,
    onReplayTutorial: () -> Unit,
    atUsageNotes: Boolean = false,
) {
    val scroll = rememberScrollState()
    // Opened from Home's usage notes row: usage notes is the last section, so start at the end.
    if (atUsageNotes) {
        LaunchedEffect(Unit) {
            snapshotFlow { scroll.maxValue }.first { it > 0 && it < Int.MAX_VALUE }
            scroll.scrollTo(scroll.maxValue)
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
            AboutSection(onOpenWhatsNew, onReplayTutorial)
            ConnectionSection()
            if (SHOW_RECOMMENDED_APPS) AppsSection(onOpenRecommendedApps)
            CertificateSection(onCertificateSetup)
            SettingsSection("Usage notes", header = USAGE_STATS_INTRO) { UsageStatsRows() }
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

/** What's new, updates and the setup guide, one tap from the top. */
@Composable
private fun AboutSection(onOpenWhatsNew: () -> Unit, onReplayTutorial: () -> Unit) {
    SettingsSection {
        SettingsRow(
            "What's new",
            Modifier.clickable(onClick = onOpenWhatsNew),
            subtitle = "Version ${BuildConfig.VERSION_NAME}",
            icon = Icons.Filled.Star,
            trailing = { Arrow() },
        )
        SettingsDivider()
        UpdateRow()
        SettingsDivider()
        UpdateNotifyRow()
        SettingsDivider()
        SettingsRow(
            "Replay the setup guide",
            Modifier.clickable(onClick = onReplayTutorial),
            subtitle = "Go through the first-run steps again",
            icon = Icons.Filled.Refresh,
            trailing = { Arrow() },
        )
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
private fun CertificateSection(onSetup: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var refresh by remember { mutableIntStateOf(0) }
    // The cached summary shows at once; the read still runs, in case the cert changed since.
    val certificateRevision by ProjectionCertificates.revision.collectAsState()
    val summary by produceState(CertSummary.cached, refresh, certificateRevision) {
        value = withContext(Dispatchers.Default) { CertSummary.read(context) }
    }

    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingUri = uri
    }

    SettingsSection("Certificate", footer = notice ?: "Changes take effect on the next connection.") {
        val s = summary
        CertificateSettings(summary = s, onChange = { refresh++; notice = null }, onSetup = onSetup)
        SettingsDivider()
        SettingsRow(
            if (downloading) "Checking for updates…" else "Check for updates",
            Modifier.clickable(enabled = !downloading) {
                downloading = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching {
                        ProjectionCertificates.refresh(context, force = true)
                        ProjectionCertificates.refreshError?.let { error(it) }
                    } }
                    downloading = false
                    result.onSuccess {
                        notice = "Certificates are up to date."
                        refresh++
                    }.onFailure {
                        notice = "Could not check for updates. Try again when you’re online."
                    }
                }
            },
            subtitle = "Also checked automatically when the app starts",
            icon = Icons.Filled.Refresh,
        )
        SettingsDivider()
        SettingsRow(
            "Import certificate",
            Modifier.clickable { picker.launch(arrayOf("*/*")) },
            subtitle = "Use your own .p12 file instead",
            icon = Icons.Filled.Add,
        )
        if (s?.kind == CertProvider.Kind.IMPORTED) {
            SettingsDivider()
            SettingsRow(
                "Remove imported certificate",
                Modifier.clickable {
                    CertProvider.removeImported(context)
                    notice = "Certificate removed. The next available certificate will be used."
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
