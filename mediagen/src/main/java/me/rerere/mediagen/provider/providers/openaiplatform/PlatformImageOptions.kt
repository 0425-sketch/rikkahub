package me.rerere.mediagen.provider.providers.openaiplatform

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Options are nullable so an untouched control delegates its default to the API. */
@Serializable
data class PlatformImageOptions(
    val quality: String? = null,
    val complexity: String? = null,
    val background: String? = null,
    val outputFormat: String? = null,
    val outputCompression: Int? = null,
    val moderation: String? = null,
    val stream: Boolean? = null,
    val partialImages: Int? = null,
    val frameCount: Int? = null,
    val frameDurationMs: Int? = null,
    val svgModel: String? = null,
    val svgReasoningEffort: String? = null,
) {
    fun validate(
        modelId: String,
        editing: Boolean,
        count: Int?,
        size: String?,
        mask: Boolean = false,
        markup: Boolean = false,
    ) {
        val capabilities = PlatformImageCapabilities.forModel(modelId)
        val format = outputFormat ?: "png"
        require(modelId.isNotBlank()) { "请选择图像模型" }
        require(quality == null || quality in capabilities.qualities) { "此模型不支持画质 $quality" }
        require(complexity == null || complexity in capabilities.complexities) { "此模型不支持复杂度 $complexity" }
        require(format in capabilities.formats(editing)) { "此模型或端点不支持 $format 输出" }
        require(count == null || count in 1..10) { "数量须为 1 至 10" }
        require(format != "svg" || count == null || count == 1) { "SVG 一次只能生成一个文件" }
        require(moderation == null || moderation in listOf("auto", "low")) { "无效的 moderation" }
        require(background == null || background in listOf("auto", "opaque", "transparent")) { "无效的背景选项" }
        require(background != "transparent" || format in listOf("png", "webp", "svg")) { "$format 不支持透明背景" }
        require(outputCompression == null || (format in listOf("jpeg", "webp") && outputCompression in 0..100)) {
            "压缩质量仅适用于 JPEG/WebP，范围为 0 至 100"
        }
        require(stream != true || format !in listOf("gif", "svg")) { "$format 不支持流式生成" }
        require(stream != true || (count ?: 1) == 1) { "流式生成只支持 1 张图，请将数量设为 1 或关闭流式生成。" }
        require(partialImages == null || (stream == true && partialImages in 0..3)) { "流式预览数量须为 0 至 3" }
        require(frameCount == null || (format == "gif" && frameCount in 2..16)) { "GIF 帧数须为 2 至 16" }
        require(frameDurationMs == null || (format == "gif" && frameDurationMs in 20..2000)) {
            "GIF 帧间隔须为 20 至 2000 毫秒"
        }
        require(svgModel == null || (format == "svg" && svgModel.isNotBlank())) { "SVG 编写模型仅适用于 SVG" }
        require(svgReasoningEffort == null || (format == "svg" && !svgModel.isNullOrBlank() && svgReasoningEffort in SVG_EFFORTS)) {
            "请先填写 SVG 编写模型，再选择该模型支持的推理强度"
        }
        require(!mask || (editing && !markup && format != "gif")) { "遮罩仅适用于静态图片编辑，且不能与标注同时使用" }
        require(!markup || (editing && capabilities.markup)) { "此模型或端点不支持标注" }
        validateSize(size, format)
    }

    internal fun writeFields(builder: JsonObjectBuilder) = with(builder) {
        quality?.let { put("quality", it) }
        complexity?.let { put("complexity", it) }
        background?.let { put("background", it) }
        outputFormat?.let { put("output_format", it) }
        outputCompression?.let { put("output_compression", it) }
        moderation?.let { put("moderation", it) }
        if (stream == true) put("stream", true)
        partialImages?.let { put("partial_images", it) }
        frameCount?.let { put("frame_count", it) }
        frameDurationMs?.let { put("frame_duration_ms", it) }
        if (svgModel != null || svgReasoningEffort != null) {
            put("svg", buildJsonObject {
                svgModel?.let { put("model", it) }
                svgReasoningEffort?.let { put("reasoning", buildJsonObject { put("effort", it) }) }
            })
        }
    }

    private fun validateSize(size: String?, format: String) {
        if (size == null || size == "auto") return
        val parts = size.split('x')
        val width = parts.firstOrNull()?.toIntOrNull() ?: 0
        val height = parts.getOrNull(1)?.toIntOrNull() ?: 0
        require(parts.size == 2 && width > 0 && height > 0 && width % 16 == 0 && height % 16 == 0) {
            "尺寸格式为 宽x高，两边均须为 16 的正整数倍"
        }
        if (format != "gif") {
            require(maxOf(width, height) <= 3840 && maxOf(width, height) <= 3L * minOf(width, height)) {
                "最长边不能超过 3840 像素，长短边比例不能超过 3:1"
            }
            require(width.toLong() * height in 655360L..8294400L) { "总像素须在 655360 至 8294400 之间" }
        }
    }

    private companion object {
        val SVG_EFFORTS = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
    }
}

class PlatformImageCapabilities private constructor(private val image26: Boolean) {
    val qualities = listOf("auto", "low", "medium", "high") + if (image26) listOf("xhigh", "max") else emptyList()
    val complexities = if (image26) listOf("low", "medium", "high") else emptyList()
    val markup get() = image26

    fun formats(editing: Boolean): List<String> = buildList {
        addAll(listOf("png", "jpeg", "webp"))
        if (image26) {
            add("gif")
            if (!editing) add("svg")
        }
    }

    companion object {
        private val image26 = Regex("""gpt-image-2\.6-(sunburst|flare)(-\d{4}-\d{2}-\d{2})?""")
        fun forModel(modelId: String) = PlatformImageCapabilities(image26.matches(modelId))
    }
}
