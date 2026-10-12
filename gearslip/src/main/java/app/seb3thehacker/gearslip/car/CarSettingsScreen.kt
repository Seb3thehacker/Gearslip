package app.seb3thehacker.gearslip.car


import app.seb3thehacker.gearslip.car.theme.*
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.car.theme.GsColors
import app.seb3thehacker.gearslip.car.theme.GsIconBox
import app.seb3thehacker.gearslip.car.theme.GsThemes
import app.seb3thehacker.gearslip.AppSettings
import app.seb3thehacker.gearslip.ui.CertSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings that make sense while sitting in the car. The web page URL is edited here, with the
 * car's own on-screen keyboard ([CarKeyboard]); certificate management still needs a real
 * keyboard and file picker, so it stays on the phone's Settings screen.
 */
@Composable
fun CarSettingsScreen() {
    val scale by CarSettings.scale.collectAsState()
    val open by CarSettings.openOnConnect.collectAsState()
    val night by CarSettings.nightMode.collectAsState()
    val units by CarSettings.units.collectAsState()
    val whatsNew by CarSettings.whatsNewAfterUpdates.collectAsState()
    val pipeAudio by CarSettings.pipeAudio.collectAsState()
    val autoplay by CarSettings.autoplay.collectAsState()
    val playerTitle by CarSettings.playerTitle.collectAsState()
    val theme by CarSettings.appTheme.collectAsState()
    val uiTheme by CarSettings.uiTheme.collectAsState()
    val experimentalFeatures by CarSettings.experimentalFeaturesEnabled.collectAsState()
    val carSensors by CarSettings.carSensorsEnabled.collectAsState()
    val systemSpeech by CarSettings.systemSpeech.collectAsState()
    val display by CarEnvironment.display.collectAsState()
    val vehicle by CarEnvironment.vehicle.collectAsState()
    val context = LocalContext.current
    val cert by produceState<CertSummary?>(null) {
        value = withContext(Dispatchers.Default) { CertSummary.read(context) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenHeader("Settings")

        ChoiceRow(
            "Open on connect",
            listOf(CarSettings.HOME to "Home") + CarApps.all.filter { it.id != "settings" }.map { it.id to it.label },
            open,
        ) { CarSettings.setOpenOnConnect(it) }

        ChoiceRow(
            "Size",
            listOf(1f to "Normal", 1.25f to "Large", 1.5f to "Extra large"),
            scale,
        ) { CarSettings.setScale(it) }

        ChoiceRow(
            "App theme",
            listOf(AppTheme.PHONE to "Follow phone", AppTheme.LIGHT to "Light", AppTheme.DARK to "Dark"),
            theme,
        ) { CarSettings.setAppTheme(it) }

        ChoiceRow(
            "Theme",
            GsThemes.builtIn.map { it.id to it.name },
            uiTheme,
        ) { CarSettings.setUiTheme(it) }

        ChoiceRow(
            "Map day/night",
            listOf(NightMode.AUTO to "Auto", NightMode.DAY to "Day", NightMode.NIGHT to "Night"),
            night,
        ) { CarSettings.setNightMode(it) }

        ChoiceRow(
            "Units",
            listOf(Units.AUTO to "Auto", Units.IMPERIAL to "Imperial (mi, ft, °F)", Units.METRIC to "Metric (km, m, °C)"),
            units,
        ) { CarSettings.setUnits(it) }

        ChoiceRow(
            "Player in the nav bar",
            listOf(false to "Skip buttons", true to "Song title"),
            playerTitle,
        ) { CarSettings.setPlayerTitle(it) }

        ChoiceRow(
            "Autoplay on connect",
            listOf(false to "Off", true to "On"),
            autoplay,
        ) { CarSettings.setAutoplay(it) }

        ChoiceRow(
            "Car audio",
            listOf(false to "Bluetooth", true to "USB"),
            pipeAudio,
        ) { CarSettings.setPipeAudio(it) }
        Text(
            "USB sends the phone's sound through Gearslip. Android asks to share your screen each " +
                "drive, and a few apps block it. Bluetooth plays as the phone normally would, and " +
                "Android never asks.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ExperimentalFeatureRow(
            title = "Use the phone's speech recognition",
            description = "Listens through the phone's own speech service instead of Gearslip's. On " +
                "most phones that's Google's, which may send your voice to Google. GrapheneOS has " +
                "none, so Gearslip keeps using its own.",
            checked = systemSpeech,
        ) { CarSettings.setSystemSpeech(it) }

        WebPageSetting()

        ScreenFit(vehicle)

        ChoiceRow(
            "Experimental features",
            listOf(false to "Hide", true to "Show"),
            experimentalFeatures,
        ) { CarSettings.setExperimentalFeaturesEnabled(it) }
        if (experimentalFeatures) {
            ExperimentalFeaturesList(carSensors)
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Connection", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Info("Phone", "${Build.MANUFACTURER} ${Build.MODEL}")
            Info("Vehicle", vehicle.ifEmpty { "-" })
            Info("Video", display.ifEmpty { "-" })
            Info("Certificate", cert?.headline ?: "Checking…")
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("About", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Info("Version", app.seb3thehacker.gearslip.BuildConfig.VERSION_NAME)
            Info("Build", app.seb3thehacker.gearslip.BuildConfig.VERSION_CODE.toString())
            ExperimentalFeatureRow(
                "What's new after updates",
                "Show the list of changes once, the first time the car connects after an update.",
                whatsNew,
            ) { CarSettings.setWhatsNewAfterUpdates(it) }
            GsButton(onClick = CarSettings::openWhatsNew, tone = GsTone.Tonal, modifier = Modifier.height(56.dp)) {
                Text("What's new", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun <T> ChoiceRow(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            options.forEach { (value, label) ->
                val on = value == selected
                val scheme = MaterialTheme.colorScheme
                GsIconBox(
                    onClick = { onSelect(value) },
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp),
                    colors = if (on) GsColors(scheme.primaryContainer, scheme.onPrimaryContainer)
                    else GsColors(scheme.surfaceContainerHigh, scheme.onSurface),
                    latched = on,
                ) {
                    Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

/**
 * Individually-switched experimental features, shown once the driver has opted into
 * experimental features as a whole. Each is its own row - a title, what it does, and a switch -
 * so the list reads fine whether there's one entry or several.
 */
@Composable
private fun ExperimentalFeaturesList(carSensors: Boolean) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ExperimentalFeatureRow(
            title = "Vehicle data",
            description = "Adds a Vehicle data tile: a live, raw dump of the head unit's sensor " +
                "channel. Field labels (speed, RPM, fuel...) are carried over from other " +
                "reverse-engineering projects and unverified against this car - treat them as a " +
                "guess, not a fact, until you've watched one change for yourself.",
            checked = carSensors,
        ) { CarSettings.setCarSensorsEnabled(it) }
    }
}

@Composable
private fun ExperimentalFeatureRow(
    title: String,
    description: String,
    checked: Boolean,
    warning: Boolean = false,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            GsSwitch(checked, onCheckedChange)
        }
    }
}

/** The URL (or raw HTML) the Web app shows. Edited with [CarKeyboard] - no phone in hand needed. */
@Composable
private fun WebPageSetting() {
    val context = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf(AppSettings.startupUrl(context)) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Web page", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Surface(
            onClick = { editing = !editing },
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                url.ifEmpty { "DuckDuckGo (default) - tap to set a different page" },
                style = MaterialTheme.typography.bodyLarge,
                color = if (url.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
        Text(
            "Shown by the Web app on the launcher. Applies on the next connection.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (editing) {
            CarKeyboard(
                url,
                onTextChange = {
                    url = it
                    AppSettings.setStartupUrl(context, it)
                },
                onSubmit = { editing = false },
                onDismiss = { editing = false },
            )
        }
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 4.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Nudges how much of the frame the UI keeps clear at the top and bottom, live, so a car's
 * display can be fitted from the driver's seat. Save writes it to this vehicle's profile.
 */
@Composable
private fun ScreenFit(vehicle: String) {
    val insets by CarEnvironment.insets.collectAsState()
    val context = LocalContext.current.applicationContext
    var saved by remember { mutableStateOf<String?>(null) }
    val known = vehicle.isNotEmpty() && vehicle != "Unknown vehicle"

    fun change(new: app.seb3thehacker.gearslip.Insets) {
        saved = null
        CarEnvironment.setInsets(new)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Screen fit", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Stepper("Top", insets.top) { change(insets.copy(top = (insets.top + it).coerceAtLeast(0))) }
        Stepper("Bottom", insets.bottom) { change(insets.copy(bottom = (insets.bottom + it).coerceAtLeast(0))) }
        Text(
            "Usable height ${480 - insets.top - insets.bottom}px of 480",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GsButton(
            onClick = {
                val ok = app.seb3thehacker.gearslip.VehicleProfiles.saveInsets(context, vehicle, insets)
                saved = if (ok) "Saved to $vehicle" else "Could not save"
            },
            enabled = known,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text(if (known) "Save for this vehicle" else "No vehicle profile matched") }
        saved?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun Stepper(label: String, value: Int, onStep: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1.1f))
        StepButton("−5", Modifier.weight(1f)) { onStep(-5) }
        StepButton("−1", Modifier.weight(1f)) { onStep(-1) }
        Text(
            "${value}px",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1.3f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        StepButton("+1", Modifier.weight(1f)) { onStep(1) }
        StepButton("+5", Modifier.weight(1f)) { onStep(5) }
    }
}

@Composable
private fun StepButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    GsIconBox(
        onClick = onClick,
        modifier = modifier.height(64.dp),
        colors = GsColors(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface),
        shape = MaterialTheme.shapes.large,
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge)
    }
}

