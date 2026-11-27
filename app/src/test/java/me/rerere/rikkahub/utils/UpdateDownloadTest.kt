package me.rerere.rikkahub.utils

import android.app.Application
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Looper
import kotlinx.coroutines.cancel
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UpdateDownloadTest {
    private val scope = AppScope()

    @After
    fun cancelScope() = scope.cancel()

    @Test
    fun `redirects stay on GitHub HTTPS release infrastructure`() {
        assertTrue("https://release-assets.githubusercontent.com/download".toHttpUrl().isGithubUpdateUrl())
        for (url in listOf("https://example.org/apk", "http://github.com/a", "https://github.com:444/a", "https://x@y.github.com/a")) {
            assertFalse(url.toHttpUrl().isGithubUpdateUrl())
        }
    }

    @Test
    fun `non-personal APK goes to public Downloads and completion leaves installation to the user`() {
        val app = RuntimeEnvironment.getApplication()
        val manager = requireNotNull(app.getSystemService(DownloadManager::class.java))
        val tag = "custom-3.3.0-personal-4ac14e80bb24-44074740868-1"
        val name = "rikkahub-3.3.0-personal.1-4ac14e80bb24-arm64.apk"
        val url = "https://github.com/0425-sketch/rikkahub/releases/download/$tag/$name"
        val selected = UpdateDownload(name, url, "58.3 MiB")

        val checker = UpdateChecker(OkHttpClient(), scope)
        if (BuildConfig.APPLICATION_ID == PersonalReleaseFeed.APPLICATION_ID) {
            assertFalse(checker.downloadUpdate(app, selected))
            assertEquals(0, manager.query(DownloadManager.Query()).use { it.count })
            return
        }
        assertTrue(checker.downloadUpdate(app, selected))
        val id = manager.query(DownloadManager.Query()).use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
        }
        val request = shadowOf(shadowOf(manager).getRequest(id))
        val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        assertEquals(url, request.uri.toString())
        assertEquals(name, request.title.toString())
        assertEquals(Uri.fromFile(File(directory, name)), request.destination)
        assertEquals("application/vnd.android.package-archive", request.mimeType.toString())
        assertEquals(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED, request.notificationVisibility)

        request.status = DownloadManager.STATUS_SUCCESSFUL
        app.sendBroadcast(Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE).setPackage(app.packageName)
            .putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, id))
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(app).nextStartedActivity)
        assertTrue(app.packageManager.packageInstaller.mySessions.isEmpty())
    }
}
