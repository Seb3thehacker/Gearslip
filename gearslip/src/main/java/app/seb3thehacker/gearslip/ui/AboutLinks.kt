package app.seb3thehacker.gearslip.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/** Where Gearslip lives outside the app: support, the chat room, and the code. */
internal object GearslipLinks {
    const val LIBERAPAY = "https://liberapay.com/Seb3thehacker"
    const val MATRIX = "https://matrix.to/#/!mf0_mgBd5KqKpc9FfX_wHp1jFCcwAtPrndIn_oGIyMk?via=matrix.org"
    const val SOURCE = "https://github.com/Seb3thehacker/gearslip"
    const val CARS = "$SOURCE/blob/main/CARS.md"

    fun open(context: Context, url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** The way to support Gearslip, opening in the browser. */
@Composable
internal fun DonateRows() {
    val context = LocalContext.current
    val arrow = @Composable {
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    SettingsRow(
        "Liberapay",
        Modifier.clickable { GearslipLinks.open(context, GearslipLinks.LIBERAPAY) },
        subtitle = "Give weekly or monthly, through a nonprofit",
        icon = Icons.Filled.Favorite,
        trailing = arrow,
    )
}

/** The About section's links: the chat room, the source code, and credits. */
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
        "Join the chat on Matrix",
        Modifier.clickable { GearslipLinks.open(context, GearslipLinks.MATRIX) },
        subtitle = "Talk with other drivers about cars, apps, and Gearslip",
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
            "(CC BY-SA 4.0), and Parakeet and Canary by NVIDIA (CC BY 4.0)",
        icon = Icons.Filled.Favorite,
    )
}
