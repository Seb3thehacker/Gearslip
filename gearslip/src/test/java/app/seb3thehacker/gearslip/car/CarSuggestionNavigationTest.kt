package app.seb3thehacker.gearslip.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarSuggestionNavigationTest {
    @Test fun `back compacts all destinations before reaching the navigation app`() {
        val navigator = CarNavigator("")
        var mapBackCalls = 0
        navigator.backInterceptor = { mapBackCalls++; true }
        navigator.toggleAllSuggestions()
        assertTrue(navigator.suggestionsExpanded)

        navigator.back()
        assertFalse(navigator.suggestionsExpanded)
        assertFalse(navigator.suggestionsCollapsed)
        assertEquals(0, mapBackCalls)
        navigator.back()
        assertEquals(1, mapBackCalls)
    }

    @Test fun `leaving home preserves a collapsed card until the suggestions clear`() {
        val navigator = CarNavigator("")
        navigator.collapseSuggestions()
        navigator.apps()
        navigator.home()
        assertTrue(navigator.suggestionsCollapsed)

        navigator.resetSuggestions()
        assertFalse(navigator.suggestionsCollapsed)
        assertFalse(navigator.suggestionsExpanded)
    }

    @Test fun `reopening the card restores the compact list`() {
        val navigator = CarNavigator("")
        navigator.toggleAllSuggestions()
        navigator.toggleSuggestionCard()
        assertTrue(navigator.suggestionsCollapsed)
        assertFalse(navigator.suggestionsExpanded)
        navigator.toggleSuggestionCard()
        assertFalse(navigator.suggestionsCollapsed)
        assertFalse(navigator.suggestionsExpanded)
    }
}
