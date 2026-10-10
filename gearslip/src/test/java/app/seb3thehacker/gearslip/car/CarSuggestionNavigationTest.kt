package app.seb3thehacker.gearslip.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarSuggestionNavigationTest {
    @Test fun `back dismisses suggestions before reaching the navigation app`() {
        val navigator = CarNavigator("")
        var mapBackCalls = 0
        navigator.backInterceptor = { mapBackCalls++; true }
        navigator.apps()
        navigator.toggleSuggestions()
        assertEquals(CarScreen.Home, navigator.current)
        assertTrue(navigator.suggestionsOpen)

        navigator.back()
        assertFalse(navigator.suggestionsOpen)
        assertEquals(0, mapBackCalls)
        navigator.back()
        assertEquals(1, mapBackCalls)
    }
}
