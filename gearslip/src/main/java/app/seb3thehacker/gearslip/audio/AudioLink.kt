package app.seb3thehacker.gearslip.audio

import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.Protobuf
import app.seb3thehacker.gearslip.ServiceDiscovery
import app.seb3thehacker.gearslip.Wire

/**
 * The phone end of the head unit's media audio sink, the audio twin of the video channel:
 *
 *   -> CHANNEL_OPEN_REQUEST   <- CHANNEL_OPEN_RESPONSE
 *   -> Setup { PCM }          <- Config { status, max_unacked, configuration_indices }
 *   -> AudioFocusRequest      <- AudioFocusResponse           (control channel)
 *   -> Start { session_id, configuration_index }
 *   -> Data { 8-byte timestamp, PCM }...   <- Ack { session_id, ack }
 *   -> Stop
 *
 * Inverted from aasdk's AudioMediaSinkService. Bench-verified only as far as capture goes:
 * nothing on this side of the wire has met a head unit yet.
 */
class AudioLink(
    private val service: ServiceDiscovery.AudioService,
    private val sendOnChannel: (messageId: Int, body: ByteArray) -> Unit,
    private val sendControl: (messageId: Int, body: ByteArray) -> Unit,
) : CarAudio.Link {

    private var configIndex = 0
    @Volatile private var ready = false
    @Volatile private var started = false
    @Volatile private var focusGranted = false
    @Volatile private var focusRequested = false
    @Volatile private var inFlight = 0
    // The Uconnect asks for 4 on its video channel; assume the same until its Config says otherwise.
    @Volatile private var maxUnacked = 4
    @Volatile private var lastAckAt = 0L
    private var chunks = 0
    private var bytes = 0L
    private var dropped = 0
    @Volatile private var configTimer: Thread? = null
    private val ackLock = Object()

    private val config get() = service.configs.getOrNull(configIndex) ?: service.configs.firstOrNull()
    override val sampleRate get() = config?.sampleRate ?: 48_000
    override val channels get() = config?.channels ?: 2

    val channelId get() = service.serviceId

    fun opened(status: Int) {
        GearslipLog.i("audio: <- ChannelOpenResponse (media audio): status=$status")
        if (status != 0) {
            GearslipLog.w("audio: the head unit refused the media audio channel (status $status)")
            return
        }
        // AVChannelSetupRequest { required uint32 config_index = 1 }. This field is an index into
        // the sink's own audio_configs, not a codec id - sending the codec type asks for a config
        // the sink does not have, and it answers with a status that never reaches READY.
        sendOnChannel(MSG_SETUP, Protobuf.varintField(1, 0L))
        GearslipLog.i("audio: -> Setup(config_index=0) on channel ${service.serviceId}, codec ${service.codecName}")
        armConfigFallback()
    }

    /**
     * The 2018 Uconnect and a Dacia ULC 4.5 both answer our Setup with a two-byte message we
     * cannot identify (id 255, no body) rather than the AVChannelSetupResponse the video channel
     * gets, so the sink never reaches READY. [MSG_SETUP_ANSWER] takes that as the go-ahead.
     *
     * Service discovery already told us everything a Config would have: the sink's rate, channels
     * and its one configuration. So for a car that answers nothing at all, wait a moment and then
     * assume the advertised configuration. If that guess is wrong the head unit simply will not
     * ack, which the write path already reports.
     */
    private fun armConfigFallback() {
        val timer = Thread({
            try {
                Thread.sleep(CONFIG_WAIT_MS)
            } catch (e: InterruptedException) {
                return@Thread
            }
            useAdvertisedSink("no usable Config after ${CONFIG_WAIT_MS}ms")
        }, "gearslip-audio-config").apply { isDaemon = true }
        configTimer = timer
        timer.start()
    }

    @Synchronized
    private fun useAdvertisedSink(why: String) {
        if (ready) return
        configTimer?.interrupt()
        GearslipLog.i(
            "audio: $why - using the sink the head unit advertised " +
                "(${sampleRate}Hz x$channels, config_index=0, max_unacked=$maxUnacked)",
        )
        configIndex = 0
        ready = true
        CarAudio.attach(this)
    }

    fun onMessage(messageId: Int, body: ByteArray) {
        when (messageId) {
            MSG_CHANNEL_OPEN_RESPONSE -> opened(Protobuf.readInt32Field(body, 1) ?: 0)

            MSG_CONFIG -> {
                val fields = Wire.fields(body)
                val status = Wire.varint(fields, 1)?.toInt()
                val window = Wire.varint(fields, 2)?.toInt()
                val indices = fields.filter { it.number == 3 && it.wireType == 0 }.map { it.varint.toInt() }
                GearslipLog.i("audio: <- Config status=$status max_unacked=$window indices=$indices")
                if (window != null) maxUnacked = maxOf(1, window)
                configIndex = indices.firstOrNull() ?: 0
                if (status == STATUS_READY) {
                    configTimer?.interrupt()
                    ready = true
                    GearslipLog.i("audio: sink ready - ${sampleRate}Hz x$channels ${config?.bits}bit")
                    CarAudio.attach(this)
                } else {
                    GearslipLog.w(
                        "audio: the sink is not ready (status=$status, 1=WAIT 2=READY) - " +
                            "nothing will be sent until it is",
                    )
                }
            }

            MSG_ACK -> {
                val acked = Wire.varint(Wire.fields(body), 2)?.toInt() ?: 1
                synchronized(ackLock) {
                    inFlight = maxOf(0, inFlight - maxOf(1, acked))
                    lastAckAt = System.currentTimeMillis()
                    ackLock.notifyAll()
                }
            }

            // Whatever else the head unit answers Setup with, we need its bytes to identify it.
            else -> if (messageId == MSG_SETUP_ANSWER && body.isEmpty()) {
                useAdvertisedSink("<- empty Setup answer (id $messageId)")
            } else {
                GearslipLog.i("audio: <- unhandled message id=$messageId (${body.size} bytes)")
                GearslipLog.hex("   audio msg", body, limit = 64)
            }
        }
    }

    /**
     * The head unit's answer to our AudioFocusRequest. Until it grants focus the sink is wired to
     * whatever else the car is playing - the radio, usually - and anything we send is discarded,
     * so [start] waits here rather than streaming into nothing.
     */
    fun onFocus(state: Int) {
        val name = FOCUS_STATES[state] ?: "unknown($state)"
        GearslipLog.i("audio: <- AudioFocusResponse state=$name")
        focusGranted = state == FOCUS_STATE_GAIN || state == FOCUS_STATE_GAIN_TRANSIENT
        if (focusGranted) startStream() else stop()
    }

    override fun start() {
        if (!ready || started) return
        if (!focusRequested) {
            focusRequested = true
            sendControl(MSG_FOCUS_REQUEST, Protobuf.varintField(1, FOCUS_GAIN.toLong()))
            GearslipLog.i("audio: -> AudioFocusRequest(GAIN); waiting for the head unit to hand the sink over")
        }
        // Some head units grant the sink silently and never answer. Do not let that be the end of
        // it: start anyway, and let the acks say whether anything is being taken.
        startStream()
    }

    private fun startStream() {
        if (!ready || started) return
        sendOnChannel(
            MSG_START,
            Protobuf.varintField(1, SESSION_ID.toLong()) + Protobuf.varintField(2, configIndex.toLong()),
        )
        inFlight = 0
        lastAckAt = System.currentTimeMillis()
        started = true
        GearslipLog.i("audio: -> Start(session=$SESSION_ID, config=$configIndex) - streaming ${sampleRate}Hz x$channels")
    }

    override fun stop() {
        if (!started) return
        started = false
        synchronized(ackLock) { ackLock.notifyAll() }
        runCatching {
            sendOnChannel(MSG_STOP, Protobuf.varintField(1, SESSION_ID.toLong()))
            if (focusRequested) sendControl(MSG_FOCUS_REQUEST, Protobuf.varintField(1, FOCUS_RELEASE.toLong()))
        }
        focusRequested = false
        focusGranted = false
        GearslipLog.i("audio: -> Stop, AudioFocusRequest(RELEASE) after $chunks chunks / $bytes bytes")
        chunks = 0
        bytes = 0
    }

    override fun write(pcm: ByteArray, size: Int) {
        if (!started) return
        // Wait for the car to catch up rather than throw the chunk away: a dropped chunk is a gap
        // the driver hears, while a short wait is soaked up by the capture buffer behind us.
        synchronized(ackLock) {
            while (started && inFlight >= maxUnacked) {
                val waited = System.currentTimeMillis() - lastAckAt
                if (waited > STALL_MS) {
                    // A lost ack must not silence the stream for good; after a stall assume the window cleared.
                    GearslipLog.w("audio: no ack for ${STALL_MS}ms with $inFlight in flight - reopening the window")
                    inFlight = 0
                    break
                }
                ackLock.wait(STALL_MS - waited + 1)
            }
        }
        if (!started) {
            dropped++
            return
        }
        val timestamp = ByteArray(8)
        val micros = System.nanoTime() / 1_000
        for (i in 0 until 8) timestamp[i] = ((micros shr ((7 - i) * 8)) and 0xFF).toByte()
        try {
            sendOnChannel(MSG_DATA, timestamp + pcm.copyOf(size))
        } catch (e: java.io.IOException) {
            GearslipLog.e("audio: the link died mid-stream", e)
            started = false
            return
        }
        synchronized(ackLock) { inFlight++ }
        chunks++
        bytes += size
        // The first chunk proves the whole path; after that, an occasional line is enough to show
        // the car is still taking audio and to catch a window that has quietly closed.
        if (chunks == 1 || chunks % CHUNK_LOG_EVERY == 0) {
            GearslipLog.i(
                "audio: -> $chunks chunks / $bytes bytes, inFlight=$inFlight/$maxUnacked, " +
                    "dropped=$dropped, focus=${if (focusGranted) "granted" else "not granted"}",
            )
        }
    }

    fun close() {
        configTimer?.interrupt()
        configTimer = null
        stop()
        ready = false
        CarAudio.detach(this)
    }

    companion object {
        const val MSG_FOCUS_REQUEST = 18
        const val MSG_FOCUS_RESPONSE = 19
        private const val MSG_CHANNEL_OPEN_RESPONSE = 8
        private const val MSG_DATA = 0
        private const val MSG_SETUP = 32768
        private const val MSG_START = 32769
        private const val MSG_STOP = 32770
        private const val MSG_CONFIG = 32771
        private const val MSG_ACK = 32772
        private const val MSG_SETUP_ANSWER = 255
        private const val STATUS_READY = 2
        private const val FOCUS_GAIN = 1
        private const val FOCUS_RELEASE = 4
        private const val FOCUS_STATE_GAIN = 1
        private const val FOCUS_STATE_GAIN_TRANSIENT = 2
        private const val SESSION_ID = 2
        private const val STALL_MS = 2_000L
        private const val CHUNK_LOG_EVERY = 200 // about every four seconds
        private const val CONFIG_WAIT_MS = 1_000L

        private val FOCUS_STATES = mapOf(
            1 to "GAIN", 2 to "GAIN_TRANSIENT", 3 to "LOSS", 4 to "LOSS_TRANSIENT",
        )
    }
}
