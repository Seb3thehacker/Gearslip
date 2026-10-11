package app.seb3thehacker.gearslip

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.util.Base64
import javax.security.auth.x500.X500Principal

/** Every test identity is generated at runtime, without any downloaded certificate or key files. */
internal class ProjectionIdentityFixture {
    private val rootKey = keyPair()
    private val phoneKey = keyPair()
    val root = certificate(rootKey, "Google Automotive Link", rootKey, "Google Automotive Link")
    val phone = certificate(phoneKey, "CarService", rootKey, "Google Automotive Link")
    val headUnit = certificate(phoneKey, "Android-Auto-Internal", rootKey, "Google Automotive Link")
    val leafPem = pem("CERTIFICATE", phone.encoded)
    val rootPem = pem("CERTIFICATE", root.encoded)
    val keyPem = pem("PRIVATE KEY", phoneKey.private.encoded)
    val headUnitPem = pem("CERTIFICATE", headUnit.encoded)
    val identity = ProjectionIdentityParser.identity(listOf(phone, root), phoneKey.private as RSAPrivateKey,
        CertProvider.Source.ANDROID_AUTO.description, CertProvider.Source.ANDROID_AUTO)

    private fun keyPair() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private fun pem(type: String, bytes: ByteArray) =
        "-----BEGIN $type-----\n${Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(bytes)}\n-----END $type-----\n"

    private fun certificate(subject: KeyPair, name: String, issuer: KeyPair, issuerName: String): X509Certificate {
        val algorithm = seq(byteArrayOf(6, 9, 42, -122, 72, -122, -9, 13, 1, 1, 11), tlv(5, byteArrayOf()))
        val tbs = seq(tlv(0xA0, tlv(2, byteArrayOf(2))), tlv(2, byteArrayOf(1)), algorithm,
            X500Principal("O=$issuerName").encoded,
            seq(tlv(0x17, "240101000000Z".toByteArray()), tlv(0x17, "490101000000Z".toByteArray())),
            X500Principal("O=$name").encoded, subject.public.encoded)
        val signature = Signature.getInstance("SHA256withRSA").run { initSign(issuer.private); update(tbs); sign() }
        return CertificateFactory.getInstance("X.509").generateCertificate(
            seq(tbs, algorithm, tlv(3, byteArrayOf(0) + signature)).inputStream()) as X509Certificate
    }
    private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(byteArrayOf()) { a, b -> a + b })
    private fun tlv(tag: Int, body: ByteArray): ByteArray {
        val length = when {
            body.size < 128 -> byteArrayOf(body.size.toByte())
            body.size < 256 -> byteArrayOf(0x81.toByte(), body.size.toByte())
            else -> byteArrayOf(0x82.toByte(), (body.size shr 8).toByte(), body.size.toByte())
        }
        return byteArrayOf(tag.toByte()) + length + body
    }
}
