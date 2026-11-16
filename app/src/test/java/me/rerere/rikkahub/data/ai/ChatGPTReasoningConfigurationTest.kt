package me.rerere.rikkahub.data.ai

import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ChatGPTModelCapabilities
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.chatgpt.ChatGPTProvider
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.putSettings
import me.rerere.rikkahub.data.datastore.toSettings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.bindConfig
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.model.withAssistantUpdate
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises persisted app configuration through the public provider's final HTTP request. */
class ChatGPTReasoningConfigurationTest {
    private val model = Model(
        modelId = "catalog-model-a",
        // Catalog reasoning must still be honored when the editable ability checkbox is absent.
        abilities = listOf(ModelAbility.TOOL),
        chatGPTCapabilities = ChatGPTModelCapabilities(
            reasoningEfforts = listOf("none", "low", "medium", "high", "xhigh", "max"),
            supportsReasoningSummary = true,
        ),
    )
    private val assistant = Assistant(chatModelId = model.id, reasoningLevel = ReasoningLevel.HIGH)

    private fun settings(assistant: Assistant = this.assistant, models: List<Model> = listOf(model)) = Settings(
        assistantId = assistant.id,
        assistants = listOf(assistant),
        providers = listOf(ProviderSetting.ChatGPT(models = models)),
    )

    private fun turn(settings: Settings, conversation: Conversation, tools: List<Tool> = emptyList()): ChatTurnContext {
        val model = checkNotNull(settings.getChatModelOf(conversation))
        return ChatTurnContext(
            settings = settings,
            assistant = settings.getAssistantOf(conversation),
            model = model,
            providerSetting = checkNotNull(model.findProvider(settings.providers)),
            conversationId = conversation.id,
            conversationSystemPrompt = null,
            workspaceCwd = null,
            memories = emptyList(),
            tools = tools,
        )
    }

    private fun reload(settings: Settings): Settings = mutablePreferencesOf().apply { putSettings(settings) }.toSettings()

    private fun reload(conversation: Conversation): Conversation = Json.decodeFromString(
        Conversation.serializer(), Json.encodeToString(Conversation.serializer(), conversation),
    )

    @Test
    fun `every UI level survives settings and conversation reload with its exact wire effort`() = runBlocking {
        for (level in ReasoningLevel.entries) {
            val settings = reload(settings(assistant.copy(reasoningLevel = level)))
            val conversation = reload(Conversation(assistantId = assistant.id, messageNodes = emptyList()).bindConfig(settings))
            val context = turn(settings, conversation)
            assertEquals(level, context.assistant.reasoningLevel)
            assertEquals(model.chatGPTCapabilities, context.model.chatGPTCapabilities)

            val body = capture(context.textGenerationParams())
            assertEquals(model.modelId, body.getValue("model").jsonPrimitive.content)
            assertEquals(
                if (level == ReasoningLevel.AUTO) null else level.effort,
                body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content,
            )
            assertEquals(conversation.id.toString(), body.getValue("prompt_cache_key").jsonPrimitive.content)
            assertTrue(body.getValue("include").jsonArray.contains(JsonPrimitive("reasoning.encrypted_content")))
        }
    }

    @Test
    fun `conversation snapshot and model custom body take precedence after reload`() = runBlocking {
        val model = model.copy(customBodies = listOf(CustomBody("reasoning", buildJsonObject { put("summary", "detailed") })))
        val originalAssistant = assistant.copy(customBodies = listOf(CustomBody("reasoning", buildJsonObject { put("summary", "concise") })))
        val original = settings(originalAssistant, listOf(model))
        val conversation = reload(Conversation(assistantId = assistant.id, messageNodes = emptyList()).bindConfig(original))
        val laterSettings = reload(original.copy(assistants = listOf(originalAssistant.copy(reasoningLevel = ReasoningLevel.LOW))))

        val body = capture(turn(laterSettings, conversation).textGenerationParams())
        assertEquals("high", body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
        assertEquals("detailed", body.getValue("reasoning").jsonObject.getValue("summary").jsonPrimitive.content)
    }

    @Test
    fun `model switch rechecks actual model support without silently lowering the saved level`() = runBlocking {
        val otherModel = model.copy(
            id = kotlin.uuid.Uuid.random(), modelId = "catalog-model-b",
            chatGPTCapabilities = ChatGPTModelCapabilities(reasoningEfforts = listOf("low")),
        )
        val settings = settings(models = listOf(model, otherModel))
        val original = Conversation(assistantId = assistant.id, messageNodes = emptyList()).bindConfig(settings)
        val switched = reload(original.withAssistantUpdate(settings.getAssistantOf(original).copy(chatModelId = otherModel.id), settings))
        assertEquals(ReasoningLevel.HIGH, settings.getAssistantOf(switched).reasoningLevel)
        val failure = runCatching { capture(turn(settings, switched).textGenerationParams()) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("high"))

        val selected = switched.withAssistantUpdate(settings.getAssistantOf(switched).copy(reasoningLevel = ReasoningLevel.LOW), settings)
        val body = capture(turn(settings, reload(selected)).textGenerationParams())
        assertEquals("catalog-model-b", body.getValue("model").jsonPrimitive.content)
        assertEquals("low", body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
    }

    @Test
    fun `custom body cannot override the chosen reasoning level or required route flags`() = runBlocking {
        for ((field, value) in listOf(
            "reasoning" to buildJsonObject { put("effort", "low") },
            "stream" to JsonPrimitive(false),
            "store" to JsonPrimitive(true),
        )) {
            val settings = reload(settings(assistant.copy(customBodies = listOf(CustomBody(field, value)))))
            val conversation = Conversation(assistantId = assistant.id, messageNodes = emptyList()).bindConfig(settings)
            val failure = runCatching { capture(turn(settings, conversation).textGenerationParams()) }.exceptionOrNull()
            assertTrue("Expected targeted error for $field, got $failure", failure?.message.orEmpty().contains(field))
        }
    }

    @Test
    fun `tool continuation and compression retain conversation effort and complete history`() = runBlocking {
        val settings = settings(assistant.copy(reasoningLevel = ReasoningLevel.XHIGH, maxTokens = 400))
        val conversation = Conversation(assistantId = assistant.id, messageNodes = emptyList()).bindConfig(settings)
        val tool = Tool("local_tool", "Client tool", execute = { emptyList() })
        val context = turn(settings, conversation, listOf(tool))
        val history = listOf(
            UIMessage.system("Keep the system prompt"),
            UIMessage.user("Use local_tool"),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool(
                toolCallId = "call_1", toolName = "local_tool", input = "{}",
                output = listOf(UIMessagePart.Text("completed client result")),
            ))),
        )
        val first = capture(context.textGenerationParams(), history.take(2))
        val next = capture(context.textGenerationParams(), history)
        // Compression uses the same buildStep and explicitly clears only the reply token limit.
        val compression = capture(context.textGenerationParams().copy(maxTokens = null), history + UIMessage.user("Compress"))
        for (body in listOf(first, next, compression)) {
            assertEquals("xhigh", body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
            assertEquals(conversation.id.toString(), body.getValue("prompt_cache_key").jsonPrimitive.content)
            assertEquals("developer", body.getValue("input").jsonArray.first().jsonObject.getValue("role").jsonPrimitive.content)
            assertFalse(body.containsKey("max_output_tokens"))
        }
        assertEquals(first["tools"], next["tools"])
        assertEquals(next["tools"], compression["tools"])
        val replay = next.getValue("input").jsonArray.map { it.jsonObject }
        assertEquals("call_1", replay.single { it["type"] == JsonPrimitive("function_call") }.getValue("call_id").jsonPrimitive.content)
        assertTrue(replay.single { it["type"] == JsonPrimitive("function_call_output") }.getValue("output").toString().contains("completed client result"))
    }

    @Test
    fun `fast model background request uses its own level and custom body`() = runBlocking {
        val fast = model.copy(id = kotlin.uuid.Uuid.random(), modelId = "fast-model", customBodies = listOf(CustomBody("prompt_cache_key", JsonPrimitive("fast-cache"))))
        val settings = reload(settings(models = listOf(model, fast)).copy(fastModelId = fast.id, fastModelReasoningLevel = ReasoningLevel.LOW))
        val params = backgroundTextGenerationParams(checkNotNull(settings.findModelById(settings.fastModelId)), kotlin.uuid.Uuid.random(), settings.fastModelReasoningLevel)
        val body = capture(params)
        assertEquals("fast-model", body.getValue("model").jsonPrimitive.content)
        assertEquals("low", body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
        assertEquals("fast-cache", body.getValue("prompt_cache_key").jsonPrimitive.content)
        assertNull(body["tools"])
    }

    @Test
    fun `translation budget survives reload and uses the selected model configuration`() = runBlocking {
        val translationModel = model.copy(
            customBodies = listOf(CustomBody("prompt_cache_key", JsonPrimitive("translation-cache"))),
        )
        for (level in ReasoningLevel.entries) {
            val settings = reload(settings(models = listOf(translationModel)).copy(
                translateModeId = translationModel.id,
                translateThinkingBudget = level.budgetTokens,
            ))
            val selected = checkNotNull(settings.findModelById(settings.translateModeId))
            val params = translationGenerationParams(selected, checkNotNull(selected.findProvider(settings.providers)), settings.translateThinkingBudget)
            val body = capture(params)
            assertEquals(level, params.reasoningLevel)
            assertEquals(if (level == ReasoningLevel.AUTO) null else level.effort, body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
            assertEquals("translation-cache", body.getValue("prompt_cache_key").jsonPrimitive.content)
        }
        for (provider in listOf(ProviderSetting.OpenAI(), ProviderSetting.Google())) {
            val ordinary = translationGenerationParams(translationModel, provider, ReasoningLevel.HIGH.budgetTokens)
            assertEquals(ReasoningLevel.HIGH, ordinary.reasoningLevel)
            assertTrue(ordinary.customBody.isEmpty())
            assertTrue(ordinary.customHeaders.isEmpty())
        }
    }

    private suspend fun capture(params: TextGenerationParams, messages: List<UIMessage> = listOf(UIMessage.user("test"))): JsonObject {
        var captured: JsonObject? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val buffer = Buffer()
            checkNotNull(request.body).writeTo(buffer)
            captured = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic fixture")
                .body(("data: {\"type\":\"response.output_text.delta\",\"item_id\":\"msg\",\"delta\":\"ok\"}\n\n" +
                    "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp\",\"status\":\"completed\"}}\n\n")
                    .toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val credential = OAuthCredential(
            accessToken = "synthetic-access", protocolVersion = 1, clientId = "synthetic-client",
            issuer = "https://auth.openai.com", subject = "synthetic-subject", grantedScopes = setOf("chatgpt.tokens.use.direct"),
        )
        try {
            ChatGPTProvider(client) { _, _ -> credential }.generateText(ProviderSetting.ChatGPT(credential = credential), messages, params)
            return checkNotNull(captured).also {
                assertEquals(JsonPrimitive(true), it["stream"])
                assertEquals(JsonPrimitive(false), it["store"])
            }
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
