package app.seb3thehacker.gearslip

import org.junit.Assert.*
import org.junit.Test

class ProtocolVersionTest {
    @Test fun `negotiates older and newer supported minor versions`() {
        assertArrayEquals(byteArrayOf(0, 1, 0, 3, 0, 0), ProtocolVersion.respond(1, 3).bytes)
        assertArrayEquals(byteArrayOf(0, 1, 0, 7, 0, 0), ProtocolVersion.respond(1, 7).bytes)
        assertArrayEquals(byteArrayOf(0, 1, 0, 7, 0, 0), ProtocolVersion.respond(1, 9).bytes)
    }

    @Test fun `unsupported major is never reported as a successful downgrade`() {
        val response = ProtocolVersion.respond(4, 3)
        assertFalse(response.supported)
        assertArrayEquals(byteArrayOf(0, 1, 0, 3, -1, -1), response.bytes)
    }
}
