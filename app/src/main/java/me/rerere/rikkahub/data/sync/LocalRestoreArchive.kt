package me.rerere.rikkahub.data.sync

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import java.io.File
import java.io.IOException

/** Keep the picker document read-only. Restore always operates on our private copy. */
internal suspend fun withLocalRestoreArchive(context: Context, uri: Uri, restore: suspend (File) -> Unit) =
    withContext(Dispatchers.IO) {
        val archive = File.createTempFile("local-restore-", ".zip", context.cacheDir)
        try {
            runInterruptible {
                val source = context.contentResolver.openInputStream(uri)
                    ?: throw IOException(context.getString(R.string.backup_page_local_read_error))
                source.use { input -> archive.outputStream().use { output -> input.copyTo(output) } }
            }
            restore(archive)
        } finally {
            archive.delete()
        }
    }
