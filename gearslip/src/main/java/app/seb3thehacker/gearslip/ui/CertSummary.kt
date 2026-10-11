package app.seb3thehacker.gearslip.ui

import android.content.Context
import app.seb3thehacker.gearslip.CertProvider
import java.text.DateFormat
import java.util.Date

/** What the UI says about the certificate a connection would present. */
data class CertSummary(
    val kind: CertProvider.Kind,
    val headline: String,
    val subject: String?,
    val issuer: String?,
    val expiry: String?,
) {
    companion object {
        /**
         * The last [read], so Settings draws the certificate row at once instead of a second
         * later, which shifted everything under it. Null until the first read.
         */
        @Volatile var cached: CertSummary? = null
            private set

        /** Blocking (parses a key store): call from a background dispatcher. */
        fun read(context: Context): CertSummary = readNow(context).also { cached = it }

        private fun readNow(context: Context): CertSummary {
            val identity = CertProvider.loadSupplied(context)
                ?: return CertSummary(
                    CertProvider.Kind.SELF_SIGNED,
                    "No certificate available",
                    null, null, null,
                )
            val cert = identity.certificate
            val name = when (identity.kind) {
                CertProvider.Kind.IMPORTED -> "Imported certificate"
                CertProvider.Kind.DOWNLOADED -> identity.downloadedSource?.label ?: "Downloaded certificate"
                CertProvider.Kind.ADB_STAGED -> "Certificate supplied via ADB"
                CertProvider.Kind.SELF_SIGNED -> "No certificate available"
            }
            return CertSummary(
                identity.kind,
                name,
                cert.subjectX500Principal.name,
                cert.issuerX500Principal.name,
                certificateExpiry(cert.notAfter),
            )
        }
    }
}

internal fun certificateExpiry(date: Date): String {
    val formatted = DateFormat.getDateInstance(DateFormat.MEDIUM).format(date)
    return if (Date().after(date)) "Expired $formatted" else "Expires $formatted"
}
