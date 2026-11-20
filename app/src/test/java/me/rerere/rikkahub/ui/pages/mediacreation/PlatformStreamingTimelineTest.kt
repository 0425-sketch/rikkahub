package me.rerere.rikkahub.ui.pages.mediacreation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.ProviderManager
import me.rerere.mediagen.provider.MediaGenerationManager
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.providers.openaiplatform.PlatformImageOptions
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.MediaCreationDAO
import me.rerere.rikkahub.data.db.entity.MediaCreationRecordEntity
import me.rerere.rikkahub.data.db.nativeRoom
import me.rerere.rikkahub.data.model.MediaCreationNode
import me.rerere.rikkahub.data.model.MediaCreationParams
import me.rerere.rikkahub.data.model.MediaCreationStatus
import me.rerere.rikkahub.data.remote.RemoteFileStore
import me.rerere.rikkahub.data.repository.MediaCreationRepository
import me.rerere.rikkahub.service.MediaCreationService
import me.rerere.rikkahub.service.MediaCreationSubmission
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.ui.toast.rememberToasterState
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Pipe
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlatformStreamingTimelineTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun openCardDisplaysStreamFrameThenSavedImageWithoutPagingRefresh() {
        val app = RuntimeEnvironment.getApplication()
        val database = nativeRoom(app, "stream-${Uuid.random()}.db")
        val dao = database.mediaCreationDao()
        val allowSuccess = CompletableDeferred<Unit>()
        val repository = MediaCreationRepository(app, database, object : MediaCreationDAO by dao {
            override suspend fun updateRecord(record: MediaCreationRecordEntity) {
                // Let the view observe the real saving phase even on very fast test storage.
                if (record.status == MediaCreationStatus.SUCCEEDED.name) allowSuccess.await()
                dao.updateRecord(record)
            }
        }, JsonInstant)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val appScope = AppScope()
        val store = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File(app.cacheDir, "stream-${Uuid.random()}.preferences_pb")
        }, scope)
        val platform = MediaGenerationProviderSetting.OpenAIPlatform(apiKey = "ui-test-key")
        val pipe = Pipe(65536)
        val writer = pipe.sink.buffer()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.encodedPath == "/v1/images/generations")
            Response.Builder().request(request).code(200).message("OK").protocol(Protocol.HTTP_1_1)
                .body(pipe.source.buffer().asResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val session = runBlocking {
            store.awaitLoaded()
            store.update { it.copy(mediaGenerationProviders = listOf(platform)) }
            repository.newSession()
        }
        val remote = RemoteFileStore(appScope, { store.settingsFlow.value.uploadS3Config }, { error("Unexpected upload") })
        val service = MediaCreationService(app, appScope, store, repository, MediaGenerationManager(client), remote, client)
        val vm = MediaCreationVM(session.id.toString(), app, store, repository, service, remote, appScope,
            ProviderManager(client, app) { _, _ -> error("Unexpected chat request") })
        val attached = mutableStateOf(true)
        try {
            val prompt = "stream exactly one image"
            val submitted = scope.async {
                service.submit(MediaCreationSubmission(
                    sessionId = session.id, provider = platform, model = platform.models.first(), prompt = prompt,
                    params = MediaCreationParams(platformOptions = PlatformImageOptions(stream = true, partialImages = 1)),
                    assets = emptyList(),
                ))
            }
            compose.waitUntil("submission", 20_000) {
                shadowOf(Looper.getMainLooper()).idle()
                submitted.isCompleted
            }
            // Hold the original page in place. A renderer which only consults the page will never show the final.
            val page = flowOf(PagingData.from(listOf(MediaCreationNode(runBlocking { submitted.await() }))))
            compose.setContent {
                if (attached.value) MaterialTheme {
                    CompositionLocalProvider(LocalToaster provides rememberToasterState()) {
                        MediaCreationTimeline(vm, page.collectAsLazyPagingItems(), null, setOf(platform.id), { _, _ -> },
                            null, true, {})
                    }
                }
            }
            val running = app.getString(R.string.media_creation_status_running)
            val saving = app.getString(R.string.media_creation_platform_status_saving)
            waitForLabel(running)
            fun cardId() = compose.onNodeWithText(prompt, useUnmergedTree = true)
                .assertIsDisplayed().fetchSemanticsNode().id
            val originalCard = cardId()
            writer.writeUtf8(event("partial_image", Color.MAGENTA)).flush()
            waitForPixels(Color.MAGENTA)
            assertEquals(originalCard, cardId())

            writer.writeUtf8(event("completed", Color.GREEN)).flush()
            writer.close()
            waitForLabel(saving)
            assertEquals(originalCard, cardId())
            allowSuccess.complete(Unit)
            waitForPixels(Color.GREEN)
            assertEquals(originalCard, cardId())
            compose.mainClock.advanceTimeBy(2_500)
            compose.onNode(hasText(running, substring = true), useUnmergedTree = true).assertDoesNotExist()
            compose.onNode(hasText(saving, substring = true), useUnmergedTree = true).assertDoesNotExist()
            assertEquals(originalCard, cardId())
        } finally {
            allowSuccess.complete(Unit)
            writer.close()
            compose.runOnUiThread { attached.value = false }
            compose.waitForIdle()
            vm.viewModelScope.cancel()
            appScope.cancel()
            scope.cancel()
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            database.close()
        }
    }

    private fun waitForLabel(label: String) {
        compose.waitUntil("visible status: $label", 20_000) {
            compose.onAllNodes(hasText(label, substring = true), useUnmergedTree = true).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithText(label, substring = true, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun waitForPixels(color: Int) {
        compose.waitUntil("visible pixels #${color.toUInt().toString(16)}", 20_000) {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            var matches = 0
            for (x in 0 until bitmap.width step 8) {
                for (y in 0 until bitmap.height step 8) {
                    if (bitmap.getPixel(x, y) == color) matches++
                }
            }
            matches >= 12
        }
    }

    private fun event(type: String, color: Int): String {
        val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream()
        try {
            bitmap.eraseColor(color)
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, png))
        } finally {
            bitmap.recycle()
        }
        val payload = Base64.getEncoder().encodeToString(png.toByteArray())
        return "event: image_generation.$type\ndata: {\"type\":\"image_generation.$type\",\"output_format\":\"png\",\"partial_image_index\":0,\"b64_json\":\"$payload\"}\n\n"
    }
}
