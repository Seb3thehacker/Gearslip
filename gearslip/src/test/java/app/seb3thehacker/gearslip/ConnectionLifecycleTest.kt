package app.seb3thehacker.gearslip

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
}
