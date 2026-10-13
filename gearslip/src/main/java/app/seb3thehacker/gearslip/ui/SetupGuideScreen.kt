package app.seb3thehacker.gearslip.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
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
import androidx.compose.material3.Text
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
    /** Drawn inside the page's badge, in the badge's content colour. */
    val icon: @Composable (Color) -> Unit,
    /** Marked "Optional" above the title: everything works without it. */
    val optional: Boolean = false,
    val permissions: Array<String>? = null,
    val command: String? = null,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    /** Drawn under [explanation]. */
    val content: (@Composable () -> Unit)? = null,
)

/** A plain Material icon, sized to fill the badge's icon box. */
private fun glyph(icon: ImageVector): @Composable (Color) -> Unit = { tint ->
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.fillMaxSize())
}

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
    title = "Help other drivers.",
    explanation = USAGE_STATS_SHORT,
    icon = { HeartIcon(it) },
    content = {
        UsageStatsReadMore()
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
            title = "Drive safely.",
            explanation = "Gearslip is unofficial. No carmaker made or checked it, and it can fail " +
                "without warning. Set it up before you go, then keep your eyes on the road.",
            icon = glyph(Icons.Filled.Warning),
        ),
    )

    // --- Permissions: skipped outright once already granted, nothing left to ask -----
    // Asked whether or not car audio is on: voice reply and the assistant need it too.
    if (!granted(context, arrayOf(Manifest.permission.RECORD_AUDIO))) {
        add(
            GuidePage(
                title = "Microphone.",
                explanation = "Gearslip uses it to send your music to the car and to hear you when you " +
                    "ask for something or reply to a message. What you say stays on the phone.",
                icon = { MicIcon(it) },
                permissions = arrayOf(Manifest.permission.RECORD_AUDIO),
            ),
        )
    }
    if (!granted(context, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))) {
        add(
            GuidePage(
                title = "Location.",
                explanation = "The car's map and nearby searches need it. Without it, the dashboard " +
                    "shows no map.",
                icon = glyph(Icons.Filled.LocationOn),
                permissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            ),
        )
    }
    if (!granted(context, arrayOf(Manifest.permission.READ_CALENDAR))) {
        add(
            GuidePage(
                title = "Calendar.",
                explanation = "The car screen shows your next event. Gearslip only reads your calendar.",
                icon = glyph(Icons.Filled.DateRange),
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
                title = "Calls and contacts.",
                explanation = "The car shows who's calling by name, and its Phone app can dial a " +
                    "number or call one of your contacts. Gearslip only reads them.",
                icon = glyph(Icons.Filled.Phone),
                permissions = callPermissions,
            ),
        )
    }

    if (!granted(context, arrayOf(Manifest.permission.POST_NOTIFICATIONS))) {
        add(
            GuidePage(
                title = "Notifications.",
                explanation = "While Gearslip is connected, it shows a notification with a button " +
                    "to stop. Without this, Android hides it.",
                icon = glyph(Icons.Filled.Notifications),
                permissions = arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            ),
        )
    }
    if (!hasNotificationAccess(context)) {
        add(
            GuidePage(
                title = "Messages.",
                explanation = "The car shows your messages, reads them aloud and lets you reply. " +
                    "Turn Gearslip on in the list this opens.",
                icon = glyph(Icons.Filled.Email),
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
                title = "Turn off Android Auto.",
                explanation = "Android Auto is still installed, and it fights Gearslip for the car's " +
                    "USB connection. Disable or uninstall it on the screen this opens.",
                icon = glyph(Icons.Filled.Close),
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
            title = "Choose Gearslip.",
            explanation = "If your phone asks which app to open when you plug into the car, pick " +
                "Gearslip and tap Always.",
            icon = glyph(Icons.Filled.Check),
        ),
    )

    // --- Optional: each is skipped once its actual effect is already in place --------
    if (!hasProjectMediaOp(context)) {
        add(
            GuidePage(
                title = "No audio prompt.",
                explanation = "Run this from a computer with USB debugging on, and Android trusts " +
                    "Gearslip with your audio for good. Without it, you tap Start once each drive.",
                icon = glyph(Icons.Filled.Build),
                optional = true,
                command = "adb shell appops set ${BuildConfig.APPLICATION_ID} PROJECT_MEDIA allow",
            ),
        )
    }
    if (!granted(context, arrayOf(Manifest.permission.WRITE_SECURE_SETTINGS))) {
        add(
            GuidePage(
                title = "Readable notifications.",
                explanation = "While audio goes to the car, Android hides what notifications say, " +
                    "as if you were sharing your screen. This lets Gearslip show them during a " +
                    "drive and hide them again after.",
                icon = glyph(Icons.Filled.Build),
                optional = true,
                command = "adb shell pm grant ${BuildConfig.APPLICATION_ID} android.permission.WRITE_SECURE_SETTINGS",
            ),
        )
    }
    if (!hasCallScreeningRole(context)) {
        add(
            GuidePage(
                title = "Answer calls.",
                explanation = "Make Gearslip your caller ID app, and the car can answer and decline " +
                    "calls. Without it, calls still ring, but only the phone can answer them.",
                icon = glyph(Icons.Filled.Call),
                optional = true,
                actionLabel = "Set up",
                onAction = onRequestCallScreening,
            ),
        )
    }

    // --- Apps worth installing, once everything above is set up -----------------------
    if (SHOW_RECOMMENDED_APPS) {
        add(
            GuidePage(
                title = "Good company.",
                explanation = RECOMMENDED_INTRO,
                icon = { StarIcon(it) },
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

    val c = introColors()
    // The badges take turns through the phone's three accent colours, as the release intro does.
    val (badge, onBadge) = listOf(c.primaryC to c.onPrimaryC, c.secondaryC to c.onSecondaryC, c.tertiaryC to c.onTertiaryC)[index % 3]
    val spin by rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(18_000, easing = LinearEasing)), label = "spin",
    )

    BoxWithConstraints(Modifier.fillMaxSize().background(c.surface).systemBarsPadding()) {
        val w = maxWidth
        // One percent of the screen's width, as in the intro, so it scales the same way.
        fun cq(n: Float): Dp = w * (n / 100f)
        val density = LocalDensity.current
        fun cqSp(n: Float): TextUnit = with(density) { cq(n).toSp() }

        Column(Modifier.fillMaxSize().padding(horizontal = cq(8f))) {
            // The page scrolls above the buttons, so a tall one (the usage notes' choices) never
            // pushes them off the screen. Short pages sit in the middle.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                key(index) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .heightIn(min = maxHeight)
                            .padding(vertical = cq(6f)),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(Modifier.size(cq(28f)).pop(entrance(index, 50, 800)), contentAlignment = Alignment.Center) {
                            Cookie(badge, Modifier.fillMaxSize().graphicsLayer { rotationZ = spin })
                            Box(Modifier.size(cq(12f))) { page.icon(onBadge) }
                        }
                        Spacer(Modifier.height(cq(7f)))
                        if (page.optional) {
                            Text(
                                "OPTIONAL", color = c.primary,
                                style = TextStyle(fontSize = cqSp(3.4f), fontWeight = FontWeight.Bold, letterSpacing = cqSp(0.4f)),
                                modifier = Modifier.up(entrance(index, 150, 700), cq(7f)),
                            )
                            Spacer(Modifier.height(cq(1.5f)))
                        }
                        Text(
                            page.title, color = c.onSurface,
                            // Long titles step down a size so they stay on two lines.
                            style = TextStyle(
                                fontSize = cqSp(if (page.title.length > 16) 8f else 10f), fontWeight = FontWeight.Black,
                                letterSpacing = cqSp(-0.4f), lineHeight = cqSp(if (page.title.length > 16) 8.6f else 10.5f),
                            ),
                            modifier = Modifier.up(entrance(index, 200, 700), cq(7f)),
                        )
                        Spacer(Modifier.height(cq(4f)))
                        Column(
                            Modifier.up(entrance(index, 350, 700), cq(7f)),
                            verticalArrangement = Arrangement.spacedBy(cq(3f)),
                        ) {
                            Text(
                                page.explanation as? AnnotatedString ?: AnnotatedString(page.explanation.toString()),
                                color = c.onVariant,
                                style = TextStyle(fontSize = cqSp(4.5f), lineHeight = cqSp(6.5f)),
                            )
                            page.command?.let { CopyableCommand(it) }
                            page.content?.invoke()
                            if (page.permissions != null && alreadyGranted) {
                                Text(
                                    "Already allowed.", color = c.primary,
                                    style = TextStyle(fontSize = cqSp(4.2f), fontWeight = FontWeight.SemiBold),
                                )
                            }
                        }
                    }
                }
            }

            // A single page has nothing to count.
            if (pages.size > 1) {
                Dots(c, pages.size, index, ::cq, Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(cq(5f)))
            }
            val back = if (index > 0) ({ index -= 1 }) else null
            val next = { index += 1 }
            when {
                page.permissions != null && !alreadyGranted -> {
                    PillRow(c, ::cq, ::cqSp, back, "Allow") { launcher.launch(page.permissions) }
                    Pill(c, ::cqSp, "Skip for now", PillStyle.TEXT, Modifier.align(Alignment.CenterHorizontally), next)
                }
                page.onAction != null -> {
                    Pill(c, ::cqSp, page.actionLabel ?: "Open", PillStyle.OUTLINED, Modifier.fillMaxWidth().padding(bottom = cq(3f)), page.onAction)
                    PillRow(c, ::cq, ::cqSp, back, "Next", next)
                }
                else -> PillRow(c, ::cq, ::cqSp, back, if (index == pages.lastIndex) "Done" else "Next", next)
            }
            Spacer(Modifier.height(cq(6f)))
        }
    }
}

private enum class PillStyle { FILLED, OUTLINED, TEXT }

/** The intro's rounded buttons: filled for the step's main action, outlined beside it, bare text to skip. */
@Composable
private fun Pill(c: IntroColors, cqSp: (Float) -> TextUnit, label: String, style: PillStyle, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .then(
                when (style) {
                    PillStyle.FILLED -> Modifier.background(c.primary)
                    PillStyle.OUTLINED -> Modifier.border(BorderStroke(2.dp, c.outline), shape)
                    PillStyle.TEXT -> Modifier
                },
            )
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (style == PillStyle.FILLED) c.onPrimary else c.primary,
            style = TextStyle(fontSize = cqSp(4.4f), fontWeight = FontWeight.Bold),
        )
    }
}

/** Back on the left once there's a page to go back to, and the page's main action filling the rest. */
@Composable
private fun PillRow(
    c: IntroColors, cq: (Float) -> Dp, cqSp: (Float) -> TextUnit,
    onBack: (() -> Unit)?, label: String, onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(cq(3f))) {
        if (onBack != null) Pill(c, cqSp, "Back", PillStyle.OUTLINED, Modifier.weight(1f), onBack)
        Pill(c, cqSp, label, PillStyle.FILLED, Modifier.weight(if (onBack != null) 1.4f else 1f), onClick)
    }
}
