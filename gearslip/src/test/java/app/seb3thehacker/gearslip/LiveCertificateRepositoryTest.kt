package app.seb3thehacker.gearslip

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in network checks. Downloaded key material remains in memory, outside the repository/APK. */
class LiveCertificateRepositoryTest {
    @Test fun `current repository identities complete TLS handshakes`() {
        assumeTrue(System.getProperty("gearslip.liveCertificates").toBoolean())
        for (source in CertProvider.Source.entries) {
            val identity = CertDownloader.fetch(source)
            assertEquals(CertProvider.Kind.DOWNLOADED, identity.kind)
            assertEquals(source, identity.downloadedSource)
            assertTrue("TLS failed for ${source.label}", TlsSelfTest.run(identity))
            assertSame(identity, CertDownloader.fetch(source, identity))
        }
    }
}
