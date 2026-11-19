package me.rerere.rikkahub.ui.pages.mediacreation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.mediagen.provider.MediaGenerationManager
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.providers.openaiplatform.PlatformImageOptions
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.nativeRoom
import me.rerere.rikkahub.data.model.MediaCreationDraft
import me.rerere.rikkahub.data.model.MediaCreationParams
import me.rerere.rikkahub.data.model.MediaCreationRecord
import me.rerere.rikkahub.data.model.MediaCreationStatus
import me.rerere.rikkahub.data.model.MediaImageVersionId
import me.rerere.rikkahub.data.remote.RemoteFileStore
import me.rerere.rikkahub.data.repository.MediaCreationRepository
import me.rerere.rikkahub.service.MediaCreationService
import me.rerere.rikkahub.service.MediaCreationSubmission
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class PlatformEditWorkflowTest {
    private data class EditUpload(val fields: Map<String, String>, val files: List<Pair<String, ByteArray>>)

    @Test
    fun thirdOutputToMaskedEditThenForkFromSavedVersionAndRestorePalette() = runBlocking {
        Dispatchers.setMain(Dispatchers.Default)
        val app = RuntimeEnvironment.getApplication()
        val db = nativeRoom(app, "images-${Uuid.random()}.db")
        val repo = MediaCreationRepository(app, db, db.mediaCreationDao(), JsonInstant)
        val diskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val appScope = AppScope()
        val store = SettingsStore(PreferenceDataStoreFactory.create(scope = diskScope) {
            File(app.cacheDir, "images.preferences_pb")
        }, diskScope)
        val vision = Model(modelId = "vision-fixture", inputModalities = listOf(Modality.TEXT, Modality.IMAGE))
        val credential = OAuthCredential(accessToken = "plan-fixture", clientId = "fixture", protocolVersion = 1,
            issuer = "https://auth.openai.com", subject = "test", grantedScopes = setOf("chatgpt.tokens.use.direct"))
        val account = ProviderSetting.ChatGPT(credential = credential, models = listOf(vision))
        val platform = MediaGenerationProviderSetting.OpenAIPlatform(apiKey = "platform-fixture", historyModelId = vision.id)
        val seeds = listOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW).map(::png)
        val uploads = CopyOnWriteArrayList<EditUpload>()
        val plannerCalls = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val (type, payload) = when (request.url.encodedPath) {
                "/v1/images/generations" -> {
                    assertEquals("Bearer platform-fixture", request.header("Authorization"))
                    "application/json" to images(seeds)
                }
                "/v1/images/edits" -> {
                    assertEquals("Bearer platform-fixture", request.header("Authorization"))
                    val parts = (request.body as MultipartBody).parts
                    val files = mutableListOf<Pair<String, ByteArray>>()
                    val fields = mutableMapOf<String, String>()
                    for (part in parts) {
                        val disposition = part.headers!!["Content-Disposition"]!!
                        val name = disposition.substringAfter("name=\"").substringBefore('"')
                        val buffer = Buffer().also { part.body.writeTo(it) }
                        if ("filename=" in disposition) files += name to buffer.readByteArray()
                        else fields[name] = buffer.readUtf8()
                    }
                    uploads += EditUpload(fields, files)
                    "application/json" to images(listOf(png(Color.rgb(uploads.size * 20, 100, 120))))
                }
                "/v1/responses" -> {
                    assertEquals("Bearer plan-fixture", request.header("Authorization"))
                    plannerCalls += Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                    val choice = """{"choices":[{"base":"v5","references":["v2"],"instruction":"仅使用图2的配色修改图1"}]}"""
                    val event = """{"type":"response.output_text.delta","item_id":"msg","delta":${JsonPrimitive(choice)}}"""
                    "text/event-stream" to "data: $event\n\ndata: {\"type\":\"response.completed\",\"response\":{\"id\":\"test\",\"status\":\"completed\"}}\n\n"
                }
                else -> error("Unexpected endpoint ${request.url.encodedPath}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(payload.toResponseBody(type.toMediaType())).build()
        }.build()
        try {
            store.awaitLoaded()
            store.update { it.copy(providers = listOf(account), mediaGenerationProviders = listOf(platform)) }
            val chat = ProviderManager(client, app) { setting, _ -> check(setting.id == account.id); credential }
            val remote = RemoteFileStore(appScope, { error("Unexpected S3 access") }, { error("Unexpected upload") })
            val service = MediaCreationService(app, appScope, store, repo, MediaGenerationManager(client), remote, client)
            val session = repo.newSession()
            lateinit var latest: MediaCreationRecord
            fun workflow(repository: MediaCreationRepository) =
                PlatformImageWorkflow(session.id, repository, store, chat, service) { latest = it }
            var flow = workflow(repo)
            suspend fun finished(recordId: Uuid): MediaCreationRecord = withTimeout(15_000) {
                var row = repo.getRecord(recordId)!!
                while (row.status.isActive) {
                    delay(15)
                    row = repo.getRecord(recordId)!!
                }
                assertEquals(row.error, MediaCreationStatus.SUCCEEDED, row.status)
                row
            }
            suspend fun edit(row: MediaCreationRecord, index: Int, prompt: String, mask: File? = null): MediaCreationRecord {
                val target = flow.target(row, row.outputs[index])
                flow.submit(target, PlatformImageEdit(target.base, emptyList(), mask, null, prompt,
                    MediaCreationParams(platformOptions = PlatformImageOptions(quality = "xhigh")), false)) {
                    error("Unexpected ambiguity")
                }
                return finished(latest.id)
            }
            val first = finished(service.submit(MediaCreationSubmission(session.id, provider = platform,
                model = platform.models.first(), prompt = "four images", params = MediaCreationParams(count = 4),
                assets = emptyList())).id)
            // The main composer must ask which of these four actual output images is the current base.
            assertEquals((0..3).map { MediaImageVersionId(first.id, it) },
                flow.composerBases(MediaCreationDraft()).map { it.version })
            val mask = File(repo.draftDir(session.id).apply { mkdirs() }, "brush.png").apply { writeBytes(png(Color.TRANSPARENT)) }
            val second = edit(first, 2, "lighten the selected area", mask)
            assertEquals(MediaImageVersionId(first.id, 2), second.parentVersion)
            assertArrayEquals(seeds[2], uploads.first().files[0].second)
            assertArrayEquals(mask.readBytes(), uploads.first().files.single { it.first == "mask" }.second)
            val snapshot = repo.resolve(second.params.imageEdit!!.maskPath!!)
            mask.delete()
            assertTrue(snapshot.isFile)

            // Reopen the persisted record through a fresh repository before continuing from it.
            val reopened = MediaCreationRepository(app, db, db.mediaCreationDao(), JsonInstant)
            flow = workflow(reopened)
            val third = edit(reopened.getRecord(second.id)!!, 0, "move the clouds")
            val fourth = edit(third, 0, "add flowers")
            val fifth = edit(fourth, 0, "add a bird")
            val sibling = edit(second, 0, "add a bridge")
            repo.switchVersion(fifth.nodeId, -1)
            assertEquals(listOf(MediaImageVersionId(fifth.id, 0)), flow.composerBases(MediaCreationDraft()).map { it.version })
            assertEquals(0, plannerCalls.size)
            val sixth = edit(fifth, 0, "只把配色改回三轮前，其他保持现在这样")
            assertEquals(1, plannerCalls.size)
            assertTrue(plannerCalls.single().contains("data:image/jpeg;base64,"))
            assertEquals(MediaImageVersionId(fifth.id, 0), sixth.parentVersion)
            assertEquals(listOf(MediaImageVersionId(second.id, 0)), sixth.params.imageEdit!!.references)
            assertEquals("xhigh", uploads.last().fields["quality"])
            assertEquals("仅使用图2的配色修改图1", uploads.last().fields["prompt"])
            val finalFiles = uploads.last().files
            assertEquals(2, finalFiles.size)
            assertArrayEquals(repo.resolve(fifth.outputs.single().path).readBytes(), finalFiles[0].second)
            assertArrayEquals(repo.resolve(second.outputs.single().path).readBytes(), finalFiles[1].second)
            assertTrue(repo.resolve(sibling.outputs.single().path).isFile)
            assertArrayEquals(seeds[2], repo.resolve(first.outputs[2].path).readBytes())

            // Masking an imported JPEG needs an upright PNG upload and the untouched photo for local history.
            val jpegBytes = encoded(Color.MAGENTA, Bitmap.CompressFormat.JPEG)
            val original = File(flow.draftDir, "photo.jpg").apply { writeBytes(jpegBytes) }
            val upright = File(flow.draftDir, "upright.png").apply { writeBytes(png(Color.MAGENTA)) }
            val region = File(flow.draftDir, "region.png").apply { writeBytes(png(Color.TRANSPARENT)) }
            val manual = PlatformEditTarget(platform.id, platform.models.first().modelId, original, null, MediaCreationParams())
            flow.submit(manual, PlatformImageEdit(
                upright, listOf(repo.resolve(second.outputs.single().path)), region, null,
                "用之前蓝色背景那版的风格", manual.params, false,
            )) { error("Manual references must bypass SIWC") }
            val importedEdit = finished(latest.id)
            assertEquals(1, plannerCalls.size)
            original.delete()
            upright.delete()
            assertArrayEquals(jpegBytes, repo.resolve(importedEdit.params.imageEdit!!.originalBasePath!!).readBytes())
            assertArrayEquals(png(Color.MAGENTA), repo.resolve(importedEdit.inputs.first().path).readBytes())
        } finally {
            appScope.coroutineContext.job.cancelAndJoin()
            diskScope.coroutineContext.job.cancelAndJoin()
            db.close()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            Dispatchers.resetMain()
        }
    }

    private fun png(color: Int) = encoded(color, Bitmap.CompressFormat.PNG)

    private fun encoded(color: Int, format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().use { bytes ->
                check(bitmap.compress(format, 100, bytes))
                bytes.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun images(files: List<ByteArray>) = files.joinToString(prefix = "{\"data\":[", postfix = "]}") {
        """{"b64_json":"${Base64.getEncoder().encodeToString(it)}"}"""
    }
}
