package me.rerere.rikkahub.ui.pages.mediacreation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import me.rerere.rikkahub.data.model.MediaCreationRecord
import kotlin.uuid.Uuid

@Composable
internal fun PlatformStreamPreview(recordId: Uuid, vm: MediaCreationVM) {
    val previews by vm.previews.collectAsStateWithLifecycle()
    previews[recordId]?.let {
        AsyncImage(
            model = it,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
        )
        Text("中间预览", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun PlatformEditSource(record: MediaCreationRecord, originalInstruction: String?) {
    record.parentVersion?.let { source ->
        Text(
            "底图 · ${source.recordId.toString().take(8)} · 第 ${source.outputIndex + 1} 张",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (originalInstruction != null) Text(originalInstruction, style = MaterialTheme.typography.bodyMedium)
}

@Composable
internal fun PlatformReturnedSettings(record: MediaCreationRecord) {
    val params = record.params
    val result = params.imageResult ?: return
    val options = params.platformOptions
    val resolved = listOfNotNull(
        result.model?.takeUnless { it == record.modelId },
        result.size?.takeUnless { it == params.resolution }?.replace('x', '×'),
        result.format?.takeUnless { it == options.outputFormat }?.uppercase(),
        result.quality?.takeUnless { it == options.quality }?.let { "画质 $it" },
        result.complexity?.takeUnless { it == options.complexity }?.let { "规划 $it" },
        result.background?.takeUnless { it == options.background }?.let { "背景 $it" },
    )
    if (resolved.isNotEmpty()) {
        Text(
            "接口返回 · " + resolved.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
