package app.seb3thehacker.gearslip.car

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CarSuggestionsTest {
    private val owner = Any()

    @Before fun begin() = CarSuggestions.begin(owner)
    @After fun clear() {
        CarSuggestions.clear(owner)
        CarToasts.clear(owner)
    }

    private fun shortcut(id: String, title: String = id, action: () -> Unit = {}) =
        CarSuggestions.Shortcut(id, title, action = action)

    @Test fun `updates replace the previous list in app order`() {
        CarSuggestions.replace(owner, listOf(shortcut("old")))
        CarSuggestions.replace(owner, listOf(shortcut("work"), shortcut("home")))
        assertEquals(listOf("work", "home"), CarSuggestions.shortcuts.value.map { it.id })
    }

    @Test fun `empty update removes every shortcut`() {
        CarSuggestions.replace(owner, listOf(shortcut("home")))
        CarSuggestions.replace(owner, emptyList())
        assertTrue(CarSuggestions.shortcuts.value.isEmpty())
    }

    @Test fun `invalid and duplicate items do not consume the six available slots`() {
        val first = shortcut("home")
        CarSuggestions.replace(owner, listOf(shortcut(" "), shortcut("bad", " "), first, shortcut("home")) +
            (1..10).map { shortcut("place$it") })
        assertEquals(listOf("home", "place1", "place2", "place3", "place4", "place5"),
            CarSuggestions.shortcuts.value.map { it.id })
        assertTrue(CarSuggestions.shortcuts.value.first() === first)
    }

    @Test fun `switching apps clears suggestions and rejects the old owner`() {
        val previous = Any()
        CarSuggestions.begin(previous)
        CarSuggestions.replace(previous, listOf(shortcut("old")))
        CarSuggestions.begin(owner)
        CarSuggestions.replace(previous, listOf(shortcut("late")))
        assertTrue(CarSuggestions.shortcuts.value.isEmpty())
        CarSuggestions.replace(owner, listOf(shortcut("new")))
        CarSuggestions.clear(previous)
        assertEquals("new", CarSuggestions.shortcuts.value.single().id)
    }

    @Test fun `disconnect rejects late updates and taps`() {
        var invoked = false
        val item = shortcut("home") { invoked = true }
        CarSuggestions.replace(owner, listOf(item))
        CarSuggestions.clear(owner)
        CarSuggestions.replace(owner, listOf(item))
        assertFalse(CarSuggestions.launch(item))
        assertFalse(invoked)
        assertTrue(CarSuggestions.shortcuts.value.isEmpty())
    }

    @Test fun `reusing an identifier does not let a stale tap run its old action`() {
        var oldInvoked = false
        var newInvoked = false
        val old = shortcut("home") { oldInvoked = true }
        val replacement = shortcut("home") { newInvoked = true }
        CarSuggestions.replace(owner, listOf(old))
        CarSuggestions.replace(owner, listOf(replacement))
        assertFalse(CarSuggestions.launch(old))
        assertTrue(CarSuggestions.launch(replacement))
        assertFalse(oldInvoked)
        assertTrue(newInvoked)
    }

    @Test fun `launch executes the selected action only`() {
        var calls = 0
        val selected = shortcut("home") { calls++ }
        CarSuggestions.replace(owner, listOf(selected, shortcut("work") { calls += 10 }))
        assertTrue(CarSuggestions.launch(selected))
        assertEquals(1, calls)
    }

    @Test fun `failed actions are removed and report a toast without breaking other shortcuts`() {
        val failed = shortcut("gone") { throw IllegalStateException() }
        val working = shortcut("home")
        CarSuggestions.replace(owner, listOf(failed, working))
        assertFalse(CarSuggestions.launch(failed))
        assertEquals(listOf(working), CarSuggestions.shortcuts.value)
        assertEquals("Could not open this suggestion.", CarToasts.message.value?.text)
        assertTrue(CarSuggestions.launch(working))
    }
}
