package app.seb3thehacker.gearslip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Parses the ServiceDiscoveryResponse the 2018 Uconnect actually sent, rebuilt field for field
 * from the decoded tree in `archive/spike/car-run-2026-09-16-run2.log`.
 *
 * The car offers two audio sinks - media on channel 5 and guidance on channel 6 - alongside the
 * video sink on channel 1 and a microphone source on channel 3. Picking the right one out of that
 * set is the whole job of [ServiceDiscovery.findAudioServices], and getting it wrong is silent:
 * audio simply never arrives.
 *
 * The 2022 Dacia Jogger (LGE ULC 4.5, protocol 1.5) offers four: AAC media on channel 3, AAC
 * guidance on 4, PCM media on 5 and PCM guidance on 6. Gearslip sends PCM, so it has to pick
 * channel 5 even though channel 3 comes first; PCM pushed into the AAC sink plays as silence.
 */
class ServiceDiscoveryAudioTest {

    private fun varint(value: Long): ByteArray = Protobuf.varint(value)

    private fun varintField(field: Int, value: Long) = Protobuf.varintField(field, value)

    private fun lenField(field: Int, body: ByteArray): ByteArray =
        varint(((field shl 3) or 2).toLong()) + varint(body.size.toLong()) + body

    /** AudioConfiguration { sampling_rate = 1; bits = 2; channels = 3 } */
    private fun audioConfig(rate: Int, bits: Int, channels: Int) =
        varintField(1, rate.toLong()) + varintField(2, bits.toLong()) + varintField(3, channels.toLong())

    /** Service { id = 1; media_sink_service = 3 } carrying an audio sink. */
    private fun audioSink(id: Int, codec: Int, audioType: Int, config: ByteArray) =
        lenField(
            1,
            varintField(1, id.toLong()) +
                lenField(3, varintField(1, codec.toLong()) + varintField(2, audioType.toLong()) + lenField(3, config)),
        )

    /** Channel 1: the H.264 video sink, which must not be mistaken for an audio one. */
    private fun videoSink(): ByteArray {
        val videoConfig = varintField(1, 2L) + varintField(2, 2L) + varintField(3, 0L) +
            varintField(4, 12L) + varintField(5, 240L) + varintField(6, 0L) + varintField(8, 10_000L)
        return lenField(
            1,
            varintField(1, 1L) + lenField(3, varintField(1, 3L) + lenField(4, videoConfig)),
        )
    }

    /** Channel 3: the car's microphone, a media *source* (field 5), not a sink. */
    private fun micSource(): ByteArray = lenField(
        1,
        varintField(1, 3L) + lenField(5, varintField(1, 1L) + lenField(2, audioConfig(16_000, 16, 1))),
    )

    private fun uconnectResponse(): ByteArray =
        videoSink() +
            micSource() +
            audioSink(id = 5, codec = 1, audioType = 3, config = audioConfig(48_000, 16, 2)) +
            audioSink(id = 6, codec = 1, audioType = 1, config = audioConfig(16_000, 16, 1)) +
            Protobuf.stringField(2, "Uconnect") +
            Protobuf.stringField(3, "Dodge Challenger") +
            Protobuf.stringField(4, "18")

    @Test
    fun `finds both of the car's audio sinks and neither the video sink nor the microphone`() {
        val sinks = ServiceDiscovery.findAudioServices(uconnectResponse())

        assertEquals(listOf(5, 6), sinks.map { it.serviceId })
        assertEquals(listOf("MEDIA", "GUIDANCE"), sinks.map { it.streamName })
    }

    @Test
    fun `the media sink is PCM at 48kHz stereo`() {
        val media = ServiceDiscovery.findAudioServices(uconnectResponse()).first { it.streamType == 3 }

        assertEquals("AUDIO_PCM", media.codecName)
        assertEquals(1, media.configs.size)
        assertEquals(48_000, media.configs[0].sampleRate)
        assertEquals(16, media.configs[0].bits)
        assertEquals(2, media.configs[0].channels)
    }

    @Test
    fun `the guidance sink is mono at 16kHz, so it must not be used for music`() {
        val guidance = ServiceDiscovery.findAudioServices(uconnectResponse()).first { it.streamType == 1 }

        assertEquals(16_000, guidance.configs[0].sampleRate)
        assertEquals(1, guidance.configs[0].channels)
    }

    @Test
    fun `the video sink is still found alongside the audio ones`() {
        assertNotNull(ServiceDiscovery.findVideoService(uconnectResponse()))
        assertEquals(1, ServiceDiscovery.findVideoService(uconnectResponse())!!.serviceId)
    }

    private fun daciaResponse(): ByteArray =
        audioSink(id = 3, codec = 4, audioType = 3, config = audioConfig(48_000, 16, 2)) +
            audioSink(id = 4, codec = 4, audioType = 1, config = audioConfig(16_000, 16, 1)) +
            audioSink(id = 5, codec = 1, audioType = 3, config = audioConfig(48_000, 16, 2)) +
            audioSink(id = 6, codec = 1, audioType = 1, config = audioConfig(16_000, 16, 1))

    @Test
    fun `the Uconnect's only media sink is picked`() {
        assertEquals(5, ServiceDiscovery.pickMediaSink(ServiceDiscovery.findAudioServices(uconnectResponse()))!!.serviceId)
    }

    @Test
    fun `a PCM media sink wins over an earlier AAC one`() {
        val sink = ServiceDiscovery.pickMediaSink(ServiceDiscovery.findAudioServices(daciaResponse()))!!
        assertEquals(5, sink.serviceId)
        assertEquals("AUDIO_PCM", sink.codecName)
    }

    @Test
    fun `with only an AAC media sink, PCM stays on the phone`() {
        val aacOnly = audioSink(id = 3, codec = 4, audioType = 3, config = audioConfig(48_000, 16, 2))
        assertNull(ServiceDiscovery.pickMediaSink(ServiceDiscovery.findAudioServices(aacOnly)))
    }

    @Test
    fun `no media sink means none is picked`() {
        val guidanceOnly = audioSink(id = 6, codec = 1, audioType = 1, config = audioConfig(16_000, 16, 1))
        assertNull(ServiceDiscovery.pickMediaSink(ServiceDiscovery.findAudioServices(guidanceOnly)))
    }
    @Test fun `missing PCM configuration fields are not invented`() {
        val response = audioSink(5, 1, 3, byteArrayOf())
        assertNull(ServiceDiscovery.pickMediaSink(ServiceDiscovery.findAudioServices(response)))
    }

    @Test fun `omitted audio codec defaults to PCM and stream type to guidance`() {
        val sink = lenField(3, audioConfig(48_000, 16, 2))
        val response = lenField(1, varintField(1, 5) + lenField(3, sink))
        val audio = ServiceDiscovery.findAudioServices(response).single()
        assertEquals(1, audio.codecType)
        assertEquals(1, audio.streamType)
        val mediaResponse = lenField(1, varintField(1, 5) + lenField(3, sink + varintField(2, 3)))
        assertNotNull(ServiceDiscovery.pickMediaSink(ServiceDiscovery.findAudioServices(mediaResponse)))
    }

    @Test fun `omitted video codec uses the DHU H264 default`() {
        val config = varintField(1, 1) + varintField(2, 2) + varintField(5, 160)
        val response = lenField(1, varintField(1, 2) + lenField(3, lenField(4, config)))
        assertEquals(3, ServiceDiscovery.findVideoService(response)!!.configs.single().codecType)
    }

}
