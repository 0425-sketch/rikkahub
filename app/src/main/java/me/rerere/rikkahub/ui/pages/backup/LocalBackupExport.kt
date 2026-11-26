package me.rerere.rikkahub.ui.pages.backup

import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.sync.BackupDocumentWriteException
import me.rerere.rikkahub.data.sync.PreparedBackup
import me.rerere.rikkahub.data.sync.finishLocalBackup
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal class LocalBackupExport(val busy: Boolean, val launch: () -> Unit)

// Activity results can arrive in a recreated activity. Keep the validated file and its exact contents together.
private val pendingBackupSaver = listSaver<PreparedBackup?, Any>(
    save = { backup ->
        backup?.let { listOf(it.file.absolutePath, it.attachmentCount, it.hasDatabase, it.hasSettings, it.byteCount) }
            .orEmpty()
    },
    restore = {
        PreparedBackup(
            file = File(it[0] as String),
            attachmentCount = it[1] as Int,
            hasDatabase = it[2] as Boolean,
            hasSettings = it[3] as Boolean,
            byteCount = it[4] as Long,
        )
    },
)

@Composable
internal fun rememberLocalBackupExport(
    prepare: suspend () -> PreparedBackup,
    onSuccess: suspend (PreparedBackup) -> Unit,
    onError: (String) -> Unit,
): LocalBackupExport {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    var pending by rememberSaveable(stateSaver = pendingBackupSaver) { mutableStateOf(null) }
    var working by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val backup = pending
        pending = null
        if (backup == null) {
            if (uri != null) onError(context.getString(R.string.backup_page_export_interrupted))
            return@rememberLauncherForActivityResult
        }
        working = true
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                // CREATE_DOCUMENT may return an existing document. It provides no proof of creation, so never
                // delete this URI on failure. Request truncation explicitly in case the user approved replacement.
                finishLocalBackup(
                    archive = backup.file,
                    expectedBytes = backup.byteCount,
                    openDocument = uri?.let { { context.contentResolver.openOutputStream(it, "wt") } },
                    onWritten = { onSuccess(backup) },
                )
            } catch (e: CancellationException) {
                if (uri != null) {
                    Toast.makeText(context.applicationContext, R.string.backup_page_export_interrupted, Toast.LENGTH_LONG)
                        .show()
                }
                throw e
            } catch (e: BackupDocumentWriteException) {
                onError(context.getString(R.string.backup_page_export_target_unusable, e.localizedMessage.orEmpty()))
            } catch (e: Exception) {
                onError(context.getString(R.string.backup_page_export_failed, e.localizedMessage.orEmpty()))
            } finally {
                working = false
            }
        }
    }

    DisposableEffect(activity) {
        onDispose {
            if (activity?.isChangingConfigurations != true) pending?.file?.delete()
        }
    }

    return LocalBackupExport(busy = working || pending != null) {
        if (!working && pending == null) {
            working = true
            scope.launch {
                try {
                    pending = prepare()
                    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                    launcher.launch("rikkahub_backup_$timestamp.zip")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    pending?.file?.delete()
                    pending = null
                    onError(context.getString(R.string.backup_page_export_failed, e.localizedMessage.orEmpty()))
                } finally {
                    working = false
                }
            }
        }
    }
}
