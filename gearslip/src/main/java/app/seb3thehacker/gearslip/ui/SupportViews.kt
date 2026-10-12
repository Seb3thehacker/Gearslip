package app.seb3thehacker.gearslip.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Where Gearslip lives outside the app: donations, the chat room, and the code. */
internal object GearslipLinks {
    const val SPONSORS = "https://github.com/sponsors/Seb3thehacker"
    const val LIBERAPAY = "https://liberapay.com/Seb3thehacker"
    const val MATRIX = "https://matrix.to/#/!mf0_mgBd5KqKpc9FfX_wHp1jFCcwAtPrndIn_oGIyMk?via=matrix.org"
    const val SOURCE = "https://github.com/Seb3thehacker/gearslip"

    fun open(context: Context, url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** "Hide for now" on the home card puts it away for this long; the About page keeps the links. */
private const val HIDE_MS = 30L * 24 * 60 * 60 * 1000
private const val PREFS = "support"
private const val KEY_HIDDEN_UNTIL = "hidden_until"

private fun hiddenUntil(context: Context) =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_HIDDEN_UNTIL, 0L)

/**
 * The donation ask on Home: always in view, never in the way. It sits below the status and update
 * cards, and "Hide for now" puts it away for a month.
 */
@Composable
internal fun SupportCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var hidden by remember { mutableStateOf(System.currentTimeMillis() < hiddenUntil(context)) }
    if (hidden) return
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier,
        color = scheme.tertiaryContainer,
        contentColor = scheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Favorite, contentDescription = null)
                Text(
                    "Support Gearslip",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Text(
                "Gearslip is free, with no ads and no tracking. If it gets you on the road, a " +
                    "donation keeps it going.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = {
                        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putLong(KEY_HIDDEN_UNTIL, System.currentTimeMillis() + HIDE_MS).apply()
                        hidden = true
                    },
                    contentPadding = PaddingValues(horizontal = 0.dp),
                ) { Text("Hide for now") }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { GearslipLinks.open(context, GearslipLinks.LIBERAPAY) }) { Text("Liberapay") }
                Button(onClick = { GearslipLinks.open(context, GearslipLinks.SPONSORS) }) { Text("GitHub") }
            }
        }
    }
}

/** The About section's links: donating, the chat room, and the source code. */
@Composable
internal fun AboutLinkRows() {
    val context = LocalContext.current
    val arrow = @Composable {
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    SettingsRow(
        "Donate on GitHub Sponsors",
        Modifier.clickable { GearslipLinks.open(context, GearslipLinks.SPONSORS) },
        subtitle = "Give once or monthly, through GitHub",
        icon = Icons.Filled.Favorite,
        trailing = arrow,
    )
    SettingsDivider()
    SettingsRow(
        "Donate on Liberapay",
        Modifier.clickable { GearslipLinks.open(context, GearslipLinks.LIBERAPAY) },
        subtitle = "Give weekly or monthly, through a nonprofit",
        icon = Icons.Filled.Favorite,
        trailing = arrow,
    )
    SettingsDivider()
    SettingsRow(
        "Join the discussion on Matrix",
        Modifier.clickable { GearslipLinks.open(context, GearslipLinks.MATRIX) },
        subtitle = "Questions, ideas, or a car to report",
        icon = Icons.AutoMirrored.Filled.Send,
        trailing = arrow,
    )
    SettingsDivider()
    SettingsRow(
        "Source code",
        Modifier.clickable { GearslipLinks.open(context, GearslipLinks.SOURCE) },
        subtitle = "Read or change Gearslip's code under the AGPL",
        icon = Icons.Filled.Build,
        trailing = arrow,
    )
    SettingsDivider()
    SettingsRow(
        "Voice credits",
        subtitle = "Speech recognition by sherpa-onnx (Apache 2.0), with Kroko ASR by Banafo " +
            "(CC BY-SA 4.0) and Parakeet by NVIDIA (CC BY 4.0)",
        icon = Icons.Filled.Favorite,
    )
}
