package app.seb3thehacker.gearslip.host

import android.os.SystemClock
import android.text.Spanned
import androidx.car.app.model.CarText
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapTemplate
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.PlaceListNavigationTemplate
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import app.seb3thehacker.gearslip.GearslipLog

/**
 * "Navigate to X" the way Android Auto does it: straight onto a route, no list to pick from.
 *
 * Android Auto looks the place up on Google's servers first. Gearslip stays on the phone, so it
 * asks the map app to search, then taps the nearest result in the app's list for the driver.
 * It stops at the app's start screen, so the driver sees where it's going and taps Start. It
 * gives up after [WINDOW_MS], or as soon as the driver taps anything themselves.
 */
internal class AutoRoute {
    private val log = GearslipLog.tagged("route")
    private var until = 0L
    private var pickedRow = false

    /** A destination was just sent to the map app. */
    fun arm() {
        armedAt = SystemClock.elapsedRealtime()
        until = armedAt + WINDOW_MS
        pickedRow = false
    }

    fun disarm() {
        until = 0L
    }

    private var armedAt = 0L
    private val armed get() = SystemClock.elapsedRealtime() < until && driverTappedAt < armedAt

    /** Each screen the map app sends while armed: tap the next step if it's there. */
    @Suppress("DEPRECATION") // Apps still send the older navigation templates.
    fun onTemplate(template: Template) {
        if (!armed) return
        when (template) {
            is NavigationTemplate -> disarm()
            // The start screen: the driver takes it from here.
            is RoutePreviewNavigationTemplate -> arrived()
            is MapWithContentTemplate -> onTemplate(template.contentTemplate)
            is PaneTemplate -> arrived()
            is MapTemplate -> if (template.pane != null) arrived() else template.itemList?.let(::onList)
            is SearchTemplate -> if (!template.isLoading) template.itemList?.let(::onList)
            is ListTemplate -> if (!template.isLoading) template.singleList?.let(::onList)
            is PlaceListMapTemplate -> if (!template.isLoading) template.itemList?.let(::onList)
            is PlaceListNavigationTemplate -> if (!template.isLoading) template.itemList?.let(::onList)
        }
    }

    /** Search results: the nearest place, or the first when the app gives no distances. */
    private fun onList(list: ItemList) {
        if (pickedRow) return
        val places = list.items.filterIsInstance<Row>()
            .filter { it.isEnabled && it.onClickDelegate != null && !it.isBrowsable }
        if (places.isEmpty()) return
        val nearest = places.minByOrNull { it.meters() ?: Double.MAX_VALUE } ?: return
        pickedRow = true
        log.i("picking the nearest of ${places.size} results")
        nearest.onClickDelegate.click(AUTO_LABEL)
    }

    private fun arrived() {
        if (pickedRow) log.i("at the start screen")
        disarm()
    }

    /** The first distance the app attached to this row's title or text lines. */
    private fun Row.meters(): Double? = (listOfNotNull(title) + texts).firstNotNullOfOrNull { it.distanceMeters() }

    private fun CarText.distanceMeters(): Double? {
        val chars = toCharSequence() as? Spanned ?: return null
        return chars.getSpans(0, chars.length, DistanceSpan::class.java).firstOrNull()?.distance?.meters()
    }

    companion object {
        /** Taps sent by this class carry this label, so they don't count as the driver's. */
        const val AUTO_LABEL = "auto-route"
        /** When the driver last tapped one of the app's buttons; that hands control back. */
        @Volatile var driverTappedAt = 0L
        private const val WINDOW_MS = 30_000L
    }
}
