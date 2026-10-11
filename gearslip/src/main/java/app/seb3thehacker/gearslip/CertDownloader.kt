package app.seb3thehacker.gearslip

import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.json.JSONObject

/** Resolves latest metadata, then validates the certificate and key from that version directory. */
object CertDownloader {
    private const val BASE = "https://raw.githubusercontent.com/iscle/android-auto-certificates/main"

    internal fun fetch(
        source: CertProvider.Source,
        cached: CertProvider.Identity? = null,
        read: (String) -> String = ::read,
    ): CertProvider.Identity {
        val folder = if (source == CertProvider.Source.ANDROID_AUTO) "android-auto" else "dhu"
        val expectedLeaf = if (source == CertProvider.Source.ANDROID_AUTO) "carservice" else "desktop-head-unit"
        val metadata = JSONObject(read("$BASE/$folder/latest/metadata.json"))
        val version = metadata.getString("version")
        val directory = versionDirectory(folder, version)
        val entries = metadata.getJSONArray("certificates")
        require(entries.length() in 1..8) { "Invalid certificate chain length" }
        require(entries.getJSONObject(0).getString("kind") == expectedLeaf) { "Unexpected certificate identity" }
        val hashes = (0 until entries.length()).map { index ->
            entries.getJSONObject(index).getString("sha256").also {
                require(Regex("[0-9a-fA-F]{64}").matches(it)) { "Invalid certificate fingerprint" }
            }.lowercase()
        }
        // Metadata is small. When the published identity is unchanged, keep the validated cache
        // and avoid fetching its certificate/key or rewriting its PKCS#12 file.
        if (cached != null) {
            val alias = cached.keyStore.aliases().asSequence().first { cached.keyStore.isKeyEntry(it) }
            val chain = cached.keyStore.getCertificateChain(alias)
            if (chain.map { fingerprint(it as X509Certificate) } == hashes) return cached
        }
        val chain = verifiedChain(read("$directory/certificate.pem"), hashes)
        val key = ProjectionIdentityParser.privateKey(read("$directory/private-key.pem"))
        return ProjectionIdentityParser.identity(chain, key, "${source.description} ($version)", source)
    }

    internal fun versionDirectory(folder: String, version: String): String {
        require(folder in setOf("android-auto", "dhu")) { "Unknown certificate source" }
        require(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}").matches(version)) { "Invalid certificate version" }
        return "$BASE/$folder/$version"
    }

    internal fun verifiedChain(pem: String, hashes: List<String>): List<X509Certificate> {
        val chain = CertificateFactory.getInstance("X.509")
            .generateCertificates(pem.byteInputStream(Charsets.US_ASCII)).map { it as X509Certificate }
        require(chain.isNotEmpty() && chain.map(::fingerprint) == hashes.map { it.lowercase() }) {
            "Downloaded certificate does not match its metadata"
        }
        chain.zipWithNext().forEach { (child, issuer) ->
            require(child.issuerX500Principal == issuer.subjectX500Principal) { "Downloaded certificate has the wrong issuer" }
            child.verify(issuer.publicKey)
        }
        return chain
    }

    private fun fingerprint(certificate: X509Certificate) = MessageDigest.getInstance("SHA-256")
        .digest(certificate.encoded).joinToString("") { "%02x".format(it) }

    private fun read(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            useCaches = false
        }
        return try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "Certificate download failed (HTTP ${connection.responseCode})" }
            connection.inputStream.use { ProjectionIdentityParser.readLimited(it, 64 * 1024).toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }
}
