package app.seb3thehacker.gearslip.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.CertProvider
import app.seb3thehacker.gearslip.ProjectionCertificates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Downloads current repository identities during setup; imports always retain priority. */
@Composable
fun CertSetupScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preparing by remember { mutableStateOf(true) }
    var hasIdentity by remember { mutableStateOf(false) }
    var setupError by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingUri = uri
    }

    LaunchedEffect(retry) {
        preparing = true
        setupError = null
        val saved = withContext(Dispatchers.IO) { runCatching { CertProvider.loadSupplied(context) }.getOrNull() }
        if (saved != null) {
            hasIdentity = true
            notice = "Ready: ${saved.source}"
        }
        val result = withContext(Dispatchers.IO) {
            ProjectionCertificates.refresh(context, force = retry > 0)
            runCatching { CertProvider.loadSupplied(context) }.getOrNull()
        }
        hasIdentity = result != null
        setupError = ProjectionCertificates.refreshError
        notice = result?.source?.let { "Ready: $it" }
        preparing = false
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Certificate setup", style = MaterialTheme.typography.headlineLarge)
            Text("Gearslip automatically downloads the latest Android Auto and head-unit certificates from iscle/android-auto-certificates. It checks for updates when the app starts and keeps a saved copy for offline use.")
            Text("You can also import your own certificate. Your imported file takes priority over automatic downloads.")
            if (preparing) {
                CircularProgressIndicator()
                Text("Preparing certificates…")
            }
            notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            setupError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                OutlinedButton(enabled = !preparing, onClick = { retry++ }) { Text("Retry certificate setup") }
            }
            if (hasIdentity) {
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
            }
            Text("Use another certificate", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Import my own file") }
            Text("A .p12 file containing your certificate and private key. Imported certificates remain selected until you remove them.", style = MaterialTheme.typography.bodySmall)
            if (!preparing && !hasIdentity) {
                TextButton(onClick = onDone) { Text("Skip car connection setup") }
            }
        }
    }

    pendingUri?.let { uri ->
        PasswordDialog(
            onDismiss = { pendingUri = null },
            onConfirm = { password, reportError ->
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { CertProvider.importFrom(context, uri, password) } }
                    result.onSuccess {
                        pendingUri = null
                        hasIdentity = true
                        notice = "Certificate imported."
                    }.onFailure { reportError("Wrong password or not a valid PKCS#12 file with a private key.") }
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
