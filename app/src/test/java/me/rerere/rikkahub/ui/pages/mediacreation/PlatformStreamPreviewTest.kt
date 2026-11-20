package me.rerere.rikkahub.ui.pages.mediacreation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import coil3.ImageLoader
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import me.rerere.mediagen.provider.MediaGenerationManager
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.providers.openaiplatform.PlatformImageOptions
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.nativeRoom
import me.rerere.rikkahub.data.files.addMediaImageDecoders
import me.rerere.rikkahub.data.model.MediaCreationAsset
import me.rerere.rikkahub.data.model.MediaCreationParams
import me.rerere.rikkahub.data.model.MediaCreationStatus
import me.rerere.rikkahub.data.remote.RemoteFileStore
import me.rerere.rikkahub.data.repository.MediaCreationRepository
import me.rerere.rikkahub.service.MediaCreationService
import me.rerere.rikkahub.service.MediaCreationSubmission
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Pipe
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class PlatformStreamPreviewTest {
    @Test
    fun legacyRecordDistinguishesUnmeasuredFromZeroReceived() {
        val legacy = JsonInstant.decodeFromString<MediaCreationParams>(
            """{"platformOptions":{"stream":true,"partialImages":2},"imageResult":{"format":"png"}}"""
        )
        assertNull(legacy.imageResult!!.receivedPartialImages)
        assertFalse("receivedPartialImages" in JsonInstant.encodeToString(legacy))
        assertEquals("中间预览 · 请求 2 张，该记录未保存实收数量", platformPreviewReceipt(legacy.platformOptions, null))
        assertEquals("中间预览 · 请求 2 张，实收 0 张", platformPreviewReceipt(legacy.platformOptions, 0))
    }

    @Test
    fun receivedPreviewIsVisibleBeforeFinalAndOnlyReceiptSurvivesCompletion() = runBlocking {
        Dispatchers.setMain(Dispatchers.Default)
        val app = RuntimeEnvironment.getApplication()
        val db = nativeRoom(app, "previews-${Uuid.random()}.db")
        val repo = MediaCreationRepository(app, db, db.mediaCreationDao(), JsonInstant)
        val diskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val appScope = AppScope()
        val store = SettingsStore(PreferenceDataStoreFactory.create(scope = diskScope) {
            File(app.cacheDir, "preview-${Uuid.random()}.preferences_pb")
        }, diskScope)
        val platform = MediaGenerationProviderSetting.OpenAIPlatform(apiKey = "test")
        val partial = png(Color.BLUE, 2048, 256)
        val final = png(Color.RED, 32, 32)
        fun event(type: String, bytes: ByteArray) =
            """data: {"type":"$type","output_format":"png","b64_json":"${Base64.encode(bytes)}"}""" + "\n\n"
        val pipe = Pipe(1024 * 1024L)
        val sink = pipe.sink.buffer()
        val sse = "text/event-stream".toMediaType()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = when (request.url.encodedPath) {
                "/v1/images/generations" -> pipe.source.buffer().asResponseBody(sse)
                "/v1/images/edits" -> event("image_edit.completed", final).toResponseBody(sse)
                else -> error("Unexpected endpoint ${request.url.encodedPath}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
        }.build()
        val loader = ImageLoader.Builder(app).components { addMediaImageDecoders() }.build()
        try {
            store.awaitLoaded()
            store.update { it.copy(mediaGenerationProviders = listOf(platform)) }
            val remote = RemoteFileStore(appScope, { error("Unexpected S3 access") }, { error("Unexpected upload") })
            val service = MediaCreationService(app, appScope, store, repo, MediaGenerationManager(client), remote, client)
            val submission = MediaCreationSubmission(repo.newSession().id, provider = platform, model = platform.models.first(),
                prompt = "红色", params = MediaCreationParams(platformOptions = PlatformImageOptions(stream = true, partialImages = 2)),
                assets = emptyList())

            withTimeout(30_000) {
                val id = service.submit(submission).id
                sink.writeUtf8(event("image_generation.partial_image", partial)).flush()
                val preview = service.previews.first { id in it }.getValue(id)
                assertEquals(1, preview.received)
                assertArrayEquals(partial, preview.bytes)
                // Exercise the card's Coil request while the response is still open and the final event is unavailable.
                val request = platformPreviewRequest(app, preview.bytes).newBuilder().size(256, 200).allowHardware(false).build()
                val decoded = (loader.execute(request) as SuccessResult).image
                assertTrue(decoded.width < 2048)
                assertEquals(Color.BLUE, decoded.toBitmap().getPixel(0, 0))
                val running = repo.getRecord(id)!!
                assertEquals(MediaCreationStatus.RUNNING, running.status)
                assertTrue(running.outputs.isEmpty())

                sink.writeUtf8(event("image_generation.completed", final)).flush()
                val done = repo.observeRecord(id).filterNotNull().first { !it.status.isActive }
                assertEquals(done.error, MediaCreationStatus.SUCCEEDED, done.status)
                assertEquals(1, done.params.imageResult!!.receivedPartialImages)
                assertArrayEquals(final, repo.resolve(done.outputs.single().path).readBytes())
                service.previews.first { id !in it }

                // The edit stream can validly close with fewer previews than requested, including zero.
                val editId = service.submit(submission.copy(assets = listOf(MediaCreationAsset(done.outputs.single().path)))).id
                val edit = repo.observeRecord(editId).filterNotNull().first { !it.status.isActive }
                assertEquals(edit.error, MediaCreationStatus.SUCCEEDED, edit.status)
                assertEquals(0, edit.params.imageResult!!.receivedPartialImages)
                assertEquals(2, edit.params.platformOptions.partialImages)
                assertArrayEquals(final, repo.resolve(edit.outputs.single().path).readBytes())
            }
        } finally {
            sink.close()
            appScope.coroutineContext.job.cancelAndJoin()
            diskScope.coroutineContext.job.cancelAndJoin()
            loader.shutdown()
            db.close()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            Dispatchers.resetMain()
        }
    }

    private fun png(color: Int, width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().use { bytes ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes))
                bytes.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
