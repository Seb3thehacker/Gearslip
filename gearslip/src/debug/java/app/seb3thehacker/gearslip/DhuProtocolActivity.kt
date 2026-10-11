package app.seb3thehacker.gearslip

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import app.seb3thehacker.gearslip.audio.CarAudio
import app.seb3thehacker.gearslip.audio.CarMic
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate

/** Debug-only DHU test endpoint. Supply a test identity in cache/dhu-test.p12; see docs/DHU_PROTOCOL.md. */
class DhuProtocolActivity : Activity() {
    @Volatile private var runner: GearslipRunner? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var active = true
    private var traffic: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GearslipLog.init(this)
        setContentView(TextView(this).apply { setText(R.string.dhu_protocol_test_status) })
        Thread({ runSession() }, "dhu-test-session").start()
    }

    private fun runSession() {
        try {
            val listener = ServerSocket(5277, 1, InetAddress.getByName("127.0.0.1"))
            server = listener
            if (!active) return
            val peer = listener.accept()
            socket = peer
            if (!active) return
            val session = GearslipRunner(peer.getInputStream(), peer.getOutputStream(), ::testIdentity)
            runner = session
            startTestTraffic()
            session.run()
        } catch (e: Exception) {
            if (active) GearslipLog.e("DHU test failed", e)
        } finally {
            closeSession()
        }
    }

    private fun testIdentity(): CertProvider.Identity {
        val password = "test-only".toCharArray()
        val store = KeyStore.getInstance("PKCS12").apply {
            File(cacheDir, "dhu-test.p12").inputStream().use { load(it, password) }
        }
        val alias = store.aliases().toList().first { store.isKeyEntry(it) }
        return CertProvider.Identity(store, store.getCertificate(alias) as X509Certificate, password, "DHU test identity")
    }

    private fun startTestTraffic() {
        val testMicrophone = intent.getBooleanExtra("microphone", false)
        traffic = Thread({
            CarAudio.captureStarted()
            try {
                var micStarted = false
                var micPackets = 0
                val deadline = System.nanoTime() + 90_000_000_000L
                while (active && System.nanoTime() < deadline) {
                    val pcm = ByteArray(CarAudio.sampleRate / 50 * CarAudio.channels * 2)
                    CarAudio.deliver(pcm, pcm.size, 0f)
                    if (testMicrophone) {
                        val mic = CarMic.current
                        if (!micStarted) micStarted = mic?.start() == true
                        if (micStarted && mic?.read(1) != null && ++micPackets == 1) {
                            GearslipLog.i("DHU test: received microphone PCM")
                        }
                    }
                    Thread.sleep(20)
                }
                GearslipLog.i("DHU test: completed, microphone packets=$micPackets")
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                CarAudio.captureStopped()
                closeSession()
            }
        }, "dhu-test-traffic").apply { start() }
    }

    private fun closeSession() {
        active = false
        runner?.stop()
        runCatching { socket?.close() }
        runCatching { server?.close() }
    }

    override fun onDestroy() {
        closeSession()
        traffic?.interrupt()
        super.onDestroy()
    }
}
