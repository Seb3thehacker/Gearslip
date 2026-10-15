package app.seb3thehacker.gearslip.audio

import app.seb3thehacker.gearslip.GearslipLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Joins the two ends of the audio path: [AudioCaptureService] on the phone, which hears what the
 * phone is playing, and the car's audio channel, which is where it has to end up.
 *
 * Capture belongs to the car session, not to any one app. Android grants it through a consent
 * dialog that cannot be answered in advance or remembered, so Gearslip asks once, when a head unit
 * first offers a sink to play through, and then holds that grant for as long as the car is
 * connected. Switching between media apps, or starting playback from the phone, costs nothing
 * further: the capture is by audio usage rather than by app, so whatever the phone plays as media
 * goes to the car.
 */
object CarAudio {

    /** What the head unit's media sink accepts, and how to hand it audio. */
    interface Link {
        val sampleRate: Int
        val channels: Int
        fun start()
        fun stop()
        fun write(pcm: ByteArray, size: Int)
    }

    enum class Capture { OFF, ASKING, ON, DENIED }

    private val _capture = MutableStateFlow(Capture.OFF)
    val capture: StateFlow<Capture> = _capture.asStateFlow()

    /** "-12 dB" style summary of what was last heard, for the media screen. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    /** Set by the activity: shows the permission and consent dialogs, which only it can. */
    var onConsentRequested: (() -> Unit)? = null

    /** Whether the driver has asked for the car to be fed at all; see CarSettings.pipeAudio. */
    @Volatile private var wanted = false

    @Volatile private var link: Link? = null

    /** Opens the car's media channel, left closed while music went over Bluetooth; null once open. */
    @Volatile var openSink: (() -> Unit)? = null

    /** Rate and channel count the capture should use: the car's, or a sensible default. */
    val sampleRate get() = link?.sampleRate ?: 48_000
    val channels get() = link?.channels ?: 2

    fun attach(link: Link) {
        this.link = link
        GearslipLog.i("audio: the car offered a sink at ${link.sampleRate}Hz x${link.channels}")
        when {
            _capture.value == Capture.ON -> link.start()
            wanted -> ask()
        }
    }

    fun detach(link: Link) {
        if (this.link === link) this.link = null
    }

    /**
     * The car wants sound. Safe to call as often as the session likes: the consent dialog appears
     * at most once, and only once there is a sink to play through - asking on the bench, where the
     * sound has nowhere to go, would put a system dialog in front of the driver for nothing.
     */
    fun request() {
        wanted = true
        when (_capture.value) {
            Capture.ON, Capture.ASKING, Capture.DENIED -> return
            Capture.OFF -> if (link == null) {
                openSink?.invoke()
                GearslipLog.i("audio: waiting for the car to offer an audio sink")
            } else {
                ask()
            }
        }
    }

    private fun ask() {
        _capture.value = Capture.ASKING
        val request = onConsentRequested
        if (request == null) {
            GearslipLog.w("audio: nothing on the phone can ask for capture consent")
            _capture.value = Capture.DENIED
        } else {
            request()
        }
    }

    /** The car went away. Everything is torn down; the next connection asks again. */
    fun release() {
        wanted = false
        AudioCaptureService.stop()
        _capture.value = Capture.OFF
    }

    fun denied() {
        _capture.value = Capture.DENIED
    }

    private val _micBlocked = MutableStateFlow(false)

    /** The microphone permission is off for good: Android refuses without asking, so say why. */
    val micBlocked: StateFlow<Boolean> = _micBlocked.asStateFlow()

    fun micBlocked() {
        _micBlocked.value = true
        denied()
    }

    /** The driver turned the permission back on mid-drive: ask for capture again. */
    fun micAllowed() {
        if (!_micBlocked.value) return
        _micBlocked.value = false
        if (_capture.value == Capture.DENIED) {
            _capture.value = Capture.OFF
            if (wanted) request()
        }
    }

    internal fun captureStarted() {
        _capture.value = Capture.ON
        link?.start()
    }

    internal fun captureStopped() {
        link?.stop()
        _level.value = 0f
        if (_capture.value == Capture.ON) _capture.value = Capture.OFF
    }

    internal fun deliver(pcm: ByteArray, size: Int, peak: Float) {
        _level.value = peak
        link?.write(pcm, size)
    }
}
