package me.rerere.rikkahub.ui.components.ai

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.PromptEnhancementMode
import me.rerere.rikkahub.data.ai.UsageRecorder
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.nativeRoom
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.ui.toast.rememberToasterState
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PromptEnhancementButtonTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun editingWhileWaitingOffersRewriteAndUndoRestoresLatestTyping() {
        val app = RuntimeEnvironment.getApplication()
        val allowReply = CountDownLatch(1)
        val requests = AtomicInteger()
        val rewritten = "Reviewed draft"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.encodedPath == "/v1/chat/completions")
            requests.incrementAndGet()
            check(allowReply.await(30, TimeUnit.SECONDS))
            val body = """{"choices":[{"message":{"role":"assistant","content":"$rewritten"}}]}"""
            Response.Builder().request(request).code(200).message("OK").protocol(Protocol.HTTP_1_1)
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val database = nativeRoom(app, "prompt-button.db")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val services = koinApplication {
            modules(module {
                single { ProviderManager(client, app) { _, _ -> error("Unexpected account request") } }
                single { UsageRecorder(database.usageRecordDao(), scope) }
            })
        }
        val chat = ChatInputState()
        val model = Model(modelId = "fixture")
        val settings = Settings(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(model), apiKey = "fixture")),
            promptEnhancementModelId = model.id,
        )
        try {
            compose.setContent {
                KoinIsolatedContext(services) {
                    CompositionLocalProvider(LocalSettings provides settings, LocalToaster provides rememberToasterState()) {
                        MaterialTheme {
                            Column {
                                TextField(state = chat.textContent, inputTransformation = chat.trackTextEdits)
                                PromptEnhancementButton(
                                    mode = PromptEnhancementMode.CHAT,
                                    textState = chat.textContent,
                                    draftEpoch = { chat.draftEpoch },
                                    editRevision = { chat.editRevision },
                                )
                            }
                        }
                    }
                }
            }
            val input = compose.onNode(hasSetTextAction())
            val enhance = compose.onNodeWithContentDescription(app.getString(R.string.prompt_enhancement))
            enhance.assertIsNotEnabled()
            val original = "First line\nSecond line"
            input.performTextInput(original)
            enhance.performClick()
            compose.waitUntil("model request", 15_000) { requests.get() == 1 }
            val extra = "\nWait for my approval."
            input.performTextInput(extra)
            allowReply.countDown()

            val review = app.getString(R.string.prompt_enhancement_review)
            compose.waitUntil("rewrite available", 15_000) {
                compose.onAllNodes(hasText(review)).fetchSemanticsNodes().isNotEmpty()
            }
            input.assertTextEquals(original + extra)
            compose.onNodeWithText(review).performClick()
            compose.onNodeWithText(rewritten).assertExists()
            compose.onNodeWithText(app.getString(R.string.prompt_enhancement_apply)).performClick()
            input.assertTextEquals(rewritten)
            compose.onNodeWithText(app.getString(R.string.prompt_enhancement_undo)).performClick()
            input.assertTextEquals(original + extra)
            assertEquals(1, requests.get())
        } finally {
            allowReply.countDown()
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            services.close()
            scope.cancel()
            database.close()
        }
    }
}
