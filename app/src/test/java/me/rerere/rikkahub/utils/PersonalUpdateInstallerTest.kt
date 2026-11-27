package me.rerere.rikkahub.utils

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest
import java.security.cert.CertificateFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@Suppress("DEPRECATION")
class PersonalUpdateInstallerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val bytes = "candidate bytes".encodeToByteArray()
    private val release = PersonalReleaseUpdate(
        211, "3.3.0-personal.11", "", "", "candidate.apk", "", bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).toHexString(),
    )
    private val certificate by lazy {
        // Public certificate extracted from the published Debug 209 APK.
        checkNotNull(javaClass.getResourceAsStream("/personal/debug-209-public.crt")).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it).encoded
        }
    }

    private fun archive(code: Int) = PackageInfo().apply {
        packageName = PersonalReleaseFeed.APPLICATION_ID
        versionCode = code
        versionName = release.versionName
        applicationInfo = ApplicationInfo()
        signatures = arrayOf(Signature(certificate))
    }

    @Test
    fun requiresMatchingSignedNewerRelease() {
        val installed = archive(210)
        release.checkArchive(archive(211), installed)
        val invalid = listOf(
            archive(211).apply { packageName = "me.rerere.rikkahub" },
            archive(211).apply { signatures = arrayOf(Signature(byteArrayOf(9))) },
            archive(211).apply { signatures = emptyArray() },
            archive(211).apply { signatures = arrayOf(Signature(certificate), Signature(byteArrayOf(9))) },
            archive(211).apply { applicationInfo!!.flags = ApplicationInfo.FLAG_DEBUGGABLE },
            archive(211).apply { versionName = "3.3.0-personal.12" },
            archive(210),
        )
        for (archive in invalid) assertThrows(IllegalStateException::class.java) {
            release.checkArchive(archive, installed)
        }
        for (installedCode in listOf(211, 212)) assertThrows(IllegalStateException::class.java) {
            release.checkArchive(archive(211), archive(installedCode))
        }
    }

    @Test
    fun checksActualBytesIncludingTruncatedOversizedAndWrongHashDownloads() {
        val file = tmp.newFile()
        runTest { release.saveVerifiedBytes(bytes.inputStream(), file) }
        assertArrayEquals(bytes, file.readBytes())
        for (invalid in listOf(bytes.copyOf(2), bytes + 0.toByte(), bytes.reversedArray())) {
            assertThrows(IllegalStateException::class.java) {
                runTest { release.saveVerifiedBytes(invalid.inputStream(), file) }
            }
        }
    }
}
