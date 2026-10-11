package app.seb3thehacker.gearslip

import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class FramingTest {
    @Test fun `fragmentation survives every byte arriving separately`() {
        val message = ByteArray(40_000) { it.toByte() }
        val output = ByteArrayOutputStream()
        assertEquals(3, Frames.writeMessage(output, 5, false, Frames.MESSAGE_SPECIFIC, message) { error("plaintext") })
        val parser = Frames.Parser()
        val assembler = Frames.Assembler()
        var received: ByteArray? = null
        for (byte in output.toByteArray()) {
            parser.append(byteArrayOf(byte), 1)
            parser.next()?.let { received = assembler.offer(it, it.payload) }
        }
        assertArrayEquals(message, received)
    }

    @Test fun `encrypted fragments count plaintext length and encrypt each chunk`() {
        val output = ByteArrayOutputStream()
        val message = ByteArray(20_000) { 42 }
        Frames.writeMessage(output, 1, true, Frames.MESSAGE_SPECIFIC, message) { byteArrayOf(99) + it }
        val parser = Frames.Parser()
        parser.append(output.toByteArray(), output.size())
        val first = parser.next()!!
        val last = parser.next()!!
        assertEquals(20_000, first.totalSize)
        assertEquals(16_385, first.payload.size)
        assertEquals(Frames.TYPE_LAST, last.frameType)
        val assembler = Frames.Assembler()
        assertNull(assembler.offer(first, first.payload.drop(1).toByteArray()))
        assertArrayEquals(message, assembler.offer(last, last.payload.drop(1).toByteArray()))
    }

    @Test fun `malformed and mismatched fragment runs are rejected`() {
        val first = Frames.Frame(1, Frames.TYPE_FIRST, true, 0, byteArrayOf(), 4)
        val last = Frames.Frame(1, Frames.TYPE_LAST, true, 0, byteArrayOf())
        assertThrows(IOException::class.java) { Frames.Assembler().offer(last, byteArrayOf(1)) }
        val assembler = Frames.Assembler()
        assembler.offer(first, byteArrayOf(1, 2))
        assertThrows(IOException::class.java) { assembler.offer(last, byteArrayOf(3)) }
        assembler.offer(first, byteArrayOf(1, 2))
        assertThrows(IOException::class.java) { assembler.offer(last, byteArrayOf(3, 4, 5)) }
        assembler.offer(first, byteArrayOf(1, 2))
        assertThrows(IOException::class.java) { assembler.offer(first, byteArrayOf(1, 2)) }
    }

    @Test fun `oversized declared message is rejected before allocation`() {
        val parser = Frames.Parser()
        val frame = byteArrayOf(1, 1, 0, 1, 0x7f, -1, -1, -1, 0)
        parser.append(frame, frame.size)
        assertThrows(IOException::class.java) { parser.next() }
    }
}
