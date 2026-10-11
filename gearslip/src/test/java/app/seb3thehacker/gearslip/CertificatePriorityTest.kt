package app.seb3thehacker.gearslip

import android.content.ContextWrapper
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CertificatePriorityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `old downloads are ignored while existing imports keep priority over repository identities`() {
        val directory = temporary.newFolder()
        val context = object : ContextWrapper(null) {
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getExternalFilesDir(type: String?) = File(directory, "external").apply { mkdirs() }
        }
        fun bytes(identity: CertProvider.Identity, password: String = "") = ByteArrayOutputStream().use {
            identity.keyStore.store(it, password.toCharArray()); it.toByteArray()
        }
        val source = CertProvider.Source.ANDROID_AUTO
        val legacy = ProjectionIdentityFixture().identity
        File(context.filesDir, "identity-downloaded").apply { mkdirs() }
            .resolve("phone.p12").writeBytes(bytes(legacy, "aaspike"))
        // An old download cannot be used while the new repository download is unavailable.
        assertEquals(CertProvider.Kind.SELF_SIGNED, CertProvider.load(context, source).kind)

        // Simulate a password-protected manual import saved by the original app.
        val imported = ProjectionIdentityFixture().identity
        val importDirectory = File(context.filesDir, "identity").apply { mkdirs() }
        val importedBytes = bytes(imported, "custom-password")
        importDirectory.resolve("phone.p12").writeBytes(importedBytes)
        importDirectory.resolve("phone.pass").writeText("custom-password")
        val activeImport = CertProvider.load(context, source)
        assertEquals(imported.certificate, activeImport.certificate)
        assertEquals(CertProvider.Kind.IMPORTED, activeImport.kind)
        assertNull(activeImport.downloadedSource)

        val downloaded = ProjectionIdentityFixture().identity
        ProjectionIdentityStore(File(context.filesDir, "projection-identities")).write(source, downloaded)
        assertEquals(imported.certificate, CertProvider.load(context, source).certificate)
        assertArrayEquals(importedBytes, importDirectory.resolve("phone.p12").readBytes())
        assertEquals("custom-password", importDirectory.resolve("phone.pass").readText())
        CertProvider.removeImported(context)
        val activeDownload = CertProvider.load(context, source)
        assertEquals(downloaded.certificate, activeDownload.certificate)
        assertEquals(CertProvider.Kind.DOWNLOADED, activeDownload.kind)
        assertEquals(source, activeDownload.downloadedSource)

        val staged = ProjectionIdentityFixture().identity
        context.getExternalFilesDir(null).resolve("phone.p12").writeBytes(bytes(staged))
        context.getExternalFilesDir(null).resolve("phone.pass").writeText("")
        assertEquals(staged.certificate, CertProvider.load(context, CertProvider.Source.ANDROID_AUTO).certificate)
    }
}
