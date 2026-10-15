package app.seb3thehacker.gearslip.audio

import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.Protobuf
import app.seb3thehacker.gearslip.ServiceDiscovery
import app.seb3thehacker.gearslip.Wire
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The car's own microphone, which most head units offer over the same link as the screen:
 *
 *   -> CHANNEL_OPEN_REQUEST          <- CHANNEL_OPEN_RESPONSE
 *   -> Setup { config_index }        <- Config
 *   -> VoiceSessionNotification { START }   (control channel)
 *   -> MicrophoneRequest { open }    <- MicrophoneResponse { status, session_id }
 *   <- Data { 8-byte timestamp, PCM }...   -> Ack { session_id, ack }
 *   -> MicrophoneRequest { open = false }
 *   -> VoiceSessionNotification { END }
 *
 * The channel opens with the session, but the car only records between [start] and [stop], and
 * only inside a voice session: the 2024 VW answered a bare MicrophoneRequest with status -7.
 * Speech wants 16 kHz mono; a car that records at a multiple of that is averaged down to it, and
 * one that doesn't isn't used, so voice falls back to the phone's microphone.
 */
class CarMic(
    private val service: ServiceDiscovery.AudioService,
    private val sendOnChannel: (messageId: Int, body: ByteArray) -> Unit,
    private val voiceSession: (start: Boolean) -> Unit,
) {
    val channelId get() = service.serviceId

    private val config = service.configs.first()
    private val step = config.sampleRate / RATE
    private val usable = service.codecType == ServiceDiscovery.CODEC_PCM && config.bits == 16 &&
        config.sampleRate % RATE == 0 && step >= 1 && config.channels in 1..2

    @Volatile private var ready = false
    @Volatile private var listening = false
    @Volatile private var session = 0
    private var packets = 0
    private val chunks = LinkedBlockingQueue<ShortArray>()

    fun onMessage(messageId: Int, body: ByteArray) {
        when (messageId) {
            MSG_CHANNEL_OPEN_RESPONSE -> {
                val status = Protobuf.readInt32Field(body, 1) ?: 0
                GearslipLog.i("mic: <- ChannelOpenResponse: status=$status")
                if (status != 0) return
                // Setup { type = the codec }, as for audio: 0 got UNEXPECTED_MESSAGE from the VW.
                sendOnChannel(MSG_SETUP, Protobuf.varintField(1, service.codecType.toLong()))
                ready = usable
                if (!usable) {
                    GearslipLog.w(
                        "mic: the car records ${service.codecName} ${config.sampleRate}Hz/${config.bits}bit/x${config.channels}, " +
                            "which speech can't use - voice stays on the phone's microphone",
                    )
                }
            }

            MSG_CONFIG -> GearslipLog.i("mic: <- Config status=${Wire.varint(Wire.fields(body), 1)}")

            MSG_MIC_RESPONSE -> {
                val fields = Wire.fields(body)
                session = Wire.varint(fields, 2)?.toInt() ?: 0
                GearslipLog.i("mic: <- MicrophoneResponse status=${Wire.varint(fields, 1)} session=$session")
            }

            MSG_DATA -> {
                sendOnChannel(MSG_ACK, Protobuf.varintField(1, session.toLong()) + Protobuf.varintField(2, 1L))
                if (!listening || body.size <= TIMESTAMP_BYTES) return
                if (packets++ == 0) GearslipLog.i("mic: first audio from the car (${body.size - TIMESTAMP_BYTES} bytes)")
                chunks.offer(toSpeechRate(body))
            }

            else -> GearslipLog.i("mic: <- unhandled message id=$messageId (${body.size} bytes)")
        }
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
    fun start(): Boolean {
        if (!ready) return false
        chunks.clear()
        packets = 0
        listening = true
        voiceSession(true)
        sendOnChannel(MSG_MIC_REQUEST, Protobuf.varintField(1, 1L) + Protobuf.varintField(4, 1L))
        GearslipLog.i("mic: -> MicrophoneRequest(open)")
        return true
    }

    /** The next piece of audio, or null if the car sent nothing for [timeoutMs]. */
    fun read(timeoutMs: Long): ShortArray? = chunks.poll(timeoutMs, TimeUnit.MILLISECONDS)

    fun stop() {
        if (!listening) return
        listening = false
        sendOnChannel(MSG_MIC_REQUEST, Protobuf.varintField(1, 0L))
        voiceSession(false)
        GearslipLog.i("mic: -> MicrophoneRequest(close) after $packets packets, voice session ended")
    }

    companion object {
        /** The connected car's microphone, or null with no car or one that has none. */
        @Volatile var current: CarMic? = null

        const val RATE = 16_000
        private const val TIMESTAMP_BYTES = 8
        private const val MSG_CHANNEL_OPEN_RESPONSE = 8
        private const val MSG_DATA = 0
        private const val MSG_SETUP = 32768
        private const val MSG_CONFIG = 32771
        private const val MSG_ACK = 32772
        private const val MSG_MIC_REQUEST = 32773
        private const val MSG_MIC_RESPONSE = 32774
    }
}
