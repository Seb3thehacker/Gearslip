package app.seb3thehacker.gearslip.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.BuildConfig
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.CompositionLocalProvider
import app.seb3thehacker.gearslip.car.CarSettings
import app.seb3thehacker.gearslip.car.theme.GsButton
import app.seb3thehacker.gearslip.car.theme.GsIconButton
import app.seb3thehacker.gearslip.car.theme.GsThemes
import app.seb3thehacker.gearslip.car.theme.GsTone
import app.seb3thehacker.gearslip.car.theme.LocalGsTheme
import app.seb3thehacker.gearslip.speech.SpeechModels
import app.seb3thehacker.gearslip.stats.UsageStats
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * The release intro: a few tap-through screens shown once after each new release (and on a new
 * install), before setup. Its look follows the 0.3 mockup: Material shapes in the phone's own colours,
 * with every size taken from the screen's width so it scales from small phones to tablets.
 */
object Intro {
    private const val PREFS = "intro"
    private const val KEY_SEEN = "seen_release"

    /** "0.3" from "0.3.00-alpha": the intro belongs to a release, not to each patch. */
    val release: String = BuildConfig.VERSION_NAME.split('.').take(2).joinToString(".")

    fun due(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SEEN, null) != release

    fun markSeen(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SEEN, release).apply()
    }
}

/** The intro's tones, taken from the phone's own Material You colours like the rest of the app. */
internal class IntroColors(
    val surface: Color, val onSurface: Color, val onVariant: Color, val outline: Color,
    val primary: Color, val onPrimary: Color, val primaryC: Color, val onPrimaryC: Color,
    val tertiaryC: Color, val onTertiaryC: Color, val secondaryC: Color, val onSecondaryC: Color,
)

@Composable
internal fun introColors(): IntroColors {
    val m = MaterialTheme.colorScheme
    return IntroColors(
        surface = m.surface, onSurface = m.onSurface, onVariant = m.onSurfaceVariant, outline = m.outline,
        primary = m.primary, onPrimary = m.onPrimary, primaryC = m.primaryContainer, onPrimaryC = m.onPrimaryContainer,
        tertiaryC = m.tertiaryContainer, onTertiaryC = m.onTertiaryContainer,
        secondaryC = m.secondaryContainer, onSecondaryC = m.onSecondaryContainer,
    )
}

private enum class IntroStep { WELCOME, WIRELESS, KNOBS, VOICE, GRAPHENE, THANKS, APPS, THEME, DRIVE }

private val Pop = CubicBezierEasing(0.3f, 1.6f, 0.5f, 1f)
private val Rise = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

@Composable
fun IntroScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val c = introColors()
    // The GrapheneOS screen is only news to people running it.
    val steps = remember {
        IntroStep.entries.filter { it != IntroStep.GRAPHENE || UsageStats.osName(context) == "GrapheneOS" }
    }
    var index by remember { mutableIntStateOf(0) }
    val step = steps[index]
    val finish = {
        Intro.markSeen(context)
        onDone()
    }
    BackHandler(enabled = index > 0) { index-- }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(c.surface)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (index < steps.lastIndex) index++
            },
    ) {
        val w = maxWidth
        // The mockup's cqw: one percent of the screen's width.
        fun cq(n: Float): Dp = w * (n / 100f)
        val density = LocalDensity.current
        fun cqSp(n: Float): TextUnit = with(density) { cq(n).toSp() }

        when (step) {
            IntroStep.WELCOME -> Welcome(c, ::cq, ::cqSp, maxHeight)
            IntroStep.WIRELESS -> BadgeStep(c, ::cq, ::cqSp, c.secondaryC, "Wireless.",
                "If your car does wireless Android Auto, pair once and leave the cable at home.", step) { WifiIcon(c.onSecondaryC) }
            IntroStep.KNOBS -> BadgeStep(c, ::cq, ::cqSp, c.tertiaryC, "Knobs work.",
                "Turn, push, done. No touchscreen? Gearslip doesn't need one anymore.", step) { DialIcon(c.onTertiaryC) }
            IntroStep.VOICE -> VoiceStep(c, ::cq, ::cqSp, step)
            IntroStep.GRAPHENE -> BadgeStep(c, ::cq, ::cqSp, c.secondaryC, "Protections back on.",
                "On GrapheneOS, Gearslip no longer needs exploit protection compatibility mode. If you " +
                    "turned it on for Gearslip, turn it off to get full protection back.", step,
                action = "Open app settings" to {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                        )
                    }
                },
                outlined = true,
            ) { ShieldIcon(c.onSecondaryC, c.secondaryC) }
            IntroStep.THANKS -> BadgeStep(c, ::cq, ::cqSp, c.tertiaryC, "Thank you.",
                "To everyone sending usage notes: they show me which cars Gearslip works in and which " +
                    "ones still fail, so I know what to fix next. Every note helps.", step) { HeartIcon(c.onTertiaryC) }
            IntroStep.APPS -> BadgeStep(c, ::cq, ::cqSp, c.secondaryC, "Good company.",
                "Free, open-source apps that work well in the car. Tap one to see it on GitHub.", step,
                extra = { AppPicks(c, ::cq, ::cqSp, step) }) { StarIcon(c.onSecondaryC) }
            IntroStep.THEME -> BadgeStep(c, ::cq, ::cqSp, c.tertiaryC, "Your buttons.",
                "Pick how the car's buttons look. Change it any time in the car's settings.", step,
                extra = { ThemePicker(c, ::cq, ::cqSp, step) }) { ButtonsIcon(c.onTertiaryC) }
            IntroStep.DRIVE -> BadgeStep(c, ::cq, ::cqSp, c.primaryC, "Let's drive.",
                "That's ${Intro.release}. Plug in, or just get in.", step,
                action = "Start" to finish) { CheckIcon(c.onPrimaryC) }
        }

        Dots(c, steps.size, index, ::cq, Modifier.align(Alignment.BottomCenter).padding(bottom = maxHeight * 0.05f))
        if (step != IntroStep.DRIVE) Hint(c, ::cqSp, step, Modifier.align(Alignment.BottomCenter).padding(bottom = maxHeight * 0.09f))
    }
}

/** 0 to 1 once each time [key] changes, after [delayMs]: drives the entrance of one element. */
@Composable
internal fun entrance(key: Any, delayMs: Int, durationMs: Int = 650): Float {
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        delay(delayMs.toLong())
        a.animateTo(1f, tween(durationMs, easing = LinearEasing))
    }
    return a.value
}

/** Slides up and fades in, like the mockup's `up`. */
internal fun Modifier.up(p: Float, distance: Dp) = graphicsLayer {
    val e = Rise.transform(p)
    alpha = e
    translationY = (1 - e) * distance.toPx()
}

/** Grows in with a bounce, like the mockup's `bounce`. */
internal fun Modifier.pop(p: Float) = graphicsLayer {
    val e = Pop.transform(p)
    alpha = p.coerceAtMost(1f)
    scaleX = 0.5f + 0.5f * e
    scaleY = 0.5f + 0.5f * e
}

@Composable
private fun Welcome(c: IntroColors, cq: (Float) -> Dp, cqSp: (Float) -> TextUnit, height: Dp) {
    val key = IntroStep.WELCOME
    val spin by rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(18_000, easing = LinearEasing)), label = "spin",
    )
    Box(Modifier.fillMaxSize()) {
        Cookie(c.primaryC, Modifier.size(cq(120f)).offset(x = cq(28f), y = -cq(22f)).align(Alignment.TopEnd).graphicsLayer { rotationZ = spin; alpha = 0.9f })
        Ticker(c.secondaryC, c.onSecondaryC, "OPEN SOURCE ✦ NO ACCOUNT ✦ YOUR CAR, YOUR PHONE ✦ ", cq, cqSp, reverse = true, tilt = 3f,
            key = key, modifier = Modifier.align(Alignment.TopCenter).padding(top = height * 0.11f))

        Column(Modifier.align(Alignment.CenterStart).padding(horizontal = cq(8f))) {
            Text("Welcome to", color = c.onVariant, style = TextStyle(fontSize = cqSp(4.2f), fontWeight = FontWeight.SemiBold),
                modifier = Modifier.up(entrance(key, 100), cq(7f)))
            Text(
                Intro.release, color = c.primary,
                style = TextStyle(fontSize = cqSp(44f), fontWeight = FontWeight.Black, letterSpacing = cqSp(-2.4f), lineHeight = cqSp(38f)),
                modifier = Modifier.offset(x = -cq(1.5f)).pop(entrance(key, 250, 900)),
            )
            Text(
                "alpha · the big one", color = c.onTertiaryC,
                style = TextStyle(fontSize = cqSp(4f), fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(top = cq(4f)).up(entrance(key, 700), cq(7f))
                    .clip(RoundedCornerShape(50)).background(c.tertiaryC).padding(horizontal = cq(4.4f), vertical = cq(2.2f)),
            )
        }

        Ticker(c.primary, c.onPrimary, "WIRELESS ✦ KNOB SUPPORT ✦ OFFLINE VOICE ✦ FASTER VIDEO ✦ AUTO UPDATES ✦ ", cq, cqSp, reverse = false, tilt = -4f,
            key = key, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = height * 0.15f))
        Confetti(c, key, Modifier.fillMaxSize())
    }
}

/** A tilted band of words scrolling past, slid in from the side when the step opens. */
@Composable
private fun Ticker(
    bg: Color, fg: Color, words: String, cq: (Float) -> Dp, cqSp: (Float) -> TextUnit,
    reverse: Boolean, tilt: Float, key: Any, modifier: Modifier,
) {
    val enter = entrance(key, 400, 800)
    val scroll by rememberInfiniteTransition(label = "ticker").animateFloat(
        0f, 1f, infiniteRepeatable(tween(if (reverse) 20_000 else 16_000, easing = LinearEasing)), label = "ticker",
    )
    Box(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                rotationZ = tilt
                scaleX = 1.2f
                val e = Rise.transform(enter)
                alpha = e
                translationX = (1 - e) * -cq(30f).toPx()
            }
            .background(bg)
            .padding(vertical = cq(2.6f)),
    ) {
        Text(
            words.repeat(4),
            color = fg, maxLines = 1, softWrap = false,
            style = TextStyle(fontSize = cqSp(5f), fontWeight = FontWeight.ExtraBold),
            modifier = Modifier
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .graphicsLayer {
                    // The text holds the words four times; sliding by half loops cleanly.
                    val shift = size.width / 2f * (if (reverse) 1 - scroll else scroll)
                    translationX = -shift
                },
        )
    }
}

/** Material shapes thrown once from behind the version number. */
@Composable
private fun Confetti(c: IntroColors, key: Any, modifier: Modifier) {
    val p = entrance(key, 450, 1300)
    val bits = remember {
        val r = Random(7)
        List(22) { i ->
            val a = i / 22f * 2 * PI.toFloat() + r.nextFloat() * 0.4f
            val d = 30f + r.nextFloat() * 45f
            Bit(cos(a) * d, sin(a) * d * 1.2f, r.nextInt(-270, 270).toFloat(), i % 4, i % 3)
        }
    }
    if (p <= 0f || p >= 1f) return
    val tones = listOf(c.primary, c.primaryC, c.tertiaryC, c.secondaryC)
    Canvas(modifier) {
        val unit = size.width / 100f
        val e = CubicBezierEasing(0.15f, 0.7f, 0.3f, 1f).transform(p)
        val alpha = if (p < 0.7f) 1f else 1f - (p - 0.7f) / 0.3f
        val origin = Offset(size.width * 0.3f, size.height * 0.42f)
        bits.forEach { b ->
            val at = origin + Offset(b.dx * unit * e, b.dy * unit * e)
            val s = 3.6f * unit * (0.3f + 0.7f * e)
            rotate(b.spin * e, at) {
                val color = tones[b.tone].copy(alpha = alpha)
                when (b.shape) {
                    0 -> drawCircle(color, s / 2, at)
                    1 -> drawRoundRect(color, at - Offset(s / 2, s / 2), Size(s, s), androidx.compose.ui.geometry.CornerRadius(unit))
                    else -> drawRoundRect(color, at - Offset(s / 2, s / 4), Size(s, s / 2), androidx.compose.ui.geometry.CornerRadius(s / 4))
                }
            }
        }
    }
}

private class Bit(val dx: Float, val dy: Float, val spin: Float, val tone: Int, val shape: Int)

/** One idea per screen: a spinning cookie with an icon, a headline, a line of text, maybe a button. */
@Composable
private fun BadgeStep(
    c: IntroColors, cq: (Float) -> Dp, cqSp: (Float) -> TextUnit, badge: Color,
    title: String, body: String, key: Any,
    action: Pair<String, () -> Unit>? = null, outlined: Boolean = false,
    extra: (@Composable () -> Unit)? = null,
    icon: @Composable () -> Unit,
) {
    val spin by rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(18_000, easing = LinearEasing)), label = "spin",
    )
    Column(
        Modifier.fillMaxSize().padding(horizontal = cq(8f)),
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(cq(34f)).pop(entrance(key, 50, 800)), contentAlignment = Alignment.Center) {
            Cookie(badge, Modifier.fillMaxSize().graphicsLayer { rotationZ = spin })
            Box(Modifier.size(cq(15f))) { icon() }
        }
        Spacer(Modifier.height(cq(8f)))
        Text(
            title, color = c.onSurface,
            style = TextStyle(fontSize = cqSp(13.5f), fontWeight = FontWeight.Black, letterSpacing = cqSp(-0.6f), lineHeight = cqSp(12.5f)),
            modifier = Modifier.up(entrance(key, 200, 700), cq(7f)),
        )
        Spacer(Modifier.height(cq(4f)))
        Text(
            body, color = c.onVariant,
            style = TextStyle(fontSize = cqSp(4.5f), lineHeight = cqSp(6.5f)),
            modifier = Modifier.up(entrance(key, 350, 700), cq(7f)),
        )
        extra?.invoke()
        if (action != null) {
            val (label, onClick) = action
            Spacer(Modifier.height(if (outlined) cq(7f) else cq(9f)))
            Text(
                label,
                color = if (outlined) c.primary else c.onPrimary,
                style = TextStyle(fontSize = cqSp(if (outlined) 4.4f else 4.6f), fontWeight = FontWeight.Bold),
                modifier = Modifier
                    .up(entrance(key, 500, 700), cq(7f))
                    .clip(RoundedCornerShape(50))
                    .then(
                        if (outlined) Modifier.border(BorderStroke(2.dp, c.outline), RoundedCornerShape(50))
                        else Modifier.background(c.primary),
                    )
                    .clickable(onClick = onClick)
                    .padding(horizontal = if (outlined) cq(7f) else cq(9f), vertical = if (outlined) cq(3.6f) else cq(4.2f)),
            )
        }
    }
}

/**
 * Voice: a few things to say, drifting past, and a button that fetches both speech models so the
 * assistant works the first time someone tries it in the car.
 */
@Composable
private fun VoiceStep(c: IntroColors, cq: (Float) -> Dp, cqSp: (Float) -> TextUnit, key: IntroStep) {
    val context = LocalContext.current
    remember { SpeechModels.init(context) }
    val needed = listOf(SpeechModels.Model.KROKO, SpeechModels.Model.CANARY)
    val states = needed.map { SpeechModels.state(it).collectAsState().value }
    val ready = states.all { it == SpeechModels.State.Ready }
    val label = when {
        states.any { it is SpeechModels.State.Downloading } ->
            "Downloading, ${needed.zip(states).sumOf { (m, st) -> m.downloadMb * percentOf(st) } / needed.sumOf { it.downloadMb }}%"
        states.any { it is SpeechModels.State.Failed } -> "Download failed. Try again"
        else -> "Download voice, ${needed.sumOf { it.downloadMb }} MB"
    }
    BadgeStep(
        c, cq, cqSp, c.primaryC, "Just say it.",
        "Tap the mic, or press the talk button on your wheel, and speak. Your phone turns speech " +
            "into text by itself, and nothing you say leaves it. Say \"help\" in the car for the full list.",
        key,
        action = if (ready) null else label to { needed.forEach { SpeechModels.download(context, it) } },
        outlined = true,
        extra = { CommandDrift(c, cq, cqSp, key) },
    ) { MicIcon(c.onPrimaryC) }
}

private fun percentOf(state: SpeechModels.State) = when (state) {
    SpeechModels.State.Ready -> 100
    is SpeechModels.State.Downloading -> state.percent
    else -> 0
}

private val SAMPLE_COMMANDS = listOf(
    "Play Daft Punk", "What's playing?", "Next song",
    "Call Mom", "Open Organic Maps", "Pause", "Help",
)

/** Example commands rising slowly through a window that fades at both edges. */
@Composable
private fun CommandDrift(c: IntroColors, cq: (Float) -> Dp, cqSp: (Float) -> TextUnit, key: Any) {
    val scroll by rememberInfiniteTransition(label = "drift").animateFloat(
        0f, 1f, infiniteRepeatable(tween(SAMPLE_COMMANDS.size * 2_200, easing = LinearEasing)), label = "drift",
    )
    Box(
        Modifier
            .padding(top = cq(6f))
            .fillMaxWidth()
            .height(cq(34f))
            .up(entrance(key, 500, 700), cq(7f))
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(0f to Color.Transparent, 0.25f to Color.Black, 0.75f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            }
            .clipToBounds(),
    ) {
        Column(
            Modifier
                .wrapContentHeight(Alignment.Top, unbounded = true)
                // The list is there twice; rising by half loops cleanly.
                .graphicsLayer { translationY = -size.height / 2f * scroll },
            verticalArrangement = Arrangement.spacedBy(cq(2.4f)),
        ) {
            repeat(2) {
                SAMPLE_COMMANDS.forEach { say ->
                    Text(
                        "\u201c$say\u201d", color = c.onSecondaryC,
                        style = TextStyle(fontSize = cqSp(4.4f), fontWeight = FontWeight.SemiBold),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(c.secondaryC)
                            .padding(horizontal = cq(4.4f), vertical = cq(2f)),
                    )
                }
            }
        }
    }
}

private val APP_PICKS = listOf(
    Triple("Music", "Metrolist", "https://github.com/MetrolistGroup/Metrolist"),
    Triple("Maps", "Vela", "https://github.com/PimpinPumpkin/Vela"),
    Triple("Podcasts", "AntennaPod", "https://github.com/AntennaPod/AntennaPod"),
)

/** One row per pick, each opening the app's GitHub page; they arrive one after another. */
@Composable
private fun AppPicks(c: IntroColors, cq: (Float) -> Dp, cqSp: (Float) -> TextUnit, key: IntroStep) {
    val context = LocalContext.current
    Column(Modifier.padding(top = cq(6f)), verticalArrangement = Arrangement.spacedBy(cq(2.6f))) {
        APP_PICKS.forEachIndexed { i, (kind, name, url) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .up(entrance(key, 500 + i * 120, 700), cq(7f))
                    .clip(RoundedCornerShape(cq(5f)))
                    .background(c.secondaryC)
                    .clickable {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                    .padding(horizontal = cq(5f), vertical = cq(3.4f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(kind.uppercase(), color = c.onSecondaryC.copy(alpha = 0.7f),
                        style = TextStyle(fontSize = cqSp(3.2f), fontWeight = FontWeight.Bold, letterSpacing = cqSp(0.3f)))
                    Text(name, color = c.onSecondaryC, style = TextStyle(fontSize = cqSp(5.2f), fontWeight = FontWeight.ExtraBold))
                }
                Text("GitHub ↗", color = c.onSecondaryC, style = TextStyle(fontSize = cqSp(3.8f), fontWeight = FontWeight.SemiBold))
            }
        }
    }
}

/**
 * The car's button styles side by side, each drawn in its own style, and a row of real car
 * controls below that redraws in whichever is picked. Picking saves it, so the car shows it too.
 */
@Composable
private fun ThemePicker(c: IntroColors, cq: (Float) -> Dp, cqSp: (Float) -> TextUnit, key: IntroStep) {
    val chosenId by CarSettings.uiTheme.collectAsState()
    val chosen = GsThemes.byId(chosenId)
    // Flat first: it's the default.
    val themes = listOf(GsThemes.Flat) + GsThemes.builtIn.filter { it != GsThemes.Flat }
    Column(
        Modifier.padding(top = cq(7f)).fillMaxWidth().up(entrance(key, 500, 700), cq(7f)),
        verticalArrangement = Arrangement.spacedBy(cq(6f)),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(cq(3f))) {
            themes.forEach { theme ->
                CompositionLocalProvider(LocalGsTheme provides theme) {
                    GsButton(
                        onClick = { CarSettings.setUiTheme(theme.id) },
                        modifier = Modifier.weight(1f),
                        tone = if (theme == chosen) GsTone.Primary else GsTone.Neutral,
                        contentPadding = PaddingValues(horizontal = cq(1f), vertical = cq(3f)),
                    ) {
                        Text(
                            if (theme == chosen) "✓ ${theme.name}" else theme.name,
                            style = TextStyle(fontSize = cqSp(4.2f), fontWeight = FontWeight.Bold),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        // What the car shows: the player's controls and a full-width action.
        CompositionLocalProvider(LocalGsTheme provides chosen) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(cq(3f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GsIconButton(Icons.Filled.Search, "Search", onClick = {}, size = cq(15f), iconSize = cq(7f))
                GsIconButton(Icons.Filled.PlayArrow, "Play", onClick = {}, tone = GsTone.Primary, size = cq(15f), iconSize = cq(8f))
                GsButton(
                    onClick = {},
                    modifier = Modifier.weight(1f).height(cq(15f)),
                    tone = GsTone.Tonal,
                ) { Text("Navigate", style = TextStyle(fontSize = cqSp(4.6f), fontWeight = FontWeight.Bold)) }
            }
        }
    }
}

/** Three keys of a keypad, pressing one after another. */
@Composable
private fun ButtonsIcon(color: Color) {
    val t by rememberInfiniteTransition(label = "keys").animateFloat(
        0f, 3f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "keys",
    )
    Canvas(Modifier.fillMaxSize()) {
        inIconBox {
            listOf(6f, 19f, 32f).forEachIndexed { i, x ->
                // Each key dips for a moment while its turn comes round.
                val dip = (1f - kotlin.math.abs(t - i - 0.5f) * 2f).coerceIn(0f, 1f) * 4f
                drawRoundRect(color.copy(alpha = 0.45f), Offset(x, 16f + 4f), Size(10f, 16f),
                    androidx.compose.ui.geometry.CornerRadius(3f))
                drawRoundRect(color, Offset(x, 16f + dip), Size(10f, 16f), androidx.compose.ui.geometry.CornerRadius(3f))
            }
        }
    }
}

/** Material's scalloped "cookie": a circle whose radius waves around its edge. */
@Composable
internal fun Cookie(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val unit = size.minDimension / 100f
        val path = Path()
        var deg = 0
        while (deg <= 360) {
            val a = deg * PI / 180
            val r = (44 + 4.5 * cos(9 * a)) * unit
            val x = center.x + (r * cos(a)).toFloat()
            val y = center.y + (r * sin(a)).toFloat()
            if (deg == 0) path.moveTo(x, y) else path.lineTo(x, y)
            deg += 3
        }
        path.close()
        drawPath(path, color)
    }
}

/** Draws in the mockup's 48-unit icon box. */
private inline fun DrawScope.inIconBox(block: DrawScope.() -> Unit) {
    val s = size.minDimension / 48f
    scale(s, s, Offset.Zero) { block() }
}

@Composable
private fun WifiIcon(color: Color) {
    val t = rememberInfiniteTransition(label = "wifi")
    val waves = (0..2).map { i ->
        t.animateFloat(
            0.35f, 1f,
            infiniteRepeatable(tween(800, delayMillis = 0, easing = FastOutSlowInEasing), RepeatMode.Reverse,
                initialStartOffset = androidx.compose.animation.core.StartOffset(i * 200)),
            label = "wave$i",
        )
    }
    Canvas(Modifier.fillMaxSize()) {
        inIconBox {
            val stroke = Stroke(5f, cap = StrokeCap.Round)
            // The mockup's three arcs, inner to outer: radius r through (24 ± half, y).
            listOf(Triple(8f, 6f, 34f), Triple(18f, 13f, 26f), Triple(28f, 20f, 18f)).forEachIndexed { i, (r, half, y) ->
                val cy = y + sqrt(r * r - half * half)
                val start = Math.toDegrees(atan2((y - cy).toDouble(), (-half).toDouble())).toFloat()
                val end = Math.toDegrees(atan2((y - cy).toDouble(), half.toDouble())).toFloat()
                drawArc(color.copy(alpha = waves[i].value), start, (end - start + 360f) % 360f, false,
                    Offset(24f - r, cy - r), Size(2 * r, 2 * r), style = stroke)
            }
            drawCircle(color, 3.6f, Offset(24f, 41f))
        }
    }
}

@Composable
private fun DialIcon(color: Color) {
    val angle by rememberInfiniteTransition(label = "dial").animateFloat(
        -70f, -70f,
        infiniteRepeatable(keyframes {
            durationMillis = 2600
            -70f at 0 using CubicBezierEasing(0.5f, 0f, 0.3f, 1f)
            70f at 1040
            70f at 1430 using CubicBezierEasing(0.5f, 0f, 0.3f, 1f)
            -70f at 2470
        }),
        label = "dial",
    )
    Canvas(Modifier.fillMaxSize()) {
        inIconBox {
            drawCircle(color, 19f, Offset(24f, 24f), style = Stroke(5f))
            rotate(angle, Offset(24f, 24f)) {
                drawRoundRect(color, Offset(21.5f, 7f), Size(5f, 13f), androidx.compose.ui.geometry.CornerRadius(2.5f))
            }
        }
    }
}

@Composable
internal fun MicIcon(color: Color) {
    val t = rememberInfiniteTransition(label = "mic")
    val waves = (0..1).map { i ->
        t.animateFloat(
            0.25f, 1f,
            infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse,
                initialStartOffset = androidx.compose.animation.core.StartOffset(i * 250)),
            label = "mic$i",
        )
    }
    Canvas(Modifier.fillMaxSize()) {
        inIconBox {
            drawRoundRect(color, Offset(18f, 5f), Size(12f, 22f), androidx.compose.ui.geometry.CornerRadius(6f))
            val stroke = Stroke(4f, cap = StrokeCap.Round)
            drawArc(color, 0f, 180f, false, Offset(12f, 14f), Size(24f, 22f), style = stroke)
            drawLine(color, Offset(24f, 36f), Offset(24f, 42f), 4f, StrokeCap.Round)
            // Sound waves either side, pulsing as if someone's talking.
            listOf(1f to 0, -1f to 0, 1f to 1, -1f to 1).forEach { (side, i) ->
                val r = if (i == 0) 15f else 21f
                drawArc(color.copy(alpha = waves[i].value), if (side > 0) -35f else 145f, 70f, false,
                    Offset(24f - r, 16f - r), Size(2 * r, 2 * r), style = stroke)
            }
        }
    }
}

@Composable
internal fun StarIcon(color: Color) {
    val turn by rememberInfiniteTransition(label = "star").animateFloat(
        -8f, 8f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "star",
    )
    Canvas(Modifier.fillMaxSize()) {
        inIconBox {
            val star = Path()
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) 20f else 8.5f
                val a = (-90 + i * 36) * PI / 180
                val x = 24f + (r * cos(a)).toFloat()
                val y = 25f + (r * sin(a)).toFloat()
                if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
            }
            star.close()
            rotate(turn, Offset(24f, 25f)) { drawPath(star, color) }
        }
    }
}

@Composable
internal fun ShieldIcon(color: Color, tick: Color) {
    val p = entrance(IntroStep.GRAPHENE, 800, 500)
    Canvas(Modifier.fillMaxSize()) {
        inIconBox {
            val shield = Path().apply {
                moveTo(24f, 4f); lineTo(40f, 10f); lineTo(40f, 22f)
                cubicTo(40f, 32f, 33f, 40f, 24f, 44f)
                cubicTo(15f, 40f, 8f, 32f, 8f, 22f)
                lineTo(8f, 10f); close()
            }
            drawPath(shield, color)
            drawPartialStroke(listOf(Offset(16f, 24f), Offset(22f, 30f), Offset(33f, 18f)), p, tick, 4.5f)
        }
    }
}

@Composable
internal fun HeartIcon(color: Color) {
    val beat by rememberInfiniteTransition(label = "beat").animateFloat(
        1f, 1f,
        infiniteRepeatable(keyframes {
            durationMillis = 1400
            1f at 0; 1.16f at 210; 1f at 420; 1.1f at 630; 1f at 840
        }),
        label = "beat",
    )
    Canvas(Modifier.fillMaxSize()) {
        inIconBox {
            scale(beat, beat, Offset(24f, 24f)) {
                val heart = Path().apply {
                    moveTo(24f, 41f)
                    cubicTo(10f, 31f, 5f, 23f, 5f, 16f)
                    cubicTo(5f, 10f, 10f, 6f, 15f, 6f)
                    cubicTo(19f, 6f, 22f, 8f, 24f, 12f)
                    cubicTo(26f, 8f, 29f, 6f, 33f, 6f)
                    cubicTo(38f, 6f, 43f, 10f, 43f, 16f)
                    cubicTo(43f, 23f, 38f, 31f, 24f, 41f)
                    close()
                }
                drawPath(heart, color)
            }
        }
    }
}

@Composable
private fun CheckIcon(color: Color) {
    val p = entrance(IntroStep.DRIVE, 300, 500)
    Canvas(Modifier.fillMaxSize()) {
        inIconBox { drawPartialStroke(listOf(Offset(10f, 26f), Offset(20f, 35f), Offset(38f, 14f)), p, color, 5.5f) }
    }
}

/** A polyline drawn up to [progress] of its length, so a tick can sweep in. */
private fun DrawScope.drawPartialStroke(points: List<Offset>, progress: Float, color: Color, width: Float) {
    if (progress <= 0f) return
    val lengths = points.zipWithNext { a, b -> (b - a).getDistance() }
    var left = lengths.sum() * FastOutSlowInEasing.transform(progress.coerceAtMost(1f))
    val path = Path().apply { moveTo(points[0].x, points[0].y) }
    for (i in lengths.indices) {
        if (left <= 0f) break
        val f = (left / lengths[i]).coerceAtMost(1f)
        val to = points[i] + (points[i + 1] - points[i]) * f
        path.lineTo(to.x, to.y)
        left -= lengths[i]
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

@Composable
internal fun Dots(c: IntroColors, count: Int, current: Int, cq: (Float) -> Dp, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(cq(2f))) {
        repeat(count) { i ->
            val on = i == current
            val width by animateDpAsState(if (on) cq(7f) else cq(2f), tween(300), label = "dot")
            Box(
                Modifier
                    .size(width, cq(2f))
                    .clip(RoundedCornerShape(50))
                    .background(if (on) c.primary else c.outline.copy(alpha = 0.45f)),
            )
        }
    }
}

/** "Tap to continue", once the step has finished arriving, breathing softly. */
@Composable
private fun Hint(c: IntroColors, cqSp: (Float) -> TextUnit, key: Any, modifier: Modifier) {
    val shown = entrance(key, 1600, 600)
    val breathe by rememberInfiniteTransition(label = "hint").animateFloat(
        0.75f, 0.35f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "hint",
    )
    Text(
        "Tap to continue", color = c.onVariant,
        style = TextStyle(fontSize = cqSp(3.6f), fontWeight = FontWeight.SemiBold),
        modifier = modifier.graphicsLayer { alpha = if (shown < 1f) shown * 0.75f else breathe },
    )
}
