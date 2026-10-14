package app.seb3thehacker.gearslip.car

import android.media.session.PlaybackState
import android.support.v4.media.session.PlaybackStateCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import app.seb3thehacker.gearslip.media.ArtColor
import app.seb3thehacker.gearslip.media.MediaArt
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.seb3thehacker.gearslip.car.theme.GsIconBox
import app.seb3thehacker.gearslip.car.theme.GsIconButton
import app.seb3thehacker.gearslip.car.theme.GsSeekBar
import app.seb3thehacker.gearslip.car.theme.GsTone
import app.seb3thehacker.gearslip.car.theme.gsColors
import app.seb3thehacker.gearslip.media.CarMedia
import app.seb3thehacker.gearslip.media.CustomAction
import app.seb3thehacker.gearslip.media.NowPlaying
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/*
 * The pieces every player on the car screen is built from - the media screen, the column beside
 * the map - so a button looks and behaves the same wherever it turns up.
 */

/** The seek bar, with elapsed and total time under it when [showTimes]. */
@Composable
internal fun PlayerProgress(now: NowPlaying, media: CarMedia, modifier: Modifier = Modifier, showTimes: Boolean = true) {
    if (now.durationMs <= 0) return
    var position by remember { mutableLongStateOf(now.currentPosition()) }
    LaunchedEffect(now) {
        while (true) {
            position = now.currentPosition()
            if (!now.playing) break
            delay(500)
        }
    }
    Column(modifier) {
        GsSeekBar(
            fraction = position.toFloat() / now.durationMs,
            onSeek = { media.seek((it * now.durationMs).toLong()) },
            enabled = now.canDo(PlaybackState.ACTION_SEEK_TO),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        if (showTimes) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(position), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatDuration(now.durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** "1:05" for under a minute past the hour, "1:01:05" once an hour is involved. */
internal fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/** Previous, play/pause, next. [skip] sizes the outer two; play is a step bigger. */
@Composable
internal fun TransportRow(now: NowPlaying, media: CarMedia, skip: Dp, modifier: Modifier = Modifier) {
    val play = skip * 1.25f
    Row(modifier, horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        GsIconButton(
            MediaIcons.Previous, "Previous", media::previous,
            enabled = now.canDo(PlaybackState.ACTION_SKIP_TO_PREVIOUS), size = skip, iconSize = skip * 0.58f, shape = CircleShape,
        )
        GsIconButton(
            if (now.playing) MediaIcons.Pause else Icons.Filled.PlayArrow,
            if (now.playing) "Pause" else "Play",
            media::togglePlay,
            tone = GsTone.Primary, size = play, iconSize = play * 0.58f, shape = CircleShape,
        )
        GsIconButton(
            MediaIcons.Next, "Next", media::next,
            enabled = now.canDo(PlaybackState.ACTION_SKIP_TO_NEXT), size = skip, iconSize = skip * 0.58f, shape = CircleShape,
        )
    }
}

/**
 * Like, shuffle and repeat, then up to [maxOther] of the app's other buttons, all icon-only. One
 * the app doesn't support stays on screen greyed out, so the row never shifts between apps.
 * [trailing] adds buttons of the caller's own at the end (the side column's lyrics button).
 */
@Composable
internal fun PlayModeRow(
    now: NowPlaying,
    media: CarMedia,
    size: Dp,
    modifier: Modifier = Modifier,
    maxOther: Int = 0,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val modes by media.modes.collectAsState()
    val liked = now.liked
    val shuffleKnown = modes.shuffle != PlaybackStateCompat.SHUFFLE_MODE_INVALID
    val repeatKnown = modes.repeat != PlaybackStateCompat.REPEAT_MODE_INVALID
    val shuffleOn = shuffleKnown && modes.shuffle != PlaybackStateCompat.SHUFFLE_MODE_NONE
    val repeatOn = repeatKnown && modes.repeat != PlaybackStateCompat.REPEAT_MODE_NONE
    val icon = size * 0.52f
    Row(modifier, horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        // An app's own like, shuffle or repeat button shows whether it's on only through its icon,
        // so that icon is drawn, as Android Auto does, and lit when its name says it's on.
        // Gearslip's own stand in for apps that report a heart rating or shuffle and repeat modes.
        val likeAction = now.likeAction
        if (likeAction != null) {
            CustomActionButton(likeAction, media, size, toggle = true)
        } else {
            GsIconButton(
                if (liked == true) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                if (liked == true) "Unlike" else "Like",
                media::toggleLike,
                enabled = liked != null,
                tone = if (liked == true) GsTone.Primary else GsTone.Neutral,
                size = size, iconSize = icon,
            )
        }
        val shuffleAction = now.shuffleAction
        if (shuffleAction != null) {
            CustomActionButton(shuffleAction, media, size, toggle = true)
        } else {
            GsIconButton(
                MediaIcons.Shuffle, if (shuffleOn) "Shuffle off" else "Shuffle on",
                media::toggleShuffle,
                enabled = shuffleKnown,
                tone = if (shuffleOn) GsTone.Primary else GsTone.Neutral,
                size = size, iconSize = icon,
            )
        }
        val repeatAction = now.repeatAction
        if (repeatAction != null) {
            CustomActionButton(repeatAction, media, size, toggle = true)
        } else {
            GsIconButton(
                if (modes.repeat == PlaybackStateCompat.REPEAT_MODE_ONE) MediaIcons.RepeatOne else MediaIcons.Repeat,
                "Repeat",
                media::cycleRepeat,
                enabled = repeatKnown,
                tone = if (repeatOn) GsTone.Primary else GsTone.Neutral,
                size = size, iconSize = icon,
            )
        }
        now.otherActions.take(maxOther).forEach { CustomActionButton(it, media, size) }
        trailing()
    }
}

/**
 * One of the app's own buttons, drawn with the app's own icon - it lives in the app's package,
 * not Gearslip's, so it's loaded from there. Its name stands in if the icon won't load.
 */
@Composable
private fun CustomActionButton(action: CustomAction, media: CarMedia, size: Dp, toggle: Boolean = false) {
    val icon = rememberActionIcon(action)
    GsIconBox(
        onClick = { media.custom(action) },
        modifier = Modifier.size(size),
        colors = gsColors(if (toggle && action.on) GsTone.Primary else GsTone.Neutral),
    ) {
        if (icon != null) {
            Icon(icon, action.name, Modifier.size(size * 0.52f))
        } else {
            Text(action.name, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun rememberActionIcon(action: CustomAction): ImageBitmap? {
    val context = LocalContext.current
    val app by CarServices.mediaApp.collectAsState()
    val pkg = app?.component?.packageName
    val icon by produceState<ImageBitmap?>(null, pkg, action.icon) {
        value = if (pkg == null || action.icon == 0) null else withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getResourcesForApplication(pkg)
                    .getDrawable(action.icon, null).toBitmap(96, 96).asImageBitmap()
            }.getOrNull()
        }
    }
    return icon
}

/** The track's art, from the session's bitmap or, failing that, its art URI. */
@Composable
internal fun rememberArt(now: NowPlaying): android.graphics.Bitmap? {
    val context = LocalContext.current
    val art by produceState(now.art, now.art, now.artUri) { value = now.art ?: MediaArt.load(context, now.artUri) }
    return art
}

/**
 * A background taken from the album art's main colour, in place of [fallback]. Only the hue and a
 * capped amount of its saturation come from the art; the lightness stays close to the theme's
 * own ([darkLightness] or [lightLightness]), so text drawn in the theme's colours reads the same
 * on any cover. Art with no real colour in it keeps [fallback]. Fades between songs rather than
 * jumping.
 */
@Composable
internal fun rememberArtColor(
    art: android.graphics.Bitmap?,
    fallback: Color,
    darkLightness: Float = 0.2f,
    lightLightness: Float = 0.86f,
): Color {
    val dark = fallback.luminance() < 0.5f
    val tint by produceState<ArtColor.Tint?>(null, art) {
        value = art?.let { withContext(Dispatchers.Default) { runCatching { ArtColor.of(it) }.getOrNull() } }
    }
    val target = tint?.let {
        if (dark) Color.hsl(it.hue, it.saturation.coerceIn(0.3f, 0.5f), darkLightness)
        else Color.hsl(it.hue, it.saturation.coerceIn(0.35f, 0.55f), lightLightness)
    } ?: fallback
    return animateColorAsState(target, tween(600), label = "art colour").value
}
