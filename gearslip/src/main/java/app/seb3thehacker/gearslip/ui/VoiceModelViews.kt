package app.seb3thehacker.gearslip.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.speech.SpeechModels
import app.seb3thehacker.gearslip.speech.SpeechModels.Model
import app.seb3thehacker.gearslip.speech.SpeechModels.State

/**
 * Which speech model the voice assistant listens with, and downloading it. Each is fetched once
 * from sherpa-onnx's author ([SpeechModels] says where); nothing is sent anywhere while listening.
 */
@Composable
internal fun VoiceModelSection() {
    val context = LocalContext.current
    remember { SpeechModels.init(context) }
    val selected by SpeechModels.selected.collectAsState()
    val final by SpeechModels.final.collectAsState()
    SettingsSection(title = "Voice: live words") {
        Text(
            "The voice assistant turns speech into text on this phone, and nothing you say leaves it. " +
                "It needs two models, about 270 MB together, downloaded once. " +
                "This one writes your words as you speak.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        Model.entries.filter { it.role == SpeechModels.Role.LIVE }.forEachIndexed { i, model ->
            if (i > 0) SettingsDivider()
            ModelRow(model, model == selected)
        }
    }
    SettingsSection(title = "Voice: final words") {
        Text(
            "When you stop talking, this model reads the whole phrase again. It catches names and " +
                "places the live model misses.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        val context = LocalContext.current
        Row(
            Modifier
                .fillMaxWidth()
                .selectable(final == null, role = Role.RadioButton) { SpeechModels.selectFinal(context, null) }
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(final == null, onClick = null, modifier = Modifier.padding(8.dp))
            Column {
                Text("None", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Keep the live words. Fastest, least accurate",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Model.entries.filter { it.role == SpeechModels.Role.FINAL }.forEach { model ->
            SettingsDivider()
            ModelRow(model, model == final)
        }
    }
}

@Composable
private fun ModelRow(model: Model, chosen: Boolean) {
    val context = LocalContext.current
    val state by SpeechModels.state(model).collectAsState()
    Column(
        Modifier
            .fillMaxWidth()
            .selectable(chosen, role = Role.RadioButton) { SpeechModels.select(context, model) }
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(chosen, onClick = null, modifier = Modifier.padding(8.dp))
            Column(Modifier.weight(1f)) {
                Text("${model.label} · ${model.downloadMb} MB", style = MaterialTheme.typography.titleMedium)
                Text(
                    when (val s = state) {
                        State.Ready -> "Downloaded. ${model.detail}"
                        is State.Downloading -> "Downloading, ${s.percent}%"
                        is State.Failed -> "Download failed: ${s.reason}"
                        State.Missing -> model.detail
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (state) {
                    State.Ready -> TextButton(onClick = { SpeechModels.delete(context, model) }) { Text("Delete") }
                    is State.Downloading -> TextButton(onClick = { SpeechModels.delete(context, model) }) { Text("Cancel") }
                    else -> OutlinedButton(onClick = { SpeechModels.download(context, model) }) { Text("Download") }
                }
            }
        }
        (state as? State.Downloading)?.let {
            LinearProgressIndicator(
                progress = { it.percent / 100f },
                modifier = Modifier.fillMaxWidth().padding(start = 48.dp, end = 8.dp, top = 4.dp),
            )
        }
    }
}
