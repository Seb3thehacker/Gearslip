package app.seb3thehacker.gearslip.audio

import app.seb3thehacker.gearslip.Protobuf
import app.seb3thehacker.gearslip.ServiceDiscovery
import app.seb3thehacker.gearslip.protocol.MediaProtocol
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AudioLinkTest {
    private val messages = mutableListOf<Pair<Int, ByteArray>>()
    private val controls = mutableListOf<Pair<Int, ByteArray>>()
    private val service = ServiceDiscovery.AudioService(5, 3, 1, listOf(ServiceDiscovery.AudioConfig(48_000, 16, 2)))
    private val link = AudioLink(service, { id, body -> messages += id to body }, { id, body -> controls += id to body })
    private val ready = byteArrayOf(8, 2, 16, 1, 24, 0)

    @After fun cleanup() = link.close()

    @Test fun `open sends codec not configuration index`() {
        link.onMessage(8, byteArrayOf(8, 0))
        assertEquals(MediaProtocol.SETUP, messages.single().first)
        assertArrayEquals(byteArrayOf(8, 1), messages.single().second)
    }

    @Test fun `rejection never becomes an implicit setup success`() {
        link.onMessage(8, byteArrayOf(8, 0))
        link.onMessage(255, byteArrayOf())
        link.onMessage(MediaProtocol.CONFIG, ready)
        link.start()
        link.onFocus(1)
        link.write(byteArrayOf(0, 0, 0, 0), 4)
        assertEquals(listOf(MediaProtocol.SETUP), messages.map { it.first })
        assertTrue(controls.isEmpty())
    }

    @Test fun `WAIT missing fields and unknown config cannot start audio`() {
        for (config in listOf(byteArrayOf(8, 1, 16, 1, 24, 0), byteArrayOf(8, 2), byteArrayOf(8, 2, 16, 1, 24, 4))) {
            link.onMessage(MediaProtocol.CONFIG, config)
            link.start()
            link.onFocus(1)
        }
        assertTrue(messages.isEmpty())
        assertTrue(controls.isEmpty())
    }

    @Test fun `focus grant precedes Start and stopping before grant releases focus`() {
        link.onMessage(MediaProtocol.CONFIG, ready)
        link.start()
        assertEquals(18, controls.single().first)
        assertTrue(messages.isEmpty())
        link.stop()
        assertArrayEquals(byteArrayOf(8, 4), controls.last().second)
        link.onFocus(1)
        assertTrue(messages.isEmpty())
    }

    @Test fun `focus loss stops stream and regain uses a new session`() {
        link.onMessage(MediaProtocol.CONFIG, ready)
        link.start()
        link.onFocus(1)
        val firstSession = Protobuf.readInt32Field(messages.last().second, 1)!!
        link.onFocus(4)
        assertEquals(MediaProtocol.STOP, messages.last().first)
        assertTrue(messages.last().second.isEmpty())
        link.onFocus(1)
        assertEquals(MediaProtocol.START, messages.last().first)
        assertNotEquals(firstSession, Protobuf.readInt32Field(messages.last().second, 1))
    }

    @Test fun `valid ack permits another data packet with a one-packet window`() {
        link.onMessage(MediaProtocol.CONFIG, ready)
        link.start()
        link.onFocus(1)
        val session = Protobuf.readInt32Field(messages.last().second, 1)!!
        repeat(3) {
            link.write(byteArrayOf(1, 2, 3, 4), 4)
            link.onMessage(MediaProtocol.ACK, MediaProtocol.ack(session))
        }
        assertEquals(3, messages.count { it.first == MediaProtocol.DATA })
    }

    @Test fun `media-only focus starts media while guidance-only focus stops it`() {
        link.onMessage(MediaProtocol.CONFIG, ready)
        link.start()
        link.onFocus(6)
        assertEquals(MediaProtocol.START, messages.single().first)
        link.write(byteArrayOf(1, 2, 3, 4), 4)
        assertEquals(MediaProtocol.DATA, messages.last().first)
        link.onFocus(7)
        assertEquals(MediaProtocol.STOP, messages.last().first)
        val stoppedCount = messages.size
        link.write(byteArrayOf(1, 2, 3, 4), 4)
        assertEquals(stoppedCount, messages.size)
        link.onFocus(6)
        assertEquals(MediaProtocol.START, messages.last().first)
    }
}
