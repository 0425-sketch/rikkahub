package me.rerere.rikkahub.ui.pages.mediacreation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.rerere.mediagen.provider.providers.openaiplatform.PlatformImageCapabilities
import me.rerere.mediagen.provider.providers.openaiplatform.PlatformImageOptions
import me.rerere.rikkahub.data.model.MediaCreationParams
import me.rerere.rikkahub.ui.components.ai.PickerHeader
import me.rerere.ui.components.FormItem

private const val SVG_AUTHOR_EXAMPLE = "gpt-6.2-sol"

internal fun platformParameterError(
    modelId: String,
    editing: Boolean,
    params: MediaCreationParams,
    mask: Boolean = false,
    markup: Boolean = false,
): String? = try {
    params.platformOptions.validate(modelId, editing, params.count, params.resolution, mask, markup)
    null
} catch (error: IllegalArgumentException) {
    error.message
}

internal fun MediaCreationParams.platformSummary(): List<String> = listOfNotNull(
    platformOptions.outputFormat?.uppercase(),
    resolution?.replace('x', '×'),
    count?.let { "${it} 张" },
    platformOptions.quality?.let { "画质 $it" },
    platformOptions.complexity?.let { "规划 $it" },
    platformOptions.background,
    platformOptions.frameCount?.let { "${it} 帧" },
    platformOptions.frameDurationMs?.let { "${it} ms" },
    platformOptions.outputCompression?.let { "压缩 $it" },
    platformOptions.moderation?.let { "审核 $it" },
    "流式".takeIf { platformOptions.stream == true },
    platformOptions.partialImages?.let { "${it} 次预览" },
    platformOptions.svgModel,
    platformOptions.svgReasoningEffort,
)

/** Changing the output format also clears controls no longer available for that format. */
internal fun MediaCreationParams.selectPlatformFormat(format: String?): MediaCreationParams {
    val svg = format == "svg"
    val gif = format == "gif"
    val compressible = format == "jpeg" || format == "webp"
    return copy(
        count = count.takeUnless { svg && it != 1 },
        platformOptions = platformOptions.copy(
            outputFormat = format,
            background = platformOptions.background.takeUnless { it == "transparent" && (gif || format == "jpeg") },
            outputCompression = platformOptions.outputCompression.takeIf { compressible },
            stream = platformOptions.stream.takeUnless { gif || svg },
            partialImages = platformOptions.partialImages.takeUnless { gif || svg },
            frameCount = platformOptions.frameCount.takeIf { gif },
            frameDurationMs = platformOptions.frameDurationMs.takeIf { gif },
            svgModel = platformOptions.svgModel.takeIf { svg },
            svgReasoningEffort = platformOptions.svgReasoningEffort.takeIf { svg },
        ),
    )
}

internal fun MediaCreationParams.selectPlatformCount(count: Int?): MediaCreationParams {
    val options = if ((count ?: 1) > 1) {
        platformOptions.copy(stream = null, partialImages = null)
    } else {
        platformOptions
    }
    return copy(count = count, platformOptions = options)
}

internal fun PlatformImageOptions.selectSvgAuthor(input: String): PlatformImageOptions {
    val author = input.trim().ifEmpty { null }
    return copy(
        svgModel = author,
        svgReasoningEffort = svgReasoningEffort.takeIf { author != null && author == svgModel },
    )
}

@Composable
internal fun PlatformImageParamsSheet(
    modelId: String,
    editing: Boolean,
    params: MediaCreationParams,
    onParamsChange: (MediaCreationParams) -> Unit,
    onDismiss: () -> Unit,
    mask: Boolean = false,
    markup: Boolean = false,
) {
    var current by remember(modelId) { mutableStateOf(params) }
    fun update(value: MediaCreationParams) {
        current = value
        onParamsChange(value)
    }
    fun options(value: PlatformImageOptions) = update(current.copy(platformOptions = value))

    val capabilities = remember(modelId) { PlatformImageCapabilities.forModel(modelId) }
    val opt = current.platformOptions
    val format = opt.outputFormat ?: "png"
    val error = platformParameterError(modelId, editing, current, mask, markup)
    ComposerSheet(onDismiss = onDismiss) {
        PickerHeader(
            title = if (editing) "编辑参数" else "生成参数",
            hint = modelId,
        ) {
            TextButton(onClick = {
                update(current.copy(count = null, resolution = null, platformOptions = PlatformImageOptions()))
            }) { Text("恢复默认") }
        }
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
        }
        Column(
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Choice("输出格式", opt.outputFormat, capabilities.formats(editing).filterNot { mask && it == "gif" },
                label = { it.uppercase() }) { update(current.selectPlatformFormat(it)) }
            SizeControl(current.resolution, format == "gif") { update(current.copy(resolution = it)) }
            Choice(
                title = "画质",
                value = opt.quality,
                choices = capabilities.qualities,
                hint = "控制成图细节和渲染质量。" + if (capabilities.complexities.isNotEmpty()) "默认 high。" else "",
            ) { options(opt.copy(quality = it)) }
            if (capabilities.complexities.isNotEmpty()) {
                Choice("规划复杂度", opt.complexity, capabilities.complexities,
                    hint = "控制生成前用于构图和理解要求的规划量。默认 high。") { options(opt.copy(complexity = it)) }
            }
            if (format == "svg") {
                Text("SVG 每次生成 1 张。")
            } else {
                NumberParam("数量（1–10）", current.count?.toLong(), listOf(1L, 2L, 4L),
                    onValueChange = { update(current.selectPlatformCount(it?.toInt())) }, maxDigits = 2)
            }
            val backgrounds = if (format == "gif" || format == "jpeg") listOf("auto", "opaque")
                else listOf("auto", "opaque", "transparent")
            Choice("背景", opt.background, backgrounds, label = {
                when (it) {
                    "auto" -> "自动"
                    "opaque" -> "不透明"
                    else -> "透明"
                }
            }) { options(opt.copy(background = it)) }
            if (format == "jpeg" || format == "webp") {
                NumberParam("压缩质量（0–100）", opt.outputCompression?.toLong(), listOf(75L, 90L, 100L),
                    onValueChange = { options(opt.copy(outputCompression = it?.toInt())) }, allowZero = true)
            }
            if (format == "gif") {
                NumberParam("帧数（2–16，默认 9）", opt.frameCount?.toLong(), listOf(4L, 9L, 16L),
                    onValueChange = { options(opt.copy(frameCount = it?.toInt())) }, maxDigits = 2)
                NumberParam("帧间隔（20–2000 毫秒，默认 200）", opt.frameDurationMs?.toLong(), listOf(100L, 200L, 500L),
                    onValueChange = { options(opt.copy(frameDurationMs = it?.toInt())) }, maxDigits = 4)
                Text("GIF 以 10 毫秒为单位保存帧间隔。可用静态图片作为动画参考。", style = MaterialTheme.typography.bodySmall)
            } else if (format == "svg") {
                FormItem(
                    label = { Text("SVG 编写模型（可选）") },
                    description = {
                        Text("$modelId 先生成参考图，编写模型再根据参考图编写 SVG 矢量图。两步均使用当前 OpenAI Platform 项目的 API 额度。")
                    },
                ) {
                    OutlinedTextField(
                        value = opt.svgModel.orEmpty(),
                        onValueChange = { options(opt.selectSvgAuthor(it)) },
                        label = { Text("Platform 模型 ID") },
                        supportingText = { Text("留空时请求会省略编写模型参数。若项目要求指定，请填入该项目有权使用的模型 ID。") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    TextButton(onClick = { options(opt.selectSvgAuthor(SVG_AUTHOR_EXAMPLE)) }) {
                        Text("填入示例 $SVG_AUTHOR_EXAMPLE")
                    }
                }
                if (!opt.svgModel.isNullOrBlank()) {
                    Choice("编写模型的推理强度", opt.svgReasoningEffort,
                        listOf("none", "minimal", "low", "medium", "high", "xhigh", "max"),
                        hint = "可用档位取决于所填的编写模型。") { options(opt.copy(svgReasoningEffort = it)) }
                }
            } else if ((current.count ?: 1) > 1) {
                FormItem(
                    label = { Text("流式生成") },
                    description = { Text("流式仅支持 1 张，若要预览请将数量改为 1。") },
                ) {
                    if (opt.stream == true || opt.partialImages != null) {
                        TextButton(onClick = { update(current.selectPlatformCount(current.count)) }) {
                            Text("关闭流式，保留 ${current.count} 张")
                        }
                    }
                }
            } else {
                Choice("流式生成", opt.stream, listOf(true), label = { "开启" }) {
                    options(opt.copy(stream = it, partialImages = opt.partialImages.takeIf { _ -> it == true }))
                }
                if (opt.stream == true) {
                    Choice("中间预览数量", opt.partialImages, (0..3).toList(),
                        hint = "Images API 默认返回 0 张中间预览。想看生成过程可选 1–3 张。生成较快时，收到的预览可能少于所选数量。图库仅保存最终图片。") { options(opt.copy(partialImages = it)) }
                }
            }
            Choice("内容审核（moderation）", opt.moderation, listOf("auto", "low")) {
                options(opt.copy(moderation = it))
            }
        }
        TextButton(onClick = onDismiss, enabled = error == null, modifier = Modifier.fillMaxWidth()) { Text("完成") }
    }
}

@Composable
private fun <T> Choice(
    title: String,
    value: T?,
    choices: List<T>,
    hint: String? = null,
    label: (T) -> String = { it.toString() },
    change: (T?) -> Unit,
) {
    FormItem(label = { Text(title) }, description = hint?.let { { Text(it) } }) {
        PresetChips(value, choices, default = null, onValueChange = change, label = label)
    }
}

private val stillSizes = mapOf(
    "1024x1024" to "1:1", "1536x1024" to "3:2", "1024x1536" to "2:3",
    "1344x1008" to "4:3", "1008x1344" to "3:4", "1792x1008" to "16:9", "1008x1792" to "9:16",
    "1792x768" to "21:9",
)
private val animationSizes = mapOf(
    "512x512" to "1:1", "768x512" to "3:2", "512x768" to "2:3",
    "768x576" to "4:3", "576x768" to "3:4", "768x432" to "16:9", "432x768" to "9:16",
)

@Composable
private fun SizeControl(value: String?, gif: Boolean, change: (String?) -> Unit) {
    val sizes = if (gif) animationSizes else stillSizes
    FormItem(label = { Text("尺寸") }, description = { Text("默认自动决定尺寸。可选比例或输入像素宽高。") }) {
        PresetChips(
            value = value,
            presets = sizes.keys.toList(),
            default = null,
            onValueChange = change,
            label = { "${sizes[it]} · ${it.replace('x', '×')}" },
        )
        val dimensions = value?.takeUnless { it == "auto" }.orEmpty().split('x', limit = 2)
        val width = dimensions.first()
        val height = dimensions.getOrNull(1).orEmpty()
        fun updateSize(w: String, h: String) = change(if (w.isEmpty() && h.isEmpty()) null else "${w}x$h")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = width,
                onValueChange = { if (it.all(Char::isDigit)) updateSize(it, height) },
                label = { Text("宽（像素）") },
                placeholder = { Text("自动") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = height,
                onValueChange = { if (it.all(Char::isDigit)) updateSize(width, it) },
                label = { Text("高（像素）") },
                placeholder = { Text("自动") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }
    }
}
