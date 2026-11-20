package me.rerere.mediagen.provider.providers.openaiplatform

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.common.http.sse.newEventSource
import me.rerere.mediagen.model.MediaGenerationOutput
import me.rerere.mediagen.provider.providers.MediaGenerationApiException
import me.rerere.mediagen.provider.providers.mediaGenerationJson
import me.rerere.mediagen.provider.providers.obj
import me.rerere.mediagen.provider.providers.string
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener

internal suspend fun OkHttpClient.readPlatformImageStream(
    request: Request,
    editing: Boolean,
    format: String,
    count: Int,
    onPreview: suspend (MediaGenerationOutput) -> Unit,
): PlatformImageResult {
    val prefix = if (editing) "image_edit" else "image_generation"
    val outputs = mutableListOf<MediaGenerationOutput>()
    var metadata = JsonObject(emptyMap())
    callbackFlow {
        val source = newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") close() else trySendBlocking(type to data)
            }

            override fun onClosed(eventSource: EventSource) { close() }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (response != null && !response.isSuccessful) {
                    // The SSE transport closes the error response as soon as this callback returns.
                    val raw = runCatching { response.body.string() }.getOrElse { response.message }
                    val json = runCatching { mediaGenerationJson.parseToJsonElement(raw).jsonObject }.getOrNull()
                    val error = json?.obj("error")
                    close(MediaGenerationApiException("openai_platform_images", response.code,
                        error?.string("code") ?: error?.string("type"), error?.string("message") ?: raw))
                } else {
                    close(t ?: IllegalStateException("Images API 流式连接失败"))
                }
            }
        })
        awaitClose { source.cancel() }
    }.buffer(0).firstOrNull { (eventType, data) ->
        val image = mediaGenerationJson.parseToJsonElement(data).jsonObject
        val type = image.string("type") ?: eventType
        when (type) {
            "$prefix.partial_image" -> onPreview(decodeImage(image, image, image.string("output_format") ?: format))
            "$prefix.completed" -> {
                outputs += decodeImage(image, image, format)
                metadata = JsonObject(image - "b64_json")
            }
            "error", "$prefix.failed" -> {
                val error = image.obj("error") ?: image
                throw MediaGenerationApiException("openai_platform_images", 200, error.string("code"),
                    error.string("message") ?: "Images API 流式生成失败")
            }
        }
        // completed carries the final bytes. Some transports keep the connection open afterwards.
        outputs.size == count
    }
    check(outputs.size == count) { "Images API 流在返回全部最终图像前结束（${outputs.size}/$count）" }
    return PlatformImageResult(outputs, metadata)
}
