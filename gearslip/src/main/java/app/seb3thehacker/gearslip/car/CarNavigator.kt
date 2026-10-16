package app.seb3thehacker.gearslip.car

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

sealed interface CarScreen {
    data object Home : CarScreen
    data object Settings : CarScreen
    data object Apps : CarScreen

    /** Opened from the clock: the day's calendar and the weather, nothing that needs a keyboard. */
    data object Dashboard : CarScreen

    /** Opened from the weather chip or the dashboard: now, the next hours and the week. */
    data object Weather : CarScreen

    /** Everything the head unit's sensor channel has advertised and sent, raw. */
    data object VehicleData : CarScreen

    /** The current media app, full size: browse, lyrics and controls. */
    data object Media : CarScreen

    /**
     * A templated app that isn't a navigation app - Spotify's own Car App Library service, among
     * others - opened on its own screen rather than the Home map slot, which only a genuine
     * navigation app can claim.
     */
    data object Browse : CarScreen

    /** A messaging app's recent conversations, read from the notifications it posts. */
    data class Messages(val packageName: String) : CarScreen

    /** [replyTo] is a notification key to open straight into its reply pane. */
    data class Notifications(val replyTo: String? = null) : CarScreen
    data class App(val id: String) : CarScreen
}

enum class SidePanel { NONE, CONTROLS, LYRICS }

/** One step on the breadcrumb trail: the screen it shows and the word for it in the trail. */
data class Crumb(val screen: CarScreen, val label: String)

/**
 * Where the car UI is, and how it got there. A car touchscreen has no hardware back button, so
 * every screen that isn't reached from [home] is pushed onto a trail (like a browser's history,
 * or a file manager's breadcrumb bar) rather than replacing the last one outright - that's what
 * lets the breadcrumb bar show "Home > Apps > Settings" and jump straight back to any of them,
 * and lets [back] undo exactly one step instead of always dumping you back at Home. An open app
 * can claim a back step first (a browser going back a page) through [backInterceptor].
 */
class CarNavigator(startId: String) {

    private val stack = mutableStateListOf(
        Crumb(
            if (CarApps.find(startId) != null) CarScreen.App(startId) else CarScreen.Home,
            "Home",
        ),
    )

    /** The whole trail, root first. Size 1 means "at Home" - the breadcrumb bar hides then. */
    val trail: List<Crumb> get() = stack

    val current: CarScreen get() = stack.last().screen

    var backInterceptor: (() -> Boolean)? = null

    /** Pushes a new step, unless it's the same screen already on top - tapping the button for
     * where you already are shouldn't grow the trail. */
    private fun push(screen: CarScreen, label: String) {
        compactSuggestions()
        if (stack.last().screen == screen) return
        stack.add(Crumb(screen, label))
    }

    /** The one way back to a clean slate: resets the whole trail to just Home. */
    fun home() {
        compactSuggestions()
        stack.clear()
        stack.add(Crumb(CarScreen.Home, "Home"))
    }

    /**
     * What the column beside the map on Home is showing, if anything. The player lives in one
     * place at a time: with [SidePanel.CONTROLS] it moves into the column and the nav bar's pill
     * steps aside; with [SidePanel.LYRICS] the column is all lyrics and the pill comes back.
     * Every other screen has no room for the column, so there the pill stays in the nav bar.
     */
    var sidePanel by mutableStateOf(SidePanel.NONE)
        private set

    /**
     * The pill's dock button: moves the player beside the map, going Home first if need be. With
     * lyrics already there it swaps them for the controls; with the controls there it closes.
     */
    fun togglePlayerDock() {
        if (current != CarScreen.Home) {
            sidePanel = SidePanel.CONTROLS
            home()
            return
        }
        sidePanel = if (sidePanel == SidePanel.CONTROLS) SidePanel.NONE else SidePanel.CONTROLS
    }

    fun showSideLyrics() { sidePanel = SidePanel.LYRICS }

    fun closeSidePanel() { sidePanel = SidePanel.NONE }

    var suggestionsExpanded by mutableStateOf(false)
        private set

    var suggestionsCollapsed by mutableStateOf(false)
        private set

    fun toggleSuggestionCard() {
        suggestionsCollapsed = !suggestionsCollapsed
        compactSuggestions()
    }

    fun toggleAllSuggestions() {
        suggestionsCollapsed = false
        suggestionsExpanded = !suggestionsExpanded
    }

    fun compactSuggestions() { suggestionsExpanded = false }

    fun collapseSuggestions() {
        suggestionsCollapsed = true
        compactSuggestions()
    }

    fun resetSuggestions() {
        suggestionsCollapsed = false
        compactSuggestions()
    }

    /** Component id of the app whose long-press menu (pin, close) is open, or null. */
    var appMenu by mutableStateOf<String?>(null)
        private set

    fun showAppMenu(componentId: String) { appMenu = componentId }

    fun dismissAppMenu() { appMenu = null }

    fun media() { push(CarScreen.Media, "Media") }

    fun browse(label: String) { push(CarScreen.Browse, label) }

    fun apps() { push(CarScreen.Apps, "Apps") }

    fun settings() { push(CarScreen.Settings, "Settings") }

    fun dashboard() { push(CarScreen.Dashboard, "Dashboard") }

    fun weather() { push(CarScreen.Weather, "Weather") }

    fun vehicleData() { push(CarScreen.VehicleData, "Vehicle data") }

    fun messages(packageName: String, label: String) { push(CarScreen.Messages(packageName), label) }

    /** The list lives on the dashboard; only a reply gets a screen of its own. */
    fun notifications(replyTo: String? = null) {
        if (replyTo == null) dashboard() else push(CarScreen.Notifications(replyTo), "Reply")
    }

    fun open(id: String) { push(CarScreen.App(id), CarApps.find(id)?.label ?: id) }

    /** Jumps straight to a step already on the trail, dropping everything after it - what
     * tapping a breadcrumb does. Index 0 (Home) is always on the trail, so this always succeeds. */
    fun jumpTo(index: Int) {
        while (stack.size > index + 1) stack.removeAt(stack.lastIndex)
    }

    /** One step back, same as tapping the crumb before the current one. */
    fun back() {
        if (suggestionsExpanded) {
            compactSuggestions()
            return
        }
        if (backInterceptor?.invoke() == true) return
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}

val LocalCarNavigator = staticCompositionLocalOf<CarNavigator> { error("no CarNavigator provided") }

/** "Is it dark outside", for apps that adapt to it. See [CarEnvironment]. */
val LocalDarkOutside = staticCompositionLocalOf { false }
