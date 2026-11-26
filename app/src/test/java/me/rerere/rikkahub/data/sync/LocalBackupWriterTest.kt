package me.rerere.rikkahub.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile

class LocalBackupWriterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val data = ByteArray(40_000) { (it % 255).toByte() }
    private fun archive(): File = folder.newFile().apply { writeBytes(data) }

    @Test
    fun recordsBackupOnlyAfterTheStreamClosesAndDeletesCache() = runBlocking {
        val file = archive()
        val caller = Thread.currentThread()
        var closed = false
        val output = object : ByteArrayOutputStream() {
            override fun close() {
                closed = true
            }
        }
        var lastBackupTime = 0L
        finishLocalBackup(file, file.length(), openDocument = {
            assertNotEquals(caller, Thread.currentThread())
            output
        }) {
            assertTrue(closed)
            lastBackupTime = 123L
        }
        assertArrayEquals(data, output.toByteArray())
        assertEquals(123L, lastBackupTime)
        assertFalse(file.exists())
    }

    @Test
    fun pickerCancellationNeverRecordsABackup() = runBlocking {
        val file = archive()
        finishLocalBackup(file, file.length(), openDocument = null) { throw AssertionError("Recorded a cancelled backup") }
        assertFalse(file.exists())
    }

    @Test
    fun nullStreamAndOpenWriteOrCloseFailuresNeverRecordABackup() {
        val openers: List<() -> OutputStream?> = listOf(
            { null },
            { throw IOException("Provider out of space") },
            {
                object : ByteArrayOutputStream() {
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        super.write(bytes, offset, length / 2)
                        throw IOException("No space left after partial write")
                    }
                }
            },
            {
                object : ByteArrayOutputStream() {
                    override fun close() = throw IOException("Close failed")
                }
            },
        )
        for (openDocument in openers) {
            val file = archive()
            var lastBackupTime = 123L
            assertThrows(BackupDocumentWriteException::class.java) {
                runBlocking { finishLocalBackup(file, file.length(), openDocument) { lastBackupTime = 456L } }
            }
            assertEquals(123L, lastBackupTime)
            assertFalse(file.exists())
        }
    }

    @Test
    fun refusesToOpenTheDestinationWhenTheVerifiedArchiveChangedInThePicker() {
        val file = archive()
        val verifiedSize = file.length()
        RandomAccessFile(file, "rw").use { it.setLength(8) }
        assertThrows(BackupDocumentWriteException::class.java) {
            runBlocking {
                finishLocalBackup(file, verifiedSize, openDocument = {
                    throw AssertionError("Opened the target for a damaged source")
                }) { throw AssertionError("Recorded a damaged backup") }
            }
        }
        assertFalse(file.exists())
    }

    @Test
    fun rejectsShortCopyWithoutRecordingSuccess() {
        val file = archive()
        assertThrows(BackupDocumentWriteException::class.java) {
            runBlocking {
                finishLocalBackup(file, file.length(), openDocument = {
                    RandomAccessFile(file, "rw").use { it.setLength(8) }
                    ByteArrayOutputStream()
                }) { throw AssertionError("Recorded a short backup") }
            }
        }
        assertFalse(file.exists())
    }

    @Test
    fun cancellationInterruptsCopyClosesTheDocumentAndRemovesCache() = runBlocking {
        val file = archive()
        var closed = false
        val job = launch {
            val copyJob = coroutineContext[Job]!!
            finishLocalBackup(file, file.length(), openDocument = {
                object : ByteArrayOutputStream() {
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        super.write(bytes, offset, length)
                        copyJob.cancel(CancellationException("Stopped"))
                    }
                    override fun close() {
                        closed = true
                    }
                }
            }) { throw AssertionError("Recorded an interrupted backup") }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(closed)
        assertFalse(file.exists())
    }
}
