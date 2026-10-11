package app.seb3thehacker.gearslip

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoLifecycleTest {
    @Test fun `late codec and focus callbacks cannot enqueue after video channel closes`() {
        Fixture().use { fixture ->
            fixture.invoke("onChannelClosed", 1)
            fixture.invoke("onVideoCodecConfig", byteArrayOf(1, 2, 3))
            fixture.invoke("requestNativeFocus")
            assertNull(fixture.get("lastCodecConfig"))
            assertEquals(0, fixture.writer.queue.size)
        }
    }

    @Test fun `codec config received while native is cached and replayed after Start`() {
        Fixture().use { fixture ->
            fixture.set("videoProjected", false)
            val config = byteArrayOf(1, 2, 3)
            fixture.invoke("onVideoCodecConfig", config)
            assertArrayEquals(config, fixture.get("lastCodecConfig") as ByteArray)
            assertEquals(0, fixture.writer.queue.size)
            fixture.invoke("onVideoFocus", 1)
            // Start and the cached SPS/PPS are queued together before frames may resume.
            assertEquals(2, fixture.writer.queue.size)
            assertEquals(true, fixture.get("awaitingVideoKeyFrame"))
        }
    }

    /** Hold writes before TLS, so these tests can detect any late enqueue without a device. */
    private class Fixture : AutoCloseable {
        private val release = CountDownLatch(1)
        val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
        private val runner = GearslipRunner(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(),
            identityProvider = { error("No identity is needed for callback lifecycle tests") })

        init {
            (get("writer") as ExecutorService).shutdownNow()
            set("writer", writer)
            val entered = CountDownLatch(1)
            writer.execute {
                entered.countDown()
                runCatching { release.await() }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            set("videoChannelId", 1)
            set("videoStarted", true)
            set("selectedConfigIndex", 0)
            set("maxUnacked", 1)
        }

        fun get(name: String): Any? = field(name).get(runner)
        fun set(name: String, value: Any) = field(name).set(runner, value)

        fun invoke(name: String, argument: Any? = null) {
            val types = when (argument) {
                is Int -> arrayOf(Int::class.javaPrimitiveType!!)
                is ByteArray -> arrayOf(ByteArray::class.java)
                else -> emptyArray()
            }
            val method = GearslipRunner::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }
            if (argument == null) method.invoke(runner) else method.invoke(runner, argument)
        }

        private fun field(name: String) = GearslipRunner::class.java.getDeclaredField(name).apply { isAccessible = true }

        override fun close() {
            runner.stop()
            release.countDown()
            writer.awaitTermination(2, TimeUnit.SECONDS)
        }
    }
}
