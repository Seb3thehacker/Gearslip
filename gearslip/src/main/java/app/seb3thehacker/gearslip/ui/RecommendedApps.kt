package app.seb3thehacker.gearslip.ui

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

/** A free app that runs on Gearslip's car screen, and where to get it. */
private class RecommendedApp(val name: String, val packageName: String, val url: String, val about: String)

private val recommended = listOf(
    "Maps" to listOf(
        RecommendedApp("Organic Maps", "app.organicmaps", "https://github.com/organicmaps/organicmaps", "Offline maps and routing."),
        RecommendedApp("Vela Maps", "app.vela", "https://github.com/PimpinPumpkin/Vela", "Turn-by-turn navigation without Google."),
    ),
    "Music" to listOf(
        RecommendedApp("ViVi Music", "com.vivi.vivimusic", "https://github.com/vivizzz007/vivi-music", "A YouTube Music player."),
        RecommendedApp("Metrolist", "com.metrolist.music", "https://github.com/MetrolistGroup/Metrolist", "A YouTube Music player."),
    ),
    "Podcasts" to listOf(
        RecommendedApp("AntennaPod", "de.danoeh.antennapod", "https://github.com/AntennaPod/AntennaPod", "Podcasts, chapters and all."),
    ),
)

/** Opened from Settings: the same list the setup guide ends with. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecommendedAppsScreen(onBack: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Recommended apps") },
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
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                RECOMMENDED_INTRO,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            RecommendedAppsList()
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Hidden for now: off, neither Settings nor the setup guide shows the list. The code stays. */
internal const val SHOW_RECOMMENDED_APPS = false

internal const val RECOMMENDED_INTRO =
    "These free apps run on Gearslip's car screen. Each button opens the app's page on GitHub."

/** The same green as the launcher's "works" check. */
private val INSTALLED_GREEN = Color(0xFF43A047)

/** The apps by section, each with a GitHub button. Installed ones say so in green. */
@Composable
internal fun RecommendedAppsList(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        for ((section, apps) in recommended) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    section,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                for (app in apps) AppRow(app) { openPage(context, app.url) }
            }
        }
    }
}

@Composable
private fun AppRow(app: RecommendedApp, onOpen: () -> Unit) {
    val context = LocalContext.current
    val icon = remember(app.packageName) { installedIcon(context, app.packageName) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Image(icon.toBitmap(96, 96).asImageBitmap(), contentDescription = null, Modifier.fillMaxSize())
            } else {
                Text(app.name.take(1), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(app.name, style = MaterialTheme.typography.titleMedium)
            Text(app.about, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (icon != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = INSTALLED_GREEN, modifier = Modifier.size(16.dp))
                    Text(
                        "Installed",
                        style = MaterialTheme.typography.labelLarge,
                        color = INSTALLED_GREEN,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
        OutlinedButton(onClick = onOpen) { Text("GitHub") }
    }
}

private fun installedIcon(context: Context, packageName: String): Drawable? =
    runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()

private fun openPage(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
