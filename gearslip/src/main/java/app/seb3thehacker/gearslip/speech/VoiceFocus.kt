package app.seb3thehacker.gearslip.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import app.seb3thehacker.gearslip.GearslipLog

/**
 * Pauses the music while Gearslip talks or listens. Without it the microphone heard the song and
 * Whisper wrote down lyrics, and a message read aloud had to compete with the music.
 *
 * Transient exclusive focus makes music apps pause rather than duck, and they resume on their
 * own once it's given back. The release waits a moment, so a read-aloud message followed by
 * "Do you want to reply?" and the microphone is one pause, not three.
 */
object VoiceFocus {

    private const val RELEASE_DELAY_MS = 1_500L
    private const val AFTER_RELEASE_MS = 300L

    private val main = Handler(Looper.getMainLooper())
    private var request: AudioFocusRequest? = null
    private var audio: AudioManager? = null
    private var holders = 0
    private val afterRelease = mutableListOf<() -> Unit>()

    private val releaseNow = Runnable {
        request?.let { r ->
            audio?.abandonAudioFocusRequest(r)
            request = null
            GearslipLog.i("voice: gave audio focus back")
        }
        val pending = afterRelease.toList()
        afterRelease.clear()
        // Just after the music app has had its focus back and acted on it.
        if (pending.isNotEmpty()) main.postDelayed({ pending.forEach { it() } }, AFTER_RELEASE_MS)
    }

    /**
     * Runs [action] once the music has its focus back, or now if Gearslip isn't holding it.
     * Metrolist takes a pause sent while it's held as nothing and resumes when focus returns,
     * so "pause" sends it again from here.
     */
    fun afterRelease(action: () -> Unit) {
        main.post {
            if (request == null && holders == 0) action() else afterRelease += action
        }
    }

    /** Pauses the music until a matching [release]. Safe from any thread. */
    fun acquire(context: Context) {
        val app = context.applicationContext
        main.post {
            holders++
            main.removeCallbacks(releaseNow)
            if (request != null) return@post
            val manager = app.getSystemService(AudioManager::class.java) ?: return@post
            val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setOnAudioFocusChangeListener { }
                .build()
            val granted = manager.requestAudioFocus(r) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            GearslipLog.i("voice: audio focus ${if (granted) "granted, music paused" else "refused"}")
            if (granted) {
                audio = manager
                request = r
            }
        }
    }

    /** Lets the music come back shortly, unless something else takes focus again first. */
    fun release() {
        main.post {
            if (holders == 0) return@post
            holders--
            if (holders == 0) main.postDelayed(releaseNow, RELEASE_DELAY_MS)
        }
    }
}
