package app.seb3thehacker.gearslip.protocol

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class MediaWindowTest {
    @Test fun `immediate ack releases credit reserved before send`() {
        val window = MediaWindow()
        window.start(1, 1)
        repeat(20) {
            assertTrue(window.reserve(1))
            assertTrue(window.acknowledge(MediaProtocol.Ack(1, 1)))
        }
        assertEquals(0, window.inFlight)
    }

    @Test fun `stale zero and oversized acknowledgements cannot release credit`() {
        val window = MediaWindow()
        window.start(2, 1)
        window.reserve(2)
        for (ack in listOf(MediaProtocol.Ack(1, 1), MediaProtocol.Ack(2, 0), MediaProtocol.Ack(2, 2))) {
            assertFalse(window.acknowledge(ack))
            assertEquals(1, window.inFlight)
        }
    }

    @Test fun `timeout stops stream instead of inventing credit`() {
        val window = MediaWindow(10)
        window.start(1, 1)
        window.reserve(1)
        assertThrows(IOException::class.java) { window.reserve(1) }
        assertFalse(window.reserve(1))
    }

    @Test fun `stop wakes a sender blocked at the advertised limit`() {
        val window = MediaWindow()
        window.start(1, 1)
        window.reserve(1)
        val done = CountDownLatch(1)
        var reserved = true
        val sender = Thread { reserved = window.reserve(1); done.countDown() }.apply { start() }
        window.stop()
        assertTrue(done.await(1, TimeUnit.SECONDS))
        sender.join()
        assertFalse(reserved)
    }

    @Test fun `sender from a previous session cannot reserve new session credit`() {
        val window = MediaWindow()
        window.start(1, 1)
        window.start(2, 1)
        assertFalse(window.reserve(1))
        assertEquals(0, window.inFlight)
    }
}
