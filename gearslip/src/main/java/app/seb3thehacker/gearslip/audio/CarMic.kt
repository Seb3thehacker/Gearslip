package app.seb3thehacker.gearslip.audio

import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.Protobuf
import app.seb3thehacker.gearslip.ServiceDiscovery
import app.seb3thehacker.gearslip.Wire
import app.seb3thehacker.gearslip.protocol.MediaProtocol
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The head unit's PCM source. Recording starts directly with MicrophoneRequest, without Setup. */
class CarMic(
    private val service: ServiceDiscovery.AudioService,
    private val sendOnChannel: (messageId: Int, body: ByteArray) -> Unit,
) {
    val channelId get() = service.serviceId

    private val config = service.configs.first()
    private val step = config.sampleRate / RATE
    private val usable = service.codecType == ServiceDiscovery.CODEC_PCM && config.isSupportedPcm() &&
        config.sampleRate % RATE == 0 && step >= 1 && config.channels in 1..2

    @Volatile private var ready = false
    @Volatile private var listening = false
    private var session: Int? = null
    private var pendingAudio: ByteArray? = null
    private var closed = false
    private val pendingRequests = ArrayDeque<Boolean>()
    private var packets = 0
    private val chunks = LinkedBlockingQueue<ShortArray>(64)

    @Synchronized
    fun onMessage(messageId: Int, body: ByteArray) {
        if (closed) return
        when (messageId) {
            MSG_CHANNEL_OPEN_RESPONSE -> {
                ready = Protobuf.readInt32Field(body, 1) == 0 && usable
                if (!ready) GearslipLog.w("mic: no supported PCM microphone; voice uses the phone")
            }
            MediaProtocol.MICROPHONE_RESPONSE -> onResponse(body)
            MediaProtocol.DATA -> onData(body)
            MediaProtocol.UNEXPECTED_MESSAGE -> close()
            else -> GearslipLog.i("mic: unhandled message id=$messageId (${body.size} bytes)")
        }
    }

    private fun onResponse(body: ByteArray) {
        val opening = pendingRequests.removeFirstOrNull() ?: return
        val fields = Wire.fields(body)
        val success = Wire.varint(fields, 1)?.toInt() == 0
        session = if (opening && success) Wire.varint(fields, 2)?.toInt() ?: 0 else null
        val firstAudio = pendingAudio
        pendingAudio = null
        if (session == null) {
            listening = false
            chunks.clear()
        } else if (firstAudio != null) {
            onData(firstAudio)
        }
        GearslipLog.i("mic: response to ${if (opening) "open" else "close"}, success=$success")
    }

    private fun onData(body: ByteArray) {
        if (!usable) return
        val pcmSize = body.size - TIMESTAMP_BYTES
        if (pcmSize <= 0 || pcmSize % (2 * config.channels) != 0) return
        val activeSession = session
        if (activeSession == null) {
            bufferPendingAudio(body, pcmSize)
            return
        }
        sendOnChannel(MediaProtocol.ACK, MediaProtocol.ack(activeSession))
        if (!listening) return
        // Bound both individual packets and the queue if speech stops consuming the stream.
        if (pcmSize > config.sampleRate * 2 * config.channels) {
            stop()
            return
        }
        val samples = toSpeechRate(body)
        if (samples.isEmpty()) return
        if (!chunks.offer(samples)) {
            GearslipLog.w("mic: speech is not consuming audio; stopping recording")
            stop()
        }
        packets++
    }

    /** DHU starts its capture thread before sending the response containing the session ID. */
    private fun bufferPendingAudio(body: ByteArray, pcmSize: Int) {
        if (!listening || pendingRequests.firstOrNull() != true) return
        // We advertise one packet of credit. Retain at most that packet until it can be ACKed.
        if (pendingAudio != null || pcmSize > config.sampleRate * 2 * config.channels) {
            stop()
            return
        }
        pendingAudio = body
    }

    /** Little-endian PCM after the timestamp, mixed to mono and averaged down to [RATE]. */
    private fun toSpeechRate(body: ByteArray): ShortArray {
        val frameBytes = 2 * config.channels
        val frames = (body.size - TIMESTAMP_BYTES) / frameBytes
        val out = ShortArray(frames / step)
        for (i in out.indices) {
            var sum = 0
            for (s in 0 until step) {
                val at = TIMESTAMP_BYTES + (i * step + s) * frameBytes
                for (c in 0 until config.channels) {
                    val o = at + 2 * c
                    sum += (body[o].toInt() and 0xFF) or (body[o + 1].toInt() shl 8)
                }
            }
            out[i] = (sum / (step * config.channels)).toShort()
        }
        return out
    }

    /** Asks the car to start recording. False when it can't, so the phone's microphone is used. */
    @Synchronized
    fun start(): Boolean {
        if (!ready || closed || listening || pendingRequests.isNotEmpty()) return false
        chunks.clear()
        packets = 0
        session = null
        pendingAudio = null
        listening = true
        requestRecording(true)
        return true
    }

    /** Speech falls back to the phone if the request fails or no audio arrives within its timeout. */
    fun read(timeoutMs: Long): ShortArray? = chunks.poll(timeoutMs, TimeUnit.MILLISECONDS)

    @Synchronized
    fun stop() {
        if (!listening) return
        listening = false
        pendingAudio = null
        chunks.clear()
        requestRecording(false)
        GearslipLog.i("mic: -> close after $packets packets")
    }

    private fun requestRecording(open: Boolean) {
        pendingRequests.addLast(open)
        var body = Protobuf.varintField(1, if (open) 1 else 0)
        if (open) body += Protobuf.varintField(4, 1) // max_unacked: acknowledge each received packet
        sendOnChannel(MediaProtocol.MICROPHONE_REQUEST, body)
    }

    @Synchronized
    fun close() {
        stop()
        closed = true
        ready = false
        session = null
        pendingAudio = null
        pendingRequests.clear()
        chunks.clear()
    }

    companion object {
        /** The connected car's microphone, or null with no car or one that has none. */
        @Volatile var current: CarMic? = null

        const val RATE = 16_000
        private const val TIMESTAMP_BYTES = 8
        private const val MSG_CHANNEL_OPEN_RESPONSE = 8
    }
}
