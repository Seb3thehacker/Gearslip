package app.seb3thehacker.gearslip

import org.junit.Assert.*
import org.junit.Test

class ProtobufTest {
    @Test fun `malformed suffix invalidates the whole message`() {
        for (suffix in listOf(byteArrayOf(0), byteArrayOf(18, -1), byteArrayOf(13, 1), byteArrayOf(17, 1))) {
            assertTrue(Wire.fields(byteArrayOf(8, 2) + suffix).isEmpty())
            assertNull(Protobuf.readInt32Field(byteArrayOf(8, 2) + suffix, 1))
        }
    }

    @Test fun `lengths cannot wrap when narrowed to int`() {
        val huge = byteArrayOf(18) + Protobuf.varint(0x1_0000_0001) + byteArrayOf(42)
        assertTrue(Wire.fields(huge).isEmpty())
    }

    @Test fun `last singular field wins and negative int32 keeps its sign`() {
        assertEquals(2, Protobuf.readInt32Field(byteArrayOf(8, 1, 8, 2), 1))
        assertEquals(-3, Protobuf.readInt32Field(Protobuf.varintField(1, -3), 1))
        assertEquals(8_803_621_255L, Protobuf.readInt64Field(Protobuf.varintField(1, 8_803_621_255L), 1))
    }
}
