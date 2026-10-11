package app.seb3thehacker.gearslip

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * Supplies the identity presented to the car: user imports and adb-staged files take priority,
 * followed by the selected automatically downloaded identity. No identity is bundled.
 */
object CertProvider {
    private val log = GearslipLog.tagged("CERT")

    private const val P12_NAME = "phone.p12"
    private const val PASS_NAME = "phone.pass"
    private const val DEFAULT_PASSWORD = "aaspike"
    private const val IMPORT_DIR = "identity"

    enum class Kind { IMPORTED, DOWNLOADED, ADB_STAGED, SELF_SIGNED }

    enum class Source(val label: String, val description: String) {
        ANDROID_AUTO("Android Auto", "downloaded Android Auto phone identity"),
        HEAD_UNIT("Head unit (DHU)", "downloaded Desktop Head Unit identity"),
    }

    class Identity(
        val keyStore: KeyStore,
        val certificate: X509Certificate,
        val password: CharArray,
        val source: String,
        val kind: Kind = Kind.SELF_SIGNED,
        val downloadedSource: Source? = null,
    )

    /**
     * The identity for the next connection. A newly imported or pushed cert takes effect on the
     * next connect: see [loadFromFiles] for how the cache notices.
     */
    fun load(context: Context, source: Source = AppSettings.certificateSource(context)): Identity =
        loadFromFiles(context) ?: ProjectionCertificates.load(context, source) ?: run {
            val generated = SelfSignedCert.generate()
            Identity(generated.keyStore, generated.certificate, SelfSignedCert.PASSWORD,
                "self-signed (no certificate available; complete certificate setup)", Kind.SELF_SIGNED)
        }

    /** The active identity without generating a self-signed key, for the certificate summary. */
    fun loadSupplied(context: Context): Identity? =
        loadFromFiles(context) ?: ProjectionCertificates.load(context, AppSettings.certificateSource(context))

    /**
     * Unlocking a PKCS#12 file takes about a second on a Pixel 6, which a head unit waiting for
     * its first reply may not wait out. So the unlocked identity is kept, keyed on the files it
     * could come from: importing, removing or pushing a cert changes the key.
     */
    @Volatile private var cached: Pair<String, Identity>? = null

    private fun sourcesKey(context: Context): String {
        val dir = context.getExternalFilesDir(null)
        val files = listOf(
            importedFile(context, P12_NAME),
            dir?.let { File(it, P12_NAME) },
            dir?.let { File(it, PASS_NAME) },
        )
        return files.joinToString("|") { f -> f?.let { "${it.path}:${it.lastModified()}:${it.length()}" } ?: "-" }
    }

    /** Unlocks the certificate ahead of time, so the first connection doesn't wait on it. */
    fun warm(context: Context) {
        runCatching { load(context) }
    }

    private fun loadFromFiles(context: Context): Identity? {
        val key = sourcesKey(context)
        cached?.let { (k, identity) -> if (k == key) return identity }
        val identity = unlockFromFiles(context) ?: return null
        cached = key to identity
        return identity
    }

    private fun unlockFromFiles(context: Context): Identity? {
        val imported = importedFile(context, P12_NAME)
        if (imported.isFile) {
            runCatching {
                return loadP12(imported, importedPassword(context), "imported certificate", Kind.IMPORTED)
            }.onFailure { log.e("could not load the imported certificate - trying the next source", it) }
        }

        val dir = context.getExternalFilesDir(null)
        val staged = dir?.let { File(it, P12_NAME) }
        if (dir != null && staged != null && staged.isFile) {
            runCatching {
                val passFile = File(dir, PASS_NAME)
                val password = if (passFile.isFile) {
                    passFile.readText().lineSequence().firstOrNull().orEmpty().trim()
                } else {
                    DEFAULT_PASSWORD
                }
                return loadP12(staged, password, "$P12_NAME staged over adb", Kind.ADB_STAGED)
            }.onFailure { log.e("could not load $P12_NAME - falling back to self-signed", it) }
        }
        return null
    }

    /**
     * Validates [uri] as a PKCS#12 identity with [password] and, only if it is good, makes it
     * the active certificate. A wrong password or a file with no private key throws and
     * leaves whatever was active before untouched.
     */
    fun importFrom(context: Context, uri: Uri, password: String): Identity {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("could not open the selected file")

        val staging = File(context.cacheDir, "import-$P12_NAME")
        try {
            staging.writeBytes(bytes)
            val identity = loadP12(staging, password, "imported certificate", Kind.IMPORTED)

            val target = importedFile(context, P12_NAME)
            target.parentFile?.mkdirs()
            staging.copyTo(target, overwrite = true)
            importedFile(context, PASS_NAME).writeText(password)
            log.i("imported a certificate: ${identity.certificate.subjectX500Principal}")
            return identity
        } finally {
            staging.delete()
        }
    }

    /** Drops the imported identity; the next source in line takes over. */
    fun removeImported(context: Context) {
        importedFile(context, P12_NAME).delete()
        importedFile(context, PASS_NAME).delete()
        log.i("removed the imported certificate")
    }

    /**
     * Validates raw PKCS#12 bytes with [password] and, if good, writes them as the imported
     * identity. Used when the app assembles a certificate in memory (e.g. from a download)
     * rather than importing from a file picker.
     */
    fun importFromBytes(context: Context, p12Bytes: ByteArray, password: String): Identity {
        val staging = File(context.cacheDir, "import-$P12_NAME")
        try {
            staging.writeBytes(p12Bytes)
            val identity = loadP12(staging, password, "imported certificate", Kind.IMPORTED)

            val target = importedFile(context, P12_NAME)
            target.parentFile?.mkdirs()
            staging.copyTo(target, overwrite = true)
            importedFile(context, PASS_NAME).writeText(password)
            log.i("imported a certificate from bytes: ${identity.certificate.subjectX500Principal}")
            return identity
        } finally {
            staging.delete()
        }
    }

    // The imported files live in the app's private internal storage (not the external files
    // dir), so other apps cannot read the key, and `adb uninstall` clearing it is expected.
    private fun importedFile(context: Context, name: String) = File(File(context.filesDir, IMPORT_DIR), name)

    private fun importedPassword(context: Context): String =
        importedFile(context, PASS_NAME).takeIf { it.isFile }?.readText().orEmpty()

    private fun loadP12(file: File, password: String, label: String, kind: Kind): Identity {
        val chars = password.toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12")
        file.inputStream().use { keyStore.load(it, chars) }

        val alias = keyStore.aliases().asSequence().firstOrNull { keyStore.isKeyEntry(it) }
            ?: throw IllegalStateException("the file contains no private-key entry")
        val certificate = keyStore.getCertificate(alias) as X509Certificate

        return Identity(keyStore, certificate, chars, "$label (alias=$alias)", kind)
    }
}
