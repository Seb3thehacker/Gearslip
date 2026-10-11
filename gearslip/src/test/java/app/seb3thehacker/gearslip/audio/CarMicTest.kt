package app.seb3thehacker.gearslip.audio

import app.seb3thehacker.gearslip.Protobuf
import app.seb3thehacker.gearslip.ServiceDiscovery
import app.seb3thehacker.gearslip.protocol.MediaProtocol
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CarMicTest {
    private val messages = mutableListOf<Pair<Int, ByteArray>>()
    private val service = ServiceDiscovery.AudioService(7, 0, 1, listOf(ServiceDiscovery.AudioConfig(16_000, 16, 1)))
    private val mic = CarMic(service) { id, body -> messages += id to body }

    @After fun cleanup() = mic.close()

    @Test fun `microphone starts without sending sink Setup`() {
        mic.onMessage(8, byteArrayOf(8, 0))
        assertTrue(messages.isEmpty())
        assertTrue(mic.start())
        assertEquals(MediaProtocol.MICROPHONE_REQUEST, messages.single().first)
        assertArrayEquals(byteArrayOf(8, 1, 32, 1), messages.single().second)
    }

    @Test fun `only accepted recording data uses the returned session for ACK`() {
        mic.onMessage(8, byteArrayOf(8, 0))
        mic.start()
        val pcm = MediaProtocol.data(123, byteArrayOf(0x34, 0x12, 0, -128))
        mic.onMessage(MediaProtocol.DATA, pcm)
        assertEquals(1, messages.size)
        mic.onMessage(MediaProtocol.MICROPHONE_RESPONSE, byteArrayOf(8, 0, 16, 7))
        mic.onMessage(MediaProtocol.DATA, pcm)
        assertArrayEquals(shortArrayOf(0x1234, Short.MIN_VALUE), mic.read(1))
        assertEquals(MediaProtocol.ACK, messages.last().first)
        assertArrayEquals(byteArrayOf(8, 7, 16, 1), messages.last().second)
    }

    @Test fun `recording rejection and absent channel status do not supply audio`() {
        mic.onMessage(8, byteArrayOf())
        assertFalse(mic.start())
        mic.onMessage(8, byteArrayOf(8, 0))
        mic.start()
        mic.onMessage(MediaProtocol.MICROPHONE_RESPONSE, Protobuf.varintField(1, -1))
        mic.onMessage(MediaProtocol.DATA, MediaProtocol.data(0, byteArrayOf(1, 0)))
        assertNull(mic.read(1))
        assertEquals(1, messages.size)
    }

    @Test fun `late open response cannot revive stopped recording`() {
        mic.onMessage(8, byteArrayOf(8, 0))
        mic.start()
        mic.stop()
        assertFalse(mic.start())
        mic.onMessage(MediaProtocol.MICROPHONE_RESPONSE, byteArrayOf(8, 0, 16, 7))
        mic.onMessage(MediaProtocol.DATA, MediaProtocol.data(0, byteArrayOf(1, 0)))
        assertNull(mic.read(1))
        mic.onMessage(MediaProtocol.MICROPHONE_RESPONSE, byteArrayOf(8, 0, 16, 0))
        assertTrue(mic.start())
    }
}
