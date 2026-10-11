package app.seb3thehacker.gearslip.audio

import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.Protobuf
import app.seb3thehacker.gearslip.ServiceDiscovery
import app.seb3thehacker.gearslip.protocol.MediaProtocol
import app.seb3thehacker.gearslip.protocol.MediaWindow
import java.io.IOException

/** Negotiates a PCM sink, obtains audio focus, then streams within the car's ACK window. */
class AudioLink(
    private val service: ServiceDiscovery.AudioService,
    private val sendOnChannel: (messageId: Int, body: ByteArray) -> Unit,
    private val sendControl: (messageId: Int, body: ByteArray) -> Unit,
) : CarAudio.Link {
    private var configIndex = 0
    private var maxUnacked = 0
    private var ready = false
    private var closed = false
    private var chunks = 0
    private var underflowLogged = false
    private var wanted = false
    private var focusGranted = false
    private var focusRequested = false
    @Volatile private var started = false
    @Volatile private var session = 0
    private val window = MediaWindow()

    private val config get() = service.configs[configIndex]
    override val sampleRate get() = config.sampleRate
    override val channels get() = config.channels
    val channelId get() = service.serviceId

    @Synchronized
    fun onMessage(messageId: Int, body: ByteArray) {
        if (closed) return
        when (messageId) {
            MSG_CHANNEL_OPEN_RESPONSE -> onOpened(Protobuf.readInt32Field(body, 1))
            MediaProtocol.CONFIG -> onConfig(body)
            MediaProtocol.ACK -> MediaProtocol.ack(body)?.let(window::acknowledge)
            0x800b -> if (!underflowLogged) {
                underflowLogged = true
                GearslipLog.w("audio: head unit reported a playback underrun")
            }
            MediaProtocol.UNEXPECTED_MESSAGE -> {
                GearslipLog.w("audio: head unit rejected a message; closing the audio stream")
                close()
            }
            else -> GearslipLog.i("audio: unhandled message id=$messageId (${body.size} bytes)")
        }
    }

    private fun onOpened(status: Int?) {
        if (status != 0 || service.codecType != ServiceDiscovery.CODEC_PCM) return
        // Setup selects a codec. Start selects an index from the subsequent Config response.
        sendOnChannel(MediaProtocol.SETUP, MediaProtocol.setup(ServiceDiscovery.CODEC_PCM))
        GearslipLog.i("audio: -> Setup(codec=PCM) on channel $channelId")
    }

    private fun onConfig(body: ByteArray) {
        val answer = MediaProtocol.config(body)
        val index = answer?.indices?.firstOrNull { service.configs.getOrNull(it)?.isSupportedPcm() == true }
        if (answer == null || answer.status != MediaProtocol.READY || index == null) {
            GearslipLog.w("audio: no ready, supported PCM configuration; waiting for the head unit")
            ready = false
            stopStream()
            CarAudio.detach(this)
            return
        }
        if (ready) return
        configIndex = index
        maxUnacked = answer.maxUnacked
        ready = true
        GearslipLog.i("audio: ready at ${sampleRate}Hz x$channels, config=$index, max_unacked=$maxUnacked")
        CarAudio.attach(this)
    }

    @Synchronized
    override fun start() {
        if (!ready || closed) return
        wanted = true
        if (!focusRequested) {
            focusRequested = true
            sendControl(MSG_FOCUS_REQUEST, Protobuf.varintField(1, FOCUS_GAIN.toLong()))
        }
        startStream()
    }

    @Synchronized
    fun onFocus(state: Int) {
        if (closed || !focusRequested) return
        focusGranted = state == FOCUS_STATE_GAIN || state == FOCUS_STATE_GAIN_TRANSIENT
        if (focusGranted) startStream() else stopStream()
    }

    private fun startStream() {
        if (!ready || !wanted || !focusGranted || started) return
        session++
        chunks = 0
        underflowLogged = false
        window.start(session, maxUnacked)
        sendOnChannel(MediaProtocol.START, MediaProtocol.start(session, configIndex))
        started = true
    }

    private fun stopStream() {
        window.stop()
        if (!started) return
        started = false
        // gal.Stop has no fields; the receiver stops the current session on this channel.
        sendOnChannel(MediaProtocol.STOP, byteArrayOf())
    }

    @Synchronized
    override fun stop() {
        wanted = false
        stopStream()
        if (focusRequested) sendControl(MSG_FOCUS_REQUEST, Protobuf.varintField(1, FOCUS_RELEASE.toLong()))
        focusRequested = false
        focusGranted = false
    }

    override fun write(pcm: ByteArray, size: Int) {
        require(size in 0..pcm.size)
        if (!started || size == 0) return
        val currentSession = session
        try {
            if (!window.reserve(currentSession)) return
            synchronized(this) {
                if (!started || session != currentSession) return
                sendOnChannel(MediaProtocol.DATA, MediaProtocol.data(System.nanoTime() / 1_000, pcm, size))
                if (++chunks % 200 == 0) GearslipLog.i("audio: sent=$chunks inFlight=${window.inFlight}/$maxUnacked")
            }
        } catch (e: IOException) {
            GearslipLog.e("audio: stream stopped", e)
            stop()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            stop()
        }
    }

    @Synchronized
    fun close() {
        if (closed) return
        stop()
        closed = true
        ready = false
        CarAudio.detach(this)
    }

    companion object {
        const val MSG_FOCUS_REQUEST = 18
        const val MSG_FOCUS_RESPONSE = 19
        private const val MSG_CHANNEL_OPEN_RESPONSE = 8
        private const val FOCUS_GAIN = 1
        private const val FOCUS_RELEASE = 4
        private const val FOCUS_STATE_GAIN = 1
        private const val FOCUS_STATE_GAIN_TRANSIENT = 2
    }
}

internal fun ServiceDiscovery.AudioConfig.isSupportedPcm(): Boolean =
    sampleRate in 8_000..192_000 && bits == 16 && channels in 1..2
