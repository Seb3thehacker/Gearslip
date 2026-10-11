package app.seb3thehacker.gearslip.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.seb3thehacker.gearslip.AppSettings
import app.seb3thehacker.gearslip.CertProvider
import app.seb3thehacker.gearslip.ProjectionCertificates
import java.security.cert.X509Certificate
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Keep the choice explicit: expiry and connection failures never change it. */
@Composable
internal fun CertificateSettings(summary: CertSummary?, onChange: () -> Unit, onSetup: () -> Unit) {
    val context = LocalContext.current
    var source by remember { mutableStateOf(AppSettings.certificateSource(context)) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    val revision by ProjectionCertificates.revision.collectAsState()
    val identities by produceState<Map<CertProvider.Source, CertProvider.Identity?>>(emptyMap(), revision) {
        value = withContext(Dispatchers.IO) {
            CertProvider.Source.entries.associateWith { ProjectionCertificates.load(context, it) }
        }
    }
    val missing = summary?.kind == CertProvider.Kind.SELF_SIGNED
    SettingsRow(
        "Active certificate",
        Modifier.clickable(role = Role.Button) { choosing = true },
        subtitle = summary?.let { listOfNotNull(it.headline, it.expiry).joinToString("\n") } ?: "Checking…",
        icon = if (missing) Icons.Filled.Warning else Icons.Filled.Lock,
        tint = if (missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        trailing = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
    if (choosing) {
        CertificatePicker(
            selected = source,
            identities = identities,
            summary = summary,
            onSetup = { choosing = false; onSetup() },
            onSelect = {
                AppSettings.setCertificateSource(context, it)
                source = it
                choosing = false
                onChange()
            },
            onDismiss = { choosing = false },
        )
    }
}

@Composable
private fun CertificatePicker(
    selected: CertProvider.Source,
    identities: Map<CertProvider.Source, CertProvider.Identity?>,
    summary: CertSummary?,
    onSetup: () -> Unit,
    onSelect: (CertProvider.Source) -> Unit,
    onDismiss: () -> Unit,
) {
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    val overridden = summary?.kind == CertProvider.Kind.IMPORTED || summary?.kind == CertProvider.Kind.ADB_STAGED
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose certificate") },
        text = {
            // Scrolling keeps both choices reachable on small screens and at larger font sizes.
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    when (summary?.kind) {
                        CertProvider.Kind.IMPORTED -> "Your imported certificate is active. Remove it in Settings to use a downloaded certificate."
                        CertProvider.Kind.ADB_STAGED -> "Your ADB certificate is active. Remove the staged file to use a downloaded certificate."
                        else -> "Choose a downloaded certificate for your next connection."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Column(
                    Modifier.selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CertProvider.Source.entries.forEach { option ->
                        CertificateOption(
                            source = option,
                            selected = !overridden && selected == option && identities[option] != null,
                            certificate = identities[option]?.certificate,
                            enabled = !overridden && identities[option] != null,
                            onClick = { onSelect(option) },
                        )
                    }
                }
                if (identities.values.none { it != null }) {
                    TextButton(onClick = onSetup) { Text("Download certificates") }
                }
                if (summary?.subject != null || summary?.issuer != null) {
                    TextButton(onClick = { detailsOpen = !detailsOpen }) {
                        Text(if (detailsOpen) "Hide certificate details" else "View active certificate details")
                    }
                    AnimatedVisibility(detailsOpen) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(summary.headline, style = MaterialTheme.typography.titleSmall)
                            summary.expiry?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            summary.subject?.let { CertificateDetail("Subject", it) }
                            summary.issuer?.let { CertificateDetail("Issuer", it) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** The whole padded row owns selection, giving the radio and its description one touch target. */
@Composable
private fun CertificateOption(
    source: CertProvider.Source,
    selected: Boolean,
    certificate: X509Certificate?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) colors.secondaryContainer else colors.surfaceContainerHigh,
        border = BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .heightIn(min = 80.dp)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RadioButton(selected = selected, enabled = enabled, onClick = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    source.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) colors.onSecondaryContainer else colors.onSurface,
                )
                if (source == CertProvider.Source.HEAD_UNIT) {
                    Text(
                        "Not compatible with newer head units.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                    )
                }
                Text(
                    certificate?.notAfter?.let(::certificateExpiry) ?: "Not downloaded yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CertificateDetail(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

/** Recheck on resume, and remember acknowledgement per certificate rather than per app install. */
@Composable
internal fun CompatWarning(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    var resume by remember { mutableIntStateOf(0) }
    var expiry by remember { mutableStateOf<Date?>(null) }
    var warningKey by remember { mutableStateOf("") }
    var acknowledged by remember { mutableStateOf("") }
    val revision by ProjectionCertificates.revision.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resume++ }
    LaunchedEffect(resume, revision) {
        if (AppSettings.certificateSource(context) != CertProvider.Source.ANDROID_AUTO) {
            expiry = null
            return@LaunchedEffect
        }
        val cert = withContext(Dispatchers.IO) {
            runCatching { CertProvider.load(context).takeIf { it.downloadedSource == CertProvider.Source.ANDROID_AUTO }?.certificate }.getOrNull()
        }
        expiry = cert?.notAfter?.takeIf { Date().after(it) }
        // The old warning promised automatic fallback, so acknowledge this wording separately.
        warningKey = cert?.let { "manual:${it.serialNumber.toString(16)}:${it.notAfter.time}" }.orEmpty()
        acknowledged = AppSettings.getString(context, "expired_certificate_warning", "")
    }
    val expiredOn = expiry ?: return
    if (warningKey == acknowledged) return
    fun dismiss() {
        AppSettings.putString(context, "expired_certificate_warning", warningKey)
        acknowledged = warningKey
    }
    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text("Android Auto certificate expired") },
        text = {
            Text(
                "The Android Auto certificate expired on ${DateFormat.getDateInstance(DateFormat.LONG).format(expiredOn)}. " +
                    "Your car may still accept it, so Gearslip will keep using it. If the connection fails, " +
                    "open Settings, select Active certificate → Head unit (DHU), and reconnect. " +
                    "The head-unit certificate is not compatible with newer head units.",
            )
        },
        confirmButton = { TextButton(onClick = ::dismiss) { Text("Got it") } },
        dismissButton = {
            TextButton(onClick = { dismiss(); onOpenSettings() }) { Text("Settings") }
        },
    )
}
