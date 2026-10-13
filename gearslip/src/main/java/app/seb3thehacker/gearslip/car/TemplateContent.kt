package app.seb3thehacker.gearslip.car

import app.seb3thehacker.gearslip.car.theme.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.Item
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.LongMessageTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row as CarRow
import androidx.car.app.model.SearchTemplate
import androidx.compose.foundation.clickable
import androidx.car.app.model.Section
import androidx.car.app.model.SectionedItemTemplate
import androidx.car.app.model.TabTemplate
import androidx.car.app.model.Template
import androidx.car.app.model.signin.InputSignInMethod
import androidx.car.app.model.signin.PinSignInMethod
import androidx.car.app.model.signin.ProviderSignInMethod
import androidx.car.app.model.signin.QRCodeSignInMethod
import androidx.car.app.model.signin.SignInTemplate
import androidx.car.app.media.model.MediaPlaybackTemplate
import androidx.car.app.OnDoneCallback
import androidx.car.app.serialization.Bundleable
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.host.isDrawable
import app.seb3thehacker.gearslip.host.inputChanged
import app.seb3thehacker.gearslip.host.inputSubmitted
import app.seb3thehacker.gearslip.host.submitted
import app.seb3thehacker.gearslip.host.tabSelected
import app.seb3thehacker.gearslip.host.text
import app.seb3thehacker.gearslip.host.textChanged

/**
 * Templates that fill a screen with content rather than sitting over a map.
 *
 * A map-backed template can hold one of these in its side pane, which is why they are drawn by
 * the same function either way - the only difference is how much room they get.
 */
@Composable
fun ContentTemplate(
    template: Template?,
    modifier: Modifier = Modifier,
    autoStartPane: Boolean = false,
    ownActionsElsewhere: Boolean = false,
    tabId: String = "",
) {
    when (template) {
        null -> Unit
        is ListTemplate -> ListContent(template, modifier)
        is GridTemplate -> GridContent(template, modifier)
        is PaneTemplate -> PaneContent(template, modifier, autoStartPane)
        is MessageTemplate -> MessageContent(template, modifier)
        is LongMessageTemplate -> LongMessageContent(template, modifier)
        is SearchTemplate -> SearchContent(template, modifier)
        is SignInTemplate -> SignInContent(template, modifier)
        is TabTemplate -> TabContent(template, modifier)
        is SectionedItemTemplate -> SectionedContent(template, modifier, showActionsInHeader = !ownActionsElsewhere, tabId = tabId)
        is MediaPlaybackTemplate -> MediaPlaybackContent(template, modifier)
        else -> UnsupportedChrome(template, modifier)
    }
}

/** True when this template is one [ContentTemplate] knows how to draw. */
fun isContentTemplate(template: Template?): Boolean = when (template) {
    is ListTemplate, is GridTemplate, is PaneTemplate, is MessageTemplate,
    is LongMessageTemplate, is SearchTemplate, is SignInTemplate, is TabTemplate,
    is SectionedItemTemplate, is MediaPlaybackTemplate,
    -> true
    else -> false
}

// --- lists, grids and panes ---------------------------------------------------------------------

@Composable
private fun ListContent(template: ListTemplate, modifier: Modifier) {
    ContentSurface(modifier) {
        HeaderBar(
            headerTitle(template.header, template.title.text()),
            template.header?.startHeaderAction ?: template.headerAction,
            template.header?.endHeaderActions.orEmpty(),
            template.actionStrip,
        )
        if (template.isLoading) Loading()
        else ItemListColumn(
            template.singleList, template.sectionedLists?.map { it.itemList },
            Modifier.weight(1f, fill = false),
        )
        ActionRow(template.actions.orEmpty(), Modifier.padding(12.dp))
    }
}

@Composable
private fun GridContent(template: GridTemplate, modifier: Modifier) {
    ContentSurface(modifier) {
        HeaderBar(
            headerTitle(template.header, template.title.text()),
            template.header?.startHeaderAction ?: template.headerAction,
            template.header?.endHeaderActions.orEmpty(),
            template.actionStrip,
        )
        if (template.isLoading) Loading() else GridItems(template.singleList, Modifier.weight(1f, fill = false))
        ActionRow(template.actions.orEmpty(), Modifier.padding(12.dp))
    }
}

@Composable
private fun PaneContent(template: PaneTemplate, modifier: Modifier, autoStart: Boolean = false) {
    ContentSurface(modifier) {
        HeaderBar(
            headerTitle(template.header, template.title.text()),
            template.header?.startHeaderAction ?: template.headerAction,
            template.header?.endHeaderActions.orEmpty(),
            template.actionStrip,
        )
        if (template.pane?.isLoading == true) Loading() else PaneRows(template.pane, autoStart = autoStart)
    }
}

// --- messages -------------------------------------------------------------------------------

@Composable
private fun MessageContent(template: MessageTemplate, modifier: Modifier) {
    ContentSurface(modifier) {
        // The header carries only the back arrow, not a title - a message template's own title
        // is usually the long-form question ("Would you like to download the map...") that
        // reads fine centred in the body but doesn't fit a one-line header without truncating.
        val backAction = template.header?.startHeaderAction ?: template.headerAction
        HeaderBar("", backAction, template.header?.endHeaderActions.orEmpty(), template.actionStrip)
        if (template.isLoading) {
            Loading()
            return@ContentSurface
        }
        // A back arrow already undoes this screen, so an app's own "Cancel" button would just
        // be a second way to do the one thing the header already offers.
        val actions = template.actions.orEmpty().filter { it.isDrawable() }.let { list ->
            if (backAction != null) list.filterNot { it.title.text().equals("Cancel", ignoreCase = true) } else list
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            template.icon?.let {
                CarGlyph(it, Modifier.size(40.dp))
                Spacer(Modifier.height(12.dp))
            }
            Text(template.title.text(), style = ChromeType.title, textAlign = TextAlign.Center)
            val detail = template.message.text()
            if (detail.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    detail,
                    style = ChromeType.body,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (actions.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                ActionRow(actions, Modifier.fillMaxWidth(0.6f), large = true)
            }
        }
    }
}

/**
 * The long form is the same idea with a scroll: it's what apps use for terms and conditions,
 * which is also why it is only ever shown parked.
 */
@Composable
private fun LongMessageContent(template: LongMessageTemplate, modifier: Modifier) {
    ContentSurface(modifier) {
        HeaderBar(
            template.title.text(),
            template.headerAction,
            actionStrip = template.actionStrip,
        )
        Text(
            template.message.text(),
            style = ChromeType.body,
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        )
        ActionRow(template.actions.orEmpty(), Modifier.padding(16.dp))
    }
}

// --- search ---------------------------------------------------------------------------------

/**
 * Search: the host owns the text and the keyboard, and the app owns the results.
 *
 * Every keystroke goes to the app through [SearchTemplate.getSearchCallbackDelegate], which is
 * what lets results update as you type. The text lives here rather than in the template
 * because the app never sends it back - it only ever sends a new list of results.
 */
@Composable
private fun SearchContent(template: SearchTemplate, modifier: Modifier) {
    // Seeded once. The app refreshes this template on every keystroke, so re-seeding from
    // initialSearchText would fight whatever is being typed.
    var query by remember { mutableStateOf(template.initialSearchText.orEmpty()) }
    val delegate = template.searchCallbackDelegate
    // An app that opens on results ("navigate to Starbucks") asks for the keyboard to stay down,
    // so the results aren't hidden under it. Tapping the field brings it up.
    var typing by remember { mutableStateOf(template.isShowKeyboardByDefault) }

    ContentSurface(modifier) {
        // Back button and field share a row: on a 480px screen the keyboard and the results
        // are already competing for every pixel, so the header can't have one to itself.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            template.headerAction?.let { ActionButton(it) }
            SearchField(
                query,
                template.searchHint.orEmpty(),
                Modifier.weight(1f).clickable { typing = true },
                onClear = {
                    query = ""
                    delegate.textChanged("")
                },
            )
            ActionRow(template.actionStrip?.actions.orEmpty())
        }

        Box(Modifier.weight(1f)) {
            if (template.isLoading) Loading()
            else ItemListColumn(template.itemList, modifier = Modifier.fillMaxSize())
        }

        if (typing) {
            CarKeyboard(
                text = query,
                onTextChange = {
                    query = it
                    delegate.textChanged(it)
                },
                onSubmit = { delegate.submitted(query) },
                onDismiss = { typing = false },
            )
        }
    }
}

/**
 * The field the on-screen keyboard types into. It is the same height and shape as the round
 * buttons beside it, so the header reads as one row of controls rather than a thin strip squeezed
 * between two big buttons, and it carries a magnifier so it reads as search at a glance.
 * [onClear] adds a clear button once something is typed; [searchIcon] is off for sign-in fields.
 */
@Composable
internal fun SearchField(
    query: String,
    hint: String,
    modifier: Modifier = Modifier,
    searchIcon: Boolean = true,
    onClear: (() -> Unit)? = null,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = androidx.compose.foundation.shape.CircleShape,
        modifier = modifier.fillMaxWidth().height(ICON_BUTTON_SIZE),
    ) {
        Row(
            Modifier.padding(start = if (searchIcon) 16.dp else 22.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (searchIcon) {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Filled.Search,
                    contentDescription = null,
                    tint = muted,
                    modifier = Modifier.size(26.dp),
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(
                query.ifEmpty { hint.ifEmpty { "Search" } },
                style = MaterialTheme.typography.titleMedium,
                color = if (query.isEmpty()) muted else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (onClear != null && query.isNotEmpty()) {
                GsIconButton(
                    androidx.compose.material.icons.Icons.Filled.Close,
                    "Clear",
                    onClear,
                    colors = GsColors(androidx.compose.ui.graphics.Color.Transparent, muted),
                    size = 42.dp,
                    iconSize = 22.dp,
                    shape = androidx.compose.foundation.shape.CircleShape,
                )
            }
        }
    }
}

// --- sign-in --------------------------------------------------------------------------------

/**
 * Sign-in comes in four shapes and the host has to draw whichever the app picked. Only the
 * typed one needs the keyboard; a PIN or QR code is something the driver reads off the screen
 * and completes on their phone.
 */
@Composable
private fun SignInContent(template: SignInTemplate, modifier: Modifier) {
    ContentSurface(modifier) {
        HeaderBar(
            template.title.text(),
            template.headerAction,
            actionStrip = template.actionStrip,
        )
        if (template.isLoading) {
            Loading()
            return@ContentSurface
        }

        val instructions = template.instructions.text()
        if (instructions.isNotEmpty()) {
            Text(
                instructions,
                style = ChromeType.body,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        when (val method = template.signInMethod) {
            is InputSignInMethod -> InputSignIn(method, Modifier.weight(1f))
            is PinSignInMethod -> Callout(method.pinCode.toString(), Modifier.weight(1f))
            is QRCodeSignInMethod -> Callout(method.uri.toString(), Modifier.weight(1f))
            is ProviderSignInMethod -> Box(Modifier.weight(1f), Alignment.Center) {
                ActionRow(listOf(method.action))
            }
            else -> Spacer(Modifier.weight(1f))
        }

        val additional = template.additionalText.text()
        if (additional.isNotEmpty()) {
            Text(
                additional,
                style = ChromeType.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        ActionRow(template.actions.orEmpty(), Modifier.padding(12.dp))
    }
}

@Composable
private fun InputSignIn(method: InputSignInMethod, modifier: Modifier) {
    var value by remember { mutableStateOf(method.defaultValue.text()) }
    val delegate = method.inputCallbackDelegate
    val masked = method.inputType == InputSignInMethod.INPUT_TYPE_PASSWORD

    Column(modifier) {
        SearchField(if (masked) "•".repeat(value.length) else value, method.hint.text(), searchIcon = false)
        val error = method.errorMessage.text()
        if (error.isNotEmpty()) {
            Text(
                error,
                style = ChromeType.label,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        CarKeyboard(
            text = value,
            onTextChange = {
                value = it
                delegate.inputChanged(it)
            },
            onSubmit = { delegate.inputSubmitted(value) },
            submitIcon = false,
        )
    }
}

/** A code the driver reads off the screen. Big, centred, nothing else competing with it. */
@Composable
private fun Callout(value: String, modifier: Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            value,
            style = ChromeType.headline,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

// --- tabs -----------------------------------------------------------------------------------

@Composable
private fun TabContent(template: TabTemplate, modifier: Modifier) {
    val active = template.activeTabContentId
    val innerTemplate = template.tabContents?.template
    // A sectioned tab's own actions (search, a Liked Songs shortcut) belong on the same line as
    // the tabs themselves - a real Android Auto host's header row, not a second near-empty row
    // underneath it that SectionedContent would otherwise draw just to hold them.
    val trailingActions = (innerTemplate as? SectionedItemTemplate)?.actions.orEmpty().filter { it.isDrawable() }
    Box(modifier) {
        ContentSurface(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .verticalScrollNone(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                template.headerAction?.let { ActionButton(it) }
                template.tabs.forEach { tab ->
                    val selected = tab.contentId == active
                    GsIconBox(
                        onClick = { template.tabCallbackDelegate.tabSelected(tab.contentId) },
                        colors = if (selected) GsColors(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                        else GsColors(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurface),
                        shape = RoundedCornerShape(20.dp),
                        latched = selected,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            tab.icon?.let {
                                CarGlyph(it, Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(tab.title.text(), style = ChromeType.label, maxLines = 1)
                        }
                    }
                }
            }
            if (template.isLoading) Loading()
            else ContentTemplate(
                innerTemplate,
                Modifier.weight(1f),
                ownActionsElsewhere = trailingActions.isNotEmpty(),
                tabId = active.orEmpty(),
            )
        }
        // Bottom-left, not on the tab row: a driver's hand rests near the bottom of the screen,
        // and the tab row is already busy with the tabs themselves.
        if (trailingActions.isNotEmpty()) {
            Row(
                Modifier.align(Alignment.BottomStart).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                trailingActions.forEach { ActionButton(it) }
            }
        }
    }
}

/** Tabs share a row; this keeps the modifier chain readable where no scroll is wanted. */
private fun Modifier.verticalScrollNone(): Modifier = this

// --- sectioned items --------------------------------------------------------------------------

/**
 * A section's items ([SectionRows]) are cached for the life of the process, keyed off the
 * foreground app and, when nested under a tab, the tab - so flipping back to a shelf already
 * seen this drive is instant instead of re-asking the app over the binder. Bounded and in
 * memory only, so it costs nothing on disk and disappears with the process.
 */
private val sectionCache = android.util.LruCache<String, List<Item>>(64)

/**
 * Sections fetch their items across the binder on demand rather than carrying them, so each one
 * is asked for its full range once and the answer is held while the section is on screen (and,
 * via [sectionCache], for the rest of the drive).
 */
@Composable
private fun SectionedContent(template: SectionedItemTemplate, modifier: Modifier, showActionsInHeader: Boolean = true, tabId: String = "") {
    val appPackage = CarServices.nav.appPackage.orEmpty()
    ContentSurface(modifier) {
        // The app's own actions (Spotify's search and Liked Songs shortcut, for two) come from
        // template.actions, not the header - but a bottom-of-content row is wrong for a driver
        // reaching for it. Normally the header's own end-actions slot is where a real Android
        // Auto host puts these; nested under a TabTemplate, though, TabContent already drew them
        // on the tab row itself, so showActionsInHeader is false and this header stays title-only
        // (and usually collapses to nothing, since a tab's own content carries no separate title).
        HeaderBar(
            headerTitle(template.header, ""),
            template.header?.startHeaderAction,
            template.header?.endHeaderActions.orEmpty() + if (showActionsInHeader) template.actions.orEmpty() else emptyList(),
        )
        if (template.isLoading) {
            Loading()
            return@ContentSurface
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            template.sections.forEachIndexed { index, section ->
                PaneTitle(section.title.text())
                SectionRows(section, "$appPackage/$tabId/$index/${section.title.text()}")
            }
        }
    }
}

@Composable
private fun SectionRows(section: Section<*>, cacheKey: String) {
    var items by remember(section) { mutableStateOf(sectionCache.get(cacheKey).orEmpty()) }

    LaunchedEffect(section) {
        if (sectionCache.get(cacheKey) != null) return@LaunchedEffect
        val delegate = section.itemsDelegate
        val sizeResult = runCatching { delegate.size }
        val size = sizeResult.getOrDefault(0)
        sizeResult.onFailure { GearslipLog.w("host: section.itemsDelegate.size threw: ${it.javaClass.simpleName}: ${it.message}") }
        if (size <= 0) return@LaunchedEffect
        runCatching {
            delegate.requestItemRange(0, size - 1, object : OnDoneCallback {
                override fun onSuccess(response: Bundleable?) {
                    val value = response?.let { runCatching { it.get() }.getOrNull() }
                    @Suppress("UNCHECKED_CAST")
                    val fetched = (value as? List<Item>).orEmpty()
                    items = fetched
                    sectionCache.put(cacheKey, fetched)
                }

                override fun onFailure(response: Bundleable) =
                    GearslipLog.w("host: a section refused to hand over its items")
            })
        }.onFailure { GearslipLog.w("host: could not fetch section items: ${it.message}") }
    }

    if (items.isEmpty()) {
        val message = section.noItemsMessage.text()
        if (message.isNotEmpty()) Text(message, Modifier.padding(14.dp), style = ChromeType.body)
        return
    }
    // A section's items are homogeneous in practice - one kind or the other - but nothing in
    // the API guarantees that, so both are handled rather than assuming whichever the app
    // happened to send first.
    val rows = items.filterIsInstance<CarRow>()
    val tiles = items.filterIsInstance<GridItem>()
    rows.forEach { RowItem(it) }
    if (tiles.isNotEmpty()) {
        // A plain scrollable Row, not LazyRow: the car screen renders into a VirtualDisplay
        // Presentation rather than a normal window, and LazyRow's SubcomposeLayout measures to
        // zero height there even with an explicit size - a handful of tiles per shelf makes the
        // lost virtualization no real loss.
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tiles.forEach { tile -> GridTile(tile, Modifier.width(180.dp)) }
        }
    }
}

// --- media ----------------------------------------------------------------------------------

/**
 * The template itself carries only a header - real Android Auto hosts draw this screen from the
 * app's MediaSession instead, which is exactly what [CarServices.media] already does for the
 * media-app launcher and the bottom bar. Reusing [NowPlayingPage] here rather than a "not
 * supported" placeholder means an app whose own now-playing template Gearslip lands on (like
 * Spotify's "Liked Songs" screen) still gets working transport controls.
 */
@Composable
private fun MediaPlaybackContent(template: MediaPlaybackTemplate, modifier: Modifier) {
    val media = CarServices.media
    val now by media.now.collectAsState()
    ContentSurface(modifier) {
        HeaderBar(
            headerTitle(template.header, ""),
            template.header?.startHeaderAction,
            template.header?.endHeaderActions.orEmpty(),
        )
        if (now.title.isNotEmpty()) {
            NowPlayingPage(now, media, Modifier.weight(1f).fillMaxWidth())
        } else {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing playing yet.",
                    style = ChromeType.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

// --- shared ---------------------------------------------------------------------------------

@Composable
private fun ContentSurface(modifier: Modifier, content: @Composable ColumnScopeAlias.() -> Unit) {
    ChromeSurface(modifier, shape = RoundedCornerShape(0.dp)) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

private typealias ColumnScopeAlias = androidx.compose.foundation.layout.ColumnScope

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
        Text("Loading…", style = ChromeType.title)
    }
}
