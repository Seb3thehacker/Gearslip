package app.seb3thehacker.gearslip.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.audio.CarMic
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Gearslip's own speech-to-text, so voice works on phones with no speech service (GrapheneOS ships
 * none). whisper.cpp runs FUTO's ACFT Whisper model, which are tuned for short phrases: a reply
 * of a few seconds comes back in well under a second on a recent phone.
 *
 * Whisper needs the whole phrase before it starts, so this records from the car's microphone (the
 * phone's when the car has none or sends nothing),
 * decides when the driver has stopped talking (a stretch of quiet after speech), then transcribes
 * once. Everything runs on one worker thread; callbacks arrive on the main thread.
 */
object SpeechEngine {

    /**
     * FUTO's ACFT Whisper (Apache-2.0), re-quantized to 5 bits, inside the APK: base when the build
     * carries it (more accurate, about twice as slow), tiny otherwise.
     */
    private val MODELS = listOf("speech/base_acft_q5_1.bin", "speech/tiny_acft_q5_1.bin")

    /** The error for an engine that can't run at all, which callers may answer with a fallback. */
    const val NOT_LOADED = "The speech model didn't load."

    private const val RATE = 16_000
    private const val FRAME = 480 // 30 ms
    private const val MAX_MS = 15_000L
    private const val NO_SPEECH_MS = 6_000L
    private const val END_SILENCE_MS = 1_100L
    private const val CALIBRATE_MS = 300L
    private const val PRE_ROLL = RATE * 3 / 10 // keep 300 ms before the first word
    private const val CUE_VOLUME = 80
    /** How long the car gets to start sending once asked, before the phone's microphone takes over. */
    private const val CAR_FIRST_AUDIO_MS = 1_500L
    private const val CAR_GAP_MS = 1_000L
    private const val CUE_MS = 150
    /** The car plays what the phone sends a moment late; waiting this out keeps the beep off the recording. */
    private const val CUE_TAIL_MS = 250L

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "gearslip-speech") }
    private val main = Handler(Looper.getMainLooper())

    private var libraryLoaded = false
    private var handle = 0L
    @Volatile private var run = 0

    /** Loads the model ahead of time, so the first reply doesn't wait for it. */
    fun warm(context: Context) {
        val app = context.applicationContext
        worker.execute { ensureLoaded(app) }
    }

    /**
     * Listens for one phrase. [cue] plays a short tone first, so the driver knows when to talk.
     * [onSpeaking] fires at the first word, [onHeard] when the driver stops and the words are being
     * worked out; [onResult] gets the text, [onError] a sentence fit to show on the car screen.
     */
    fun listen(
        context: Context,
        cue: Boolean = true,
        onSpeaking: () -> Unit = {},
        onHeard: () -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val app = context.applicationContext
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onError("Needs the microphone permission.")
            return
        }
        val id = ++run
        fun post(block: () -> Unit) { main.post { if (id == run) block() } }
        worker.execute {
            if (id != run) return@execute
            if (!ensureLoaded(app)) return@execute post { onError(NOT_LOADED) }
            VoiceFocus.acquire(app)
            val audio = try {
                if (cue) cue()
                if (id != run) return@execute
                record(id) { post(onSpeaking) }
            } catch (t: Throwable) {
                GearslipLog.e("speech: recording failed", t)
                return@execute post { onError("The microphone isn't available right now.") }
            } finally {
                VoiceFocus.release()
            }
            if (id != run) return@execute
            if (audio == null) return@execute post { onError("Didn't catch that.") }
            post(onHeard)
            val started = SystemClock.uptimeMillis()
            val text = clean(transcribe(audio))
            GearslipLog.i(
                "speech: ${audio.size * 1000L / RATE} ms of audio took ${SystemClock.uptimeMillis() - started} ms",
            )
            post { if (text.isEmpty()) onError("Didn't catch that.") else onResult(text) }
        }
    }

    /** Stops listening; a phrase already being worked out is dropped. */
    fun cancel() { run++ }

    /**
     * A short beep on the media stream, so it plays through the car like everything else, then a
     * pause long enough for it to finish there before the microphone opens and hears it.
     */
    private fun cue() {
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, CUE_VOLUME) }.getOrNull() ?: return
        try {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, CUE_MS)
            Thread.sleep(CUE_MS + CUE_TAIL_MS)
        } finally {
            tone.release()
        }
    }

    // --- recording --------------------------------------------------------------------------

    /**
     * Records until the driver stops talking. Returns the phrase from just before the first word,
     * or null when nobody spoke. The speech threshold follows the noise in the first 300 ms, so a
     * loud cabin needs a louder voice than a quiet one to count as talking.
     */
    /** Where a phrase is recorded from: [read] fills [frame] and returns how many samples it got. */
    private interface Mic {
        fun read(frame: ShortArray): Int
        fun close()
    }

    @SuppressLint("MissingPermission") // checked in listen()
    private fun phoneMic(): Mic {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, FRAME * 8),
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord didn't initialize" }
        recorder.startRecording()
        return object : Mic {
            override fun read(frame: ShortArray) = recorder.read(frame, 0, frame.size)
            override fun close() {
                runCatching { recorder.stop() }
                recorder.release()
            }
        }
    }

    /**
     * The car's microphone, which hears the driver better than a phone in a cup holder. The car
     * sends audio in its own sized pieces; they're cut to [FRAME]s here. Null when there's no car
     * microphone, or it sends nothing at first, so the phone's is used instead.
     */
    private fun carMic(): Mic? {
        val car = CarMic.current ?: return null
        if (!car.start()) return null
        val first = car.read(CAR_FIRST_AUDIO_MS)
        if (first == null) {
            car.stop()
            GearslipLog.w("speech: the car's microphone sent nothing - using the phone's")
            return null
        }
        GearslipLog.i("speech: listening through the car's microphone")
        return object : Mic {
            var pending = first
            var at = 0
            override fun read(frame: ShortArray): Int {
                var filled = 0
                while (filled < frame.size) {
                    val chunk = pending ?: car.read(CAR_GAP_MS) ?: break
                    pending = chunk
                    val n = minOf(frame.size - filled, chunk.size - at)
                    System.arraycopy(chunk, at, frame, filled, n)
                    filled += n
                    at += n
                    if (at == chunk.size) { pending = null; at = 0 }
                }
                // The car stopped sending mid-phrase: end the recording rather than wait forever.
                return if (filled == 0) -1 else filled
            }
            override fun close() = car.stop()
        }
    }

    /**
     * Records until the driver stops talking. Returns the phrase from just before the first word,
     * or null when nobody spoke. The speech threshold follows the noise in the first 300 ms, so a
     * loud cabin needs a louder voice than a quiet one to count as talking.
     */
    private fun record(id: Int, onSpeaking: () -> Unit): FloatArray? {
        val mic = carMic() ?: phoneMic()
        val samples = FloatArray((RATE * MAX_MS / 1000).toInt())
        var count = 0
        val frame = ShortArray(FRAME)
        var noise = 0.0
        var noiseFrames = 0
        var speechStart = -1
        var lastSpeech = 0
        try {
            while (id == run && count + FRAME <= samples.size) {
                val n = mic.read(frame)
                if (n < 0) break
                if (n == 0) continue
                var sum = 0.0
                for (i in 0 until n) {
                    val v = frame[i] / 32768f
                    samples[count + i] = v
                    sum += v * v
                }
                count += n
                val db = 20 * log10(sqrt(sum / n) + 1e-9)
                val ms = count * 1000L / RATE
                if (ms <= CALIBRATE_MS) {
                    noise += db; noiseFrames++
                    continue
                }
                val floor = if (noiseFrames > 0) noise / noiseFrames else -60.0
                val talking = db > max(floor + 12, -45.0)
                if (talking) {
                    if (speechStart < 0) {
                        speechStart = max(0, count - n - PRE_ROLL)
                        onSpeaking()
                    }
                    lastSpeech = count
                }
                if (speechStart < 0 && ms > NO_SPEECH_MS) return null
                if (speechStart >= 0 && (count - lastSpeech) * 1000L / RATE > END_SILENCE_MS) break
            }
        } finally {
            mic.close()
        }
        if (speechStart < 0) return null
        return samples.copyOfRange(speechStart, count)
    }

    // --- the model --------------------------------------------------------------------------

    private fun ensureLoaded(context: Context): Boolean {
        if (handle != 0L) return true
        if (!libraryLoaded) {
            runCatching { System.loadLibrary("gearslip_speech") }
                .onFailure { GearslipLog.e("speech: native library missing", it); return false }
            libraryLoaded = true
        }
        val started = SystemClock.uptimeMillis()
        val model = MODELS.firstOrNull { name -> runCatching { context.assets.open(name).close() }.isSuccess }
            ?: run { GearslipLog.w("speech: no model in the build"); return false }
        handle = nativeInitAsset(context.assets, model)
        if (handle == 0L) {
            GearslipLog.w("speech: couldn't load the model")
            return false
        }
        GearslipLog.i("speech: loaded $model in ${SystemClock.uptimeMillis() - started} ms")
        return true
    }

    private fun transcribe(audio: FloatArray): String {
        val bytes = nativeTranscribe(handle, audio, language(), threads())
        return String(bytes, Charsets.UTF_8)
    }

    /** The phone's language, as Whisper names it. Older Java codes differ for a few. */
    private fun language(): String = when (val lang = Locale.getDefault().language) {
        "iw" -> "he"
        "in" -> "id"
        "ji" -> "yi"
        "" -> "en"
        else -> lang
    }

    /** The big cores only: more threads than that slow Whisper down on a phone. */
    private fun threads() = Runtime.getRuntime().availableProcessors().coerceIn(1, 8).let { if (it >= 8) 4 else max(1, it / 2) }

    /** Whisper marks silence and noise as "[BLANK_AUDIO]" or "(wind blowing)"; none of it is words. */
    private fun clean(text: String): String =
        text.replace(Regex("""\[[^\]]*\]|\([^)]*\)|\*[^*]*\*"""), " ").replace(Regex("\\s+"), " ").trim()

    private external fun nativeInitAsset(assets: AssetManager, path: String): Long
    private external fun nativeTranscribe(handle: Long, samples: FloatArray, language: String, threads: Int): ByteArray
    private external fun nativeFree(handle: Long)
}
