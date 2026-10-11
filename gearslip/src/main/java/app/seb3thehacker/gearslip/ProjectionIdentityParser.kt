package app.seb3thehacker.gearslip

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/** Parses downloaded keys and validates complete projection identities before they are saved. */
internal object ProjectionIdentityParser {
    private const val MAX_BLOCK = 32 * 1024
    internal fun identity(chain: List<X509Certificate>, key: RSAPrivateKey, source: String, downloadedSource: CertProvider.Source): CertProvider.Identity {
        require(chain.isNotEmpty()) { "Empty certificate chain" }
        val publicKey = chain.first().publicKey as? RSAPublicKey ?: error("Certificate is not RSA")
        require(key.modulus == publicKey.modulus) { "Certificate and private key do not match" }
        // Some Android providers expose only RSAPrivateKey, even for a PKCS#8 CRT key.
        // A signature proves the pair matches without requiring provider-specific key details.
        val proof = Signature.getInstance("SHA256withRSA").run {
            initSign(key)
            update(chain.first().encoded)
            sign()
        }
        require(Signature.getInstance("SHA256withRSA").run {
            initVerify(publicKey)
            update(chain.first().encoded)
            verify(proof)
        }) { "Certificate and private key do not match" }
        chain.zipWithNext().forEach { (child, parent) ->
            require(child.issuerX500Principal == parent.subjectX500Principal) { "Certificate chain is out of order" }
            child.verify(parent.publicKey)
        }
        val password = CharArray(0)
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("projection", key, password, chain.toTypedArray())
        }
        return CertProvider.Identity(store, chain.first(), password, source, CertProvider.Kind.DOWNLOADED, downloadedSource)
    }

    internal fun privateKey(pem: String): RSAPrivateKey {
        val block = pemBlocks(pem, "PRIVATE KEY").single()
        val encoded = block.removePrefix("-----BEGIN PRIVATE KEY-----").removeSuffix("-----END PRIVATE KEY-----").filterNot { it.isWhitespace() }
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded))) as RSAPrivateKey
    }

    private fun pemBlocks(text: String, type: String): List<String> {
        val header = "-----BEGIN $type-----"
        val footer = "-----END $type-----"
        val blocks = mutableListOf<String>()
        var start = text.indexOf(header)
        while (start >= 0 && blocks.size < 32) {
            val end = text.indexOf(footer, start + header.length)
            if (end >= 0 && end - start <= MAX_BLOCK) blocks += text.substring(start, end + footer.length)
            start = text.indexOf(header, start + header.length)
        }
        return blocks
    }

    internal fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            require(output.size().toLong() + read <= limit) { "Certificate source exceeds the size limit" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

}
