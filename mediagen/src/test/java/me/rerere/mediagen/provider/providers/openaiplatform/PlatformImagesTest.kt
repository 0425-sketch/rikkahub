package me.rerere.mediagen.provider.providers.openaiplatform

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.mediagen.model.MediaGenerationInput
import me.rerere.mediagen.model.MediaGenerationOutput
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.provider.MediaGenerationManager
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.providers.MediaGenerationApiException
import me.rerere.mediagen.provider.providers.bodyAsString
import me.rerere.mediagen.provider.providers.fakeClient
import me.rerere.mediagen.provider.providers.jsonResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

class PlatformImagesTest {
    @get:Rule val files = TemporaryFolder()
    private val setting = MediaGenerationProviderSetting.OpenAIPlatform(apiKey = "platform-test")
    private val model = setting.models.first()
    private val prompt = MediaGenerationRequest(prompt = "画一只猫")

    @Test
    fun apiDefaultsAreOmittedAndOnlyPlatformBearerIsSent() = runBlocking {
        val requests = mutableListOf<Request>()
        val manager = MediaGenerationManager(fakeClient(requests) { jsonResponse(it, payload(PNG)) })
        val task = manager.create(setting, model, prompt).getOrThrow()
        val request = requests.single()
        assertEquals("https://api.openai.com/v1/images/generations", request.url.toString())
        assertEquals("Bearer platform-test", request.header("Authorization"))
        assertNull(request.header("ChatGPT-Account-Id"))
        assertEquals(setOf("model", "prompt"), Json.parseToJsonElement(request.bodyAsString()).jsonObject.keys)
        assertEquals("image/png", task.outputs.single().mimeType)
        assertEquals(model.modelId, task.model)
    }

    @Test
    fun bothModelsSupportExtendedQualityAndIndependentComplexityAndValidateEndpointRules() {
        for (model in setting.models) {
            for (quality in listOf("xhigh", "max")) {
                PlatformImageOptions(quality = quality, complexity = "low").validate(model.modelId, false, 4, "1536x1024")
            }
            val gif = PlatformImageOptions(outputFormat = "gif", frameCount = 16, frameDurationMs = 200)
            gif.validate(model.modelId, true, 2, "512x512")
            val svg = PlatformImageOptions(outputFormat = "svg", background = "transparent")
            svg.validate(model.modelId, false, 1, null)
            fun reject(options: PlatformImageOptions, edit: Boolean = false, n: Int = 1,
                size: String? = null, mask: Boolean = false, markup: Boolean = false) {
                assertThrows(IllegalArgumentException::class.java) {
                    options.validate(model.modelId, edit, n, size, mask, markup)
                }
            }
            reject(svg, edit = true)
            reject(svg, n = 2)
            reject(svg.copy(stream = true))
            reject(svg.copy(svgReasoningEffort = "max"))
            reject(gif.copy(stream = true))
            reject(gif.copy(outputCompression = 80))
            reject(gif, edit = true, mask = true)
            reject(gif.copy(frameCount = 17))
            reject(gif.copy(quality = "highest"))
            reject(PlatformImageOptions(outputFormat = "jpeg", background = "transparent"))
            reject(PlatformImageOptions(partialImages = 1))
            reject(PlatformImageOptions(), size = "512x512")
            reject(PlatformImageOptions(), size = "1537x1024")
            reject(PlatformImageOptions(), size = "3840x256")
            reject(PlatformImageOptions(), edit = true, mask = true, markup = true)
            reject(PlatformImageOptions(), markup = true)
        }
        val unknown = PlatformImageCapabilities.forModel("future-model")
        assertFalse("max" in unknown.qualities)
        assertFalse("svg" in unknown.formats(false))
    }

    @Test
    fun svgIsDecodedAsOriginalXmlAndPermissionErrorsOrWrongBytesFail() = runBlocking {
        val options = PlatformImageOptions(outputFormat = "svg", svgModel = "writer", svgReasoningEffort = "high")
        val request = prompt.copy(platformOptions = options)
        val xml = """<?xml version="1.0"?><svg xmlns="http://www.w3.org/2000/svg"><circle r="5"/></svg>""".toByteArray()
        val manager = MediaGenerationManager(fakeClient {
            val body = Json.parseToJsonElement(it.bodyAsString()).jsonObject
            assertEquals("""{"model":"writer","reasoning":{"effort":"high"}}""", body["svg"].toString())
            jsonResponse(it, payload(Base64.encode(xml)))
        })
        val output = manager.create(setting, model, request).getOrThrow().outputs.single()
        assertEquals("image/svg+xml", output.mimeType)
        assertArrayEquals(xml, output.data)
        val denied = MediaGenerationManager(fakeClient {
            jsonResponse(it, """{"error":{"message":"Writer unavailable for project","code":"permission_denied"}}""", 403)
        })
        val e = denied.create(setting, model, request).exceptionOrNull() as MediaGenerationApiException
        assertEquals(403, e.statusCode)
        assertEquals("permission_denied", e.code)
        val wrong = MediaGenerationManager(fakeClient { jsonResponse(it, payload(PNG)) })
        assertTrue(wrong.create(setting, model, request).isFailure)
    }

    @Test
    fun animatedGifFromStaticReferencesPreservesFramesAndReferenceOrder() = runBlocking {
        val base = files.newFile("base.png").apply { writeBytes(Base64.decode(PNG)) }
        val ref = files.newFile("reference.png").apply { writeBytes(Base64.decode(TRANSPARENT)) }
        val manager = MediaGenerationManager(fakeClient {
            assertEquals("/v1/images/edits", it.url.encodedPath)
            val parts = (it.body as MultipartBody).parts
            val images = parts.filter { part -> part.headers!!.get("Content-Disposition")!!.contains("name=\"image[]\"") }
            assertEquals(2, images.size)
            assertArrayEquals(base.readBytes(), Buffer().also(images[0].body::writeTo).readByteArray())
            assertArrayEquals(ref.readBytes(), Buffer().also(images[1].body::writeTo).readByteArray())
            val fields = parts.associate { part -> part.headers!!.get("Content-Disposition")!! to
                Buffer().also(part.body::writeTo).readUtf8() }
            assertEquals("9", fields["form-data; name=\"frame_count\""])
            assertEquals("200", fields["form-data; name=\"frame_duration_ms\""])
            assertFalse(fields.keys.any { key -> key.contains("input_fidelity") || key.contains("mask") })
            jsonResponse(it, payload(GIF))
        })
        val request = prompt.copy(inputs = listOf(base, ref).map { MediaGenerationInput.Image(it.path) },
            platformOptions = PlatformImageOptions(outputFormat = "gif", frameCount = 9, frameDurationMs = 200))
        val output = manager.create(setting, model, request).getOrThrow().outputs.single()
        assertEquals("image/gif", output.mimeType)
        assertArrayEquals(Base64.decode(GIF), output.data)
    }

    @Test
    fun jpegAndWebpEditsMapQualityComplexityCompressionAndModerationIndependently() = runBlocking {
        val base = files.newFile("base.png").apply { writeBytes(Base64.decode(PNG)) }
        val jpeg = ByteArrayOutputStream().also { out ->
            assertTrue(ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "jpeg", out))
        }.toByteArray()
        for ((format, bytes) in listOf("jpeg" to jpeg, "webp" to Base64.decode(WEBP))) {
            val options = PlatformImageOptions(quality = "max", complexity = "low", outputFormat = format,
                outputCompression = 72, moderation = "low")
            val manager = MediaGenerationManager(fakeClient { http ->
                val fields = (http.body as MultipartBody).parts.filter { it.body.contentType() == null }
                    .associate { part ->
                        part.headers!!["Content-Disposition"]!!.substringAfter("name=\"").substringBefore('"') to
                            Buffer().also(part.body::writeTo).readUtf8()
                    }
                assertEquals(mapOf("model" to model.modelId, "prompt" to prompt.prompt, "quality" to "max",
                    "complexity" to "low", "output_format" to format, "output_compression" to "72", "moderation" to "low",
                    "size" to "1536x1024"), fields)
                jsonResponse(http, payload(Base64.encode(bytes)))
            })
            val output = manager.create(setting, model, prompt.copy(inputs = listOf(MediaGenerationInput.Image(base.path)),
                resolution = "1536x1024", platformOptions = options)).getOrThrow().outputs.single()
            assertEquals("image/$format", output.mimeType)
            assertArrayEquals(bytes, output.data)
        }
    }

    @Test
    fun maskIsItsOwnPartAndMustMatchCleanBaseDimensions() = runBlocking {
        val base = files.newFile("clean.png").apply { writeBytes(Base64.decode(PNG)) }
        val mask = files.newFile("mask.png").apply { writeBytes(Base64.decode(TRANSPARENT)) }
        val manager = MediaGenerationManager(fakeClient {
            val parts = (it.body as MultipartBody).parts
            assertEquals(listOf("image", "mask"), parts.filter { p -> p.body.contentType()?.type == "image" }
                .map { p -> p.headers!!["Content-Disposition"]!!.substringAfter("name=\"").substringBefore('"') })
            jsonResponse(it, payload(PNG))
        })
        val request = prompt.copy(inputs = listOf(MediaGenerationInput.Image(base.path)), maskPath = mask.path)
        assertTrue(manager.create(setting, model, request).isSuccess)
        mask.writeBytes(Base64.decode(TRANSPARENT).apply { ByteBuffer.wrap(this).putInt(16, 3) })
        assertTrue(manager.create(setting, model, request).exceptionOrNull()!!.message!!.contains("尺寸"))
    }

    @Test
    fun multiImageStreamingIsRejectedBeforeSendingGenerationOrEdit() = runBlocking {
        val base = files.newFile("base.png").apply { writeBytes(Base64.decode(PNG)) }
        val editInputs = listOf(MediaGenerationInput.Image(base.path))
        val restored = Json.decodeFromString<MediaGenerationRequest>(
            """{"prompt":"四张图","count":4,"platformOptions":{"stream":true,"partialImages":1}}"""
        )
        val requests = mutableListOf<Request>()
        val manager = MediaGenerationManager(fakeClient(requests) { jsonResponse(it, payload(PNG)) })
        for (imageModel in setting.models) {
            for (inputs in listOf(emptyList(), editInputs)) {
                val error = manager.create(setting, imageModel, restored.copy(inputs = inputs)).exceptionOrNull()
                assertTrue(error is IllegalArgumentException)
                assertTrue(error?.message.orEmpty().contains("流式生成只支持 1 张图"))
            }
        }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun nonStreamingBatchPreservesCountForBothEndpoints() = runBlocking {
        val base = files.newFile("base.png").apply { writeBytes(Base64.decode(PNG)) }
        val requests = mutableListOf<Request>()
        val manager = MediaGenerationManager(fakeClient(requests) {
            jsonResponse(it, """{"data":[{"b64_json":"$PNG"},{"b64_json":"$TRANSPARENT"}]}""")
        })
        val request = prompt.copy(count = 2, platformOptions = PlatformImageOptions(stream = false))
        for (inputs in listOf(emptyList(), listOf(MediaGenerationInput.Image(base.path)))) {
            val outputs = manager.create(setting, model, request.copy(inputs = inputs)).getOrThrow().outputs
            assertEquals(2, outputs.size)
            assertArrayEquals(Base64.decode(PNG), outputs[0].data)
            assertArrayEquals(Base64.decode(TRANSPARENT), outputs[1].data)
        }
        assertEquals(listOf("/v1/images/generations", "/v1/images/edits"), requests.map { it.url.encodedPath })
        val json = Json.parseToJsonElement(requests.first().bodyAsString()).jsonObject
        assertEquals("2", json["n"].toString())
        assertFalse("stream" in json || "partial_images" in json)
        val multipart = requests.last().body as MultipartBody
        val fields = multipart.parts.filter { it.body.contentType() == null }.associate { part ->
            part.headers!!["Content-Disposition"]!!.substringAfter("name=\"").substringBefore('"') to
                Buffer().also(part.body::writeTo).readUtf8()
        }
        assertEquals("2", fields["n"])
        assertFalse("stream" in fields || "partial_images" in fields)
    }

    @Test
    fun streamPreviewsNeverBecomeFinalFilesAndInterruptedStreamFails() = runBlocking {
        fun sse(request: Request, events: String): Response = jsonResponse(request, "").newBuilder()
            .body(events.toResponseBody("text/event-stream".toMediaType())).build()
        val partial = """data: {"type":"image_generation.partial_image","b64_json":"$TRANSPARENT","partial_image_index":0}""" + "\n\n"
        val complete = """data: {"type":"image_generation.completed","b64_json":"$PNG","output_format":"png"}""" + "\n\n"
        val manager = MediaGenerationManager(fakeClient { sse(it, partial + complete) })
        val request = prompt.copy(platformOptions = PlatformImageOptions(stream = true, partialImages = 1))
        for (count in listOf(null, 1)) {
            val previews = mutableListOf<MediaGenerationOutput>()
            val final = manager.create(setting, model, request.copy(count = count)) { previews += it }.getOrThrow()
            assertArrayEquals(Base64.decode(TRANSPARENT), previews.single().data)
            assertArrayEquals(Base64.decode(PNG), final.outputs.single().data)
            assertFalse(final.metadata.containsKey("b64_json"))
        }
        val interrupted = MediaGenerationManager(fakeClient { sse(it, partial) })
        assertTrue(interrupted.create(setting, model, request).isFailure)
        val denied = MediaGenerationManager(fakeClient {
            jsonResponse(it, """{"error":{"message":"Forbidden","code":"denied"}}""", 403)
        })
        assertEquals("denied", (denied.create(setting, model, request).exceptionOrNull() as MediaGenerationApiException).code)
    }

    @Test
    fun providerAndHistorySelectionRoundTripIndependentlyOfLegacyOpenAI() {
        val configured = setting.copy(historyModelId = Uuid.random())
        val wire = Json.encodeToString(MediaGenerationProviderSetting.serializer(), configured)
        assertEquals(configured, Json.decodeFromString(MediaGenerationProviderSetting.serializer(), wire))
        assertEquals(configured.historyModelId, configured.copyProvider(name = "个人账户").historyModelId)
        assertTrue(Json.decodeFromString(MediaGenerationProviderSetting.serializer(), """{"type":"openai"}""")
            is MediaGenerationProviderSetting.OpenAI)
    }

    companion object {
        private const val PNG = "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR4nGP8z8DAwMDAxMDAwMDAAAANHQEDasKb6QAAAABJRU5ErkJggg=="
        private const val TRANSPARENT = "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAC0lEQVR4nGNgQAcAABIAAXfx+gAAAAAASUVORK5CYII="
        private const val WEBP = "UklGRjwAAABXRUJQVlA4IDAAAADQAQCdASoCAAIAAUAmJaACdLoB+AADsAD+8ut//NgVzXPv9//S4P0uD9Lg/9KQAAA="
        private const val GIF = "R0lGODlhAgACAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQAFAAAACwAAAAAAgACAAAIBgABCAQQEAAh+QQBFAABACwAAAAAAgACAIEAAP8AAAAAAAAAAAAIBgABCAQQEAA7"
        private fun payload(base64: String) = """{"data":[{"b64_json":"$base64"}]}"""
    }
}
