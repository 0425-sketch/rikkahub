package me.rerere.rikkahub.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream

internal class BackupDocumentWriteException(cause: Exception) : IOException(cause.message, cause)

/** Consumes the prepared cache file, including when the user cancels the document picker. */
internal suspend fun finishLocalBackup(
    archive: File,
    expectedBytes: Long,
    openDocument: (() -> OutputStream?)?,
    onWritten: suspend () -> Unit,
) {
    try {
        if (openDocument == null) return
        try {
            withContext(Dispatchers.IO) {
                if (expectedBytes <= 0 || archive.length() != expectedBytes) {
                    throw IOException("Prepared backup is missing or has changed")
                }
                archive.inputStream().use { input ->
                    val stream = openDocument() ?: throw IOException("Cannot open the backup document for writing")
                    stream.use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var copied = 0L
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                        }
                        if (copied != expectedBytes) throw IOException("Copied $copied of $expectedBytes backup bytes")
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw BackupDocumentWriteException(e)
        }
        onWritten()
    } finally {
        archive.delete()
    }
}
