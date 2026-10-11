package app.seb3thehacker.gearslip.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.key
import app.seb3thehacker.gearslip.BuildConfig
import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.AppSettings
import app.seb3thehacker.gearslip.call.CarCalls
import app.seb3thehacker.gearslip.car.Prefetch
import app.seb3thehacker.gearslip.host.AndroidAuto
import app.seb3thehacker.gearslip.notify.GearslipNotificationListener
import app.seb3thehacker.gearslip.stats.UsageStats
import androidx.compose.runtime.DisposableEffect

/**
 * One page of the guide. [permissions] drives a system permission dialog; [command] is an adb
 * command to copy; [actionLabel] with [onAction] is a button that does something else (opens a
 * settings screen) without by itself finishing the page. Everything is skippable: none of this
 * can be verified as done, only offered.
 */
private class GuidePage(
    val title: String,
    /** Plain text, or an [AnnotatedString] when part of it needs to stand out. */
    val explanation: CharSequence,
    val permissions: Array<String>? = null,
    val command: String? = null,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    /** Drawn under [explanation], inside the card. */
    val content: (@Composable () -> Unit)? = null,
)

/** True once every permission in [permissions] is already granted - nothing left for this page to do. */
private fun granted(context: Context, permissions: Array<String>): Boolean =
    permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

/** Whether Gearslip already holds the hidden `PROJECT_MEDIA` app-op an adb command grants -
 * checked the same way the platform checks it, since there is no public permission for it. */
private fun hasProjectMediaOp(context: Context): Boolean {
    val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
    val mode = runCatching {
        ops.unsafeCheckOpNoThrow("android:project_media", android.os.Process.myUid(), context.packageName)
    }.getOrDefault(AppOpsManager.MODE_IGNORED)
    return mode == AppOpsManager.MODE_ALLOWED
}

private fun hasCallScreeningRole(context: Context): Boolean {
    val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
    return runCatching { roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) }.getOrDefault(false)
}

/** Whether the driver has already granted Gearslip's [GearslipNotificationListener] access. */
private fun hasNotificationAccess(context: Context): Boolean {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    val component = ComponentName(context, GearslipNotificationListener::class.java)
    return runCatching { manager.isNotificationListenerAccessGranted(component) }.getOrDefault(false)
}

/**
 * The usage notes question: the setup guide's last page, and on its own the one screen people
 * who set up before the notes existed see once. Leaving it without turning them on is a no.
 */
private fun usageNotesPage(context: Context) = GuidePage(
    title = USAGE_STATS_TITLE,
    explanation = USAGE_STATS_INTRO,
    content = {
        UsageStatsChoices()
        DisposableEffect(Unit) {
            onDispose { if (!UsageStats.asked(context)) UsageStats.set(context, false) }
        }
    },
)

private fun pages(context: Context, onRequestCallScreening: () -> Unit): List<GuidePage> = buildList {
    // --- Safety: always first, and never skipped by an already-granted check ---------
    add(
        GuidePage(
            title = "Drive safely",
            explanation = "This guide sets up the screen you will see mounted in your car. " +
                "Gearslip is unofficial software: it is not made or reviewed by any carmaker, " +
                "and it can fail without warning. Never interact with it while driving - set it " +
                "up, mount the phone, then keep your attention on the road.",
        ),
    )

    // --- Permissions: skipped outright once already granted, nothing left to ask -----
    // Asked whether or not car audio is on: voice reply and the assistant need it too.
    if (!granted(context, arrayOf(Manifest.permission.RECORD_AUDIO))) {
        add(
            GuidePage(
                title = "Microphone",
                explanation = "Gearslip needs the microphone for two jobs: passing your music " +
                    "app's sound to the car, and hearing you when you reply to a message or ask " +
                    "the assistant. It listens only then, and your speech stays on the phone.",
                permissions = arrayOf(Manifest.permission.RECORD_AUDIO),
            ),
        )
    }
    if (!granted(context, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))) {
        add(
            GuidePage(
                title = "Location",
                explanation = "The car's map and nearby places need to know where you are. " +
                    "Without it, the dashboard has no map card.",
                permissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            ),
        )
    }
    if (!granted(context, arrayOf(Manifest.permission.READ_CALENDAR))) {
        add(
            GuidePage(
                title = "Calendar",
                explanation = "Gearslip reads your next event to show an agenda card on the car " +
                    "screen. It only reads; it never edits or deletes anything.",
                permissions = arrayOf(Manifest.permission.READ_CALENDAR),
            ),
        )
    }
    val callPermissions = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.CALL_PHONE,
    )
    if (!granted(context, callPermissions)) {
        add(
            GuidePage(
                title = "Phone and contacts",
                explanation = "When a call comes in, Gearslip matches the number against your " +
                    "contacts, so the car screen can show a name instead of just digits. The " +
                    "same permissions let the car screen's Phone app dial a number or call " +
                    "someone from your contacts.",
                permissions = callPermissions,
            ),
        )
    }

    if (!granted(context, arrayOf(Manifest.permission.POST_NOTIFICATIONS))) {
        add(
            GuidePage(
                title = "Notifications",
                explanation = "Gearslip shows a notification while it's connected to the car or " +
                    "sending it audio, with a button to stop. Without this permission, Android " +
                    "hides it.",
                permissions = arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            ),
        )
    }
    if (!hasNotificationAccess(context)) {
        add(
            GuidePage(
                title = "Notification access",
                explanation = "The car screen shows your phone's notifications and lets you " +
                    "reply from there. Android only allows that once you grant Gearslip access, " +
                    "from the list this opens - it needs this to work at all.",
                actionLabel = "Open notification access",
                onAction = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                },
            ),
        )
    }

    // --- Android Auto ------------------------------------------------------------------
    val androidAutoPackage = AndroidAuto.installedPackage(context)
        ?.takeIf { AndroidAuto.isActive(context, it) }
    if (androidAutoPackage != null) {
        add(
            GuidePage(
                title = "Remove Android Auto",
                explanation = "Android Auto is still installed. It claims the same USB " +
                    "connection Gearslip needs, so the two fight over it. Disable or uninstall " +
                    "it from the app info screen this opens.",
                actionLabel = "Open Android Auto's app info",
                onAction = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", androidAutoPackage, null)),
                    )
                },
            ),
        )
    }

    // --- Default handler ---------------------------------------------------------------
    add(
        GuidePage(
            title = "Make Gearslip the default",
            explanation = "Android has no separate default-app setting for a USB accessory like " +
                "the car. It just launches whichever single app matches - which is Gearslip, " +
                "now that Android Auto is out of the way. If a chooser still appears when you " +
                "plug in, pick Gearslip and tap Always so Android stops asking.",
        ),
    )

    // --- Optional: each is skipped once its actual effect is already in place --------
    if (!hasProjectMediaOp(context)) {
        add(
            GuidePage(
                title = "Optional: trust audio every drive",
                explanation = "Connect the phone to a computer with USB debugging on, and run this " +
                    "command. It tells Android to trust Gearslip with audio from now on. Skip it " +
                    "and everything still works, but you tap Start once each time you set off.",
                command = "adb shell appops set ${BuildConfig.APPLICATION_ID} PROJECT_MEDIA allow",
            ),
        )
    }
    if (!granted(context, arrayOf(Manifest.permission.WRITE_SECURE_SETTINGS))) {
        add(
            GuidePage(
                title = "Optional: keep notifications readable",
                explanation = "Android treats sending audio to the car like sharing your screen, and " +
                    "hides what your notifications say. This command switches that off only while " +
                    "music is going to the car, then puts it back.",
                command = "adb shell pm grant ${BuildConfig.APPLICATION_ID} android.permission.WRITE_SECURE_SETTINGS",
            ),
        )
    }
    if (!hasCallScreeningRole(context)) {
        add(
            GuidePage(
                title = "Optional: answer and decline calls",
                explanation = "This sets Gearslip as your phone's caller ID and spam-blocking app, " +
                    "the same kind of consent a call-blocking app asks for. Without it, calls still " +
                    "ring, but only the phone itself can answer or decline them.",
                actionLabel = "Set up",
                onAction = onRequestCallScreening,
            ),
        )
    }

    // --- Apps worth installing, once everything above is set up -----------------------
    if (SHOW_RECOMMENDED_APPS) {
        add(
            GuidePage(
                title = "Recommended apps",
                explanation = RECOMMENDED_INTRO,
                content = { RecommendedAppsList() },
            ),
        )
    }

    // --- The very end, every time: usage notes. On a replay, the switch shows what was
    // chosen before.
    add(usageNotesPage(context))
}

/**
 * The first-run guide, one page at a time: permissions, then removing Android Auto if it is still installed, then the optional extras. Nothing
 * here can be verified as done except a permission grant, so every other page is a "Next" a
 * driver takes on trust, with "Skip" sitting right next to it.
 */
@Composable
fun SetupGuideScreen(onRequestCallScreening: () -> Unit, onDone: () -> Unit, onlyUsageNotes: Boolean = false) {
    val context = LocalContext.current
    val pages = remember { if (onlyUsageNotes) listOf(usageNotesPage(context)) else pages(context, onRequestCallScreening) }
    var index by remember { mutableIntStateOf(0) }

    // Only ours to handle once there is a previous page to return to; at the first page, the
    // system back press falls through to whatever the screen beneath this one does with it.
    BackHandler(enabled = index > 0) { index -= 1 }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.any { it }) {
            Prefetch.warm(context, force = true)
            CarCalls.start(context)
        }
        index += 1
    }

    val page = pages.getOrNull(index)
    if (page == null) {
        AppSettings.setSeenPermissionsSetup(context)
        onDone()
        return
    }

    val alreadyGranted = page.permissions?.all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    } ?: false

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            // A single page has no progress to show.
            if (pages.size > 1) {
                LinearProgressIndicator(
                    progress = { (index + 1f) / pages.size },
                    modifier = Modifier.fillMaxWidth().height(10.dp).clip(MaterialTheme.shapes.extraLarge),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "${index + 1} of ${pages.size}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The page scrolls between the progress bar and the buttons, so a tall page (the usage
            // note's choices, say) never pushes Next off the screen. Short pages stay centred.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                key(index) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .heightIn(min = maxHeight)
                            .padding(vertical = 24.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge) {
                            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    page.title,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    page.explanation as? AnnotatedString ?: AnnotatedString(page.explanation.toString()),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                page.command?.let { CopyableCommand(it) }
                                page.content?.invoke()
                            }
                        }
                    }
                }
            }

            when {
                page.permissions != null && alreadyGranted -> {
                    Text(
                        "Already allowed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(16.dp))
                    PrimaryRow(showBack = index > 0, onBack = { index -= 1 }) {
                        Button(onClick = { index += 1 }, modifier = it) { Text("Next") }
                    }
                }
                page.permissions != null -> {
                    PrimaryRow(showBack = index > 0, onBack = { index -= 1 }) {
                        Button(onClick = { launcher.launch(page.permissions) }, modifier = it) { Text("Allow") }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { index += 1 }) { Text("Skip for now") }
                }
                page.onAction != null -> {
                    FilledTonalButton(
                        onClick = page.onAction,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text(page.actionLabel ?: "Open") }
                    Spacer(Modifier.height(8.dp))
                    PrimaryRow(showBack = index > 0, onBack = { index -= 1 }) {
                        Button(onClick = { index += 1 }, modifier = it) { Text("Next") }
                    }
                }
                else -> {
                    PrimaryRow(showBack = index > 0, onBack = { index -= 1 }) {
                        Button(onClick = { index += 1 }, modifier = it) {
                            Text(if (index == pages.lastIndex) "Done" else "Next")
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * The bottom action row: a lower-emphasis Back on the left once there is a previous page, and the
 * page's own primary button - passed [content] so the caller keeps the button's own label and
 * click behavior - filling the rest of the width.
 */
@Composable
private fun PrimaryRow(showBack: Boolean, onBack: () -> Unit, content: @Composable (Modifier) -> Unit) {
    if (showBack) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("Back") }
            content(Modifier.weight(1f).height(52.dp))
        }
    } else {
        content(Modifier.fillMaxWidth().height(52.dp))
    }
}
