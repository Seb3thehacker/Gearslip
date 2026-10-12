package app.seb3thehacker.gearslip

import android.os.Build
import app.seb3thehacker.gearslip.audio.AudioLink
import app.seb3thehacker.gearslip.audio.CarMic
import app.seb3thehacker.gearslip.media.CarMediaStatus
import app.seb3thehacker.gearslip.car.ClusterNav
import app.seb3thehacker.gearslip.car.CarAssistant
import app.seb3thehacker.gearslip.car.CarEnvironment
import app.seb3thehacker.gearslip.car.CarKeys
import app.seb3thehacker.gearslip.car.CarSensors
import app.seb3thehacker.gearslip.car.CarServices
import app.seb3thehacker.gearslip.car.CarSettings
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.FutureTask

/**
 * Drives the phone side of the control-channel handshake far enough to answer one question:
 * does this head unit accept a self-signed certificate from the phone?
 *
 * The head unit initiates (aasdk's ControlServiceChannel::sendVersionRequest), so until
 * step 5 this is purely reactive:
 *
 *   1  HU -> phone  VersionRequest          id 1  plain
 *   2  phone -> HU  VersionResponse         id 2  plain
 *   3  both         EncapsulatedSSL         id 3  plain     (repeats until TLS completes)
 *   4  HU -> phone  AuthComplete            id 4  plain     <- the answer
 *   5  phone -> HU  ServiceDiscoveryRequest id 5  ENCRYPTED
 *   6  HU -> phone  ServiceDiscoveryResponse id 6 ENCRYPTED <- the confirmation
 */
class GearslipRunner(
    private val input: InputStream,
    private val output: OutputStream,
    private val identityProvider: () -> CertProvider.Identity,
    private val projection: Projection? = null,
    private val vehicleProfileFor: (ServiceDiscovery.HeadUnitInfo) -> VehicleProfile? = { null },
) {
    private val log = GearslipLog.tagged("PROTO")

    private enum class State { WAIT_VERSION, TLS_HANDSHAKE, WAIT_AUTH, WAIT_SDR, DONE }

    private val parser = Frames.Parser()
    private val assembler = Frames.Assembler()
    private lateinit var identityTask: FutureTask<PhoneTls>

    /** Sends every message, in order, so no other thread ever blocks on the USB link. See [send]. */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "gearslip-writer") }

    /** Waits for the certificate only when TLS is first needed; see [run]. */
    private val tls: PhoneTls by lazy { identityTask.get() }
    private var authenticationFailed = false
    @Volatile private var stopped = false

    @Volatile private var state = State.WAIT_VERSION
    @Volatile private var lastProgress = System.currentTimeMillis()
    @Volatile private var running = true

    @Volatile private var videoChannelId: Int = -1
    private var videoConfigs: List<ServiceDiscovery.VideoConfig> = emptyList()
    private var selectedConfigIndex: Int? = null
    private var vehicleProfile: VehicleProfile? = null
    @Volatile private var videoStarted = false
    private var videoSource: VideoSource? = null
    @Volatile private var inFlight = 0
    /** Guards [inFlight]: the encoder thread waits on it for the head unit's acks. */
    private val ackLock = Object()
    /** Time the encoder spent waiting for acks since the last stats line. */
    @Volatile private var ackWaitMs = 0L
    @Volatile private var maxUnacked = 4
    @Volatile private var framesSent = 0
    @Volatile private var inputChannelId: Int = -1
    @Volatile private var inputKeycodes: List<Int> = emptyList()
    @Volatile private var sensorChannelId: Int = -1
    @Volatile private var mediaStatus: CarMediaStatus? = null
    @Volatile private var clusterNav: ClusterNav? = null
    private var sensorTypes: List<Int> = emptyList()
    @Volatile private var audioLink: AudioLink? = null
    private var audioMessagesSeen = 0
    private var touchWidth = 0
    private var touchHeight = 0
    private var touchesSeen = 0
    @Volatile private var acksSeen = 0
    @Volatile private var lastAckAt = 0L
    @Volatile private var splitMessages = 0
    @Volatile private var resyncRequested = false
    @Volatile private var resyncAttempts = 0
    @Volatile private var lastKeyframeSentAt = 0L

    fun run() {
        SessionReport.begin()
        log.i("--- session starting ---")
        SessionStatus.connecting("Connected to the head unit")
        log.i("device=${Build.MANUFACTURER} ${Build.MODEL}  android=${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")

        // The head unit speaks first, and some give up if the phone doesn't read within about a
        // second. The version exchange needs no certificate, so read at once and unlock the
        // certificate alongside; the TLS handshake waits for it.
        identityTask = FutureTask {
            val identity = identityProvider()
            val cert = identity.certificate
            log.i("phone certificate source: ${identity.source}")
            SessionReport.certificate(identity.source.toString(), cert.issuerX500Principal.toString())
            log.i("  subject = ${cert.subjectX500Principal}")
            log.i("  issuer  = ${cert.issuerX500Principal}")
            log.i("  serial  = ${cert.serialNumber}  valid ${cert.notBefore}..${cert.notAfter}")
            PhoneTls(identity.keyStore, identity.password)
        }.also { Thread(it, "gearslip-identity").start() }

        startWatchdog()

        val chunk = ByteArray(16 * 1024) // AOAP: one read returns one bulk transfer
        try {
            while (running) {
                val read = input.read(chunk)
                if (stopped) return
                if (read < 0) {
                    log.w("head unit closed the connection (EOF) while in state $state")
                    reportDisconnect()
                    return
                }
                if (read == 0) continue
                parser.append(chunk, read)
                while (true) {
                    val frame = parser.next() ?: break
                    handleFrame(frame)
                }
            }
        } catch (t: Throwable) {
            if (stopped) return
            log.e("transport failed in state $state", t)
            SessionReport.fail(SessionReport.Category.USB, "transport failed", t, state.name)
            reportDisconnect()
        } finally {
            running = false
            writer.shutdownNow()
            log.flush()
        }
    }

    fun stop() {
        stopped = true
        running = false
        writer.shutdownNow()
        CarFocus.request = null
        CarFocus.set(true)
        synchronized(ackLock) { ackLock.notifyAll() } // free an encoder waiting on an ack
        videoSource?.stop()
        videoSource = null
        audioLink?.close()
        audioLink = null
        CarSensors.clear()
        CarMic.current = null
        mediaStatus?.stop()
        mediaStatus = null
        clusterNav?.stop()
        clusterNav = null
        lastSensorNight = null
        CarEnvironment.setSensorNight(null) // back to the clock until a car says otherwise
        projection?.onProjectionStopped()
        SessionStatus.disconnected()
    }

    private fun handleFrame(frame: Frames.Frame) {
        // A rejected handshake cannot consume later messages as if authentication succeeded.
        if (authenticationFailed || stopped) return
        val payload = if (frame.encrypted) {
            if (!tls.handshakeComplete) {
                log.w("encrypted frame arrived before the handshake finished - ignoring")
                return
            }
            tls.decrypt(frame.payload)
        } else {
            frame.payload
        }

        val message = assembler.offer(frame, payload) ?: return
        if (message.size < 2) {
            log.w("runt message on channel ${frame.channel}")
            return
        }

        val messageId = ((message[0].toInt() and 0xFF) shl 8) or (message[1].toInt() and 0xFF)
        val body = message.copyOfRange(2, message.size)

        lastProgress = System.currentTimeMillis()

        if (frame.channel != Frames.CHANNEL_CONTROL) {
            if (frame.channel == videoChannelId) {
                onVideoMessage(messageId, body)
            } else if (frame.channel == inputChannelId) {
                onInputMessage(messageId, body)
            } else if (frame.channel == sensorChannelId) {
                onSensorMessage(messageId, body)
            } else if (frame.channel == clusterNav?.channelId) {
                clusterNav?.onMessage(messageId, body)
            } else if (frame.channel == mediaStatus?.channelId) {
                mediaStatus?.onMessage(messageId, body)
            } else if (frame.channel == CarMic.current?.channelId) {
                CarMic.current?.onMessage(messageId, body)
            } else if (frame.channel == audioLink?.channelId) {
                // The audio channel is the one still being reverse-engineered, and its first few
                // replies are what tell us why. Raw bytes for those, then quiet.
                if (audioMessagesSeen < AUDIO_TRACE_MESSAGES) {
                    audioMessagesSeen++
                    log.i(
                        "audio frame #$audioMessagesSeen: channel=${frame.channel} " +
                            "encrypted=${frame.encrypted} raw=${frame.payload.size}B decrypted=${message.size}B",
                    )
                    log.hex("   audio raw", message, limit = 64)
                }
                audioLink?.onMessage(messageId, body)
            } else {
                log.i("ignoring message id $messageId on channel ${frame.channel}")
            }
            return
        }

        when (messageId) {
            MSG_VERSION_REQUEST -> onVersionRequest(body)
            MSG_ENCAPSULATED_SSL -> onHandshake(body)
            MSG_AUTH_COMPLETE -> onAuthComplete(body)
            MSG_SERVICE_DISCOVERY_RESPONSE -> onServiceDiscoveryResponse(body)
            MSG_PING_REQUEST -> onPingRequest(body)
            MSG_BYEBYE_REQUEST -> onByeByeRequest(body)
            AudioLink.MSG_FOCUS_RESPONSE -> audioLink?.onFocus(Protobuf.readInt32Field(body, 1) ?: 0)
            else -> log.i("unhandled control message id=$messageId (${body.size} bytes) - continuing")
        }
    }

    /**
     * The head unit asking to end the session. It drops USB a few seconds later whether or not
     * we answer, so the point here is the reason: an Audi MIB2+ sent this about a second after
     * video started, every time, and the old build logged it only as "id=15".
     */
    private fun onByeByeRequest(body: ByteArray) {
        val reason = Protobuf.readInt32Field(body, 1)
        val name = when (reason) {
            1 -> "USER_SELECTION"
            2 -> "DEVICE_SWITCH"
            3 -> "NOT_SUPPORTED"
            4 -> "NOT_CURRENTLY_SUPPORTED"
            5 -> "PROBE_SUPPORTED"
            else -> "unknown"
        }
        log.w("<- ByeByeRequest: reason=$reason ($name) in state $state")
        log.hex("   byebye raw", body)
        SessionReport.fail(SessionReport.Category.BYEBYE, "ByeByeRequest reason=$reason ($name)", state.name, "bye $reason")
        send(MSG_BYEBYE_RESPONSE, ByteArray(0), encrypted = true)
        log.i("-> ByeByeResponse")
    }

    private fun onVersionRequest(body: ByteArray) {
        if (body.size < 4) {
            log.w("VersionRequest too short (${body.size} bytes)")
            return
        }
        val major = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        val minor = ((body[2].toInt() and 0xFF) shl 8) or (body[3].toInt() and 0xFF)
        log.i("<- VersionRequest: head unit speaks $major.$minor")
        SessionReport.protocolVersion(major, minor)

        // PROTOCOL-DOWNGRADE EXPERIMENT. The 2018 Uconnect speaks 1.3 and ACCEPTS the JVC cert;
        // the 2025 unit speaks 4.3 and rejects it at AuthComplete (-3). If the 4.3 identity check
        // is tied to the *negotiated* protocol version rather than baked into the firmware,
        // claiming an older version makes the newer unit skip the check entirely. CLAIM_VERSION
        // = null restores the previous echo-the-head-unit behaviour.
        val claim = CLAIM_VERSION ?: (major to minor)
        val response = byteArrayOf(
            ((claim.first shr 8) and 0xFF).toByte(), (claim.first and 0xFF).toByte(),
            ((claim.second shr 8) and 0xFF).toByte(), (claim.second and 0xFF).toByte(),
            0, 0, // STATUS_SUCCESS
        )
        send(MSG_VERSION_RESPONSE, response, encrypted = false)
        log.i(
            "-> VersionResponse: ${claim.first}.${claim.second} status=0 (STATUS_SUCCESS)" +
                if (CLAIM_VERSION != null) " [FORCED; head unit offered $major.$minor]" else "",
        )
        state = State.TLS_HANDSHAKE
        SessionStatus.connecting("Securing the link")
        log.i("awaiting ClientHello - the head unit is the TLS client, we are the server")
    }

    private fun onHandshake(body: ByteArray) {
        if (state != State.TLS_HANDSHAKE && state != State.WAIT_AUTH) return
        log.i("<- EncapsulatedSSL (${body.size} bytes)")
        val replies = try {
            val preparedTls = tls
            if (stopped) return
            preparedTls.pumpHandshake(body)
        } catch (t: Throwable) {
            if (stopped) return
            log.e("TLS handshake threw", t)
            SessionReport.fail(SessionReport.Category.TLS, "handshake threw", t, state.name)
            log.verdict(
                "NOT VIABLE (TLS rejected)",
                "The head unit aborted the TLS handshake. A bad_certificate / unknown_ca / " +
                    "handshake_failure alert here means it validates the phone's certificate.",
            )
            SessionStatus.failed(
                "Head unit rejected the certificate",
                "The head unit aborted the secure handshake. It validates the phone's certificate.",
            )
            state = State.DONE
            authenticationFailed = true
            SessionReport.print()
            return
        }
        if (stopped) return
        replies.forEach {
            send(MSG_ENCAPSULATED_SSL, it, encrypted = false)
            log.i("-> EncapsulatedSSL (${it.size} bytes)")
        }
        if (tls.handshakeComplete && state == State.TLS_HANDSHAKE) {
            state = State.WAIT_AUTH
            log.i("awaiting AuthComplete - this carries the certificate verdict")
        }
    }

    private fun onAuthComplete(body: ByteArray) {
        val status = Protobuf.readInt32Field(body, 1)
        if (state != State.WAIT_AUTH && !(state == State.TLS_HANDSHAKE && status in listOf(-2, -3))) return
        log.i("<- AuthComplete: status=$status")
        status?.let { SessionReport.auth(it) }

        when (status) {
            0 -> {
                log.i("STATUS_SUCCESS - certificate accepted; confirming with service discovery")
                state = State.WAIT_SDR
                SessionStatus.connecting("Certificate accepted - discovering services")
                sendServiceDiscoveryRequest()
            }
            -2 -> {
                SessionReport.fail(SessionReport.Category.CERTIFICATE, "AuthComplete status -2 (certificate error)", state.name, "auth -2")
                log.verdict(
                    "NOT VIABLE (STATUS_CERTIFICATE_ERROR)",
                    "The head unit explicitly rejected the phone's self-signed certificate. " +
                        "This is a definitive no - it validates the phone against a trust chain " +
                        "we cannot issue from.",
                )
                SessionStatus.failed(
                    "Head unit rejected the certificate",
                    "The head unit refused the phone's certificate (certificate error).",
                )
                state = State.DONE
                authenticationFailed = true
            }
            -3 -> {
                SessionReport.fail(SessionReport.Category.AUTH, "AuthComplete status -3 (authentication failure)", state.name, "auth -3")
                log.verdict(
                    "NOT VIABLE (STATUS_AUTHENTICATION_FAILURE)",
                    "Authentication rejected. Broader than a pure certificate error, but the " +
                        "practical answer is the same.",
                )
                SessionStatus.failed(
                    "Head unit rejected the certificate",
                    "Authentication failed. The head unit does not trust the certificate in use.",
                )
                state = State.DONE
                authenticationFailed = true
            }
            else -> {
                SessionReport.fail(SessionReport.Category.AUTH, "AuthComplete status $status (unexpected)", state.name, "auth $status")
                log.verdict(
                    "INCONCLUSIVE (AuthComplete status=$status)",
                    "Unexpected status. Check MessageStatus.proto in aasdk for the meaning " +
                        "before drawing any conclusion.",
                )
                SessionStatus.failed("Unexpected response", "The head unit answered authentication with status $status.")
                state = State.DONE
                authenticationFailed = true
            }
        }
    }

    private fun sendServiceDiscoveryRequest() {
        val body = Protobuf.stringField(4, "Gearslip") + // label_text
            Protobuf.stringField(5, Build.MODEL)        // device_name
        send(MSG_SERVICE_DISCOVERY_REQUEST, body, encrypted = true)
        log.i("-> ServiceDiscoveryRequest (encrypted)")
    }

    private fun onServiceDiscoveryResponse(body: ByteArray) {
        log.i("<- ServiceDiscoveryResponse (${body.size} bytes, decrypted successfully)")
        // Field 5 is the car's serial number, and the Bluetooth service carries the car's
        // Bluetooth address. Either one identifies the car, and logs get shared, so both stay out.
        val described = Protobuf.describe(body)
            .replace(Regex("(?m)^  #5 string = .*$"), "  #5 string = (car serial, left out)")
            .replace(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"), "(Bluetooth address, left out)")
        log.i("head unit describes itself as:\n" + described)
        log.verdict(
            "VIABLE",
            "The head unit accepted the presented phone certificate, completed TLS, and is " +
                "advertising its channels over the encrypted session. A phone-side client is " +
                "possible against THIS unit - portability to newer units is still unproven.",
        )
        state = State.DONE
        val info = ServiceDiscovery.findHeadUnitInfo(body)
        log.i("head unit: $info")
        SessionReport.headUnit(info.toString())
        SessionReport.headUnitInfo = info
        vehicleProfile = runCatching { vehicleProfileFor(info) }.getOrNull()
        val profile = vehicleProfile
        SessionReport.profile(profile?.name)
        if (profile != null) {
            log.i("vehicle profile matched: \"${profile.name}\" resolution=${profile.resolution} insets=${profile.insets}")
        } else {
            log.i("no vehicle profile matched - default display settings (add one to vehicles.json)")
        }
        CarEnvironment.setVehicle(profile?.name ?: "Unknown vehicle", profile?.insets ?: Insets.NONE)
        SessionStatus.connecting("Starting video")

        startVideoChannel(body)
    }

    // --- Phase A: video channel bring-up (no encoder yet) -------------------------------
    //
    // Inverting aasdk's head-unit video sink (src/Channel/MediaSink/Video/
    // VideoMediaSinkService.cpp): every message that sink *receives* is one we must *send*.
    //
    //   -> CHANNEL_OPEN_REQUEST   <- CHANNEL_OPEN_RESPONSE
    //   -> SETUP                  <- CONFIG   (status, max_unacked, configuration_indices)
    //   -> VIDEO_FOCUS_REQUEST    <- VIDEO_FOCUS_NOTIFICATION
    //
    // Reaching VIDEO_FOCUS_NOTIFICATION means the head unit is waiting for frames, which is
    // the Phase A milestone. Phase B then sends CODEC_CONFIG + DATA.

    private fun startVideoChannel(serviceDiscoveryResponse: ByteArray) {
        val video = ServiceDiscovery.findVideoService(serviceDiscoveryResponse)
        if (video == null) {
            log.w("no video service in the discovery response - cannot start Phase A")
            SessionReport.fail(SessionReport.Category.VIDEO, "head unit advertised no video service", state.name, "no video service")
            return
        }
        videoChannelId = video.serviceId
        videoConfigs = video.configs

        log.i("video service: channel=${video.serviceId} codec=${video.codecName} display=${video.displayId}")
        video.configs.forEachIndexed { index, config ->
            log.i("  config[$index] = $config")
        }
        if (video.codecType != CODEC_H264_BP) {
            log.w("head unit wants ${video.codecName}, not H264_BP - Phase B must match this")
        }

        openChannel(video.serviceId)
        startAudioChannel(serviceDiscoveryResponse)
        startSensorChannel(serviceDiscoveryResponse)
        startMicChannel(serviceDiscoveryResponse)
        startMediaStatusChannel(serviceDiscoveryResponse)
        startClusterNavChannel(serviceDiscoveryResponse)

        val input = ServiceDiscovery.findInputService(serviceDiscoveryResponse, video.displayId)
        if (input == null) {
            log.w("no input service advertised - projection will be output-only")
        } else {
            inputChannelId = input.serviceId
            inputKeycodes = input.keycodesSupported
            touchWidth = input.touchWidth
            touchHeight = input.touchHeight
            log.i(
                "input service: channel=${input.serviceId} touchscreen=" +
                    "${input.touchWidth}x${input.touchHeight} keycodes=${input.keycodesSupported}",
            )
            openChannel(input.serviceId)
        }
    }

    /** Media audio rides its own sink; guidance, system and telephony sinks are left closed. */
    private fun startAudioChannel(serviceDiscoveryResponse: ByteArray) {
        val sinks = ServiceDiscovery.findAudioServices(serviceDiscoveryResponse)
        sinks.forEach { sink ->
            log.i(
                "audio sink: channel=${sink.serviceId} stream=${sink.streamName} codec=${sink.codecName} " +
                    sink.configs.joinToString(prefix = "[", postfix = "]") { "${it.sampleRate}Hz/${it.bits}bit/x${it.channels}" },
            )
        }
        val media = ServiceDiscovery.pickMediaSink(sinks) ?: run {
            log.w("no media audio sink advertised - media apps will play on the phone only")
            SessionReport.audio(
                "no MEDIA sink; head unit offered ${sinks.joinToString { it.streamName }.ifEmpty { "none" }}",
            )
            return
        }
        if (media.codecType != ServiceDiscovery.CODEC_PCM) {
            log.w("the car offers no PCM media sink, only ${media.codecName}; it may play our PCM as silence")
        }
        val first = media.configs.firstOrNull()
        SessionReport.audio(
            "MEDIA sink on channel ${media.serviceId}, ${media.codecName}, " +
                "${first?.sampleRate ?: 48_000}Hz/${first?.bits ?: 16}bit/x${first?.channels ?: 2}",
        )
        audioLink = AudioLink(
            media,
            sendOnChannel = { id, body -> send(id, body, encrypted = true, channel = media.serviceId) },
            sendControl = { id, body -> send(id, body, encrypted = true) },
        )
        openChannel(media.serviceId)
    }

    /** The car's microphone, for voice: opened now, recording only while something listens. */
    private fun startMicChannel(serviceDiscoveryResponse: ByteArray) {
        val mic = ServiceDiscovery.findMicService(serviceDiscoveryResponse) ?: run {
            log.i("no microphone advertised - voice uses the phone's")
            return
        }
        val config = mic.configs.first()
        log.i("car microphone: channel=${mic.serviceId} ${mic.codecName} ${config.sampleRate}Hz/${config.bits}bit/x${config.channels}")
        CarMic.current = CarMic(mic) { id, body -> send(id, body, encrypted = true, channel = mic.serviceId) }
        openChannel(mic.serviceId)
    }

    /** What's playing, for the car's own screens: the cluster, the media source page. */
    private fun startMediaStatusChannel(serviceDiscoveryResponse: ByteArray) {
        val channel = ServiceDiscovery.findMediaStatusChannel(serviceDiscoveryResponse) ?: run {
            log.i("no media status channel - the car's own screens won't show what's playing")
            return
        }
        log.i("media status channel: $channel")
        mediaStatus = CarMediaStatus(channel) { id, body -> send(id, body, encrypted = true, channel = channel) }
        openChannel(channel)
    }

    /** Turn-by-turn for the instrument cluster: the next turn, its road, and how far. */
    private fun startClusterNavChannel(serviceDiscoveryResponse: ByteArray) {
        val channel = ServiceDiscovery.findNavStatusChannel(serviceDiscoveryResponse) ?: run {
            log.i("no navigation status channel - the cluster won't show turns")
            return
        }
        log.i("navigation status channel: $channel")
        clusterNav = ClusterNav(channel) { id, body -> send(id, body, encrypted = true, channel = channel) }
        openChannel(channel)
    }

    /**
     * Every sensor type the head unit advertised, subscribed to at once - there is no screen
     * to pick and choose yet, this is meant to show everything reachable. [CarSensors] answers
     * every request with "unsupported" today per BUILDING_APPS.md's line to third-party apps, but
     * that's a promise made to apps Gearslip hosts, not to what Gearslip itself may ask a real
     * head unit for over this same channel.
     */
    private fun startSensorChannel(serviceDiscoveryResponse: ByteArray) {
        val sensors = ServiceDiscovery.findSensorService(serviceDiscoveryResponse)
        if (sensors == null) {
            log.i("no sensor service advertised - this head unit offers no vehicle data channel")
            return
        }
        sensorChannelId = sensors.serviceId
        sensorTypes = sensors.types
        log.i("sensor service: channel=${sensors.serviceId} types=${sensors.types}")
        CarSensors.onDiscovered(sensors.types)
        openChannel(sensors.serviceId)
    }

    /**
     * The car may stream sensors many times a second, on the same thread that reads its audio and
     * video messages. Log each type's first reading in full, then only a count every
     * [SENSOR_LOG_EVERY_MS], so the log can never hold that thread up.
     */
    private val sensorTypesLogged = HashSet<Int>()
    private var sensorEventsSinceLog = 0
    private var sensorLoggedAt = 0L

    private fun logSensorEvent(body: ByteArray) {
        val types = Wire.fields(body).map { it.number }.toSet()
        if (sensorTypesLogged.addAll(types)) {
            // Type 1 is the car's GPS position; logs get shared, so say it arrived and nothing more.
            if (SENSOR_LOCATION in types) log.i("<- SensorEvent (first of its kind): types=$types, location left out")
            else log.i("<- SensorEvent (first of its kind):\n" + Protobuf.describe(body))
        }
        sensorEventsSinceLog++
        val now = System.currentTimeMillis()
        if (sensorLoggedAt == 0L) sensorLoggedAt = now
        if (now - sensorLoggedAt >= SENSOR_LOG_EVERY_MS) {
            log.i("<- $sensorEventsSinceLog sensor events in the last ${(now - sensorLoggedAt) / 1000}s")
            sensorEventsSinceLog = 0
            sensorLoggedAt = now
        }
    }

    /**
     * The car's own day/night signal (its headlights or light sensor): NightData { bool is_night = 1; }.
     * It replaces the clock guess, so maps go dark when the car does.
     */
    private fun onNightSensor(body: ByteArray) {
        val night = Wire.bytes(Wire.fields(body), SENSOR_NIGHT) ?: return
        val isNight = Wire.varint(Wire.fields(night), 1) == 1L
        if (isNight != lastSensorNight) {
            lastSensorNight = isNight
            log.i("car says it's ${if (isNight) "night" else "day"}")
            CarEnvironment.setSensorNight(isNight)
        }
    }

    private var lastSensorNight: Boolean? = null

    private fun onSensorMessage(messageId: Int, body: ByteArray) {
        when (messageId) {
            MSG_CHANNEL_OPEN_RESPONSE -> {
                val status = Protobuf.readInt32Field(body, 1)
                log.i("<- ChannelOpenResponse (sensor): status=$status")
                if (status != 0) {
                    log.w("head unit refused the sensor channel (status=$status)")
                    return
                }
                // SensorRequest { required SensorType type = 1; required int64 min_update_period = 2; }
                // A period of 0 asks for every update the car sends.
                sensorTypes.forEach { type ->
                    send(
                        MSG_SENSOR_START_REQUEST,
                        Protobuf.varintField(1, type.toLong()) + Protobuf.varintField(2, 0L),
                        encrypted = true, channel = sensorChannelId,
                    )
                    log.i("-> SensorStartRequest(sensor_type=$type)")
                }
            }

            MSG_SENSOR_START_RESPONSE ->
                log.i("<- SensorStartResponse: status=${Protobuf.readInt32Field(body, 1)}")

            MSG_SENSOR_EVENT_INDICATION -> {
                CarSensors.onEvent(body)
                logSensorEvent(body)
                onNightSensor(body)
            }

            // SensorError { required SensorType sensor_type = 1; required SensorErrorType sensor_error_type = 2; }
            MSG_SENSOR_ERROR -> log.w(
                "<- SensorError: type=${Protobuf.readInt32Field(body, 1)} error=${Protobuf.readInt32Field(body, 2)}",
            )

            else -> log.i("<- unhandled sensor message id=$messageId (${body.size} bytes)")
        }
    }

    private fun openChannel(serviceId: Int) {
        val request = Protobuf.varintField(1, 0L) +          // priority (sint32 zigzag: 0 -> 0)
            Protobuf.varintField(2, serviceId.toLong())      // service_id
        send(
            MSG_CHANNEL_OPEN_REQUEST, request, encrypted = true,
            channel = serviceId, messageType = Frames.MESSAGE_CONTROL,
        )
        log.i("-> ChannelOpenRequest(service_id=$serviceId) on channel $serviceId")
    }

    /**
     * Touch events arrive **already in projected video coordinates**, not in the head unit's
     * native touchscreen space.
     *
     * Confirmed on the 2018 Uconnect: its panel is advertised as 1258x708, but with 800x480
     * projected the raw x never exceeded 791 and raw y never exceeded ~351 even when tapping
     * the far edges. Scaling by touchscreen/video (as an earlier version did) compressed the
     * right-hand third of the screen away entirely - taps on a right-edge button landed on
     * the middle one. The head unit maps its own panel onto our video before reporting.
     *
     * So: inject as-is, clamped to the video bounds. The advertised touchscreen size is kept
     * only for diagnostics.
     */
    private fun onInputMessage(messageId: Int, body: ByteArray) {
        when (messageId) {
            MSG_CHANNEL_OPEN_RESPONSE -> {
                val status = Protobuf.readInt32Field(body, 1)
                log.i("<- ChannelOpenResponse (input): status=$status")
                if (status == 0) sendKeyBinding()
            }

            MSG_KEY_BINDING_RESPONSE ->
                log.i("<- KeyBindingResponse: status=${Protobuf.readInt32Field(body, 1)}")

            MSG_INPUT_REPORT -> {
                val report = Wire.fields(body)
                // The 2018 Uconnect agrees to Exit but keeps showing our last frame and sending
                // touches, which looked like a crash. A touch means our screen is still up there.
                if (videoStarted && !videoProjected && Wire.bytes(report, 3) != null) {
                    log.w("the car said it took its screen back but still sends touches - showing ours again")
                    onVideoFocus(VIDEO_FOCUS_PROJECTED)
                }
                Wire.bytes(report, 4)?.let(::onKeyEvent)
                Wire.bytes(report, 6)?.let(::onRelativeEvent)
                val touch = Wire.bytes(report, 3) ?: Wire.bytes(report, 7)
                if (touch == null) {
                    return
                }
                val touchFields = Wire.fields(touch)
                val action = Wire.varint(touchFields, 3)?.toInt() ?: 0
                val pointers = Wire.allBytes(touchFields, 1)
                if (pointers.isEmpty()) return
                val size = videoConfigs.getOrNull(selectedConfigIndex ?: 0)?.pixelSize()
                val points = pointers.mapIndexedNotNull { index, pointer ->
                    val fields = Wire.fields(pointer)
                    val rawX = Wire.varint(fields, 1)?.toInt() ?: return@mapIndexedNotNull null
                    val rawY = Wire.varint(fields, 2)?.toInt() ?: return@mapIndexedNotNull null
                    val id = Wire.varint(fields, 3)?.toInt() ?: index
                    TouchPoint(id, clamp(rawX, size?.first), clamp(rawY, size?.second))
                }
                if (points.isEmpty()) return
                val actionIndex = (Wire.varint(touchFields, 2)?.toInt() ?: 0).coerceIn(0, points.size - 1)
                if (points.size > 1 && touchesSeen < 3) {
                    log.i("multi-touch: ${points.size} pointers, action=$action index=$actionIndex")
                }

                touchesSeen++
                if (touchesSeen <= 10 || action == ACTION_DOWN || action == ACTION_UP) {
                    val at = points[actionIndex]
                    log.i("<- Touch action=$action at (${at.x.toInt()},${at.y.toInt()}) pointers=${points.size}")
                }
                // Time the next frame against this tap: see [reportTouchLatency].
                if (action == ACTION_DOWN || action == ACTION_UP) touchAtUs = System.nanoTime() / 1000
                projection?.onTouch(action, actionIndex, points)
            }

            else -> log.i("<- unhandled input message id=$messageId (${body.size} bytes)")
        }
    }

    /**
     * Asks the head unit to forward its buttons. Many units send nothing from the steering wheel
     * until the phone names the keys it wants. What the unit offered is asked for; a unit that
     * offered nothing gets [CarKeys.WANTED], since some leave the list empty and send keys anyway.
     *
     *   KeyBindingRequest { repeated int32 keycodes = 1 [packed = true]; }
     */
    private fun sendKeyBinding() {
        val keycodes = inputKeycodes.ifEmpty { CarKeys.WANTED }
        val packed = keycodes.fold(ByteArray(0)) { acc, code -> acc + Protobuf.varint(code.toLong()) }
        val body = Protobuf.varint(((1 shl 3) or 2).toLong()) + Protobuf.varint(packed.size.toLong()) + packed
        send(MSG_KEY_BINDING_REQUEST, body, encrypted = true, channel = inputChannelId)
        log.i("-> KeyBindingRequest(keycodes=$keycodes)")
    }

    /**
     * Steering-wheel and head-unit buttons: InputReport.key_event (field 4), shaped as
     *   KeyEvent { repeated Key keys = 1; }
     *   Key { keycode = 1; down = 2; metastate = 3; longpress = 4; }
     *
     * The codes are android.view.KeyEvent's; [CarKeys] decides what each one does.
     */
    private fun onKeyEvent(keyEvent: ByteArray) {
        for (key in Wire.allBytes(Wire.fields(keyEvent), 1)) {
            val fields = Wire.fields(key)
            val keycode = Wire.varint(fields, 1)?.toInt() ?: continue
            val down = Wire.varint(fields, 2) == 1L
            val longPress = Wire.varint(fields, 4) == 1L
            log.i("<- key event: keycode=$keycode ${if (down) "down" else "up"}${if (longPress) " long" else ""}")
            if (!CarKeys.onKey(keycode, down, longPress) && down) log.i("unhandled keycode=$keycode")
        }
    }

    /**
     * The rotary knob: InputReport.relative_event (field 6), shaped as
     *   RelativeEvent { repeated Rel data = 1; }  Rel { keycode = 1; delta = 2 (int32); }
     */
    private fun onRelativeEvent(event: ByteArray) {
        for (rel in Wire.allBytes(Wire.fields(event), 1)) {
            val fields = Wire.fields(rel)
            val keycode = Wire.varint(fields, 1)?.toInt() ?: continue
            // int32, so a turn the other way arrives as a 64-bit two's complement varint.
            val delta = Wire.varint(fields, 2)?.toInt() ?: continue
            log.i("<- relative event: keycode=$keycode delta=$delta")
            CarKeys.onRotate(delta)
        }
    }

    private fun onVideoMessage(messageId: Int, body: ByteArray) {
        when (messageId) {
            MSG_CHANNEL_OPEN_RESPONSE -> {
                val status = Protobuf.readInt32Field(body, 1)
                log.i("<- ChannelOpenResponse: status=$status")
                if (status != 0) {
                    log.verdict(
                        "PHASE A FAILED (channel open rejected)",
                        "The head unit refused to open the video channel (status=$status).",
                    )
                    SessionStatus.failed("Video channel refused", "The head unit would not open the video channel (status $status).")
                    return
                }
                // Setup { required MediaCodecType type = 1 }
                send(MSG_MEDIA_SETUP, Protobuf.varintField(1, CODEC_H264_BP.toLong()),
                    encrypted = true, channel = videoChannelId)
                log.i("-> Setup(codec=VIDEO_H264_BP)")
            }

            MSG_MEDIA_CONFIG -> {
                val fields = Wire.fields(body)
                val status = Wire.varint(fields, 1)?.toInt()
                val maxUnackedFromConfig = Wire.varint(fields, 2)?.toInt()
                val indices = fields.filter { it.number == 3 && it.wireType == 0 }.map { it.varint }
                log.i(
                    "<- Config: status=$status (1=WAIT 2=READY) max_unacked=$maxUnackedFromConfig " +
                        "configuration_indices=$indices",
                )
                if (maxUnackedFromConfig != null) maxUnacked = maxOf(1, maxUnackedFromConfig)
                val offered = indices.map { it.toInt() }
                val preferred = vehicleProfile?.resolution?.let { want ->
                    offered.firstOrNull { videoConfigs.getOrNull(it)?.resolutionName == want }
                }
                selectedConfigIndex = preferred ?: fitToScreen(offered) ?: offered.firstOrNull()
                log.i("selected config index $selectedConfigIndex from offered $offered")
                SessionReport.video(
                    "${videoConfigs.getOrNull(selectedConfigIndex ?: -1)?.resolutionName ?: "?"} " +
                        "(head unit offered ${offered.size}), max_unacked=$maxUnacked",
                )
                // VideoFocusRequestNotification { mode = 2; reason = 3 }
                val focus = Protobuf.varintField(2, VIDEO_FOCUS_PROJECTED.toLong()) +
                    Protobuf.varintField(3, 1L)
                send(MSG_VIDEO_FOCUS_REQUEST, focus, encrypted = true, channel = videoChannelId)
                log.i("-> VideoFocusRequest(mode=PROJECTED)")
            }

            MSG_VIDEO_FOCUS_NOTIFICATION -> {
                // VideoFocusNotification { focus = 1; unsolicited = 2 }
                val fields = Wire.fields(body)
                val mode = Wire.varint(fields, 1)?.toInt() ?: VIDEO_FOCUS_PROJECTED
                log.i("<- VideoFocusNotification: mode=$mode unsolicited=${Wire.varint(fields, 2)}")
                onVideoFocus(mode)
            }

            // Not in aasdk's direction of travel, but LIVI sends it: log what it asks for.
            MSG_VIDEO_FOCUS_REQUEST -> {
                val fields = Wire.fields(body)
                log.i("<- VideoFocusRequest from the head unit: mode=${Wire.varint(fields, 2)} reason=${Wire.varint(fields, 3)}")
            }

            MSG_MEDIA_ACK -> {
                val fields = Wire.fields(body)
                val acked = Wire.varint(fields, 2)?.toInt() ?: 1
                synchronized(ackLock) {
                    inFlight = maxOf(0, inFlight - maxOf(1, acked))
                    acksSeen++
                    lastAckAt = System.currentTimeMillis()
                    ackLock.notifyAll()
                }
                if (framesSent <= 3 || framesSent % 60 == 0) {
                    log.i("<- MediaAck(ack=$acked) inFlight=$inFlight after $framesSent frames")
                }
            }

            else -> log.i("<- unhandled video message id=$messageId (${body.size} bytes)")
        }
    }

    // --- Phase B: encode and stream H.264 ----------------------------------------------
    //
    //   -> Start { session_id, configuration_index }
    //   -> CodecConfig (raw SPS/PPS, NO timestamp - aasdk routes it to onMediaIndication)
    //   -> Data (8-byte big-endian timestamp, then the H.264 access unit)
    //   <- Ack { session_id, ack }   - at most max_unacked frames may be outstanding

    /**
     * The offered config that best fills the car's screen: the smallest whose visible width
     * reaches most of the touchscreen's, else the largest there is. Always taking the smallest
     * put 800x480 on a 1540x720 Audi screen and stretched it. With no touchscreen size to go
     * by, the smallest is still the safe choice.
     */
    private fun fitToScreen(offered: List<Int>): Int? {
        val known = offered.filter { videoConfigs.getOrNull(it)?.pixelSize() != null }
        fun pixels(idx: Int) = videoConfigs[idx].pixelCount()
        if (touchWidth <= 0) return known.minByOrNull(::pixels)
        val wide = known.filter { (videoConfigs[it].visibleSize()?.first ?: 0) >= touchWidth * 8 / 10 }
        return wide.minByOrNull(::pixels) ?: known.maxByOrNull(::pixels)
    }

    private fun startVideoSource() {
        lastKeyframeSentAt = System.currentTimeMillis() // frame #1 is always a keyframe
        val index = selectedConfigIndex ?: 0
        val config = videoConfigs.getOrNull(index)
        if (config == null) {
            log.e("no video config at index $index - cannot start the encoder")
            return
        }
        val (width, height) = config.pixelSize() ?: run {
            log.e("unsupported resolution ${config.resolutionName}")
            return
        }
        config.visibleSize()?.let { (w, h) -> SessionReport.carScreen("${w}x$h", config.density) }

        val start = Protobuf.varintField(1, SESSION_ID.toLong()) +
            Protobuf.varintField(2, index.toLong())
        send(MSG_MEDIA_START, start, encrypted = true, channel = videoChannelId)
        log.i("-> Start(session_id=$SESSION_ID, configuration_index=$index -> $config)")
        // The car crops its margins off the frame, so keep the UI out of them unless a vehicle
        // profile has its own tuned insets.
        if (vehicleProfile?.insets.let { it == null || it == Insets.NONE }) {
            val insets = config.marginInsets()
            CarEnvironment.setInsets(insets)
            if (insets != Insets.NONE) log.i("keeping the UI clear of the car's margins: $insets")
        }

        val useProjection = projection != null
        videoSource = VideoSource(
            width = width,
            height = height,
            frameRate = if (config.frameRate == 1) 60 else 30,
            mode = if (useProjection) VideoSource.Mode.SURFACE else VideoSource.Mode.TEST_CARD,
            onCodecConfig = { csd ->
                lastCodecConfig = csd
                send(MSG_MEDIA_CODEC_CONFIG, csd, encrypted = true, channel = videoChannelId)
                log.i("-> CodecConfig (${csd.size} bytes SPS/PPS)")
                log.hex("   csd", csd, limit = 64)
            },
            onFrame = { data, presentationTimeUs, keyFrame ->
                sendVideoFrame(data, presentationTimeUs, keyFrame)
            },
        ).also { source ->
            runCatching {
                source.start()
                val surface = source.inputSurface
                if (projection != null && surface != null) {
                    projection.onSurfaceReady(surface, width, height, config.density)
                    SessionStatus.projecting()
                }
            }.onFailure { e -> log.e("encoder start failed", e) }
        }
    }

    /**
     * Flow control: the head unit allows max_unacked frames in flight (LIVI allows one). When
     * the window is full this waits for an ack instead of dropping the frame. A dropped frame
     * breaks the decoder's chain, so it cost a keyframe to repair; keyframes are twenty times
     * the size, so they caused more drops, and the stream sank into a storm of them (345 in a
     * minute on LIVI, at 5-12 fps). Waiting here holds the encoder back instead, which simply
     * makes it produce fewer frames, each one whole.
     */
    private fun sendVideoFrame(data: ByteArray, presentationTimeUs: Long, keyFrame: Boolean) {
        // The car is showing its own screen and would throw these away.
        if (!videoProjected) return
        synchronized(ackLock) {
            val waitStart = System.currentTimeMillis()
            while (running && inFlight >= maxUnacked) {
                // A lost ack would otherwise hold the stream forever, which looks like a frozen
                // picture. After a long silence, assume the window cleared and carry on.
                val since = System.currentTimeMillis() - lastAckAt
                if (since > ACK_STALL_MS) {
                    log.w("no ack for ${since}ms with $inFlight in flight - resetting the window")
                    inFlight = 0
                    lastAckAt = System.currentTimeMillis() // one warning per stall, not one per frame
                    break
                }
                ackLock.wait(ACK_STALL_MS - since + 1)
            }
            ackWaitMs += System.currentTimeMillis() - waitStart
        }
        if (!running) return
        val timestamp = ByteArray(8)
        for (i in 0 until 8) {
            timestamp[i] = ((presentationTimeUs shr ((7 - i) * 8)) and 0xFF).toByte()
        }
        if (keyFrame) {
            if (resyncRequested) {
                log.i("resync keyframe delivered after $resyncAttempts attempt(s)")
            }
            resyncRequested = false
            resyncAttempts = 0
            lastKeyframeSentAt = System.currentTimeMillis()
        }
        // Counted before it's queued: the writer may send it and the ack come back before this
        // thread runs again, and an ack counted ahead of its frame would leave the window full.
        synchronized(ackLock) { inFlight++ }
        send(MSG_MEDIA_DATA, timestamp + data, encrypted = true, channel = videoChannelId)
        reportTouchLatency(presentationTimeUs)
        framesSent++
        if (framesSent <= 3 || keyFrame && framesSent % 30 == 0) {
            log.i(
                "-> Data frame #$framesSent (${data.size} bytes, keyFrame=$keyFrame, " +
                    "ptsUs=$presentationTimeUs)",
            )
        }
    }

    /** Whether the car is showing Gearslip; false while it shows its own interface. */
    @Volatile private var videoProjected = true
    /** The encoder's SPS/PPS, resent when the car hands its screen back. */
    @Volatile private var lastCodecConfig: ByteArray? = null

    /**
     * The car says who has its screen. The first grant starts the video. After that the car may
     * take its screen back (our Exit, or its own buttons) and later return it: while it has the
     * screen nothing is sent, and on return the stream restarts from the codec config and a fresh
     * keyframe, since the car's decoder may have been reset in between.
     */
    private fun onVideoFocus(mode: Int) {
        val projected = mode == VIDEO_FOCUS_PROJECTED || mode == VIDEO_FOCUS_PROJECTED_NO_INPUT
        if (!videoStarted) {
            // The head unit repeats this while it waits for frames; only start once.
            if (!projected) return
            videoStarted = true
            CarFocus.request = ::requestNativeFocus
            log.i("PHASE A COMPLETE - video focus granted; starting video source")
            startVideoSource()
            return
        }
        if (projected == videoProjected) return
        videoProjected = projected
        CarFocus.set(projected)
        if (projected) {
            log.i("the car handed its screen back - restarting the stream")
            // Frames sent just before the car took over may never be acked.
            synchronized(ackLock) {
                inFlight = 0
                ackLock.notifyAll()
            }
            val start = Protobuf.varintField(1, SESSION_ID.toLong()) +
                Protobuf.varintField(2, (selectedConfigIndex ?: 0).toLong())
            send(MSG_MEDIA_START, start, encrypted = true, channel = videoChannelId)
            log.i("-> Start(session_id=$SESSION_ID)")
            lastCodecConfig?.let { send(MSG_MEDIA_CODEC_CONFIG, it, encrypted = true, channel = videoChannelId) }
            videoSource?.requestSyncFrame()
        } else {
            // A phone ends the stream when the car takes its screen. LIVI switches on the focus
            // answer alone, but the 2018 Uconnect kept showing our last frame until it got Stop.
            send(MSG_MEDIA_STOP, Protobuf.varintField(1, SESSION_ID.toLong()), encrypted = true, channel = videoChannelId)
            log.i("the car is showing its own screen - video stopped, audio carries on")
        }
    }

    /** Android Auto's Exit: asks the car for its own interface, leaving the session running. */
    private fun requestNativeFocus() {
        if (!videoStarted) return
        // VideoFocusRequestNotification { disp_channel_id = 1; mode = 2; reason = 3 }. The
        // display's channel is deprecated, but the 2018 Uconnect (protocol 1.3) predates that:
        // without it, it agreed to show its own screen and never did. LIVI ignores the field.
        val request = Protobuf.varintField(1, videoChannelId.toLong()) +
            Protobuf.varintField(2, VIDEO_FOCUS_NATIVE.toLong()) +
            Protobuf.varintField(3, VIDEO_FOCUS_REASON_LAUNCH_NATIVE.toLong())
        send(MSG_VIDEO_FOCUS_REQUEST, request, encrypted = true, channel = videoChannelId)
        log.i("-> VideoFocusRequest(display=$videoChannelId, mode=NATIVE, reason=LAUNCH_NATIVE)")
    }

    /** Arrival time of the last tap still waiting to be seen in a frame; 0 when none is. */
    @Volatile private var touchAtUs = 0L

    /**
     * How long a tap takes to reach the car, split where the phone can see it: the UI drawing a
     * frame after the tap, the encoder compressing it, and the send. A frame's timestamp is when
     * the UI finished drawing it, on the same clock as [touchAtUs]. The head unit's own decode
     * and display come on top and can't be seen from here.
     */
    private fun reportTouchLatency(presentationTimeUs: Long) {
        val touchAt = touchAtUs
        if (touchAt == 0L || presentationTimeUs <= touchAt) return
        touchAtUs = 0L
        val nowUs = System.nanoTime() / 1000
        val draw = (presentationTimeUs - touchAt) / 1000
        val encode = (nowUs - presentationTimeUs) / 1000
        log.i("touch -> frame: ${draw + encode}ms (draw ${draw}ms, encode and wait ${encode}ms)")
    }

    private fun clamp(value: Int, limit: Int?): Float = when {
        limit == null -> value.toFloat()
        value < 0 -> 0f
        value >= limit -> (limit - 1).toFloat()
        else -> value.toFloat()
    }

    private fun onPingRequest(body: ByteArray) {
        // The timestamp is an int64 and has to come back unchanged. Read as an int32 it was cut
        // short, and a car that checks the echo (an Audi MIB2+) gave up at its second ping.
        val timestamp = Protobuf.readInt64Field(body, 1) ?: System.nanoTime()
        send(MSG_PING_RESPONSE, Protobuf.varintField(1, timestamp), encrypted = tls.handshakeComplete)
        log.i("<- PingRequest(timestamp=$timestamp) / -> PingResponse (keeping the session alive)")
    }

    private fun send(
        messageId: Int,
        body: ByteArray,
        encrypted: Boolean,
        channel: Int = Frames.CHANNEL_CONTROL,
        messageType: Int = Frames.MESSAGE_SPECIFIC,
    ) {
        val payload = byteArrayOf(
            ((messageId shr 8) and 0xFF).toByte(), (messageId and 0xFF).toByte(),
        ) + body

        // Every write goes through one thread, in order. The reading thread must never wait on
        // a write: a head unit like LIVI writes its ack before it reads on, so a reader stuck
        // behind a big video frame (to answer a ping) and a head unit stuck writing that ack
        // held each other up until something timed out, about two seconds a time.
        try {
            writer.execute {
                try {
                    if (payload.size <= Frames.MAX_FRAME_PAYLOAD) {
                        val framePayload = if (encrypted) tls.encrypt(payload) else payload
                        output.write(Frames.build(channel, encrypted, framePayload, messageType))
                    } else {
                        // Split exactly as aasdk does: chunk the *plaintext*, encrypt each chunk
                        // separately, and carry the total plaintext length in the FIRST frame.
                        // Video keyframes for anything busier than a test card exceed 16 KB routinely.
                        var offset = 0
                        while (offset < payload.size) {
                            val size = minOf(Frames.MAX_FRAME_PAYLOAD, payload.size - offset)
                            val chunk = payload.copyOfRange(offset, offset + size)
                            val frameType = when {
                                offset == 0 -> Frames.TYPE_FIRST
                                offset + size >= payload.size -> Frames.TYPE_LAST
                                else -> Frames.TYPE_MIDDLE
                            }
                            val framePayload = if (encrypted) tls.encrypt(chunk) else chunk
                            output.write(
                                Frames.build(
                                    channel, encrypted, framePayload, messageType, frameType,
                                    if (frameType == Frames.TYPE_FIRST) payload.size else null,
                                ),
                            )
                            offset += size
                        }
                        splitMessages++
                    }
                    output.flush()
                } catch (e: java.io.IOException) {
                    if (running) log.w("send failed, stopping: ${e.message}")
                    running = false
                    videoSource?.stop()
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // The session is over; nothing more goes out.
        }
    }

    private fun reportDisconnect() {
        when (state) {
            State.WAIT_VERSION -> SessionReport.fail(SessionReport.Category.NO_HANDSHAKE, "no VersionRequest arrived", state.name, "closed")
            State.TLS_HANDSHAKE -> SessionReport.fail(SessionReport.Category.TLS, "head unit dropped the link during TLS", state.name, "closed")
            State.WAIT_AUTH -> SessionReport.fail(SessionReport.Category.AUTH, "dropped after TLS, before AuthComplete", state.name, "closed")
            State.WAIT_SDR -> SessionReport.fail(SessionReport.Category.SERVICE_DISCOVERY, "accepted, then dropped before service discovery", state.name, "closed")
            State.DONE -> {}
        }
        when (state) {
            State.DONE -> SessionStatus.disconnected()
            State.WAIT_VERSION -> SessionStatus.failed(
                "Head unit never started talking",
                "The connection opened but the head unit sent nothing. Check the phone is set to use Gearslip for this USB accessory.",
            )
            State.TLS_HANDSHAKE, State.WAIT_AUTH -> SessionStatus.failed(
                "Connection dropped during setup",
                "The head unit disconnected while securing the link. This usually means it rejected the certificate.",
            )
            State.WAIT_SDR -> SessionStatus.failed(
                "Connection dropped after authentication",
                "The certificate was accepted but the head unit disconnected before finishing setup.",
            )
        }
        when (state) {
            State.WAIT_VERSION -> log.verdict(
                "INCONCLUSIVE (no VersionRequest)",
                "The head unit never opened the control channel. This is a connection problem, " +
                    "not a certificate verdict - check that the accessory was actually claimed.",
            )
            State.TLS_HANDSHAKE -> log.verdict(
                "LIKELY NOT VIABLE (dropped during TLS)",
                "The unit disconnected mid-handshake without sending an alert we could read. " +
                    "Rejection is the likeliest reading, but re-run once before trusting it.",
            )
            State.WAIT_AUTH -> log.verdict(
                "INCONCLUSIVE (dropped after TLS, before AuthComplete)",
                "TLS completed but the unit disconnected before stating a verdict. Re-run.",
            )
            State.WAIT_SDR -> log.verdict(
                "INCONCLUSIVE (accepted, then dropped before service discovery)",
                "AuthComplete said success, so the certificate was accepted - but our " +
                    "ServiceDiscoveryRequest drew no reply. Suspect our own encryption or " +
                    "framing rather than the head unit.",
            )
            State.DONE -> log.i("connection closed after the run completed")
        }
        SessionReport.print()
    }

    private fun startWatchdog() {
        Thread {
            var lastFrames = 0
            while (running) {
                Thread.sleep(5_000)
                if (!running) break

                if (state != State.DONE) {
                    val idle = System.currentTimeMillis() - lastProgress
                    if (idle > 10_000) log.w("stalled in state $state for ${idle / 1000}s")
                }

                // Stream health. Without this a drive cannot tell "streaming fine" from
                // "stalled after three frames" - the per-frame logging is deliberately sparse.
                if (videoStarted) {
                    val sent = framesSent
                    val fps = (sent - lastFrames) / 5.0
                    lastFrames = sent
                    // Time the encoder spent held back waiting for acks: near 5000ms means the
                    // head unit's acks, not the phone, set the frame rate.
                    val waited = ackWaitMs
                    ackWaitMs = 0
                    SessionReport.stream("${"%.1f".format(fps)} fps, sent=$sent acked=$acksSeen")
                    log.i(
                        "video: ${"%.1f".format(fps)} fps over 5s | sent=$sent waited=${waited}ms " +
                            "acked=$acksSeen inFlight=$inFlight split=$splitMessages",
                    )

                    // Self-healing safety net: force a fresh keyframe periodically regardless
                    // of whether we think one is owed. Protects against any future case where
                    // our own drop/resync bookkeeping is wrong or a corruption slips through
                    // some other path - a real decoder desync should never outlive this.
                    val idleSinceKeyframe = System.currentTimeMillis() - lastKeyframeSentAt
                    if (idleSinceKeyframe > PERIODIC_KEYFRAME_MS) {
                        videoSource?.requestSyncFrame()
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private companion object {
        /**
         * Force the protocol version Gearslip claims in VersionResponse, as (major, minor), or
         * null to echo the head unit. 1.3 is what the 2018 Uconnect (which accepts the JVC cert)
         * speaks. This is the 2025 identity-check downgrade experiment: see onVersionRequest.
         */
        val CLAIM_VERSION: Pair<Int, Int>? = 1 to 3

        // protobuf/aap_protobuf/service/control/ControlMessageType.proto:7-17
        const val MSG_VERSION_REQUEST = 1
        const val MSG_VERSION_RESPONSE = 2
        const val MSG_ENCAPSULATED_SSL = 3
        const val MSG_AUTH_COMPLETE = 4
        const val MSG_SERVICE_DISCOVERY_REQUEST = 5
        const val MSG_SERVICE_DISCOVERY_RESPONSE = 6
        const val MSG_PING_REQUEST = 11
        const val MSG_PING_RESPONSE = 12
        const val MSG_BYEBYE_REQUEST = 15
        const val MSG_BYEBYE_RESPONSE = 16
        const val MSG_CHANNEL_OPEN_REQUEST = 7
        const val MSG_CHANNEL_OPEN_RESPONSE = 8

        // aap_protobuf/service/media/.../MediaMessageId
        const val MSG_MEDIA_DATA = 0
        const val MSG_MEDIA_CODEC_CONFIG = 1
        const val MSG_MEDIA_SETUP = 32768
        const val MSG_MEDIA_START = 32769
        const val MSG_MEDIA_STOP = 32770
        const val MSG_MEDIA_CONFIG = 32771
        const val MSG_MEDIA_ACK = 32772
        const val MSG_VIDEO_FOCUS_REQUEST = 32775
        const val MSG_VIDEO_FOCUS_NOTIFICATION = 32776

        // aap_protobuf/service/sensor/... - same "first specific message on this channel type
        // starts at 0x8000" numbering as media/input above.
        // aasdk SensorMessageId: REQUEST, RESPONSE, BATCH, ERROR.
        const val MSG_SENSOR_START_REQUEST = 32769
        const val MSG_SENSOR_START_RESPONSE = 32770
        const val MSG_SENSOR_EVENT_INDICATION = 32771
        const val MSG_SENSOR_ERROR = 32772
        private const val SENSOR_LOCATION = 1
        private const val SENSOR_NIGHT = 10
        private const val SENSOR_LOG_EVERY_MS = 10_000L

        const val STREAM_MEDIA = 3
        const val CODEC_H264_BP = 3
        const val VIDEO_FOCUS_PROJECTED = 1
        const val VIDEO_FOCUS_NATIVE = 2
        const val VIDEO_FOCUS_PROJECTED_NO_INPUT = 4
        const val VIDEO_FOCUS_REASON_LAUNCH_NATIVE = 2
        const val SESSION_ID = 1
        const val MSG_INPUT_REPORT = 32769
        const val MSG_KEY_BINDING_REQUEST = 32770
        const val MSG_KEY_BINDING_RESPONSE = 32771
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1

        const val ACK_STALL_MS = 2_000L
        const val AUDIO_TRACE_MESSAGES = 6
        const val PERIODIC_KEYFRAME_MS = 5_000L
    }
}
