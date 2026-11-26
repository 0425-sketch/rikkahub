package me.rerere.rikkahub.ui.activity

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.sync.BackupManager
import me.rerere.rikkahub.data.sync.PreparedBackup
import me.rerere.rikkahub.data.sync.withLocalRestoreArchive
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.pages.backup.LocalBackupExport
import me.rerere.rikkahub.ui.pages.backup.components.BackupDialog
import me.rerere.rikkahub.ui.pages.backup.components.backupContentItem

internal enum class RescueItem(val label: Int) {
    SETTINGS(R.string.settings),
    DATABASE(R.string.safe_mode_backup_database),
    FILES(R.string.backup_page_files),
}

internal class SafeModeBackup(
    private val context: Context,
    private val manager: BackupManager,
) : ViewModel() {
    var importing by mutableStateOf(false)
        private set
    var staged by mutableStateOf(false)
        private set
    var selected by mutableStateOf(RescueItem.entries.toList())
    var message by mutableStateOf<String?>(null)

    suspend fun prepare(): PreparedBackup {
        message = null
        return manager.prepareLocalBackup(
            includeSettings = RescueItem.SETTINGS in selected,
            includeDatabase = RescueItem.DATABASE in selected,
            includeFiles = RescueItem.FILES in selected,
            safeMode = true,
        )
    }

    fun exported(result: PreparedBackup) {
        val contents = buildList {
            if (result.hasSettings) add(context.getString(R.string.settings))
            if (result.hasDatabase) add(context.getString(R.string.safe_mode_backup_database))
            add(context.getString(R.string.safe_mode_backup_file_count, result.attachmentCount))
        }
        message = context.getString(R.string.safe_mode_backup_exported, contents.joinToString("\n"))
    }

    fun stage(uri: Uri) {
        if (importing || staged) return
        importing = true
        message = null
        viewModelScope.launch {
            try {
                withLocalRestoreArchive(context, uri) { archive ->
                    manager.stageRestore(archive, includeDatabase = true, includeFiles = true)
                }
                staged = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = context.getString(
                    R.string.backup_page_restore_failed,
                    e.localizedMessage ?: e.javaClass.simpleName,
                )
            } finally {
                importing = false
            }
        }
    }
}

@Composable
internal fun SafeModeBackupControls(backup: SafeModeBackup, export: LocalBackupExport, enabled: Boolean) {
    var confirmImport by rememberSaveable { mutableStateOf(false) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) backup.stage(uri)
    }
    CardGroup(title = { Text(stringResource(R.string.backup_page_title)) }) {
        backupContentItem(
            options = RescueItem.entries,
            selected = backup.selected,
            onChange = { if (enabled) backup.selected = it },
        ) { stringResource(it.label) }
        item(
            onClick = if (enabled && backup.selected.isNotEmpty()) export.launch else null,
            headlineContent = { Text(stringResource(R.string.backup_page_local_backup_export)) },
            supportingContent = {
                val label = if (export.busy) R.string.backup_page_exporting else R.string.backup_page_local_export_desc
                Text(stringResource(label))
            },
        )
        item(
            onClick = if (enabled) ({ confirmImport = true }) else null,
            headlineContent = { Text(stringResource(R.string.backup_page_local_backup_import)) },
            supportingContent = {
                val label = if (backup.importing) R.string.backup_page_importing else R.string.backup_page_import_desc
                Text(stringResource(label))
            },
        )
    }
    backup.message?.let { message ->
        AlertDialog(
            onDismissRequest = { backup.message = null },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { backup.message = null }) { Text(stringResource(R.string.confirm)) }
            },
        )
    }
    if (confirmImport) {
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            title = { Text(stringResource(R.string.backup_page_local_backup_import)) },
            text = {
                Text(
                    stringResource(R.string.safe_mode_import_all) + "\n\n" +
                        stringResource(R.string.backup_page_local_import_confirm)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmImport = false
                    open.launch(arrayOf("application/zip", "application/x-zip-compressed"))
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmImport = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    if (backup.staged) BackupDialog()
}
