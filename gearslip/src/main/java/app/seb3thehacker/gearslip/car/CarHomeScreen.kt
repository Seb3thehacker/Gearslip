package app.seb3thehacker.gearslip.car

import app.seb3thehacker.gearslip.car.theme.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import android.support.v4.media.session.PlaybackStateCompat
import app.seb3thehacker.gearslip.R
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import android.media.session.PlaybackState
import app.seb3thehacker.gearslip.media.MediaArt
import kotlinx.coroutines.delay
import androidx.compose.material3.Icon
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.seb3thehacker.gearslip.media.ArtColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.seb3thehacker.gearslip.host.CarAppConnection
import app.seb3thehacker.gearslip.media.CarMedia
import app.seb3thehacker.gearslip.media.NowPlaying
import kotlinx.coroutines.launch

/**
 * The home screen: the map beside destination suggestions and the optional docked player.
 *
 * The map is whatever navigation app was used last, brought back on its own. The player sits in
 * the nav bar's pill until its dock button moves it here. The map fills the screen when neither
 * suggestions nor a docked player need the column.
 */
@Composable
fun CarHome() {
    val frame by CarEnvironment.frame.collectAsStateWithLifecycle()
    val navStatus by CarServices.nav.status.collectAsStateWithLifecycle()
    val phase by CarServices.media.phase.collectAsStateWithLifecycle()
    val now by CarServices.media.now.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { CarServices.autostart(frame) }
    LaunchedEffect(phase) { if (phase == CarMedia.Phase.READY) CarServices.autoplayIfDue() }

    val navigator = LocalCarNavigator.current
    val panel = navigator.sidePanel

    // Coming back to the map from another screen: the map stayed loaded underneath, so its surface
    // is only replaced if it died meanwhile. The first composition is skipped, since the surface
    // is only just being lent.
    val onHome = navigator.current == CarScreen.Home
    val seenHome = remember { booleanArrayOf(false) }
    LaunchedEffect(onHome) {
        if (onHome && seenHome[0]) CarServices.nav.replaceDeadSurface()
        if (onHome) seenHome[0] = true
    }
    val showPlayer = phase == CarMedia.Phase.READY && now.isActive && panel != SidePanel.NONE
    val suggestions by CarSuggestions.shortcuts.collectAsStateWithLifecycle()
    LaunchedEffect(suggestions.isEmpty()) {
        if (suggestions.isEmpty()) navigator.resetSuggestions()
    }
    val showSuggestions = suggestions.isNotEmpty()
    val splitScreen = showPlayer || showSuggestions

    // No outer padding on any side, split screen or not - only the gap between the two panes
    // when the player is sharing the screen with the map.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sideWidth = (maxWidth / 3.1f).coerceAtLeast(240.dp).coerceAtMost(maxWidth * 0.45f)
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                MapPane(navStatus, frame)
            }
            if (splitScreen) {
                BoxWithConstraints(Modifier.width(sideWidth).fillMaxHeight()) {
                    val availableHeight = maxHeight
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (showSuggestions) {
                            // Keep room for transport controls even while browsing all destinations.
                            val cardHeight = if (showPlayer) (availableHeight - 144.dp).coerceAtLeast(64.dp) else availableHeight
                            CarSuggestionCard(suggestions, navigator, Modifier.fillMaxWidth().heightIn(max = cardHeight), compactRows = cardHeight < 200.dp)
                        }
                        if (showPlayer) {
                            val player = Modifier.weight(1f).fillMaxWidth()
                            if (panel == SidePanel.LYRICS) SideLyrics(now, player)
                            else DockedPlayer(now, player, compact = showSuggestions)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MapPane(status: CarAppConnection.Status, frame: CarEnvironment.Frame) {
    val navigator = LocalCarNavigator.current
    if (status.phase == CarAppConnection.Phase.RUNNING) {
        CarAppStage(CarServices.nav, status, frame, embedded = true) { CarServices.stopNav() }
        return
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val hasLast = CarSettings.lastNav.value != null
        val hasNavApps by CarServices.hasNavApps.collectAsStateWithLifecycle()
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        when (status.phase) {
            CarAppConnection.Phase.BINDING, CarAppConnection.Phase.HANDSHAKE ->
                Text("Starting ${status.app ?: "the map"}…", style = MaterialTheme.typography.titleMedium)
            CarAppConnection.Phase.REJECTED, CarAppConnection.Phase.FAILED -> {
                Text("${status.app ?: "The map app"} would not start.", style = MaterialTheme.typography.titleMedium)
                status.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                GsButton(onClick = navigator::apps, tone = GsTone.Tonal) { Text("Choose another app") }
            }
            else -> if (!hasNavApps) {
                // Nothing to auto-select and nothing to pick from Car apps either - this is the
                // only case worth a distinct screen, since every other IDLE case resolves itself.
                Text("No mapping apps found.", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Install a navigation app that supports Android Auto and it will start here on its own.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (hasLast) {
                // IDLE with a last app on record means the driver stopped it themselves - it will
                // not come back on its own, so the screen must offer a way to bring it back rather
                // than sit on a "starting" message that never resolves.
                Text("Map stopped.", style = MaterialTheme.typography.titleMedium)
                GsButton(onClick = { scope.launch { CarServices.reconnectNav(frame) } }, tone = GsTone.Tonal) { Text("Reopen") }
                GsButton(onClick = { navigator.apps() }, tone = GsTone.Tonal) { Text("Car apps") }
            } else {
                // Autostart is still picking one - a navigation app is installed, so this resolves
                // to a "Starting..." state itself, in the next frame or two.
            }
        }
    }
}

/**
 * The player, docked beside the map: art, title, progress, transport, then like, shuffle, repeat
 * and lyrics. While it's here the nav bar's pill steps aside on Home, so the controls never show
 * twice. The X sends it back to the nav bar.
 */
@Composable
private fun DockedPlayer(now: NowPlaying, modifier: Modifier, compact: Boolean = false) {
    val navigator = LocalCarNavigator.current
    val media = CarServices.media
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val art by produceState(now.art, now.art, now.artUri) { value = now.art ?: MediaArt.load(context, now.artUri) }

    Surface(modifier = modifier, color = rememberArtColor(art, MaterialTheme.colorScheme.surfaceVariant)) {
        if (compact) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { navigator.media() }) {
                        Text(now.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(now.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    GsIconButton(Icons.Filled.Close, "Back to the nav bar", navigator::closeSidePanel, size = 44.dp)
                }
                Spacer(Modifier.height(8.dp))
                TransportRow(now, media, skip = 48.dp, modifier = Modifier.fillMaxWidth())
            }
            return@Surface
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
            // Whatever height the controls below leave over, so the art grows on a taller
            // screen and shrinks rather than pushing the buttons off a short one.
            val artSize = minOf(maxWidth - 44.dp, maxHeight - 250.dp).coerceIn(56.dp, 200.dp)
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth()) {
                    // Art opens the full media screen, same as on the pill.
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .size(artSize)
                            .clip(RoundedCornerShape(12.dp))
                            .background(scheme.surface)
                            .clickable { navigator.media() },
                    ) {
                        art?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    }
                    GsIconButton(
                        Icons.Filled.Close, "Back to the nav bar", navigator::closeSidePanel,
                        Modifier.align(Alignment.TopEnd), size = 36.dp, iconSize = 20.dp,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    now.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    now.artist, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (now.durationMs > 0) {
                    PlayerProgress(now, media, Modifier.fillMaxWidth().padding(vertical = 2.dp), showTimes = false)
                } else {
                    Spacer(Modifier.height(10.dp))
                }
                TransportRow(now, media, skip = 48.dp, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                PlayModeRow(now, media, size = 42.dp, modifier = Modifier.fillMaxWidth()) {
                    GsIconButton(
                        ImageVector.vectorResource(R.drawable.lyrics_24), "Lyrics", navigator::showSideLyrics,
                        size = 42.dp, iconSize = 22.dp,
                    )
                }
            }
        }
    }
}

/**
 * Lyrics alone beside the map, big enough to read at a glance. No art: that space goes to a
 * larger title and artist instead. The player goes back to the nav bar meanwhile, whose dock
 * button swaps these back out for the controls.
 */
@Composable
private fun SideLyrics(now: NowPlaying, modifier: Modifier) {
    val navigator = LocalCarNavigator.current
    val context = LocalContext.current
    val art by produceState(now.art, now.art, now.artUri) { value = now.art ?: MediaArt.load(context, now.artUri) }
    Surface(modifier = modifier, color = rememberArtColor(art, MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        now.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        now.artist, style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                GsIconButton(Icons.Filled.Close, "Hide lyrics", navigator::closeSidePanel, size = 36.dp, iconSize = 20.dp)
            }
            Spacer(Modifier.height(12.dp))
            LyricsPanel(now, Modifier.weight(1f).fillMaxWidth(), lineStyle = MaterialTheme.typography.headlineSmall)
        }
    }
}
