package me.rerere.rikkahub.ui.pages.mediacreation

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import me.rerere.mediagen.provider.providers.openaiplatform.PlatformImageOptions
import me.rerere.rikkahub.data.model.MediaCreationRecord
import me.rerere.rikkahub.data.model.MediaCreationStatus

internal fun platformPreviewRequest(context: Context, bytes: ByteArray): ImageRequest =
    ImageRequest.Builder(context)
        .data(bytes)
        .crossfade(false)
        .memoryCachePolicy(CachePolicy.DISABLED)
        .diskCachePolicy(CachePolicy.DISABLED)
        .build()

@Composable
internal fun PlatformStreamPreview(record: MediaCreationRecord, vm: MediaCreationVM) {
    val options = record.params.platformOptions
    if (options.stream != true) return
    val previews by vm.previews.collectAsStateWithLifecycle()
    val frame = previews[record.id]
    val requested = options.partialImages
    if (frame == null) {
        if (record.status == MediaCreationStatus.RUNNING && requested != null && requested > 0) {
            Text("等待中间预览 · 已请求 $requested 张", style = MaterialTheme.typography.labelMedium)
        }
        return
    }
    val context = LocalContext.current
    val request = remember(context, frame.bytes) { platformPreviewRequest(context, frame.bytes) }
    var lastPainter by remember(record.id) { mutableStateOf<Painter?>(null) }
    var failed by remember(frame.bytes) { mutableStateOf(false) }
    AsyncImage(
        model = request,
        contentDescription = "第 ${frame.received} 张中间预览",
        placeholder = lastPainter,
        error = lastPainter,
        onSuccess = { lastPainter = it.painter },
        onError = { failed = true },
        modifier = Modifier.fillMaxWidth().height(200.dp),
    )
    val count = if (requested == null) "${frame.received} 张" else "${frame.received} / $requested 张"
    Text("中间预览 · 实收 $count" + if (failed) " · 当前帧解码失败" else "", style = MaterialTheme.typography.labelMedium)
}

internal fun platformPreviewReceipt(options: PlatformImageOptions, received: Int?): String? {
    if (options.stream != true || (options.partialImages == null && received == null)) return null
    val requested = options.partialImages?.let { "请求 $it 张" } ?: "请求数采用 API 默认"
    val actual = received?.let { "实收 $it 张" } ?: "该记录未保存实收数量"
    return "中间预览 · $requested，$actual"
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
    platformPreviewReceipt(options, result.receivedPartialImages)?.let {
        Text(it, style = MaterialTheme.typography.labelMedium)
    }
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
