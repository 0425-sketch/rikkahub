package me.rerere.rikkahub.data.sync

import android.app.Application
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalRestoreArchiveTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun cancellingStagingDeletesOnlyTheCopy() {
        val original = File(context.filesDir, "my-backup.zip")
        val bytes = "synthetic archive contents".toByteArray()
        original.writeBytes(bytes)
        val caller = Thread.currentThread()
        lateinit var copy: File
        assertThrows(CancellationException::class.java) {
            runBlocking {
                withLocalRestoreArchive(context, Uri.fromFile(original)) { temporary ->
                    copy = temporary
                    assertEquals(context.cacheDir.canonicalFile, temporary.parentFile!!.canonicalFile)
                    assertNotEquals(caller, Thread.currentThread())
                    assertArrayEquals(bytes, temporary.readBytes())
                    throw CancellationException("left the restore screen")
                }
            }
        }
        assertFalse(copy.exists())
        assertArrayEquals(bytes, original.readBytes())
    }

    @Test
    fun unreadableSourceCannotStartRestoreAndLeavesNoTemporaryArchive() {
        val filesBefore = context.cacheDir.list().orEmpty().toSet()
        val missing = File(context.filesDir, "missing.zip")
        assertThrows(FileNotFoundException::class.java) {
            runBlocking {
                withLocalRestoreArchive(context, Uri.fromFile(missing)) {
                    error("An unreadable source reached restore staging")
                }
            }
        }
        assertEquals(filesBefore, context.cacheDir.list().orEmpty().toSet())
    }
}
