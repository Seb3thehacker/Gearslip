package app.seb3thehacker.gearslip.speech

import android.content.Context
import app.seb3thehacker.gearslip.GearslipLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * The speech models Gearslip listens with, in two roles: a streaming one that writes the words
 * while the driver talks, and an optional final one that reads the whole phrase again once they
 * stop, for a more accurate result. None ships in the APK: each is fetched once from sherpa-onnx's
 * GitHub releases, where its authors publish it, and kept in the app's own storage.
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
        val joiner: String,
        val modelType: String = "",
    ) {
        KROKO(
            Role.LIVE, "kroko-en", "Kroko", "Banafo's Kroko ASR, CC BY-SA 4.0", 54,
            "sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06",
            "encoder.onnx", "decoder.onnx", "joiner.onnx", modelType = "zipformer2",
        ),
        PARAKEET_SMALL(
            Role.FINAL, "parakeet-110m-en", "Parakeet small", "Lighter and quicker, misses more names. NVIDIA NeMo, CC BY 4.0", 103,
            "sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8",
            "encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", modelType = "nemo_transducer",
        ),
        PARAKEET_LARGE(
            Role.FINAL, "parakeet-0.6b-en", "Parakeet large", "Recommended. Hears names and places best. NVIDIA NeMo, CC BY 4.0", 460,
            "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8",
            "encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", modelType = "nemo_transducer",
        ),
        ;

        val url get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$archive.tar.bz2"
        val files get() = listOf(encoder, decoder, joiner, "tokens.txt")
    }

    sealed interface State {
        data object Missing : State
        data class Downloading(val percent: Int) : State
        data object Ready : State
        data class Failed(val reason: String) : State
    }

    private const val PREFS = "speech"
    private const val KEY_MODEL = "model"
    private const val KEY_FINAL = "final_model"

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "gearslip-speech-download") }
    private val states = Model.entries.associateWith { MutableStateFlow<State>(State.Missing) }
    private val _selected = MutableStateFlow(Model.KROKO)
    val selected: StateFlow<Model> = _selected.asStateFlow()
    private val _final = MutableStateFlow<Model?>(Model.PARAKEET_LARGE)

    /** The model that rereads the whole phrase at the end; null to keep the live model's words. */
    val final: StateFlow<Model?> = _final.asStateFlow()
    @Volatile private var cancelled: Model? = null

    fun init(context: Context) {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODEL, null)
        _selected.value = Model.entries.firstOrNull { it.id == id && it.role == Role.LIVE } ?: Model.KROKO
        val finalId = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FINAL, Model.PARAKEET_LARGE.id)
        _final.value = Model.entries.firstOrNull { it.id == finalId && it.role == Role.FINAL }
        Model.entries.forEach { m -> if (isReady(context, m)) states.getValue(m).value = State.Ready }
        // Models an older build offered and this one doesn't: hundreds of MB nobody can use.
        val known = Model.entries.map { it.id }.toSet()
        File(context.filesDir, "speech").listFiles()?.filter { it.isDirectory && it.name !in known }?.forEach {
            GearslipLog.i("speech: removing ${it.name}, no longer offered")
            it.deleteRecursively()
        }
    }

    fun state(model: Model): StateFlow<State> = states.getValue(model).asStateFlow()

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
        if (model == _selected.value || model == _final.value) SpeechEngine.unload()
    }

    /** Fetches [model] and unpacks just the files the recognizer reads; the rest of the archive is skipped. */
    fun download(context: Context, model: Model) {
        val app = context.applicationContext
        val state = states.getValue(model)
        if (state.value is State.Downloading || state.value == State.Ready) return
        state.value = State.Downloading(0)
        cancelled = null
        worker.execute {
            val target = dir(app, model)
            val partial = File(target.parentFile, "${model.id}.partial").apply { deleteRecursively(); mkdirs() }
            try {
                val conn = (URL(model.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                }
                check(conn.responseCode == 200) { "the server answered ${conn.responseCode}" }
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: (model.downloadMb * 1_048_576L)
                val counting = CountingStream(conn.inputStream) { read ->
                    if (cancelled == model) throw InterruptedException("cancelled")
                    state.value = State.Downloading(((read * 100) / total).toInt().coerceIn(0, 99))
                }
                TarArchiveInputStream(BZip2CompressorInputStream(counting.buffered())).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        val name = entry.name.substringAfterLast('/')
                        if (entry.isDirectory || name !in model.files) continue
                        File(partial, name).outputStream().use { tar.copyTo(it) }
                    }
                }
                check(model.files.all { File(partial, it).isFile }) { "the archive was missing files" }
                target.deleteRecursively()
                check(partial.renameTo(target)) { "couldn't store the model" }
                GearslipLog.i("speech: downloaded ${model.label}")
                state.value = State.Ready
            } catch (e: Exception) {
                partial.deleteRecursively()
                if (cancelled == model) {
                    state.value = State.Missing
                } else {
                    GearslipLog.w("speech: download of ${model.label} failed: ${e.message}")
                    state.value = State.Failed(e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

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
