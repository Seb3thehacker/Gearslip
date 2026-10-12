package app.seb3thehacker.gearslip.car

import androidx.car.app.CarToast
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class CarToastsTest {
    @Before
    @After
    fun clearToast() {
        while (true) CarToasts.message.value?.let(CarToasts::dismiss) ?: break
    }

    @Test
    fun aNewToastWaitsForTheOneOnScreen() {
        val owner = Any()
        CarToasts.show(owner, "First", CarToast.LENGTH_SHORT)
        val first = requireNotNull(CarToasts.message.value)
        CarToasts.show(owner, "Second", CarToast.LENGTH_LONG)

        assertSame(first, CarToasts.message.value)
        assertEquals(1, CarToasts.waiting.value)
        CarToasts.dismiss(first)
        assertEquals("Second", CarToasts.message.value?.text)
        assertEquals(0, CarToasts.waiting.value)
    }

    @Test
    fun expiredTimerCannotDismissItsReplacement() {
        val owner = Any()
        CarToasts.show(owner, "First", CarToast.LENGTH_SHORT)
        val first = requireNotNull(CarToasts.message.value)
        CarToasts.show(owner, "Second", CarToast.LENGTH_LONG)
        CarToasts.dismiss(first)
        val second = requireNotNull(CarToasts.message.value)

        CarToasts.dismiss(first)
        assertSame(second, CarToasts.message.value)
        CarToasts.dismiss(second)
        assertNull(CarToasts.message.value)
    }

    @Test
    fun identicalMessagesStillRestartTheDisplayTimer() {
        val owner = Any()
        CarToasts.show(owner, "Saved", CarToast.LENGTH_SHORT)
        val first = requireNotNull(CarToasts.message.value)
        CarToasts.show(owner, "Saved", CarToast.LENGTH_SHORT)
        val repeated = requireNotNull(CarToasts.message.value)

        assertNotSame(first, repeated)
        assertEquals(0, CarToasts.waiting.value)
        CarToasts.dismiss(first)
        assertSame(repeated, CarToasts.message.value)
    }

    @Test
    fun disconnectOnlyClearsItsOwnToast() {
        val nav = Any()
        val browse = Any()
        CarToasts.show(nav, "Route saved", CarToast.LENGTH_SHORT)
        CarToasts.show(browse, "Added to playlist", CarToast.LENGTH_SHORT)

        CarToasts.clear(nav)
        assertEquals("Added to playlist", CarToasts.message.value?.text)
        CarToasts.clear(browse)
        assertNull(CarToasts.message.value)
    }

    @Test
    fun aBurstKeepsOnlyTheNewestFewWaiting() {
        val owner = Any()
        listOf("1", "2", "3", "4", "5", "6").forEach { CarToasts.show(owner, it, CarToast.LENGTH_SHORT) }

        assertEquals("1", CarToasts.message.value?.text)
        assertEquals(3, CarToasts.waiting.value)
        CarToasts.message.value?.let(CarToasts::dismiss)
        assertEquals("4", CarToasts.message.value?.text)
    }

    @Test
    fun blankRequestsDoNotReplaceAVisibleMessage() {
        val owner = Any()
        CarToasts.show(owner, "Saved", CarToast.LENGTH_SHORT)
        val visible = CarToasts.message.value

        CarToasts.show(owner, " \n\t", CarToast.LENGTH_LONG)
        assertSame(visible, CarToasts.message.value)
        assertEquals(0, CarToasts.waiting.value)
    }

    @Test
    fun unknownDurationsFallBackToShortRatherThanStayingVisible() {
        val owner = Any()
        CarToasts.show(owner, "Short", CarToast.LENGTH_SHORT)
        assertEquals(4_000L, CarToasts.message.value?.durationMs)
        CarToasts.message.value?.let(CarToasts::dismiss)
        CarToasts.show(owner, "Long", CarToast.LENGTH_LONG)
        assertEquals(7_000L, CarToasts.message.value?.durationMs)
        CarToasts.message.value?.let(CarToasts::dismiss)
        CarToasts.show(owner, "Unknown", Int.MAX_VALUE)
        assertEquals(4_000L, CarToasts.message.value?.durationMs)
    }
}
