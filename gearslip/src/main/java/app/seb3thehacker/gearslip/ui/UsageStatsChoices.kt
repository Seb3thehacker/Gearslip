package app.seb3thehacker.gearslip.ui

import androidx.compose.animation.AnimatedVisibility
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import app.seb3thehacker.gearslip.stats.WorkedPrompt
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.BuildConfig
import app.seb3thehacker.gearslip.stats.UsageStats

internal const val USAGE_STATS_TITLE = "Help show which cars work"

private const val INTRO_START =
    "Gearslip can't tell which cars it works in unless drivers say so. Turn this on, and Gearslip " +
        "sends a short note when you open it and after each drive, never in the background. The " +
        "note carries the Gearslip version and whatever you choose to share. "
private const val INTRO_PRIVACY =
    "It holds no name, account, or location, only a random number so your phone counts once. " +
        "Cloudflare carries the note, and the server keeps no IP address."
private const val INTRO_END =
    " Nothing helps the project more. The notes show which cars work, so the next driver knows " +
        "before installing. When a car fails, the note says where the connection broke, which is " +
        "often enough to fix a car no one working on Gearslip owns."

/** The pitch for the usage notes, with the privacy promise in bold so it isn't missed. */
internal val USAGE_STATS_INTRO: AnnotatedString = buildAnnotatedString {
    append(INTRO_START)
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(INTRO_PRIVACY) }
    append(INTRO_END)
}

/**
 * The setup guide's version of [USAGE_STATS_INTRO]: what the notes are and why, in three
 * sentences. The full text is one tap away in [UsageStatsReadMore].
 */
internal val USAGE_STATS_SHORT: AnnotatedString = buildAnnotatedString {
    append("Gearslip can send a short note after each drive: its version and the details you pick below. ")
    append("The notes show which cars work and where the others fail. ")
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("No name, account, or location goes with them.") }
}

/** "Read more", opening the whole pitch and privacy promise in a dialog. */
@Composable
internal fun UsageStatsReadMore() {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, contentPadding = PaddingValues(0.dp)) { Text("Read more") }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("About usage notes") },
            text = { Text(USAGE_STATS_INTRO, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
        )
    }
}

/** The usage notes card for the setup guide: the same rows Settings shows. */
@Composable
internal fun UsageStatsChoices(modifier: Modifier = Modifier) {
    SettingsCard(modifier) { UsageStatsRows() }
}

/**
 * The on switch, what to share, and the note itself, saved as they change.
 * Rows only: the caller puts them in a card.
 */
@Composable
internal fun ColumnScope.UsageStatsRows() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(UsageStats.enabled(context)) }
    var choices by remember { mutableStateOf(UsageStats.choices(context)) }
    var noteOpen by remember { mutableStateOf(false) }
    fun save() = UsageStats.set(context, on, choices)
    // Nothing goes out while the choices are on screen; leaving them sends the first note.
    DisposableEffect(Unit) { onDispose { UsageStats.choicesClosed(context) } }

    SettingsRow(
        "Send usage notes",
        Modifier.toggleable(on, role = Role.Switch) { on = it; save() },
        subtitle = buildNote(),
        icon = Icons.Filled.Send,
        trailing = { Switch(checked = on, onCheckedChange = null) },
    )
    AnimatedVisibility(on) {
        Column {
            SettingsDivider()
            Text(
                "Also share",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 56.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            )
            Choice("Android version and system, such as GrapheneOS or stock Samsung", choices.android) { choices = choices.copy(android = it); save() }
            Choice("Phone model", choices.phone) { choices = choices.copy(phone = it); save() }
            Choice("Each car's head unit, model, year, and screen size, whether it connected, and how: cable or wireless, USB or Bluetooth audio", choices.cars) {
                choices = choices.copy(cars = it)
                save()
            }
            Choice("How each drive went, with the car's name: how far the connection got, how long it lasted, and how it ended, crashes included", choices.failures) {
                choices = choices.copy(failures = it)
                save()
            }
            SettingsDivider()
            SettingsRow(
                "What gets sent",
                Modifier.clickable { noteOpen = !noteOpen },
                subtitle = "Each car goes after the drive ends. The Android version and phone model go only when they change, and once a month",
                icon = Icons.Filled.Info,
                trailing = {
                    Icon(
                        if (noteOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (noteOpen) "Hide the note" else "Show the note",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            AnimatedVisibility(noteOpen) {
                // Rebuilt from the saved choices each time one changes, so it always matches.
                val note = remember(choices) { notePreview(context) }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                ) {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
}

/** A share choice: the label lines up with the rows' titles, the checkbox sits at the end. */
@Composable
private fun Choice(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(start = 56.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(vertical = 8.dp))
    }
}

/** The note as it would go out now, with an example drive until a real one is in it. */
private fun notePreview(context: Context): String {
    val preview = UsageStats.preview(context, everything = true)
    // Until the first drive the list is empty, so show what one entry looks like.
    val example = if (preview.optJSONArray("sessions")?.length() == 0) {
        "\n\nExample, not sent: one drive adds this to \"sessions\"\n" + UsageStats.exampleSession.toString(2)
    } else ""
    return preview.toString(2) + example
}

/** Why this build sends nothing, when it doesn't; null for a normal release. */
private fun buildNote(): String? = when {
    BuildConfig.DEBUG -> "Dev build: nothing is ever sent"
    BuildConfig.STATS_URL.isBlank() -> "This build has no stats server, so it sends nothing"
    else -> null
}

/**
 * The one-time ask after the first drive that worked, in the status card's place on Home. Share
 * turns on usage notes like the switch does; the link shows exactly what that sends first.
 */
@Composable
internal fun WorkedCard(car: String, onSeeWhatsSent: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier,
        color = scheme.primaryContainer,
        contentColor = scheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Text(
                    WorkedPrompt.TITLE_START + car,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Text(
                "Share that so the next driver knows it works? No name, account, or location.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            buildNote()?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onSeeWhatsSent, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text("What gets sent")
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { WorkedPrompt.answer(context, share = false) }) { Text("Not now") }
                Button(onClick = { WorkedPrompt.answer(context, share = true) }) { Text("Share") }
            }
        }
    }
}
