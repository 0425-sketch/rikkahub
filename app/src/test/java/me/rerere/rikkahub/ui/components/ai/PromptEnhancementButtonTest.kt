package me.rerere.rikkahub.ui.components.ai

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.mediagen.provider.MediaGenerationManager
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.UsageRecorder
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.nativeRoom
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.remote.RemoteFileStore
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.MediaCreationRepository
import me.rerere.rikkahub.service.MediaCreationService
import me.rerere.rikkahub.ui.context.LocalASRState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.rememberCustomAsrState
import me.rerere.rikkahub.ui.pages.enhancement.PromptEnhancementPage
import me.rerere.rikkahub.ui.pages.mediacreation.MediaCreationComposer
import me.rerere.rikkahub.ui.pages.mediacreation.MediaCreationVM
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.SoundEffectPlayer
import me.rerere.ui.toast.rememberToasterState
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinIsolatedContext
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PromptEnhancementButtonTest {
    @get:Rule val compose = createComposeRule()

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val appScope = AppScope()
    private val requests = CopyOnWriteArrayList<JsonObject>()
    private val replies = LinkedBlockingQueue<String>()
    private val platform = MediaGenerationProviderSetting.OpenAIPlatform()
    private val model = Model(modelId = "fixture", abilities = listOf(ModelAbility.REASONING))
    private lateinit var client: OkHttpClient
    private lateinit var database: AppDatabase
    private lateinit var store: SettingsStore
    private lateinit var services: KoinApplication

    @Before
    fun setup() {
        client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.encodedPath == "/v1/chat/completions")
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            requests += Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            val reply = checkNotNull(replies.poll(30, TimeUnit.SECONDS))
            val body = """{"choices":[{"message":{"role":"assistant","content":${JsonPrimitive(reply)}}}]}"""
            Response.Builder().request(request).code(200).message("OK").protocol(Protocol.HTTP_1_1)
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        database = nativeRoom(app, "prompt-${Uuid.random()}.db")
        store = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File(app.cacheDir, "prompt-${Uuid.random()}.preferences_pb")
        }, scope)
        runBlocking {
            store.awaitLoaded()
            store.update {
                it.copy(
                    providers = listOf(ProviderSetting.OpenAI(models = listOf(model), apiKey = "fixture")),
                    mediaGenerationProviders = listOf(platform),
                    promptEnhancementModelId = model.id,
                    chatEnhancementPrompt = "CHAT rule",
                    imageEnhancementPrompt = "IMAGE rule",
                    chatEnhancementThinkingBudget = ReasoningLevel.LOW.budgetTokens,
                    imageEnhancementThinkingBudget = ReasoningLevel.MEDIUM.budgetTokens,
                )
            }
        }
        services = koinApplication {
            modules(module {
                single { client }
                single { store }
                single { ProviderManager(client, app) { _, _ -> error("Unexpected account request") } }
                single { UsageRecorder(database.usageRecordDao(), scope) }
                single { SoundEffectPlayer(app) }
                single { FilesManager(app, FilesRepository(database.managedFileDao()), appScope) }
            })
        }
    }

    @After
    fun close() {
        replies.offer("Test finished")
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        services.close()
        appScope.cancel()
        scope.cancel()
        database.close()
    }

    private fun show(content: @Composable () -> Unit) {
        val navigator = Navigator(mutableListOf())
        compose.setContent {
            KoinIsolatedContext(services) {
                CompositionLocalProvider(
                    LocalNavController provides navigator,
                    LocalSettings provides store.settingsFlow.value,
                    LocalToaster provides rememberToasterState(),
                ) {
                    MaterialTheme { content() }
                }
            }
        }
    }

    private fun waitFor(description: String, ready: () -> Boolean) {
        compose.waitUntil(description, 15_000) {
            shadowOf(Looper.getMainLooper()).idle()
            ready()
        }
    }

    private fun assertRequest(index: Int, source: String, rule: String, effort: String) {
        waitFor("captured enhancement request") { requests.size > index }
        val body = requests[index]
        assertEquals(model.modelId, body.getValue("model").jsonPrimitive.content)
        assertEquals(effort, body.getValue("reasoning_effort").jsonPrimitive.content)
        assertEquals(listOf(rule, source), body.getValue("messages").jsonArray.map {
            it.jsonObject.getValue("content").jsonPrimitive.content
        })
    }

    @Test
    fun editingWhileWaitingOffersRewriteAndUndoRestoresLatestTyping() {
        val chat = ChatInputState()
        val assistant = Assistant()
        show {
            CompositionLocalProvider(LocalASRState provides rememberCustomAsrState()) {
                ChatInput(
                    state = chat, loading = false, settings = store.settingsFlow.value, assistant = assistant,
                    chatModel = null, hazeState = rememberHazeState(), onUpdateChatModel = {}, onUpdateAssistant = {},
                    onMoreClick = {}, onCancelClick = {}, onSendClick = { error("Enhancement sent a message") },
                    onLongSendClick = {},
                )
            }
        }
        val input = compose.onNode(hasSetTextAction())
        val enhance = compose.onNodeWithContentDescription(app.getString(R.string.prompt_enhancement))
        enhance.assertIsNotEnabled()
        val original = "First line\nSecond line"
        input.performTextInput(original)
        enhance.performClick()
        assertRequest(0, original, "CHAT rule", "low")
        val extra = "\nWait for my approval."
        input.performTextInput(extra)
        val rewritten = "Reviewed draft"
        replies.add(rewritten)

        val review = app.getString(R.string.prompt_enhancement_review)
        waitFor("rewrite available") { compose.onAllNodes(hasText(review)).fetchSemanticsNodes().isNotEmpty() }
        input.assertTextEquals(original + extra)
        compose.onNodeWithText(review).performClick()
        compose.onNodeWithText(rewritten).assertExists()
        compose.onNodeWithText(app.getString(R.string.prompt_enhancement_apply)).performClick()
        input.assertTextEquals(rewritten)
        compose.onNodeWithText(app.getString(R.string.prompt_enhancement_undo)).performClick()
        input.assertTextEquals(original + extra)
        assertEquals(1, requests.size)
    }

    @Test
    fun imageComposerUsesImageRuleAndMediumWithTheSameTextModel() {
        val repo = MediaCreationRepository(app, database, database.mediaCreationDao(), JsonInstant)
        val session = runBlocking { repo.newSession() }
        val remote = RemoteFileStore(appScope, { store.settingsFlow.value.uploadS3Config }, { error("Unexpected upload") })
        val service = MediaCreationService(app, appScope, store, repo, MediaGenerationManager(client), remote, client)
        val vm = MediaCreationVM(session.id.toString(), app, store, repo, service, remote, appScope, services.koin.get())
        try {
            show {
                val draft by vm.draft.collectAsState()
                val selection by vm.selection.collectAsState()
                MediaCreationComposer(
                    vm = vm, draft = draft, selection = selection, providers = listOf(platform),
                    recentOutputs = emptyList(), uploadConfigured = false, onOpenMediaSettings = {},
                )
            }
            val label = app.getString(R.string.prompt_enhancement)
            waitFor("image model selected") { vm.selection.value?.provider == platform }
            val input = compose.onNode(hasSetTextAction())
            val original = "Edit only the car paint."
            input.performTextInput(original)
            compose.onNodeWithContentDescription(label).performClick()
            assertRequest(0, original, "IMAGE rule", "medium")
            val rewritten = "Change only the car paint, keeping everything else as it is."
            replies.add(rewritten)
            waitFor("image draft filled") { vm.promptState.text.toString() == rewritten }
            input.assertTextEquals(rewritten)
            compose.onNodeWithText(app.getString(R.string.prompt_enhancement_undo)).performClick()
            input.assertTextEquals(original)
            assertEquals(1, requests.size)
            assertEquals(0, runBlocking { repo.getRecordsOfSession(session.id).size })
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun standaloneUsesChatSettingsKeepsTheSourceAndCopiesOnlyTheCurrentResult() {
        show { PromptEnhancementPage() }
        val input = compose.onNodeWithTag("prompt_enhancement_source")
        val run = compose.onNodeWithTag("prompt_enhancement_run")
        val result = compose.onNodeWithTag("prompt_enhancement_result")
        val copy = compose.onNodeWithText(app.getString(R.string.prompt_enhancement_page_copy))
        run.assertIsNotEnabled()
        copy.assertIsNotEnabled()
        val original = "First line\nSecond line"
        val clipboard = requireNotNull(app.getSystemService(ClipboardManager::class.java))
        clipboard.setPrimaryClip(ClipData.newPlainText(null, original))
        compose.onNodeWithText(app.getString(R.string.translator_page_paste)).performClick()
        input.assert(hasText(original))
        run.performClick()
        assertRequest(0, original, "CHAT rule", "low")
        run.assertIsNotEnabled().performTouchInput { click() }

        val extra = "\nDo not start yet."
        val latest = original + extra
        input.performTextReplacement(latest)
        replies.add("Obsolete answer")
        waitFor("request finished") {
            compose.onAllNodes(hasText(app.getString(R.string.prompt_enhancement_working))).fetchSemanticsNodes().isEmpty()
        }
        run.assertIsEnabled()
        result.assertDoesNotExist()
        copy.assertIsNotEnabled()
        input.assert(hasText(latest))
        assertEquals(1, requests.size)

        run.performClick()
        assertRequest(1, latest, "CHAT rule", "low")
        val rewritten = "Review the following:\n\n- First point\n  Keep this indentation."
        replies.add(rewritten)
        waitFor("standalone result") { compose.onAllNodes(hasText(rewritten)).fetchSemanticsNodes().isNotEmpty() }
        result.assertTextEquals(rewritten)
        input.assert(hasText(latest))
        copy.performClick()
        waitFor("whole result on Android clipboard") { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == rewritten }
        input.performTextInput(" More detail.")
        result.assertDoesNotExist()
        copy.assertIsNotEnabled()
        assertEquals(2, requests.size)
    }
}
