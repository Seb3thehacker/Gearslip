package app.seb3thehacker.gearslip

import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPrivateKey
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectionIdentityParserTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `supports Android providers without CRT key parameters`() {
        val sample = ProjectionIdentityFixture()
        val original = sample.identity.keyStore.getKey("projection", CharArray(0)) as RSAPrivateKey
        val basic: RSAPrivateKey = object : RSAPrivateKey by original {}
        assertFalse(basic is RSAPrivateCrtKey)
        val identity = ProjectionIdentityParser.identity(listOf(sample.phone, sample.root), basic, "test", CertProvider.Source.ANDROID_AUTO)
        assertTrue(TlsSelfTest.run(identity))
    }

    @Test fun `bounded reads reject oversized input`() {
        assertThrows(IllegalArgumentException::class.java) { ProjectionIdentityParser.readLimited(ByteArray(10).inputStream(), 9) }
        assertEquals(10, ProjectionIdentityParser.readLimited(ByteArray(10).inputStream(), 10).size)
    }

    @Test fun `saved identities survive restarts and failed downloads`() {
        val directory = temporary.newFolder()
        val store = ProjectionIdentityStore(directory)
        val old = ProjectionIdentityFixture().identity
        store.write(CertProvider.Source.ANDROID_AUTO, old)
        assertThrows(IllegalStateException::class.java) {
            val next = CertDownloader.fetch(CertProvider.Source.ANDROID_AUTO, old) { error("offline") }
            store.write(CertProvider.Source.ANDROID_AUTO, next)
        }
        val restored = ProjectionIdentityStore(directory).read(CertProvider.Source.ANDROID_AUTO)!!
        assertEquals(old.certificate, restored.certificate)
        assertEquals(CertProvider.Kind.DOWNLOADED, restored.kind)
        assertEquals(CertProvider.Source.ANDROID_AUTO, restored.downloadedSource)
        assertTrue(TlsSelfTest.run(restored))
        val updated = ProjectionIdentityFixture().identity
        store.write(CertProvider.Source.ANDROID_AUTO, updated)
        assertEquals(updated.certificate, ProjectionIdentityStore(directory).read(CertProvider.Source.ANDROID_AUTO)!!.certificate)
    }
}
