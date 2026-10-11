package app.seb3thehacker.gearslip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the two riskiest pieces - the hand-written X.509 encoder and the SSLEngine wiring -
 * on the local JVM, so a defect surfaces here rather than as a mystery failure in a car.
 *
 * The JVM uses SunJSSE where the phone uses Conscrypt, so this validates the DER and the
 * engine logic, not the exact provider behaviour. The on-device "Self-test" button remains
 * the authoritative bench check.
 */
class CryptoTest {

    @Test
    fun `generates a parseable self-signed certificate`() {
        val identity = SelfSignedCert.generate(commonName = "TestCN", organisation = "TestO")
        val cert = identity.certificate

        // X500Principal.getName() is RFC 2253, which lists RDNs in reverse order.
        assertEquals("O=TestO,CN=TestCN", cert.subjectX500Principal.name)
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        assertEquals(3, cert.version)
        assertEquals("SHA256withRSA", cert.sigAlgName)
        assertTrue("serial must be positive", cert.serialNumber.signum() > 0)

        cert.checkValidity()
        cert.verify(cert.publicKey)

        val key = identity.keyStore.getKey(SelfSignedCert.ALIAS, SelfSignedCert.PASSWORD)
        assertEquals("RSA", key.algorithm)
    }

    @Test
    fun `completes a TLS handshake as the server and round-trips data`() {
        val ss = SelfSignedCert.generate()
        val identity = CertProvider.Identity(
            ss.keyStore, ss.certificate, SelfSignedCert.PASSWORD, "test",
        )
        assertTrue(TlsSelfTest.run(identity))
    }

    @Test
    fun `TLS rejects a head unit that does not present a client certificate`() {
        val generated = SelfSignedCert.generate()
        val identity = CertProvider.Identity(generated.keyStore, generated.certificate,
            SelfSignedCert.PASSWORD, "test")
        assertFalse(TlsSelfTest.run(identity, withClientCertificate = false))
    }

    @Test
    fun `frames round-trip through the parser`() {
        val payload = ByteArray(300) { it.toByte() }
        val parser = Frames.Parser()
        val frame = Frames.build(Frames.CHANNEL_CONTROL, encrypted = false, payload = payload)

        // Split mid-frame: a bulk transfer boundary must not break parsing.
        parser.append(frame, 7)
        assertEquals(null, parser.next())
        parser.append(frame.copyOfRange(7, frame.size), frame.size - 7)

        val parsed = parser.next()!!
        assertEquals(Frames.CHANNEL_CONTROL, parsed.channel)
        assertEquals(Frames.TYPE_BULK, parsed.frameType)
        assertEquals(false, parsed.encrypted)
        assertTrue(payload.contentEquals(parsed.payload))
    }

    @Test
    fun `reads negative AuthResponse status values`() {
        // STATUS_CERTIFICATE_ERROR = -2 encodes as a 10-byte varint.
        val encoded = Protobuf.varintField(1, -2L)
        assertEquals(11, encoded.size)
        assertEquals(-2, Protobuf.readInt32Field(encoded, 1))
        assertEquals(0, Protobuf.readInt32Field(Protobuf.varintField(1, 0L), 1))
        assertEquals(-3, Protobuf.readInt32Field(Protobuf.varintField(1, -3L), 1))
    }

    // --- Phase A: service discovery parsing ---------------------------------------------

    private fun lenField(number: Int, body: ByteArray): ByteArray =
        Protobuf.varint(((number shl 3) or 2).toLong()) +
            Protobuf.varint(body.size.toLong()) + body

    /**
     * Rebuilds the exact shape the 2018 Uconnect sent (docs/spike/car-run-2026-09-16-run2.log)
     * and checks we read it back correctly: video on channel 1, H264_BP, 1280x720 and 800x480
     * both at 30fps.
     */
    @Test
    fun `extracts the video service as the Uconnect described it`() {
        val config720 = Protobuf.varintField(1, 2) + Protobuf.varintField(2, 2) +
            Protobuf.varintField(3, 0) + Protobuf.varintField(4, 12) +
            Protobuf.varintField(5, 240) + Protobuf.varintField(6, 0) +
            Protobuf.varintField(8, 10000)
        val config480 = Protobuf.varintField(1, 1) + Protobuf.varintField(2, 2) +
            Protobuf.varintField(3, 0) + Protobuf.varintField(4, 30) +
            Protobuf.varintField(5, 160) + Protobuf.varintField(6, 0) +
            Protobuf.varintField(8, 10000)

        val mediaSink = Protobuf.varintField(1, 3) +          // available_type = H264_BP
            lenField(4, config720) + lenField(4, config480)   // video_configs
        val videoService = Protobuf.varintField(1, 1) + lenField(3, mediaSink)

        // An audio sink (no video_configs) must not be mistaken for the video service.
        val audioSink = Protobuf.varintField(1, 1) + lenField(3, Protobuf.varintField(1, 48000))
        val audioService = Protobuf.varintField(1, 5) + lenField(3, audioSink)

        val response = lenField(1, audioService) + lenField(1, videoService)

        val video = ServiceDiscovery.findVideoService(response)!!
        assertEquals(1, video.serviceId)
        assertEquals("VIDEO_H264_BP", video.codecName)
        assertEquals(2, video.configs.size)
        assertEquals("1280x720", video.configs[0].resolutionName)
        assertEquals("30fps", video.configs[0].frameRateName)
        assertEquals(240, video.configs[0].density)
        assertEquals("800x480", video.configs[1].resolutionName)
        assertEquals("30fps", video.configs[1].frameRateName)
    }

    @Test
    fun `media message ids above 32767 survive the 2-byte encoding`() {
        // SETUP = 32768 sets the high bit; a signed-byte slip here would corrupt every
        // video-channel message.
        val id = 32768
        val hi = ((id shr 8) and 0xFF).toByte()
        val lo = (id and 0xFF).toByte()
        val decoded = ((hi.toInt() and 0xFF) shl 8) or (lo.toInt() and 0xFF)
        assertEquals(32768, decoded)
    }

    /**
     * Regression: channel-open messages must carry MessageType::CONTROL even on a service
     * channel. Sending them as SPECIFIC made the 2018 Uconnect drop the USB link ~160ms
     * later with no protocol-level error at all - invisible in code, fatal on the wire.
     */
    @Test
    fun `channel-open frames carry the CONTROL message type bit`() {
        val control = Frames.build(1, encrypted = false, payload = byteArrayOf(0, 7),
            messageType = Frames.MESSAGE_CONTROL)
        assertEquals(Frames.MESSAGE_CONTROL, control[1].toInt() and Frames.MESSAGE_CONTROL)

        val specific = Frames.build(1, encrypted = false, payload = byteArrayOf(0x80.toByte(), 0))
        assertEquals(0, specific[1].toInt() and Frames.MESSAGE_CONTROL)

        val parser = Frames.Parser()
        parser.append(control, control.size)
        assertEquals(Frames.MESSAGE_CONTROL, parser.next()!!.messageType)
        parser.append(specific, specific.size)
        assertEquals(0, parser.next()!!.messageType)
    }

    @Test
    fun `maps resolution enums to pixel sizes and prefers the smallest`() {
        val small = ServiceDiscovery.VideoConfig(resolution = 1, frameRate = 2, density = 160)
        val large = ServiceDiscovery.VideoConfig(resolution = 2, frameRate = 2, density = 240)
        assertEquals(800 to 480, small.pixelSize())
        assertEquals(1280 to 720, large.pixelSize())
        assertTrue(small.pixelCount() < large.pixelCount())
    }

    /** The Data payload is an 8-byte big-endian timestamp followed by the access unit. */
    @Test
    fun `video frame timestamp is 8 bytes big-endian`() {
        val pts = 0x0102030405060708L
        val stamp = ByteArray(8)
        for (i in 0 until 8) stamp[i] = ((pts shr ((7 - i) * 8)) and 0xFF).toByte()
        assertEquals(0x01.toByte(), stamp[0])
        assertEquals(0x08.toByte(), stamp[7])

        var rebuilt = 0L
        for (b in stamp) rebuilt = (rebuilt shl 8) or (b.toLong() and 0xFF)
        assertEquals(pts, rebuilt)
    }

    /** Matches the Uconnect's input service: id 2, touchscreen 1258x708. */
    @Test
    fun `extracts the input service and touchscreen size`() {
        val touchScreen = Protobuf.varintField(1, 1258) + Protobuf.varintField(2, 708) +
            Protobuf.varintField(3, 1)
        val inputSource = lenField(1, Protobuf.varintField(1, 42)) + // keycodes, ignored
            lenField(2, touchScreen)
        val inputService = Protobuf.varintField(1, 2) + lenField(4, inputSource)

        // A video service must not be mistaken for the input service.
        val videoService = Protobuf.varintField(1, 1) +
            lenField(3, Protobuf.varintField(1, 3) + lenField(4, Protobuf.varintField(1, 1)))

        val response = lenField(1, videoService) + lenField(1, inputService)

        val input = ServiceDiscovery.findInputService(response)!!
        assertEquals(2, input.serviceId)
        assertEquals(1258, input.touchWidth)
        assertEquals(708, input.touchHeight)
    }

    /**
     * Regression: touch arrives already in projected video coordinates, NOT in the head
     * unit's advertised touchscreen space. An earlier version scaled by video/touchscreen,
     * which compressed the right-hand third of the screen away - a tap on a right-edge
     * button landed on the middle one (confirmed on the Uconnect: 800x480 projected, panel
     * advertised 1258x708, raw x never exceeded 791).
     *
     * Also pins the action mapping: Android Auto's PointerAction values equal Android's
     * MotionEvent actions, so no translation table is needed.
     */
    @Test
    fun `touch coordinates are used as-is and clamped to the video bounds`() {
        fun clamp(value: Int, limit: Int?): Float = when {
            limit == null -> value.toFloat()
            value < 0 -> 0f
            value >= limit -> (limit - 1).toFloat()
            else -> value.toFloat()
        }

        // A right-edge tap must stay at the right edge, not be scaled inward.
        assertEquals(791f, clamp(791, 800))
        assertEquals(216f, clamp(216, 800))
        assertEquals(0f, clamp(-5, 800))
        assertEquals(799f, clamp(1258, 800))
        assertEquals(479f, clamp(708, 480))
        assertEquals(351f, clamp(351, 480))

        assertEquals(0, android.view.MotionEvent.ACTION_DOWN)
        assertEquals(1, android.view.MotionEvent.ACTION_UP)
        assertEquals(2, android.view.MotionEvent.ACTION_MOVE)
    }

    /**
     * Regression: a video frame over 16 KB used to throw out of Frames.build and kill the
     * encoder loop, freezing the head unit picture. Anything busier than a test card exceeds
     * that routinely, so large messages must split into FIRST/MIDDLE/LAST and reassemble.
     */
    @Test
    fun `oversized messages split and reassemble`() {
        val payload = ByteArray(Frames.MAX_FRAME_PAYLOAD * 2 + 500) { (it % 251).toByte() }

        val frames = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < payload.size) {
            val size = minOf(Frames.MAX_FRAME_PAYLOAD, payload.size - offset)
            val chunk = payload.copyOfRange(offset, offset + size)
            val frameType = when {
                offset == 0 -> Frames.TYPE_FIRST
                offset + size >= payload.size -> Frames.TYPE_LAST
                else -> Frames.TYPE_MIDDLE
            }
            frames += Frames.build(
                7, encrypted = false, payload = chunk, messageType = Frames.MESSAGE_SPECIFIC,
                frameType = frameType,
                totalSize = if (frameType == Frames.TYPE_FIRST) payload.size else null,
            )
            offset += size
        }
        assertEquals(3, frames.size)

        // The FIRST frame carries the 4-byte total length; the others use the short header.
        assertEquals(4 + 4 + Frames.MAX_FRAME_PAYLOAD, frames[0].size)
        assertEquals(4 + Frames.MAX_FRAME_PAYLOAD, frames[1].size)

        val parser = Frames.Parser()
        val assembler = Frames.Assembler()
        var rebuilt: ByteArray? = null
        for (frame in frames) {
            parser.append(frame, frame.size)
            val parsed = parser.next()!!
            assembler.offer(parsed, parsed.payload)?.let { rebuilt = it }
        }
        assertTrue(payload.contentEquals(rebuilt!!))
    }

    /**
     * Regression: a single resync request was not enough. The requested keyframe is itself
     * just another frame, so it can be dropped by the same flow-control check that provoked
     * the request, and the old logic (request once, wait for a keyframe to clear the flag)
     * never asked again - leaving the picture permanently corrupted for the rest of the
     * session. Confirmed on the road: docs/spike/phase-c-stuck-resync-2026-09-17.log shows
     * dropped=27 in one burst, then 0.0 fps for over 100 seconds with no recovery.
     *
     * Model of the fixed decision: ask again on every drop, not just the first, and only
     * stop asking once a keyframe actually goes out (not merely once requested).
     */
    @Test
    fun `resync keeps retrying if the requested keyframe is itself dropped`() {
        var requests = 0
        var resyncRequested = false

        fun onDrop() {
            resyncRequested = true
            requests++ // old, buggy behaviour would gate this behind `if (!resyncRequested)`
        }
        fun onSent(keyFrame: Boolean) {
            if (keyFrame) resyncRequested = false
        }

        // Burst: drop, drop (this "keyframe attempt" also dropped), drop again, then finally
        // a keyframe gets through once the window clears.
        onDrop()
        assertEquals(1, requests)
        onDrop() // the resync keyframe we asked for got dropped too
        assertEquals(2, requests) // must ask again - this is what the old code failed to do
        onDrop()
        assertEquals(3, requests)
        onSent(keyFrame = true) // window finally clears, a keyframe gets out
        assertTrue(!resyncRequested)

        // A later drop must be able to trigger a fresh request cycle.
        onDrop()
        assertEquals(4, requests)
    }
}
