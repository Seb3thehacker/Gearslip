package app.seb3thehacker.gearslip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LIVI 2.0.1 offers a second screen alongside the main one, and lists that screen's input first:
 *   channel 3:  video sink, no display id (the main screen)
 *   channel 19: video sink, display_id = 1, display_type = 1 (a cluster)
 *   channel 20: input service with only display_id = 1
 *   channel 8:  input service with a 1280x720 touchscreen and keys (the main screen)
 * Taking the first input service left every touch on the main screen ignored.
 */
class ServiceDiscoveryDisplayTest {

    @Test fun `omitted video resolution and frame rate use protocol defaults`() {
        val response = lenField(1, varintField(1, 1) + lenField(3, lenField(4, byteArrayOf())))
        val config = ServiceDiscovery.findVideoService(response)!!.configs.single()
        assertEquals(1, config.resolution)
        assertEquals(1, config.frameRate)
        assertEquals(800 to 480, config.pixelSize())
    }

    @Test fun `video uses per-config codec and preserves configuration indices`() {
        val h265 = varintField(5, 160) + varintField(10, 7)
        val h264 = varintField(5, 160) + varintField(10, 3)
        val legacy = varintField(5, 160)
        val sink = varintField(1, 1) + lenField(4, h265) + lenField(4, h264) + lenField(4, legacy)
        val response = lenField(1, varintField(1, 3) + lenField(3, sink))
        val video = ServiceDiscovery.findVideoService(response)!!
        // A default PCM value at the old service level must not reject the video channel.
        assertEquals(listOf(7, 3, 3), video.configs.map { it.codecType })
        assertEquals(3, video.configs.size)
        assertEquals(listOf(1, 2), video.configs.indices.filter { video.configs[it].isSupportedH264() })
    }

    @Test fun `invalid video geometry and density cannot be selected`() {
        val valid = ServiceDiscovery.VideoConfig(1, 1, 160)
        assertTrue(valid.isSupportedH264())
        val invalid = listOf(
            ServiceDiscovery.VideoConfig(99, 1, 160),
            ServiceDiscovery.VideoConfig(1, 0, 160),
            ServiceDiscovery.VideoConfig(1, 3, 160),
            ServiceDiscovery.VideoConfig(1, 1, 0),
            ServiceDiscovery.VideoConfig(1, 1, -1),
            ServiceDiscovery.VideoConfig(1, 1, 160, widthMargin = -1),
            ServiceDiscovery.VideoConfig(1, 1, 160, widthMargin = 800),
            ServiceDiscovery.VideoConfig(1, 1, 160, heightMargin = -1),
            ServiceDiscovery.VideoConfig(1, 1, 160, heightMargin = 480),
        )
        invalid.forEach { assertFalse(it.toString(), it.isSupportedH264()) }
    }

    @Test fun `odd video margins match Android Auto crop edges`() {
        val config = ServiceDiscovery.VideoConfig(1, 1, 160, widthMargin = 3, heightMargin = 5)
        assertEquals(Insets(left = 1, right = 2, top = 3, bottom = 2), config.marginInsets())
        assertEquals(797 to 475, config.visibleSize())
    }

    private fun varintField(field: Int, value: Long) = Protobuf.varintField(field, value)

    private fun lenField(field: Int, body: ByteArray): ByteArray =
        Protobuf.varint(((field shl 3) or 2).toLong()) + Protobuf.varint(body.size.toLong()) + body

    private fun videoSink(id: Int, displayId: Int?): ByteArray {
        val config = varintField(1, 2L) + varintField(2, 1L) + varintField(5, 180L)
        var sink = varintField(1, 3L) + lenField(4, config)
        if (displayId != null) sink += varintField(6, displayId.toLong()) + varintField(7, 1L)
        return lenField(1, varintField(1, id.toLong()) + lenField(3, sink))
    }

    private fun input(id: Int, body: ByteArray) = lenField(1, varintField(1, id.toLong()) + lenField(4, body))

    private fun liviResponse(): ByteArray =
        videoSink(3, null) +
            videoSink(19, 1) +
            input(20, varintField(5, 1L)) +
            input(8, lenField(1, Protobuf.varint(3L) + Protobuf.varint(4L)) + lenField(2, varintField(1, 1280L) + varintField(2, 720L)))

    @Test
    fun picksTheMainScreensVideo() {
        val video = ServiceDiscovery.findVideoService(liviResponse())!!
        assertEquals(3, video.serviceId)
        assertEquals(0, video.displayId)
    }

    @Test
    fun picksTheInputForTheProjectedScreen() {
        val input = ServiceDiscovery.findInputService(liviResponse(), displayId = 0)!!
        assertEquals(8, input.serviceId)
        assertEquals(1280, input.touchWidth)
        assertEquals(720, input.touchHeight)
        assertEquals(listOf(3, 4), input.keycodesSupported)
    }
}
