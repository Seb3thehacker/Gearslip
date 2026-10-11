package app.seb3thehacker.gearslip

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Check both latest identities once per process, with explicit retries from setup and Settings. */
internal object ProjectionCertificates {
    private val refreshLock = Any()
    private val cacheLock = Any()
    private val identities = mutableMapOf<CertProvider.Source, CertProvider.Identity>()
    private var attempted = false
    private val changes = MutableStateFlow(0)
    val revision = changes.asStateFlow()
    @Volatile var refreshError: String? = null
        private set

    private fun store(context: Context) = ProjectionIdentityStore(File(context.filesDir, "projection-identities"))

    /** Local only: reading a certificate never waits for a network request. */
    fun load(context: Context, source: CertProvider.Source): CertProvider.Identity? = synchronized(cacheLock) {
        identities[source] ?: runCatching { store(context).read(source) }.getOrNull()?.also { identities[source] = it }
    }

    fun refresh(context: Context, force: Boolean = false) = synchronized(refreshLock) {
        if (attempted && !force) return@synchronized
        val failures = mutableListOf<String>()
        for (source in CertProvider.Source.entries) {
            try {
                val current = load(context, source)
                val downloaded = CertDownloader.fetch(source, current)
                if (downloaded !== current) {
                    store(context).write(source, downloaded)
                    synchronized(cacheLock) { identities[source] = downloaded }
                    changes.update { it + 1 }
                }
            } catch (e: Exception) {
                val message = "${source.label}: ${e.message}"
                failures += message
                GearslipLog.w("Certificate refresh failed: $message")
            }
        }
        refreshError = failures.takeIf { it.isNotEmpty() }?.joinToString("\n")
        attempted = true
        changes.update { it + 1 }
    }
}
