package app.seb3thehacker.gearslip

import java.security.MessageDigest
import java.security.SignatureException
import java.security.cert.X509Certificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CertDownloaderTest {
    private fun hash(certificate: X509Certificate) = MessageDigest.getInstance("SHA-256")
        .digest(certificate.encoded).joinToString("") { "%02x".format(it) }

    private fun metadata(sample: ProjectionIdentityFixture, version: String = "99.1", headUnit: Boolean = false): String {
        val certificates = if (headUnit) listOf("desktop-head-unit" to sample.headUnit)
            else listOf("carservice" to sample.phone, "google-automotive-link" to sample.root)
        return JSONObject().put("version", version).put("certificates", JSONArray(certificates.map { (kind, certificate) ->
            JSONObject().put("kind", kind).put("sha256", hash(certificate))
        })).toString()
    }

    @Test fun `resolves latest to versioned files and completes TLS with the phone identity`() {
        val sample = ProjectionIdentityFixture()
        val urls = mutableListOf<String>()
        val identity = CertDownloader.fetch(CertProvider.Source.ANDROID_AUTO) { url ->
            urls += url
            when (url.substringAfterLast('/')) {
                "metadata.json" -> metadata(sample)
                "certificate.pem" -> sample.leafPem + sample.rootPem
                "private-key.pem" -> sample.keyPem
                else -> error("unexpected request")
            }
        }
        assertTrue(urls[0].endsWith("/android-auto/latest/metadata.json"))
        assertTrue(urls[1].endsWith("/android-auto/99.1/certificate.pem"))
        assertTrue(urls[2].endsWith("/android-auto/99.1/private-key.pem"))
        assertEquals(sample.phone, identity.certificate)
        assertTrue(TlsSelfTest.run(identity))
    }

    @Test fun `downloads the leaf-only DHU identity`() {
        val sample = ProjectionIdentityFixture()
        val identity = CertDownloader.fetch(CertProvider.Source.HEAD_UNIT) { url ->
            assertTrue(url.contains("/dhu/"))
            when (url.substringAfterLast('/')) {
                "metadata.json" -> metadata(sample, headUnit = true)
                "certificate.pem" -> sample.headUnitPem
                else -> sample.keyPem
            }
        }
        assertEquals(1, identity.keyStore.getCertificateChain("projection").size)
        assertEquals(sample.headUnit, identity.certificate)
        assertTrue(TlsSelfTest.run(identity))
    }

    @Test fun `unchanged metadata reuses the cached identity without downloading files`() {
        val sample = ProjectionIdentityFixture()
        var requests = 0
        val identity = CertDownloader.fetch(CertProvider.Source.ANDROID_AUTO, sample.identity) { url ->
            requests++
            assertTrue(url.endsWith("/latest/metadata.json"))
            metadata(sample, version = "100.0")
        }
        assertSame(sample.identity, identity)
        assertEquals(1, requests)
    }

    @Test fun `changed metadata replaces an older identity`() {
        val old = ProjectionIdentityFixture().identity
        val sample = ProjectionIdentityFixture()
        val identity = CertDownloader.fetch(CertProvider.Source.ANDROID_AUTO, old) { url ->
            when (url.substringAfterLast('/')) {
                "metadata.json" -> metadata(sample)
                "certificate.pem" -> sample.leafPem + sample.rootPem
                else -> sample.keyPem
            }
        }
        assertNotEquals(old.certificate, identity.certificate)
        assertEquals(sample.phone, identity.certificate)
    }

    @Test fun `rejects a certificate changed since metadata was read`() {
        val sample = ProjectionIdentityFixture()
        assertThrows(IllegalArgumentException::class.java) {
            CertDownloader.verifiedChain(sample.leafPem + sample.rootPem, listOf("00".repeat(32), hash(sample.root)))
        }
    }

    @Test fun `rejects a different root even when its hash matches metadata`() {
        val sample = ProjectionIdentityFixture()
        val other = ProjectionIdentityFixture()
        assertThrows(SignatureException::class.java) {
            CertDownloader.verifiedChain(sample.leafPem + other.rootPem, listOf(hash(sample.phone), hash(other.root)))
        }
    }

    @Test fun `rejects mismatched private keys before returning an identity`() {
        val sample = ProjectionIdentityFixture()
        val wrong = ProjectionIdentityFixture()
        assertThrows(IllegalArgumentException::class.java) {
            CertDownloader.fetch(CertProvider.Source.ANDROID_AUTO) { url ->
                when (url.substringAfterLast('/')) {
                    "metadata.json" -> metadata(sample)
                    "certificate.pem" -> sample.leafPem + sample.rootPem
                    else -> wrong.keyPem
                }
            }
        }
    }

    @Test fun `rejects malformed metadata before downloading identity files`() {
        for (body in listOf("{}", "not json", "{\"version\":\"1\",\"certificates\":[]}")) {
            var requests = 0
            assertThrows(Exception::class.java) {
                CertDownloader.fetch(CertProvider.Source.ANDROID_AUTO) { requests++; body }
            }
            assertEquals(1, requests)
        }
    }

    @Test fun `rejects paths outside the version directory`() {
        assertTrue(CertDownloader.versionDirectory("android-auto", "17.9.664004-release").endsWith("/android-auto/17.9.664004-release"))
        for (version in listOf("../latest", "", "/latest", "1?redirect=x", "..")) {
            assertThrows(IllegalArgumentException::class.java) { CertDownloader.versionDirectory("android-auto", version) }
        }
    }
}
