package app.seb3thehacker.gearslip.car

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import app.seb3thehacker.gearslip.car.theme.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import android.provider.Settings
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.seb3thehacker.gearslip.media.CarMedia
import app.seb3thehacker.gearslip.media.MediaApp
import app.seb3thehacker.gearslip.media.MediaArt
import app.seb3thehacker.gearslip.media.LyricsResult
import app.seb3thehacker.gearslip.media.Lyrics
import app.seb3thehacker.gearslip.media.MediaEntry
import app.seb3thehacker.gearslip.media.NowPlaying
import app.seb3thehacker.gearslip.media.QueueTrack
import android.media.session.PlaybackState
import kotlinx.coroutines.delay
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues

/**
 * A media app on the car screen: what it is playing and its controls on the left, the tree it
 * offers to browse on the right. The app owns the content; this only draws it.
 */
@Composable
fun MediaScreen(app: MediaApp, onExit: () -> Unit) {
    val context = LocalContext.current
    val media = CarServices.media
    val phase by media.phase.collectAsStateWithLifecycle()
    val now by media.now.collectAsStateWithLifecycle()
    val queue by media.queue.collectAsStateWithLifecycle()
    val browse by media.browse.collectAsStateWithLifecycle()
    val rejection by media.rejection.collectAsStateWithLifecycle()
    val search by media.search.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(Tab.NOW_PLAYING) }
    var searching by remember { mutableStateOf(false) }
    val searchAllowed = CarSettings.experimentalFeaturesEnabled.collectAsState().value && CarSettings.mediaSearchEnabled.collectAsState().value

    // The connection outlives this screen: the home screen's player is the same one.
    LaunchedEffect(app) { CarServices.openMedia(app) }

    val art = rememberArt(now)

    // The art's colour behind everything, header included; the buttons keep their own.
    Surface(Modifier.fillMaxSize(), color = rememberArtColor(art, MaterialTheme.colorScheme.background, darkLightness = 0.15f, lightLightness = 0.9f)) {
        if (phase != CarMedia.Phase.READY) {
            Column(Modifier.fillMaxSize()) {
                AppIdentity(app, onExit, Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                when (phase) {
                    CarMedia.Phase.REJECTED -> when (rejection) {
                        CarMedia.Rejection.NEEDS_NOTIFICATION_ACCESS -> Notice(
                            "${app.label} keeps its library to itself.",
                            "Gearslip can still control it once it has Notification access on the phone. " +
                                "Allow it under Settings > Notifications > Notification access.",
                            action = "Open on phone" to {
                                val app = context.applicationContext
                                runCatching {
                                    app.startActivity(
                                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                        )
                        CarMedia.Rejection.NEEDS_APP_RUNNING -> Notice(
                            "${app.label} isn't running.",
                            "${app.label} plays through Gearslip's own player, but only while it's " +
                                "open on the phone. Open it once and it keeps playing here in the car.",
                            action = "Open ${app.label}" to { media.launchAndConnect(app) },
                        )
                        CarMedia.Rejection.REFUSED -> Notice(
                            "${app.label} would not let Gearslip browse it.",
                            "Some media apps only accept Google's own host.",
                        )
                    }
                    else -> Notice("Connecting to ${app.label}…", null)
                }
            }
            return@Surface
        }

        if (searching) {
            SearchPanel(
                appLabel = app.label,
                media = media,
                canPickForYou = now.canSearch,
                onPlayed = {
                    searching = false
                    tab = Tab.NOW_PLAYING
                },
                onOpened = {
                    searching = false
                    tab = Tab.BROWSE
                },
                onPickForYou = { query ->
                    media.search(query)
                    searching = false
                    tab = Tab.NOW_PLAYING
                },
                onClose = { searching = false },
            )
            return@Surface
        }

        // Two columns, split the same way on every tab so nothing jumps when switching: the app
        // and the art (or, beside a list, the compact player) on the left; the tabs across the
        // top of the right column, over whatever the tab shows.
        Row(
            Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(Modifier.weight(0.8f).fillMaxHeight()) {
                AppIdentity(app, onExit)
                Spacer(Modifier.height(12.dp))
                if (tab == Tab.NOW_PLAYING) {
                    CoverArt(art, Modifier.weight(1f).fillMaxWidth())
                } else {
                    CompactPlayer(now, media, Modifier.weight(1f).fillMaxWidth())
                }
            }
            Column(Modifier.weight(1.2f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MediaTab("Now playing", tab == Tab.NOW_PLAYING, Modifier.weight(1f)) { tab = Tab.NOW_PLAYING }
                    MediaTab("Browse", tab == Tab.BROWSE, Modifier.weight(1f)) { tab = Tab.BROWSE }
                    MediaTab("Up next", tab == Tab.QUEUE, Modifier.weight(1f)) { tab = Tab.QUEUE }
                    MediaTab("Lyrics", tab == Tab.LYRICS, Modifier.weight(1f)) { tab = Tab.LYRICS }
                    if (searchAllowed && (now.canSearch || media.canSearchLibrary)) {
                        GsIconButton(
                            Icons.Filled.Search,
                            "Search ${app.label}",
                            { searching = true },
                            colors = GsColors(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface),
                            size = 44.dp,
                            shape = MaterialTheme.shapes.large,
                        )
                    }
                }
                search?.let { SearchStatus(it, app.label, media::dismissSearch) }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        Tab.NOW_PLAYING -> NowPlayingControls(now, media, Modifier.fillMaxSize())
                        Tab.BROWSE -> BrowsePanel(browse, media, app.label, Modifier.fillMaxSize())
                        Tab.QUEUE -> QueuePanel(queue, now, media, Modifier.fillMaxSize())
                        Tab.LYRICS -> LyricsPanel(now, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

/**
 * Back, then which app this is: its own icon and its name, quieter than the song title - the
 * name alone in big bold type read as one more heading fighting the track for attention.
 */
@Composable
private fun AppIdentity(app: MediaApp, onExit: () -> Unit, modifier: Modifier = Modifier) {
    val icon = remember(app) { findEntry(app.component.flattenToString())?.icon }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        GsIconButton(Icons.Filled.ArrowBack, "Back", onExit, size = 44.dp)
        Spacer(Modifier.width(14.dp))
        icon?.let {
            Image(it, null, Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)))
            Spacer(Modifier.width(10.dp))
        }
        Text(
            app.label.lowercase().replaceFirstChar { it.titlecase() },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private enum class Tab { NOW_PLAYING, BROWSE, QUEUE, LYRICS }

// --- search ----------------------------------------------------------------------------------

/**
 * Types a search for the app to play. The app picks the result and plays it, the way it would
 * for "play X" from a voice assistant, so there's no list to choose from.
 */
/**
 * Search inside the media app. It asks the app's library for matches half a second after the
 * driver stops typing and lists them, so the driver picks exactly what plays: a song plays, an
 * album or artist opens in Browse. The search key hides the keyboard to show more results; the
 * field brings it back. An app with no library search can still be told to play its own best
 * match, which is all the panel could do before.
 */
@Composable
private fun SearchPanel(
    appLabel: String,
    media: CarMedia,
    canPickForYou: Boolean,
    onPlayed: () -> Unit,
    onOpened: () -> Unit,
    onPickForYou: (String) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf(true) }
    val results by media.results.collectAsStateWithLifecycle()
    LaunchedEffect(query) {
        delay(SEARCH_PAUSE_MS)
        media.searchLibrary(query)
    }
    DisposableEffect(Unit) { onDispose { media.clearResults() } }

    val trimmed = query.trim()
    val current = results?.takeIf { it.query == trimmed }
    val unsupported = current?.supported == false

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GsIconButton(Icons.Filled.ArrowBack, "Back", onClose, size = ICON_BUTTON_SIZE)
            SearchField(
                query,
                "Search $appLabel",
                Modifier.weight(1f).clickable { typing = true },
                onClear = {
                    query = ""
                    typing = true
                },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
            val entries = current?.entries
            when {
                trimmed.isEmpty() -> SearchNote("Type a song, artist, album, or playlist.")
                unsupported && canPickForYou ->
                    SearchNote("$appLabel can't list results. Press search, and $appLabel picks what to play.")
                unsupported -> SearchNote("$appLabel can't search from the car.")
                entries == null -> SearchNote("Searching $appLabel…")
                entries.isEmpty() -> SearchNote("$appLabel found nothing for \"$trimmed\".")
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                    items(entries) { entry ->
                        EntryRow(entry, media) {
                            if (entry.browsable) {
                                media.select(entry)
                                onOpened()
                            } else {
                                media.playAll(entry)
                                onPlayed()
                            }
                        }
                    }
                }
            }
        }
        if (typing) {
            CarKeyboard(
                text = query,
                onTextChange = { query = it },
                onSubmit = {
                    when {
                        trimmed.isEmpty() -> Unit
                        unsupported && canPickForYou -> onPickForYou(trimmed)
                        else -> typing = false
                    }
                },
                onDismiss = { if (trimmed.isEmpty()) onClose() else typing = false },
            )
        }
    }
}

@Composable
private fun SearchNote(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** How long typing has to pause before the library is searched. */
private const val SEARCH_PAUSE_MS = 1_000L

/** A line under the tabs while the app works on a search, and if it plays nothing for it. */
@Composable
private fun SearchStatus(search: CarMedia.Search, appLabel: String, onDismiss: () -> Unit) {
    Surface(
        onClick = onDismiss,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Text(
            if (search.ignored) "$appLabel didn't play anything for \"${search.query}\"."
            else "Searching $appLabel for \"${search.query}\"…",
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/** A bigger, easier-to-hit tab than a plain [TextButton] - this row is the only navigation on the screen. */
@Composable
private fun MediaTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    GsIconBox(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        colors = if (selected) GsColors(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        else GsColors(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface),
        shape = MaterialTheme.shapes.large,
        latched = selected,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The song's lyrics, fetched when this tab opens. Synced lyrics follow the playback position with
 * the current line in the accent colour; unsynced ones are plain scrolling text.
 */
@Composable
internal fun LyricsPanel(
    now: NowPlaying,
    modifier: Modifier,
    lineStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleLarge,
) {
    val context = LocalContext.current
    val lyrics by produceState<LyricsState>(LyricsState.Loading, now.title, now.artist, now.album, now.durationMs) {
        value = LyricsState.Loading
        value = Lyrics.find(context, now.title, now.artist, now.album, now.durationMs)
            ?.let { LyricsState.Found(it) } ?: LyricsState.Missing
    }
    val list = rememberLazyListState()

    // Ticks with playback so the highlighted line moves; only synced lyrics need it.
    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(now) {
        while (true) {
            position = now.currentPosition()
            kotlinx.coroutines.delay(300)
        }
    }

    when (val state = lyrics) {
        LyricsState.Loading -> Notice("Looking for lyrics…", null)
        LyricsState.Missing -> Notice(
            if (now.title.isEmpty()) "Nothing playing." else "No lyrics found for this track.",
            null,
        )
        is LyricsState.Found -> {
            val lines = state.result.lines
            val current = if (state.result.synced) lines.indexOfLast { it.timeMs <= position } else -1
            LaunchedEffect(current) {
                if (current >= 0) list.animateScrollToItem((current - 1).coerceAtLeast(0))
            }
            LazyColumn(modifier, state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(lines) { index, line ->
                    Text(
                        line.text.ifEmpty { " " },
                        style = lineStyle,
                        fontWeight = if (index == current) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            !state.result.synced -> MaterialTheme.colorScheme.onSurface
                            index == current -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

private sealed interface LyricsState {
    data object Loading : LyricsState
    data object Missing : LyricsState
    class Found(val result: LyricsResult) : LyricsState
}

@Composable
private fun Notice(title: String, detail: String?, action: Pair<String, () -> Unit>? = null) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        detail?.let {
            Text(
                it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        action?.let { (label, run) ->
            Spacer(Modifier.height(12.dp))
            GsButton(onClick = run, tone = GsTone.Tonal) { Text(label) }
        }
    }
}

// --- now playing -----------------------------------------------------------------------------

/**
 * The now-playing view on its own, for places with no tabs around it (an app's own
 * now-playing template): the cover on the left, [NowPlayingControls] on the right.
 */
@Composable
internal fun NowPlayingPage(now: NowPlaying, media: CarMedia, modifier: Modifier) {
    Row(
        modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        CoverArt(rememberArt(now), Modifier.weight(0.8f).fillMaxHeight())
        NowPlayingControls(now, media, Modifier.weight(1.2f).fillMaxHeight())
    }
}

/** The cover, as big a square as its space allows, centred in it. */
@Composable
private fun CoverArt(art: android.graphics.Bitmap?, modifier: Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 6.dp,
            modifier = Modifier.size(minOf(maxWidth, maxHeight)),
        ) {
            art?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
    }
}

/**
 * Everything about the track and every control for it, read top to bottom: title and artist,
 * the seek bar, the transport, then like, shuffle and repeat as big icon-only buttons.
 */
@Composable
private fun NowPlayingControls(now: NowPlaying, media: CarMedia, modifier: Modifier) {
    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Column {
            Text(
                now.title.ifEmpty { "Nothing playing" },
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(now.artist, now.album).filter { it.isNotEmpty() }.joinToString(" - "),
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            now.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, maxLines = 2) }
        }
        PlayerProgress(now, media, Modifier.fillMaxWidth())
        TransportRow(now, media, skip = 60.dp, modifier = Modifier.fillMaxWidth())
        PlayModeRow(now, media, size = 56.dp, modifier = Modifier.fillMaxWidth(), maxOther = 2)
    }
}

/**
 * The player beside the browse list, the queue and the lyrics: no art, which the list needs the
 * room for, just what's playing, the seek bar and the controls.
 */
@Composable
private fun CompactPlayer(now: NowPlaying, media: CarMedia, modifier: Modifier) {
    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column {
            Text(
                now.title.ifEmpty { "Nothing playing" },
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                now.artist,
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        PlayerProgress(now, media, Modifier.fillMaxWidth())
        TransportRow(now, media, skip = 52.dp, modifier = Modifier.fillMaxWidth())
        PlayModeRow(now, media, size = 48.dp, modifier = Modifier.fillMaxWidth())
    }
}

// --- browsing --------------------------------------------------------------------------------

@Composable
private fun BrowsePanel(browse: app.seb3thehacker.gearslip.media.BrowseState, media: CarMedia, appLabel: String, modifier: Modifier) {
    Column(modifier) {
        // At the library root there's no trail and nowhere to go up to, so there's nothing this
        // row could say that the app's name in the top-left corner hasn't already - showing it
        // again here (as "Browse", or as the app's own name) was just the same label twice.
        if (media.canGoUp || browse.trail.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (media.canGoUp) {
                    GsIconButton(Icons.Filled.ArrowBack, "Up", media::up)
                }
                browse.trail.lastOrNull()?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        when {
            browse.loading -> Notice("Loading…", null)
            browse.unavailable -> Notice(
                "$appLabel keeps its library to itself.",
                "Start music in the app on your phone. Play, pause, skip and seek work from here.",
            )
            browse.failed -> Notice("Could not load this list.", null)
            browse.entries.isEmpty() -> Notice("Nothing here.", null)
            else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(browse.entries, key = { it.id }) { entry -> EntryRow(entry, media) }
            }
        }
    }
}

// --- up next -----------------------------------------------------------------------------------

/**
 * Whatever the app has queued up, starting right after the track that's playing now - not the
 * whole queue from the top, which would just repeat what the player beside it already shows. Apps
 * that never publish a queue (many don't) show an empty notice rather than a permanently blank tab.
 */
@Composable
private fun QueuePanel(queue: List<QueueTrack>, now: NowPlaying, media: CarMedia, modifier: Modifier) {
    val upNext = remember(queue, now.activeQueueItemId) {
        val current = queue.indexOfFirst { it.queueId == now.activeQueueItemId }
        if (current >= 0) queue.drop(current + 1) else queue
    }
    Column(modifier) {
        if (upNext.isEmpty()) {
            Notice(
                "Nothing queued.",
                "${if (now.title.isEmpty()) "Nothing playing" else "Only what's already playing"} - " +
                    "not every app tells Gearslip what comes next.",
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(upNext, key = { it.queueId }) { track -> QueueRow(track, media) }
            }
        }
    }
}

@Composable
private fun QueueRow(track: QueueTrack, media: CarMedia) {
    val context = LocalContext.current
    val icon by produceState(track.iconBitmap, track.iconUri) {
        value = track.iconBitmap ?: MediaArt.load(context, track.iconUri)
    }
    Surface(
        onClick = { media.playQueueItem(track) },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(62.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surface)) {
                icon?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (track.subtitle.isNotEmpty()) {
                    Text(track.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(Icons.Filled.PlayArrow, "Play", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EntryRow(entry: MediaEntry, media: CarMedia, onClick: () -> Unit = { media.select(entry) }) {
    val context = LocalContext.current
    val icon by produceState(entry.iconBitmap, entry.iconUri) {
        value = entry.iconBitmap ?: MediaArt.load(context, entry.iconUri)
    }
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(62.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surface)) {
                icon?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (entry.subtitle.isNotEmpty()) {
                    Text(entry.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (entry.browsable && entry.playable) {
                GsIconButton(Icons.Filled.PlayArrow, "Play", { media.playAll(entry) })
            }
            if (entry.browsable) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
    }
}

/** Material's transport icons, from their published path data (they live in the extended set). */
internal object MediaIcons {
    val Pause = icon("Pause", "M6,19h4L10,5L6,5v14zM14,5v14h4L18,5h-4z")
    val Next = icon("SkipNext", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z")
    /** The 3x3 app grid; not in material-icons-core. */
    val Apps = icon("Apps", "M4,8h4L8,4L4,4v4zM10,20h4v-4h-4v4zM4,20h4v-4L4,16v4zM4,14h4v-4L4,10v4zM10,14h4v-4h-4v4zM16,4v4h4L20,4h-4zM10,8h4L14,4h-4v4zM16,14h4v-4h-4v4zM16,20h4v-4h-4v4z")
    val Previous = icon("SkipPrevious", "M6,6h2v12L6,18zM9.5,12l8.5,6V6z")
    val Equalizer = icon("Equalizer", "M10,20h4L14,4h-4v16zM4,20h4v-8L4,12v8zM16,9v11h4L20,9h-4z")
    val Shuffle = icon("Shuffle", "M10.59,9.17L5.41,4 4,5.41l5.17,5.17 1.42,-1.41zM14.5,4l2.04,2.04L4,18.59 5.41,20 17.96,7.46 20,9.5L20,4h-5.5zM14.83,13.41l-1.41,1.41 3.13,3.13L14.5,20L20,20v-5.5l-2.04,2.04 -3.13,-3.13z")
    val Repeat = icon("Repeat", "M7,7h10v3l4,-4 -4,-4v3L5,5v6h2L7,7zM17,17L7,17v-3l-4,4 4,4v-3h12v-6h-2v4z")
    val RepeatOne = icon("RepeatOne", "M7,7h10v3l4,-4 -4,-4v3L5,5v6h2L7,7zM17,17L7,17v-3l-4,4 4,4v-3h12v-6h-2v4zM13,15L13,9h-1l-2,1v1h1.5v4L13,15z")
    /** A screen with a filled right-hand panel: "put the player beside the map". */
    val DockRight = icon("DockRight", "M2,4h20v16H2zM4,6h10v12H4z", PathFillType.EvenOdd)

    private fun icon(name: String, path: String, fillType: PathFillType = PathFillType.NonZero) = ImageVector.Builder(
        name, 24.dp, 24.dp, 24f, 24f,
    ).addPath(addPathNodes(path), pathFillType = fillType, fill = SolidColor(androidx.compose.ui.graphics.Color.Black)).build()
}
