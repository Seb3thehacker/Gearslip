package app.seb3thehacker.gearslip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceDiscoveryChannelTest {
    private fun service(id: Long, field: Int, payload: ByteArray = byteArrayOf()) =
        Protobuf.bytesField(1, Protobuf.varintField(1, id) + Protobuf.bytesField(field, payload))

    @Test fun `invalid channel IDs cannot alias the control or another service channel`() {
        val config = Protobuf.varintField(1, 16000) + Protobuf.varintField(2, 16) + Protobuf.varintField(3, 1)
        val audio = Protobuf.bytesField(3, config)
        val microphone = Protobuf.bytesField(2, config)
        val sensor = Protobuf.bytesField(1, Protobuf.varintField(1, 1))
        for (id in listOf(-1L, 0L, 256L, 0x1_0000_0001L)) {
            assertNull(ServiceDiscovery.findMediaStatusChannel(service(id, 9)))
            assertNull(ServiceDiscovery.findNavStatusService(service(id, 8, Protobuf.varintField(1, 1000))))
            assertNull(ServiceDiscovery.findVideoService(service(id, 3, Protobuf.bytesField(4, byteArrayOf()))))
            assertNull(ServiceDiscovery.findInputService(service(id, 4)))
            assertNull(ServiceDiscovery.findMicService(service(id, 5, microphone)))
            assertNull(ServiceDiscovery.findSensorService(service(id, 2, sensor)))
            assertTrue(ServiceDiscovery.findAudioServices(service(id, 3, audio)).isEmpty())
        }
        assertEquals(255, ServiceDiscovery.findMediaStatusChannel(service(255, 9)))
    }

    @Test fun `navigation discovery preserves the requested update interval`() {
        val navigation = requireNotNull(ServiceDiscovery.findNavStatusService(service(6, 8, Protobuf.varintField(1, 1500))))
        assertEquals(6, navigation.serviceId)
        assertEquals(1500, navigation.minimumIntervalMs)
        assertNull(ServiceDiscovery.findNavStatusService(service(6, 8)))
        assertNull(ServiceDiscovery.findNavStatusService(service(6, 8, Protobuf.varintField(1, -1))))
    }
}
