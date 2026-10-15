package app.seb3thehacker.gearslip.speech

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.seb3thehacker.gearslip.GearslipActivity
import app.seb3thehacker.gearslip.GearslipLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The speech models Gearslip listens with, in two roles: a streaming one that writes the words
 * while the driver talks, and an optional final one that reads the whole phrase again once they
 * stop, for a more accurate result. None ships in the APK: each is fetched once and kept in the
 * app's own storage. sherpa-onnx's author publishes them twice: as .tar.bz2 archives on GitHub and
 * as plain files on Hugging Face. Gearslip takes the plain files: unpacking bzip2 on the phone
 * pinned a core and held a download to 150 KB/s on Wi-Fi that does 19 MB/s.
 */
object SpeechModels {

    enum class Role { LIVE, FINAL }

    enum class Model(
        val role: Role,
        val id: String,
        val label: String,
        val detail: String,
        val downloadMb: Int,
        private val archive: String,
        val encoder: String,
        val decoder: String,
        /** Empty for Canary, which decodes with attention and has no joiner. */
        val joiner: String,
        val modelType: String = "",
        /** Where the unpacked files are, when not under the archive's own name on Hugging Face. */
        private val filesAt: String? = null,
    ) {
        KROKO(
            Role.LIVE, "kroko-en", "Kroko", "Banafo's Kroko ASR, CC BY-SA 4.0", 68,
            "sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06",
            "encoder.onnx", "decoder.onnx", "joiner.onnx", modelType = "zipformer2",
        ),
        PARAKEET_SMALL(
            Role.FINAL, "parakeet-110m-en", "Parakeet small", "Lighter and quicker, misses more names. NVIDIA NeMo, CC BY 4.0", 131,
            "sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8",
            "encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", modelType = "nemo_transducer",
            // The official unpacked copy is empty. This mirror's files are byte for byte the
            // official archive's, checked by SHA-256, and pinned to that commit so they can't change.
            filesAt = "punitd/sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8/resolve/66a4fa70643dc7ce25c9b38b2f87e1b35ddad33d",
        ),
        CANARY(
            Role.FINAL, "canary-180m-en", "Canary", "Recommended. Quick, and hears names and places well. Knows English, Spanish, German and French; Gearslip listens in English for now. NVIDIA NeMo, CC BY 4.0", 198,
            "sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8",
            "encoder.int8.onnx", "decoder.int8.onnx", "", modelType = "canary",
        ),
        PARAKEET_LARGE(
            Role.FINAL, "parakeet-0.6b-en", "Parakeet large", "Hears names best, but slower and three times the size. NVIDIA NeMo, CC BY 4.0", 631,
            "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8",
            "encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", modelType = "nemo_transducer",
        ),
        ;

        fun fileUrl(name: String) = "https://huggingface.co/${filesAt ?: "csukuangfj/$archive/resolve/main"}/$name"
        val files get() = listOf(encoder, decoder, joiner, "tokens.txt").filter { it.isNotEmpty() }
    }

    /** What setup offers in one tap: Kroko for the live words, Canary for the final ones. */
    val RECOMMENDED = listOf(Model.KROKO, Model.CANARY)

    fun downloadRecommended(context: Context) = RECOMMENDED.forEach { download(context, it) }

    sealed interface State {
        data object Missing : State
        data class Downloading(val percent: Int) : State
        data object Ready : State
        data class Failed(val reason: String) : State
    }

    private const val PREFS = "speech"
    private const val KEY_MODEL = "model"
    private const val KEY_FINAL = "final_model"
    private const val CHANNEL = "voice_download"
    private const val NOTIFICATION_ID = 4_312

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "gearslip-speech-download") }
    private val states = Model.entries.associateWith { MutableStateFlow<State>(State.Missing) }
    private val _selected = MutableStateFlow(Model.KROKO)
    val selected: StateFlow<Model> = _selected.asStateFlow()
    private val _final = MutableStateFlow<Model?>(Model.CANARY)

    /** The model that rereads the whole phrase at the end; null to keep the live model's words. */
    val final: StateFlow<Model?> = _final.asStateFlow()
    @Volatile private var cancelled: Model? = null

    fun init(context: Context) {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODEL, null)
        _selected.value = Model.entries.firstOrNull { it.id == id && it.role == Role.LIVE } ?: Model.KROKO
        // Canary is the default. A driver who never chose but already has Parakeet large keeps it.
        val finalId = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FINAL, null)
            ?: if (isReady(context, Model.PARAKEET_LARGE) && !isReady(context, Model.CANARY)) Model.PARAKEET_LARGE.id else Model.CANARY.id
        _final.value = Model.entries.firstOrNull { it.id == finalId && it.role == Role.FINAL }
        Model.entries.forEach { m -> if (isReady(context, m)) states.getValue(m).value = State.Ready }
        // Models an older build offered and this one doesn't: hundreds of MB nobody can use. A
        // known model's ".partial" folder is a download that may be running right now; [download]
        // clears its own leftovers when it starts.
        val known = Model.entries.map { it.id }.toSet()
        File(context.filesDir, "speech").listFiles()
            ?.filter { it.isDirectory && it.name.removeSuffix(".partial") !in known }?.forEach {
            GearslipLog.i("speech: removing ${it.name}, no longer offered")
            it.deleteRecursively()
        }
    }

    fun state(model: Model): StateFlow<State> = states.getValue(model).asStateFlow()

    /**
     * The models fetched since the last batch finished, so the percent covers all of them and
     * doesn't jump back to 0 when the second one starts.
     */
    private val batch = mutableSetOf<Model>()
    private val _progress = MutableStateFlow<Int?>(null)

    /** Percent through the current downloads, for Home and the notification; null when none run. */
    val progress: StateFlow<Int?> = _progress.asStateFlow()

    private fun updateProgress(context: Context) {
        val percent = synchronized(batch) {
            if (batch.none { states.getValue(it).value is State.Downloading }) {
                batch.clear()
                null
            } else {
                val done = batch.sumOf { m ->
                    m.downloadMb * when (val st = states.getValue(m).value) {
                        is State.Downloading -> st.percent
                        State.Ready -> 100
                        else -> 0
                    }
                }
                done / batch.sumOf { it.downloadMb }
            }
        }
        if (percent == _progress.value) return
        _progress.value = percent
        showProgress(context, percent)
    }

    /** A silent notification with a progress bar, so the download is visible with the app closed. */
    private fun showProgress(context: Context, percent: Int?) {
        val manager = NotificationManagerCompat.from(context)
        if (percent == null) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Voice download", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(context, GearslipActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading voice")
            .setContentText("$percent%")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    fun dir(context: Context, model: Model) = File(File(context.filesDir, "speech"), model.id)

    fun isReady(context: Context, model: Model) = model.files.all { File(dir(context, model), it).isFile }

    fun select(context: Context, model: Model) {
        if (model.role == Role.FINAL) return selectFinal(context, model)
        _selected.value = model
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODEL, model.id).apply()
        SpeechEngine.unload()
    }

    /** [model] null keeps the live words as they are. */
    fun selectFinal(context: Context, model: Model?) {
        _final.value = model
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_FINAL, model?.id ?: "none").apply()
        SpeechEngine.unload()
    }

    fun delete(context: Context, model: Model) {
        cancelled = model
        dir(context, model).deleteRecursively()
        states.getValue(model).value = State.Missing
        updateProgress(context.applicationContext)
        if (model == _selected.value || model == _final.value) SpeechEngine.unload()
    }

    /** Fetches just the files the recognizer reads. */
    fun download(context: Context, model: Model) {
        val app = context.applicationContext
        val state = states.getValue(model)
        if (state.value is State.Downloading || state.value == State.Ready) return
        state.value = State.Downloading(0)
        synchronized(batch) { batch.add(model) }
        updateProgress(app)
        cancelled = null
        worker.execute {
            val target = dir(app, model)
            val partial = File(target.parentFile, "${model.id}.partial").apply { deleteRecursively(); mkdirs() }
            try {
                val total = model.downloadMb * 1_048_576L
                var done = 0L
                fun progress(read: Long) {
                    if (cancelled == model) throw InterruptedException("cancelled")
                    state.value = State.Downloading((((done + read) * 100) / total).toInt().coerceIn(0, 99))
                    updateProgress(app)
                }
                for (name in model.files) {
                    val conn = open(model.fileUrl(name))
                    val length = conn.contentLengthLong
                    val file = File(partial, name)
                    CountingStream(conn.inputStream, ::progress).use { input ->
                        file.outputStream().use { input.copyTo(it, 256 * 1024) }
                    }
                    check(length <= 0 || file.length() == length) { "$name arrived cut short" }
                    done += file.length()
                }
                check(model.files.all { File(partial, it).isFile }) { "some files were missing" }
                target.deleteRecursively()
                check(partial.renameTo(target)) { "couldn't store the model" }
                GearslipLog.i("speech: downloaded ${model.label}")
                state.value = State.Ready
                updateProgress(app)
            } catch (e: Exception) {
                partial.deleteRecursively()
                if (cancelled == model) {
                    state.value = State.Missing
                } else {
                    GearslipLog.w("speech: download of ${model.label} failed: ${e.message}")
                    state.value = State.Failed(e.message ?: e.javaClass.simpleName)
                }
                updateProgress(app)
            }
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }.also { check(it.responseCode == 200) { "the server answered ${it.responseCode}" } }

    /** Counts bytes as they pass, reporting about every megabyte. */
    private class CountingStream(
        private val inner: java.io.InputStream,
        private val onProgress: (Long) -> Unit,
    ) : java.io.InputStream() {
        private var read = 0L
        private var reported = 0L

        override fun read(): Int = inner.read().also { if (it >= 0) count(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) count(it.toLong()) }
        override fun close() = inner.close()

        private fun count(n: Long) {
            read += n
            if (read - reported >= 1_048_576) {
                reported = read
                onProgress(read)
            }
        }
    }
}
