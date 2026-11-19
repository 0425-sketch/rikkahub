package me.rerere.rikkahub.ui.pages.mediacreation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.CompletableDeferred
import me.rerere.rikkahub.data.ai.imagehistory.ImageHistoryDecision
import me.rerere.rikkahub.data.model.MediaImageVersionId
import java.io.File

@Composable
internal fun PlatformImageEditDialog(
    target: PlatformEditTarget,
    vm: MediaCreationVM,
    historyAvailable: Boolean,
    onDismiss: () -> Unit,
) {
    val originals by produceState(emptyMap<MediaImageVersionId, File>(), target) {
        value = vm.platformEditor.historyFiles()
    }
    var choices by remember { mutableStateOf(emptyList<ImageHistoryDecision.Edit>()) }
    var pending by remember { mutableStateOf<CompletableDeferred<ImageHistoryDecision.Edit?>?>(null) }
    PlatformImageEditor(
        base = target.base,
        draftDir = vm.platformEditor.draftDir,
        modelId = target.modelId,
        initialParams = target.params,
        initialReferences = target.otherInputs.map { vm.resolve(it.path) },
        initialPrompt = target.prompt,
        historyImages = originals.values.toList(),
        historyAvailable = historyAvailable && target.version != null,
        onDismiss = onDismiss,
        onSubmit = { edit ->
            vm.platformEditor.submit(target, edit) { alternatives ->
                val answer = CompletableDeferred<ImageHistoryDecision.Edit?>()
                pending = answer
                choices = alternatives
                try {
                    answer.await() ?: error("已取消选择，可更改描述或手动选择参考图")
                } finally {
                    choices = emptyList()
                    pending = null
                }
            }
            target.sourceDraft?.let(vm::clearSubmittedEditorDraft)
        },
    )
    if (choices.isNotEmpty()) AlertDialog(
        onDismissRequest = { pending?.complete(null) },
        title = { Text("你指的是哪一版？") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(choices) { candidate ->
                    Surface(
                        onClick = { pending?.complete(candidate) },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(listOf(candidate.base) + candidate.references) { version ->
                                    Column {
                                        AsyncImage(
                                            model = originals[version], contentDescription = null,
                                            contentScale = ContentScale.Fit, modifier = Modifier.size(72.dp),
                                        )
                                        Text(if (version == candidate.base) "底图" else "参考图",
                                            style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            Text(candidate.instruction)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { pending?.complete(null) }) { Text("返回编辑") } },
    )
}
