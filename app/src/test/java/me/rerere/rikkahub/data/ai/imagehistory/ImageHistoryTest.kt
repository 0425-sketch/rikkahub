package me.rerere.rikkahub.data.ai.imagehistory

import android.app.Application
import android.graphics.BitmapFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.chatgpt.ChatGPTProvider
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationManager
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.capabilities
import me.rerere.mediagen.provider.providers.openaiplatform.PlatformImageOptions
import me.rerere.rikkahub.data.model.MediaCreationAsset
import me.rerere.rikkahub.data.model.MediaCreationOutput
import me.rerere.rikkahub.data.model.MediaCreationParams
import me.rerere.rikkahub.data.model.MediaCreationRecord
import me.rerere.rikkahub.data.model.MediaCreationStatus
import me.rerere.rikkahub.data.model.MediaImageVersionId
import me.rerere.rikkahub.data.model.PlatformImageEditHistory
import me.rerere.rikkahub.data.model.buildMediaGenerationRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Base64
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageHistoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val session = Uuid.random()
    private val providerId = Uuid.random()

    private fun generation(parent: MediaImageVersionId? = null, count: Int = 1): MediaCreationRecord =
        MediaCreationRecord(
            sessionId = session, providerId = providerId, providerName = "Platform",
            modelId = "gpt-image-2.6-sunburst", kind = MediaKind.IMAGE, prompt = "edit",
            params = MediaCreationParams(imageEdit = parent?.let { PlatformImageEditHistory(parent = it) }),
            status = MediaCreationStatus.SUCCEEDED,
            outputs = List(count) { MediaCreationOutput("${Uuid.random()}.png", "image/png") },
        )

    private fun MediaCreationRecord.image(index: Int = 0) = MediaImageVersionId(id, index)

    private val first = generation(count = 4)
    private val second = generation(first.image(2), count = 2)
    private val third = generation(second.image(1))
    private val sideBranch = generation(second.image(0))
    private val fourth = generation(third.image())
    private val fifth = generation(fourth.image())
    private val records = listOf(first, second, third, sideBranch, fourth, fifth)
    private val colorRequest = "只把配色改回三轮前，其他保持现在这样"
    private val colorReply = """{"choices":[{"base":"v5","references":["v2"],"instruction":"保留图 1 的构图，采用图 2 的配色"}]}"""

    @Test
    fun threeRoundsFollowParentOutputsDespiteSiblingsAndShuffledRecords() {
        val context = imageHistoryContext(records.reversed(), fifth.image(), colorRequest)
        assertEquals(setOf(fifth.image(), second.image(1)), context.images.map { it.id }.toSet())
        assertEquals(listOf(second.image(1), third.image(), fourth.image(), fifth.image()), context.editRecords.map { it.id })
        val plan = context.validate(colorReply) as ImageHistoryDecision.Edit
        assertEquals(fifth.image(), plan.base)
        assertEquals(listOf(second.image(1)), plan.references)
        val side = imageHistoryContext(records, sideBranch.image(), "use the palette from two edits ago")
        assertEquals(setOf(sideBranch.image(), first.image(2)), side.images.map { it.id }.toSet())
    }

    @Test
    fun rejectsUnknownIdsWrongBaseDuplicateReferencesAndMissingAncestor() {
        val context = imageHistoryContext(records, fifth.image(), colorRequest)
        val invalid = listOf(
            colorReply.replace("v2", "v400"),
            colorReply.replace("v5", "v2").replace("[\"v2\"]", "[\"v5\"]"),
            colorReply.replace("[\"v2\"]", "[\"v2\",\"v2\"]"),
            colorReply.replace("[\"v2\"]", "[]"),
        )
        invalid.forEach { assertThrows(Exception::class.java) { context.validate(it) } }
        assertThrows(Exception::class.java) { imageHistoryContext(records - third, fifth.image(), colorRequest) }
    }

    @Test
    fun absoluteVersionsAndMixedReferencesStayOnTheSelectedBranch() {
        val numbered = imageHistoryContext(records, fifth.image(), "只采用第二版配色")
        assertEquals(setOf(fifth.image(), second.image(1)), numbered.images.map { it.id }.toSet())
        assertEquals(setOf(second.image(1)), numbered.required)
        val relative = imageHistoryContext(records, fifth.image(), "恢复上上一版的构图")
        assertEquals(setOf(third.image()), relative.required)
        val mixed = imageHistoryContext(records, fifth.image(), "用三轮前的构图，配上之前蓝色背景那版的配色")
        assertTrue(mixed.images.any { it.id == sideBranch.image() })
        assertEquals(setOf(second.image(1)), mixed.required)
    }

    @Test
    fun descriptiveAmbiguityKeepsBothCandidatesAndOmitsUnrelatedSession() {
        val unrelated = generation().copy(sessionId = Uuid.random(), nodeId = first.nodeId)
        val context = imageHistoryContext(records + unrelated, fifth.image(), "用之前蓝色背景那版")
        assertFalse(context.images.any { it.record.id == unrelated.id })
        val label = context.images.single { it.id == sideBranch.image() }.label
        val reply = """{"choices":[{"base":"v5","references":["v2"],"instruction":"用图2的底色"},{"base":"v5","references":["$label"],"instruction":"用图2的底色"}]}"""
        val result = context.validate(reply) as ImageHistoryDecision.Choose
        assertEquals(listOf(second.image(1), sideBranch.image()), result.options.map { it.references.single() })
    }

    @Test
    fun ordinaryDescriptionsAndSelectedReferencesDoNotNeedHistoricalInterpretation() {
        assertFalse(needsImageHistory("只让这一朵花变亮"))
        assertFalse(needsImageHistory("把上一排的花换成百合"))
        assertFalse(needsImageHistory("画一座旧图书馆"))
        assertFalse(needsImageHistory("画一张原版海报"))
        assertFalse(needsImageHistory("画一张以前的上海街景"))
        assertFalse(needsImageHistory("用之前未见过的视角"))
        assertFalse(needsImageHistory("把它画成最初的海洋世界"))
        assertFalse(needsImageHistory("画一张上次旅行去的街景"))
        assertTrue(needsImageHistory("把字体改回上一版的样子"))
        assertTrue(needsImageHistory(colorRequest))
        assertTrue(needsImageHistory("用之前蓝色背景那版的风格"))
        assertTrue(needsImageHistory("match the previous image"))
    }

    private val planCredential = OAuthCredential(
        accessToken = "test-plan-only", protocolVersion = 1, clientId = "test-client",
        issuer = "https://auth.openai.com", subject = "test-subject", grantedScopes = setOf("chatgpt.tokens.use.direct"),
    )
    private val vision = Model(
        modelId = "test-vision", inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
        tools = setOf(BuiltInTools.ImageGeneration),
    )
    private val account = ProviderSetting.ChatGPT(credential = planCredential, models = listOf(vision, Model(modelId = "text-only")))

    @Test
    fun configurationOnlyOffersSignedInPlanVisionChatModels() {
        val providers = listOf(
            account, ProviderSetting.OpenAI(models = listOf(vision)),
            account.copy(credential = null), account.copy(enabled = false),
            account.copy(credential = planCredential.copy(protocolVersion = 0)),
            account.copy(credential = planCredential.copy(grantedScopes = emptySet())),
        )
        assertEquals(listOf(account to vision), siwcHistoryModels(providers))
    }

    @Test
    fun statelessResponsesPlansFromThumbnailsThenImagesEditsSendsFullOriginalsWithPlatformKey() = runBlocking {
        val history = imageHistoryContext(records, fifth.image(), colorRequest)
        val fullBase = Base64.getDecoder().decode(RED_PNG)
        val fullReference = Base64.getDecoder().decode(BLUE_PNG)
        val files = history.images.associate { image ->
            val pixels = if (image.id == fifth.image()) fullBase else fullReference
            image.id to folder.newFile(image.output.path).apply { writeBytes(pixels) }
        }
        val thumbnails = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val (mime, text) = when (request.url.encodedPath) {
                "/v1/responses" -> {
                    assertEquals("Bearer test-plan-only", request.header("Authorization"))
                    val wire = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                    val body = Json.parseToJsonElement(wire).jsonObject
                    assertEquals(JsonPrimitive(false), body["store"])
                    assertEquals(JsonPrimitive(true), body["stream"])
                    assertFalse("previous_response_id" in body)
                    assertFalse("image_generation" in wire)
                    assertEquals(2, thumbnails.size)
                    thumbnails.forEach { assertTrue(it in wire) }
                    assertFalse(RED_PNG in wire || BLUE_PNG in wire || "test-platform-only" in wire)
                    val delta = """{"type":"response.output_text.delta","item_id":"msg","delta":${JsonPrimitive(colorReply)}}"""
                    "text/event-stream" to "data: $delta\n\ndata: {\"type\":\"response.completed\",\"response\":{\"id\":\"test\",\"status\":\"completed\"}}\n\n"
                }
                "/v1/images/edits" -> {
                    assertEquals("Bearer test-platform-only", request.header("Authorization"))
                    val parts = (request.body as MultipartBody).parts
                    val images = parts.filter { "filename=" in it.headers?.get("Content-Disposition").orEmpty() }
                    assertEquals(2, images.size)
                    assertArrayEquals(fullBase, Buffer().also { images[0].body.writeTo(it) }.readByteArray())
                    assertArrayEquals(fullReference, Buffer().also { images[1].body.writeTo(it) }.readByteArray())
                    val wire = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                    assertTrue("xhigh" in wire && "图 2 的配色" in wire)
                    assertFalse("test-plan-only" in wire || "data:image/jpeg" in wire)
                    "application/json" to """{"data":[{"b64_json":"$RED_PNG"}]}"""
                }
                else -> error("Unexpected endpoint")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(text.toResponseBody(mime.toMediaType())).build()
        }.build()
        try {
            val planner = ChatGPTProvider(client) { setting, _ ->
                check(setting.id == account.id)
                planCredential
            }
            val plan = requestHistoryPlan(planner, account, vision, history, colorRequest) {
                historyThumbnail(files.getValue(it.id), 384).also { url ->
                    assertTrue(url.startsWith("data:image/jpeg;base64,"))
                    val bytes = Base64.getDecoder().decode(url.substringAfter(','))
                    val thumbnail = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    assertEquals(1, thumbnail.width)
                    thumbnail.recycle()
                    thumbnails.add(url)
                }
            } as ImageHistoryDecision.Edit
            val paths = (listOf(plan.base) + plan.references).map { files.getValue(it).absolutePath }
            val platform = MediaGenerationProviderSetting.OpenAIPlatform(apiKey = "test-platform-only")
            val request = buildMediaGenerationRequest(
                plan.instruction, MediaCreationParams(platformOptions = PlatformImageOptions(quality = "xhigh")),
                paths.map { MediaCreationAsset(it) }, paths, platform.capabilities(MediaKind.IMAGE)!!,
            )
            val task = MediaGenerationManager(client).create(platform, platform.models.first(), request).getOrThrow()
            assertArrayEquals(fullBase, task.outputs.single().data)
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    private companion object {
        const val RED_PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC"
        const val BLUE_PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGNgYPgPAAEDAQAIicLsAAAAAElFTkSuQmCC"
    }
}
