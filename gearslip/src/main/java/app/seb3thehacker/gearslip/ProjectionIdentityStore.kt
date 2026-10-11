package app.seb3thehacker.gearslip

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.cert.X509Certificate

/** Validated identities live in private app storage; a failed refresh leaves the old file intact. */
internal class ProjectionIdentityStore(private val directory: File) {
    fun read(source: CertProvider.Source): CertProvider.Identity? {
        val file = file(source)
        if (!file.isFile) return null
        val password = CharArray(0)
        val store = KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, password) } }
        val alias = store.aliases().asSequence().firstOrNull { store.isKeyEntry(it) } ?: error("Cached certificate has no private key")
        store.getKey(alias, password) ?: error("Cached certificate key is unavailable")
        return CertProvider.Identity(store, store.getCertificate(alias) as X509Certificate, password, source.description, CertProvider.Kind.DOWNLOADED, source)
    }

    fun write(source: CertProvider.Source, identity: CertProvider.Identity) {
        directory.mkdirs()
        val temporary = File.createTempFile("identity-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                identity.keyStore.store(output, identity.password)
                output.fd.sync()
            }
            Files.move(temporary.toPath(), file(source).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    private fun file(source: CertProvider.Source) = File(directory, "${source.name.lowercase()}.p12")
}
