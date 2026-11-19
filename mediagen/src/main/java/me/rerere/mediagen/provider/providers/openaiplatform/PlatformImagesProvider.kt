package me.rerere.mediagen.provider.providers.openaiplatform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.mediagen.model.MediaGenerationInput
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaGenerationOutput
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.model.MediaGenerationStatus
import me.rerere.mediagen.model.MediaGenerationTask
import me.rerere.mediagen.model.MediaGenerationUsage
import me.rerere.mediagen.provider.MediaGenerationProvider
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.providers.bearerRequest
import me.rerere.mediagen.provider.providers.executeJson
import me.rerere.mediagen.provider.providers.long
import me.rerere.mediagen.provider.providers.obj
import me.rerere.mediagen.provider.providers.postJson
import me.rerere.mediagen.provider.providers.string
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

internal class PlatformImagesProvider(
    private val client: OkHttpClient,
) : MediaGenerationProvider<MediaGenerationProviderSetting.OpenAIPlatform> {
    override val id = "openai_platform_images"

    override suspend fun create(
        setting: MediaGenerationProviderSetting.OpenAIPlatform,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ) = create(setting, model, request) {}

    suspend fun create(
        setting: MediaGenerationProviderSetting.OpenAIPlatform,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
        onPreview: suspend (MediaGenerationOutput) -> Unit,
    ): Result<MediaGenerationTask> = withContext(Dispatchers.IO) {
        try {
            require(setting.apiKey.isNotBlank()) { "请先填写 OpenAI Platform API Key" }
            val fields = fields(model, request)
            val editing = request.inputs.isNotEmpty()
            val endpoint = if (editing) "edits" else "generations"
            val builder = bearerRequest("${setting.baseUrl}/images/$endpoint", setting.apiKey)
            val http = if (editing) builder.post(editBody(fields, request)) else builder.postJson(fields)
            val format = request.platformOptions?.outputFormat ?: "png"
            val count = request.count ?: 1
            val result = if (request.platformOptions?.stream == true) {
                client.readPlatformImageStream(http.build(), editing, format, count, onPreview)
            } else {
                val response = client.executeJson(http.build(), id)
                val data = response["data"] as? JsonArray ?: error("Images API 响应缺少图像 data")
                val outputs = data.map { decodeImage(it.jsonObject, response, format) }
                require(outputs.size == count) { "Images API 返回了 ${outputs.size}/$count 个结果" }
                PlatformImageResult(outputs, JsonObject(response - "data"))
            }
            val metadata = result.metadata
            Result.success(MediaGenerationTask(
                id = Uuid.random().toString(),
                model = metadata.string("model") ?: model.modelId,
                provider = id,
                status = MediaGenerationStatus.SUCCEEDED,
                outputs = result.outputs,
                createdAtEpochSeconds = metadata.long("created") ?: metadata.long("created_at"),
                usage = metadata.obj("usage")?.let { MediaGenerationUsage(totalTokens = it.long("total_tokens")) },
                metadata = metadata,
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    internal fun fields(model: MediaGenerationModel, request: MediaGenerationRequest): JsonObject {
        require(!request.prompt.isNullOrBlank() && request.prompt.codePointCount(0, request.prompt.length) <= 32000) { "提示词须为 1 至 32000 个字符" }
        require(request.inputs.size <= 16 && request.inputs.all { it is MediaGenerationInput.Image }) {
            "编辑可使用 1 至 16 张静态参考图"
        }
        require(request.aspectRatio == null && request.durationSeconds == null && request.generateAudio == null &&
            request.watermark == null && request.seed == null && request.promptEnhancement == null &&
            request.callbackUrl == null && request.extraParameters.isEmpty()) { "此图像接口不支持这些附加字段" }
        val options = request.platformOptions ?: PlatformImageOptions()
        options.validate(model.modelId, request.inputs.isNotEmpty(), request.count, request.resolution,
            request.maskPath != null, request.markupPath != null)
        return buildJsonObject {
            put("model", model.modelId)
            put("prompt", request.prompt)
            request.count?.let { put("n", it) }
            request.resolution?.let { put("size", it) }
            options.writeFields(this)
        }
    }

    private fun editBody(fields: JsonObject, request: MediaGenerationRequest): MultipartBody {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        fields.forEach { (name, value) -> builder.addFormDataPart(name, value.jsonPrimitive.content) }
        val name = if (request.inputs.size == 1) "image" else "image[]"
        val files = request.inputs.map { input -> File((input as MediaGenerationInput.Image).url.removePrefix("file://")) }
        files.forEach { file ->
            require(file.isFile && file.length() in 1 until (50L * 1024 * 1024)) { "输入图片须小于 50 MB" }
            val type = rasterImageFormat(file)
            require(type in setOf("jpeg", "webp", "png")) { "请使用 PNG、JPEG 或 WebP 静态参考图" }
            require(!isAnimatedPlatformImage(file, "image/$type")) { "编辑输入必须为静态图像" }
            builder.addFormDataPart(name, file.name, file.asRequestBody("image/$type".toMediaType()))
        }
        request.maskPath?.let { builder.addOverlay("mask", File(it), files.first()) }
        request.markupPath?.let { builder.addOverlay("markup", File(it), files.first()) }
        return builder.build()
    }
}

internal data class PlatformImageResult(val outputs: List<MediaGenerationOutput>, val metadata: JsonObject)

internal fun decodeImage(image: JsonObject, root: JsonObject, expected: String): MediaGenerationOutput {
    val declared = image.string("output_format") ?: root.string("output_format") ?: expected
    val bytes = Base64.decode(image.string("b64_json") ?: error("Images API 响应缺少 b64_json"))
    require(declared == expected && matchesImageFormat(bytes, expected)) { "Images API 返回的文件与请求的 $expected 格式不符" }
    return MediaGenerationOutput(
        data = bytes,
        mimeType = if (expected == "svg") "image/svg+xml" else "image/$expected",
        resolution = image.string("size") ?: root.string("size"),
    )
}
