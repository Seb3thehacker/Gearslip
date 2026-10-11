package app.seb3thehacker.gearslip.protocol

import app.seb3thehacker.gearslip.Protobuf
import org.junit.Assert.*
import org.junit.Test

class MediaProtocolTest {
    @Test fun `PCM setup matches the message accepted by DHU`() {
        assertArrayEquals(byteArrayOf(8, 1), MediaProtocol.setup(1))
    }

    @Test fun `decodes the actual DHU audio config`() {
        // Captured from DHU 2.0 after Setup(codec=PCM): READY, 4096 credits, indices 0 and 1.
        val response = byteArrayOf(8, 2, 16, 0x80.toByte(), 0x20, 24, 0, 24, 1)
        assertEquals(MediaProtocol.Config(2, 4096, listOf(0, 1)), MediaProtocol.config(response))
    }

    @Test fun `accepts packed configuration indices too`() {
        val response = byteArrayOf(8, 2, 16, 4, 26, 2, 0, 1)
        assertEquals(listOf(0, 1), MediaProtocol.config(response)!!.indices)
    }

    @Test fun `missing or invalid required config fields do not authorize streaming`() {
        for (body in listOf(byteArrayOf(), byteArrayOf(8, 2), byteArrayOf(8, 2, 16, 0), byteArrayOf(8, 0, 16, 1))) {
            assertNull(MediaProtocol.config(body))
        }
    }

    @Test fun `ack requires session and a bounded positive packet count`() {
        assertNull(MediaProtocol.ack(byteArrayOf(16, 1)))
        assertNull(MediaProtocol.ack(byteArrayOf(8, 1)))
        assertNull(MediaProtocol.ack(Protobuf.varintField(1, 1) + Protobuf.varintField(2, -1)))
        assertEquals(MediaProtocol.Ack(7, 3), MediaProtocol.ack(byteArrayOf(8, 7, 16, 3)))
        assertNull(MediaProtocol.ack(byteArrayOf(8, 1, 16, 0)))
        assertEquals(MediaProtocol.Ack(1, 4096), MediaProtocol.ack(
            Protobuf.varintField(1, 1) + Protobuf.varintField(2, 4096)))
        assertNull(MediaProtocol.ack(Protobuf.varintField(1, 1) + Protobuf.varintField(2, 4097)))
    }

    @Test fun `timestamp acknowledgements take precedence over missing or inconsistent count`() {
        val timestamps = Protobuf.varintField(3, 0x1_0000_0000L) + Protobuf.varintField(3, Long.MAX_VALUE)
        for (count in listOf(byteArrayOf(), Protobuf.varintField(2, 0), Protobuf.varintField(2, 100))) {
            val ack = Protobuf.varintField(1, 7) + count + timestamps
            assertEquals(MediaProtocol.Ack(7, 2), MediaProtocol.ack(ack))
        }
    }

    @Test fun `packed and mixed timestamp acknowledgements preserve each 64 bit value`() {
        val packed = Protobuf.bytesField(3, Protobuf.varint(Long.MAX_VALUE) + Protobuf.varint(-1))
        val body = Protobuf.varintField(1, 7) + packed + Protobuf.varintField(3, 0)
        assertEquals(MediaProtocol.Ack(7, 3), MediaProtocol.ack(body))
    }

    @Test fun `malformed packed timestamps cannot release credit through the count fallback`() {
        val body = Protobuf.varintField(1, 7) + Protobuf.varintField(2, 1) +
            Protobuf.bytesField(3, byteArrayOf(0x80.toByte()))
        assertNull(MediaProtocol.ack(body))
    }

    @Test fun `media timestamp uses all eight bytes in network order`() {
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 42),
            MediaProtocol.data(0x0102030405060708, byteArrayOf(42, 43), 1))
    }
}
