package app.seb3thehacker.gearslip.car

import app.seb3thehacker.gearslip.Protobuf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterNavTest {
    @Test fun `head unit minimum interval limits repeated navigation updates`() {
        val first = CountDownLatch(1)
        val next = CountDownLatch(1)
        val count = AtomicInteger()
        val nav = ClusterNav(6, 1000) { _, _ ->
            if (count.incrementAndGet() == 1) first.countDown() else next.countDown()
        }
        try {
            nav.onMessage(8, Protobuf.varintField(1, 0))
            assertTrue(first.await(2, TimeUnit.SECONDS))
            nav.onMessage(32769, byteArrayOf())
            assertFalse("the fixed 500ms timer must not override the head unit", next.await(700, TimeUnit.MILLISECONDS))
            assertTrue(next.await(2, TimeUnit.SECONDS))
        } finally {
            nav.stop()
        }
    }

    @Test fun `head unit stop pauses updates and start resends current status`() {
        val messages = mutableListOf<Int>()
        val nav = ClusterNav(6) { id, _ -> messages += id }
        // Drive polling synchronously so the test does not depend on a background timer.
        val running = ClusterNav::class.java.getDeclaredField("running").apply { isAccessible = true }
        val tick = ClusterNav::class.java.getDeclaredMethod("tick").apply { isAccessible = true }
        running.set(nav, true)
        try {
            tick.invoke(nav)
            assertEquals(listOf(32771), messages)
            nav.onMessage(32770, byteArrayOf())
            tick.invoke(nav)
            assertEquals(1, messages.size)
            nav.onMessage(32769, byteArrayOf())
            tick.invoke(nav)
            assertEquals(listOf(32771, 32771), messages)
            nav.stop()
            tick.invoke(nav)
            assertEquals(2, messages.size)
        } finally {
            nav.stop()
        }
    }

    @Test fun `missing channel open status does not start publishing`() {
        val nav = ClusterNav(6) { _, _ -> error("channel was not accepted") }
        nav.onMessage(8, byteArrayOf())
        val running = ClusterNav::class.java.getDeclaredField("running").apply { isAccessible = true }
        assertFalse(running.getBoolean(nav))
    }
}
