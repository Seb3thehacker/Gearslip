package app.seb3thehacker.gearslip

import android.view.Surface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import app.seb3thehacker.gearslip.protocol.MediaWindow
import app.seb3thehacker.gearslip.audio.CarMic
import org.junit.Test

class ConnectionLifecycleTest {
    private val version = message(1, byteArrayOf(0, 1, 0, 3))

    private fun message(id: Int, body: ByteArray = byteArrayOf()) = Frames.build(
        Frames.CHANNEL_CONTROL, false, byteArrayOf(0, id.toByte()) + body,
    )

    private fun hello(): ByteArray {
        val client = SSLContext.getDefault().createSSLEngine().apply {
            useClientMode = true
            enabledProtocols = arrayOf("TLSv1.2")
            beginHandshake()
        }
        val packet = ByteBuffer.allocate(client.session.packetBufferSize)
        client.wrap(ByteBuffer.allocate(0), packet)
        return message(3, packet.array().copyOf(packet.position()))
    }

    private fun identity(): CertProvider.Identity {
        val generated = SelfSignedCert.generate()
        return CertProvider.Identity(
            generated.keyStore, generated.certificate, SelfSignedCert.PASSWORD, "generated test identity",
        )
    }

    private fun runner(input: InputStream) = GearslipRunner(input, ByteArrayOutputStream(), ::identity)

    @Test fun `rejected authentication ignores subsequent success and service discovery in the same read`() {
        for (status in listOf(-2, -3)) {
            runner(ByteArrayInputStream(
                version + hello() + message(4, Protobuf.varintField(1, status.toLong())) +
                    message(4, Protobuf.varintField(1, 0)) + message(6) + version,
            )).run()
            assertEquals(
                if (status == -2) SessionReport.Category.CERTIFICATE else SessionReport.Category.AUTH,
                SessionReport.category,
            )
            assertEquals(SessionStatus.Phase.FAILED, SessionStatus.state.value.phase)
            assertEquals("Head unit rejected the certificate", SessionStatus.state.value.headline)
            assertTrue(SessionReport.stage < SessionReport.Stage.ACCEPTED)
        }
    }

    @Test fun `TLS failure ignores later protocol messages`() {
        val fatalAlert = message(3, byteArrayOf(21, 3, 3, 0, 2, 2, 42))
        runner(ByteArrayInputStream(version + hello() + fatalAlert + version + message(6))).run()
        assertEquals(SessionReport.Category.TLS, SessionReport.category)
        assertEquals("Head unit rejected the certificate", SessionStatus.state.value.headline)
        assertTrue(SessionReport.stage < SessionReport.Stage.ACCEPTED)
    }

    @Test fun `authentication success before TLS completes is ignored`() {
        runner(ByteArrayInputStream(version + message(4, Protobuf.varintField(1, 0)))).run()
        assertEquals(SessionReport.Category.TLS, SessionReport.category)
        assertEquals(SessionReport.Stage.VERSIONS, SessionReport.stage)
    }

    @Test fun `stop followed by EOF does not report a handshake failure`() {
        stopDuringRead { -1 }
    }

    @Test fun `stop followed by a read exception does not report a USB failure`() {
        stopDuringRead { throw IOException("transport closed by owner") }
    }

    @Test fun `stop ignores bytes returned by an in-flight read`() {
        stopDuringRead { bytes -> version.copyInto(bytes); version.size }
    }

    private fun stopDuringRead(result: (ByteArray) -> Int) {
        lateinit var runner: GearslipRunner
        val input = object : InputStream() {
            override fun read(): Int = error("bulk reads only")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                runner.stop()
                return result(bytes)
            }
        }
        runner = runner(input)
        runner.run()
        assertEquals(SessionReport.Category.NONE, SessionReport.category)
        assertEquals(SessionStatus.Phase.DISCONNECTED, SessionStatus.state.value.phase)
    }

    @Test fun `stop while preparing TLS leaves the session disconnected`() {
        val releaseIdentity = CountDownLatch(1)
        val runner = GearslipRunner(ByteArrayInputStream(version + hello()), ByteArrayOutputStream(), {
            check(releaseIdentity.await(5, TimeUnit.SECONDS))
            identity()
        })
        val reader = Thread { runner.run() }
        reader.start()
        try {
            // The reader waits on FutureTask.get while the identity provider is held above.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (reader.state != Thread.State.WAITING && reader.isAlive && System.nanoTime() < deadline) {
                reader.join(1)
            }
            assertEquals(Thread.State.WAITING, reader.state)
            runner.stop()
        } finally {
            releaseIdentity.countDown()
            reader.join(5_000)
        }
        assertTrue("reader must finish", !reader.isAlive)
        assertEquals(SessionReport.Category.NONE, SessionReport.category)
        assertEquals(SessionStatus.Phase.DISCONNECTED, SessionStatus.state.value.phase)
    }

    @Test fun `EOF and transport failure shut down the writer`() {
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("test disconnect")
        }
        for (input in listOf(ByteArrayInputStream(version), failing)) {
            val runner = runner(input)
            runner.run()
            val field = GearslipRunner::class.java.getDeclaredField("writer").apply { isAccessible = true }
            assertTrue((field.get(runner) as ExecutorService).isShutdown)
        }
    }
    @Test fun `stop during profile lookup cannot publish channels after cleanup`() {
        SessionStatus.connecting("Test discovery")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runner = GearslipRunner(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), ::identity,
            vehicleProfileFor = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); null })
        val state = GearslipRunner::class.java.declaredClasses.first { it.simpleName == "State" }
        setField(runner, "state", requireNotNull(state.enumConstants).first { it.toString() == "WAIT_SDR" })
        val video = Protobuf.varintField(1, 1) + Protobuf.bytesField(3,
            Protobuf.varintField(1, 3) + Protobuf.bytesField(4, byteArrayOf(8, 1, 16, 2, 40, -96, 1)))
        val response = Protobuf.bytesField(1, video)
        val method = GearslipRunner::class.java.getDeclaredMethod("onServiceDiscoveryResponse", ByteArray::class.java)
            .apply { isAccessible = true }
        val reader = Thread { method.invoke(runner, response) }.apply { start() }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            runner.stop()
        } finally {
            release.countDown()
            reader.join(5_000)
        }
        assertTrue(!reader.isAlive)
        assertEquals(-1, getField(runner, "videoChannelId"))
        assertNull(getField(runner, "audioLink"))
        assertNull(CarMic.current)
        assertNull(CarFocus.request)
        assertEquals(SessionStatus.Phase.DISCONNECTED, SessionStatus.state.value.phase)
    }

    @Test fun `stop during encoder startup cleans up the published encoder`() {
        SessionStatus.connecting("Test encoder startup")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var runner: GearslipRunner
        runner = GearslipRunner(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), ::identity,
            startEncoder = { source ->
                assertSame(source, getField(runner, "videoSource"))
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            })
        // Only exercise lifecycle coordination; no transport or Android MediaCodec in this JVM test.
        (getField(runner, "writer") as ExecutorService).shutdownNow()
        setField(runner, "selectedConfigIndex", 0)
        setField(runner, "maxUnacked", 1)
        setField(runner, "videoConfigs", listOf(ServiceDiscovery.VideoConfig(1, 2, 160)))
        val focus = GearslipRunner::class.java.getDeclaredMethod("onVideoFocus", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val reader = Thread { focus.invoke(runner, 1) }.apply { start() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val stopper = Thread { runner.stop() }.apply { start() }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (getField(runner, "stopped") != true && System.nanoTime() < deadline) stopper.join(1)
            assertEquals(true, getField(runner, "stopped"))
            release.countDown()
            reader.join(5_000)
            stopper.join(5_000)
            assertTrue(!reader.isAlive && !stopper.isAlive)
            assertNull(getField(runner, "videoSource"))
            assertNull(CarFocus.request)
            assertEquals(SessionStatus.Phase.DISCONNECTED, SessionStatus.state.value.phase)
        } finally {
            release.countDown()
            runner.stop()
        }
    }

    @Test fun `stop cannot return before an earlier teardown notifies the projection`() {
        val notifying = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopReturned = CountDownLatch(1)
        val projection = object : Projection {
            override fun onSurfaceReady(surface: Surface, width: Int, height: Int, densityDpi: Int) = Unit
            override fun onTouch(action: Int, actionIndex: Int, points: List<TouchPoint>) = Unit
            override fun onProjectionStopped() {
                notifying.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val runner = GearslipRunner(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), ::identity, projection)
        val cleanup = GearslipRunner::class.java.getDeclaredMethod("releaseChannels").apply { isAccessible = true }
        val reader = Thread { cleanup.invoke(runner) }.apply { start() }
        assertTrue(notifying.await(2, TimeUnit.SECONDS))
        val stopper = Thread { runner.stop(); stopReturned.countDown() }.apply { start() }
        try {
            assertFalse("reconnect must wait for the old projection notification", stopReturned.await(100, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            reader.join(5_000)
            stopper.join(5_000)
        }
        assertTrue(stopReturned.await(1, TimeUnit.SECONDS))
        assertTrue(!reader.isAlive && !stopper.isAlive)
    }

    @Test fun `each video session waits for a keyframe without consuming credit`() {
        val runner = runner(ByteArrayInputStream(byteArrayOf()))
        // Observe producer state without requiring a TLS peer or Android encoder.
        (getField(runner, "writer") as ExecutorService).shutdownNow()
        setField(runner, "maxUnacked", 4)
        val start = GearslipRunner::class.java.getDeclaredMethod("beginVideoStream", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val frame = GearslipRunner::class.java.getDeclaredMethod("sendVideoFrame", ByteArray::class.java,
            Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val window = getField(runner, "videoWindow") as MediaWindow
        try {
            repeat(2) { session ->
                start.invoke(runner, 0)
                frame.invoke(runner, byteArrayOf(1), 1L, false)
                assertEquals(session * 2, getField(runner, "framesSent"))
                assertEquals(0, window.inFlight)
                frame.invoke(runner, byteArrayOf(2), 2L, true)
                frame.invoke(runner, byteArrayOf(3), 3L, false)
                assertEquals((session + 1) * 2, getField(runner, "framesSent"))
                assertEquals(2, window.inFlight)
            }
        } finally {
            runner.stop()
        }
    }

    @Test fun `closed channels reject pending and later output`() {
        val output = ByteArrayOutputStream()
        val runner = GearslipRunner(ByteArrayInputStream(byteArrayOf()), output, ::identity)
        val writer = getField(runner, "writer") as ExecutorService
        val waiting = CountDownLatch(1)
        val release = CountDownLatch(1)
        writer.execute { waiting.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        assertTrue(waiting.await(2, TimeUnit.SECONDS))
        setField(runner, "inputChannelId", 8)
        val send = GearslipRunner::class.java.getDeclaredMethod("send", Int::class.javaPrimitiveType,
            ByteArray::class.java, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType).apply { isAccessible = true }
        val close = GearslipRunner::class.java.getDeclaredMethod("onChannelClosed", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        try {
            send.invoke(runner, 32770, byteArrayOf(), false, 8, Frames.MESSAGE_SPECIFIC)
            close.invoke(runner, 8)
            send.invoke(runner, 32770, byteArrayOf(), false, 8, Frames.MESSAGE_SPECIFIC)
            release.countDown()
            writer.shutdown()
            assertTrue(writer.awaitTermination(2, TimeUnit.SECONDS))
            assertEquals(-1, getField(runner, "inputChannelId"))
            assertEquals(0, output.size())
        } finally {
            release.countDown()
            runner.stop()
        }
    }

    @Test fun `duplicate and unsolicited channel responses do not repeat setup`() {
        val runner = runner(ByteArrayInputStream(byteArrayOf()))
        (getField(runner, "writer") as ExecutorService).shutdownNow()
        setField(runner, "inputChannelId", 8)
        val open = GearslipRunner::class.java.getDeclaredMethod("openChannel", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val frame = GearslipRunner::class.java.getDeclaredMethod("onServiceFrame", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, ByteArray::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        try {
            frame.invoke(runner, 8, 8, Protobuf.varintField(1, 0), true)
            assertTrue((getField(runner, "openedChannels") as Set<*>).isEmpty())
            open.invoke(runner, 8)
            frame.invoke(runner, 8, 8, Protobuf.varintField(1, 0), false)
            assertTrue((getField(runner, "openedChannels") as Set<*>).isEmpty())
            frame.invoke(runner, 8, 8, Protobuf.varintField(1, 0), true)
            assertEquals(setOf(8), getField(runner, "openedChannels"))
            // A repeated response with a failure status must not close an already opened channel.
            frame.invoke(runner, 8, 8, Protobuf.varintField(1, -5), true)
            assertEquals(setOf(8), getField(runner, "openedChannels"))
            assertTrue((getField(runner, "closedChannels") as Set<*>).isEmpty())
        } finally {
            runner.stop()
        }
    }

    @Test fun `focus granted before ready configuration starts video after configuration`() {
        var starts = 0
        val runner = GearslipRunner(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), ::identity,
            startEncoder = { starts++ })
        (getField(runner, "writer") as ExecutorService).shutdownNow()
        setField(runner, "videoConfigs", listOf(ServiceDiscovery.VideoConfig(1, 2, 160)))
        val focus = GearslipRunner::class.java.getDeclaredMethod("onVideoFocus", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val config = GearslipRunner::class.java.getDeclaredMethod("onVideoConfig", ByteArray::class.java)
            .apply { isAccessible = true }
        try {
            focus.invoke(runner, 1)
            assertEquals(0, starts)
            config.invoke(runner, Protobuf.varintField(1, 2) + Protobuf.varintField(2, 4) + Protobuf.varintField(3, 0))
            assertEquals(1, starts)
            assertTrue(getField(runner, "videoStarted") as Boolean)
            assertNull(getField(runner, "pendingVideoFocus"))
            focus.invoke(runner, 1)
            assertEquals(1, starts)
        } finally {
            runner.stop()
        }
    }

    @Test fun `projected without input focus keeps video but suppresses touches`() {
        var touches = 0
        val projection = object : Projection {
            override fun onSurfaceReady(surface: Surface, width: Int, height: Int, densityDpi: Int) = Unit
            override fun onTouch(action: Int, actionIndex: Int, points: List<TouchPoint>) { touches++ }
            override fun onProjectionStopped() = Unit
        }
        val runner = GearslipRunner(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), ::identity,
            projection, startEncoder = {})
        (getField(runner, "writer") as ExecutorService).shutdownNow()
        setField(runner, "videoConfigs", listOf(ServiceDiscovery.VideoConfig(1, 2, 160)))
        setField(runner, "selectedConfigIndex", 0)
        setField(runner, "maxUnacked", 4)
        setField(runner, "videoChannelId", 5)
        val focus = GearslipRunner::class.java.getDeclaredMethod("onVideoFocus", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val input = GearslipRunner::class.java.getDeclaredMethod("onInputReport", ByteArray::class.java)
            .apply { isAccessible = true }
        val pointer = Protobuf.varintField(1, 20) + Protobuf.varintField(2, 30) + Protobuf.varintField(3, 0)
        val report = Protobuf.varintField(1, 1) + Protobuf.bytesField(3, Protobuf.bytesField(1, pointer))
        try {
            focus.invoke(runner, 4)
            input.invoke(runner, report)
            assertTrue(getField(runner, "videoStarted") as Boolean)
            assertTrue(getField(runner, "videoProjected") as Boolean)
            assertEquals(0, touches)
            focus.invoke(runner, 1)
            input.invoke(runner, report)
            assertEquals(1, touches)
            focus.invoke(runner, 3)
            input.invoke(runner, report)
            assertEquals("touches cannot grant projection focus", 1, touches)
            assertFalse(getField(runner, "videoProjected") as Boolean)
            assertFalse(getField(runner, "videoInputAllowed") as Boolean)
            focus.invoke(runner, 1)
            val close = GearslipRunner::class.java.getDeclaredMethod("onChannelClosed", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
            close.invoke(runner, 5)
            input.invoke(runner, report)
            assertEquals("closed video cannot accept input on the still-open input channel", 1, touches)
            assertFalse(getField(runner, "videoInputAllowed") as Boolean)
        } finally {
            runner.stop()
        }
    }

    private fun getField(runner: GearslipRunner, name: String): Any? =
        GearslipRunner::class.java.getDeclaredField(name).apply { isAccessible = true }.get(runner)

    private fun setField(runner: GearslipRunner, name: String, value: Any) {
        GearslipRunner::class.java.getDeclaredField(name).apply { isAccessible = true }.set(runner, value)
    }

}
