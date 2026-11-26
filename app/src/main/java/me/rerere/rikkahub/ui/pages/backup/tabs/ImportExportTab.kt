package me.rerere.rikkahub.ui.pages.backup.tabs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.FileExport
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.importer.DeepSeekImportResult
import me.rerere.rikkahub.data.sync.withLocalRestoreArchive
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.rikkahub.ui.pages.backup.components.BackupActionIcon
import me.rerere.rikkahub.ui.pages.backup.components.backupContentItem
import me.rerere.rikkahub.ui.pages.backup.rememberLocalBackupExport
import me.rerere.ui.toast.ToastType
import java.io.File
import java.io.FileOutputStream

@Composable
fun ImportExportTab(
    vm: BackupVM,
    onShowRestartDialog: () -> Unit
) {
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val selectedBackupItems by vm.localBackupItems.collectAsStateWithLifecycle()
    var isRestoring by remember { mutableStateOf(false) }
    var showImportConfirmDialog by remember { mutableStateOf(false) }
    var deepSeekImportResult by remember { mutableStateOf<DeepSeekImportResult?>(null) }

    // 导入类型：local、chatbox、cherry、deepseek
    var importType by rememberSaveable { mutableStateOf("local") }

    val export = rememberLocalBackupExport(
        prepare = vm::prepareLocalBackup,
        onSuccess = {
            vm.recordBackupTime()
            toaster.show(context.getString(R.string.backup_page_backup_success), type = ToastType.Success)
        },
        onError = { message -> toaster.show(message, type = ToastType.Error) },
    )

    // 创建文件选择的launcher
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { sourceUri ->
            scope.launch {
                isRestoring = true
                try {
                    when (importType) {
                        "local" -> withLocalRestoreArchive(context, sourceUri, vm::restoreFromLocalFile)

                        "chatbox" -> {
                            // Chatbox Backup v2：处理 ZIP 文件
                            val tempFile =
                                File(context.cacheDir, "temp_chatbox_${System.currentTimeMillis()}.zip")

                            try {
                                context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                                    FileOutputStream(tempFile).use { outputStream ->
                                        inputStream.copyTo(outputStream)
                                    }
                                }

                                vm.restoreFromChatBox(tempFile)
                            } finally {
                                tempFile.delete()
                            }
                        }

                        "deepseek" -> {
                            deepSeekImportResult = withContext(Dispatchers.IO) {
                                val tempFile = File.createTempFile("temp_deepseek_", ".zip", context.cacheDir)
                                try {
                                    requireNotNull(context.contentResolver.openInputStream(sourceUri)) {
                                        context.getString(R.string.backup_page_import_deepseek_read_error)
                                    }.use { input ->
                                        tempFile.outputStream().use { output -> input.copyTo(output) }
                                    }
                                    vm.restoreFromDeepSeek(tempFile)
                                } finally {
                                    tempFile.delete()
                                }
                            }
                        }

                        "cherry" -> {
                            // Cherry Studio导入：处理zip文件
                            val tempFile =
                                File(context.cacheDir, "temp_cherry_${System.currentTimeMillis()}.zip")

                            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                                FileOutputStream(tempFile).use { outputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }

                            // 从Cherry Studio备份恢复
                            vm.restoreFromCherryStudio(tempFile)

                            // 清理临时文件
                            tempFile.delete()
                        }
                    }

                    if (importType != "deepseek") {
                        if (importType != "local") {
                            toaster.show(
                                context.getString(R.string.backup_page_restore_success),
                                type = ToastType.Success
                            )
                        }
                        onShowRestartDialog()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                    toaster.show(
                        context.getString(R.string.backup_page_restore_failed, e.message ?: ""),
                        type = ToastType.Error
                    )
                } finally {
                    isRestoring = false
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CardGroup(
            title = { Text(stringResource(R.string.backup_page_local_backup_export)) },
        ) {
            backupContentItem(
                options = WebDavConfig.BackupItem.entries,
                selected = selectedBackupItems,
                onChange = { vm.updateLocalBackupItems(it) },
            ) { item ->
                when (item) {
                    WebDavConfig.BackupItem.DATABASE -> stringResource(R.string.backup_page_chat_records)
                    WebDavConfig.BackupItem.FILES -> stringResource(R.string.backup_page_files)
                }
            }
            item(
                onClick = if (!export.busy && !isRestoring) export.launch else null,
                headlineContent = { Text(stringResource(R.string.backup_page_local_backup_export)) },
                supportingContent = {
                    Text(
                        if (export.busy) {
                            stringResource(R.string.backup_page_exporting)
                        } else {
                            stringResource(R.string.backup_page_local_export_desc)
                        }
                    )
                },
                leadingContent = { BackupActionIcon(HugeIcons.FileExport, loading = export.busy) },
            )

            item(
                onClick = if (!isRestoring && !export.busy) {
                    {
                        showImportConfirmDialog = true
                    }
                } else null,
                headlineContent = { Text(stringResource(R.string.backup_page_local_backup_import)) },
                supportingContent = {
                    Text(
                        if (isRestoring && importType == "local") {
                            stringResource(R.string.backup_page_importing)
                        } else {
                            stringResource(R.string.backup_page_import_desc)
                        }
                    )
                },
                leadingContent = {
                    BackupActionIcon(HugeIcons.FileImport, loading = isRestoring && importType == "local")
                },
            )
        }

        CardGroup(
            title = { Text(stringResource(R.string.backup_page_import_from_other_app)) },
        ) {
            item(
                onClick = if (!isRestoring && !export.busy) {
                    {
                        importType = "deepseek"
                        openDocumentLauncher.launch(arrayOf("application/zip"))
                    }
                } else null,
                headlineContent = { Text(stringResource(R.string.backup_page_import_from_deepseek)) },
                supportingContent = {
                    Text(stringResource(R.string.backup_page_import_deepseek_desc))
                },
                leadingContent = {
                    BackupActionIcon(HugeIcons.FileImport, loading = isRestoring && importType == "deepseek")
                },
            )

            item(
                onClick = if (!isRestoring && !export.busy) {
                    {
                        importType = "chatbox"
                        openDocumentLauncher.launch(arrayOf("application/zip"))
                    }
                } else null,
                headlineContent = { Text(stringResource(R.string.backup_page_import_from_chatbox)) },
                supportingContent = { Text(stringResource(R.string.backup_page_import_chatbox_desc)) },
                leadingContent = {
                    BackupActionIcon(HugeIcons.FileImport, loading = isRestoring && importType == "chatbox")
                },
            )

            item(
                onClick = if (!isRestoring && !export.busy) {
                    {
                        importType = "cherry"
                        openDocumentLauncher.launch(arrayOf("application/zip"))
                    }
                } else null,
                headlineContent = { Text(stringResource(R.string.backup_page_import_from_cherry_studio)) },
                supportingContent = { Text(stringResource(R.string.backup_page_import_cherry_studio_desc)) },
                leadingContent = {
                    BackupActionIcon(HugeIcons.FileImport, loading = isRestoring && importType == "cherry")
                },
            )
        }
    }

    deepSeekImportResult?.let { result ->
        AlertDialog(
            onDismissRequest = { deepSeekImportResult = null },
            title = { Text(stringResource(R.string.backup_page_import_deepseek_complete)) },
            text = {
                Text(
                    stringResource(
                        R.string.backup_page_import_deepseek_summary,
                        result.importedConversations,
                        result.skippedExistingConversations,
                        result.skippedEmptyConversations,
                        result.missingAttachments,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { deepSeekImportResult = null }) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }

    if (showImportConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showImportConfirmDialog = false },
            title = { Text(stringResource(R.string.backup_page_local_backup_import)) },
            text = { Text(stringResource(R.string.backup_page_local_import_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportConfirmDialog = false
                        importType = "local"
                        openDocumentLauncher.launch(arrayOf("application/zip"))
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirmDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
