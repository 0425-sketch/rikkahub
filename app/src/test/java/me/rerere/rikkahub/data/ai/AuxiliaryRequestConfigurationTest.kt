package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ChatGPTModelCapabilities
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.chatgpt.ChatGPTProvider
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.transformers.ocrGenerationParams
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.pages.setting.components.connectionTestRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AuxiliaryRequestConfigurationTest {
    private val chatGPT = ProviderSetting.ChatGPT()
    private val model = Model(
        modelId = "auxiliary-model",
        abilities = listOf(ModelAbility.REASONING),
        chatGPTCapabilities = ChatGPTModelCapabilities(reasoningEfforts = listOf("high", "xhigh")),
        customHeaders = listOf(CustomHeader(name = "X-Auxiliary-Test", value = "model-owned")),
        customBodies = listOf(CustomBody(key = "prompt_cache_key", value = JsonPrimitive("auxiliary-cache"))),
    )

    @Test
    fun `connection test captures selected model route and AUTO request independent of later selection`() = runBlocking {
        var selected = model.copy(providerOverwrite = chatGPT)
        val snapshot = connectionTestRequest(selected, ProviderSetting.OpenAI())
        selected = Model(modelId = "later-selection", providerOverwrite = ProviderSetting.Google())

        assertSame(chatGPT, snapshot.providerSetting)
        assertEquals("later-selection", selected.modelId)
        assertEquals(model.id, snapshot.params.model.id)
        assertEquals(ReasoningLevel.AUTO, snapshot.params.reasoningLevel)
        assertEquals(model.customHeaders, snapshot.params.customHeaders)
        assertEquals(model.customBodies, snapshot.params.customBody)

        val body = captureBody(snapshot.params)
        assertEquals(model.modelId, body.getValue("model").jsonPrimitive.content)
        assertNull(body["reasoning"]?.jsonObject?.get("effort"))
        assertEquals("auxiliary-cache", body.getValue("prompt_cache_key").jsonPrimitive.content)
    }

    @Test
    fun `ChatGPT OCR uses server default even when catalog does not allow disabling reasoning`() = runBlocking {
        val params = ocrGenerationParams(model.copy(providerOverwrite = chatGPT), ProviderSetting.OpenAI())
        assertEquals(ReasoningLevel.AUTO, params.reasoningLevel)
        assertEquals(model.customHeaders, params.customHeaders)
        assertEquals(model.customBodies, params.customBody)
        val body = captureBody(params)
        assertEquals(model.modelId, body.getValue("model").jsonPrimitive.content)
        assertNull(body["reasoning"]?.jsonObject?.get("effort"))
    }

    @Test
    fun `ordinary API and Gemini OCR keep original OFF setting including overrides`() {
        listOf(ProviderSetting.OpenAI(), ProviderSetting.Google()).forEach { provider ->
            assertEquals(ReasoningLevel.OFF, ocrGenerationParams(model, provider).reasoningLevel)
            assertEquals(
                ReasoningLevel.OFF,
                ocrGenerationParams(model.copy(providerOverwrite = provider), chatGPT).reasoningLevel,
            )
            assertSame(provider, connectionTestRequest(model, provider).providerSetting)
        }
    }

    @Test
    fun `enhancement sends its mode-specific SIWC effort and omits effort for the 206 defaults`() = runBlocking {
        // This model is routed through ChatGPT and advertises efforts through its account catalog.
        val selected = model.copy(providerOverwrite = chatGPT, abilities = emptyList())
        val settings = Settings(
            promptEnhancementModelId = selected.id,
            providers = listOf(ProviderSetting.OpenAI(models = listOf(selected))),
        )
        suspend fun effort(current: Settings, mode: PromptEnhancementMode): String? {
            val body = captureBody { client, account ->
                val result = enhancePrompt(current, mode, "草稿") { setting, messages, params ->
                    assertEquals(account.id, setting.id)
                    client.generateText(account, messages, params)
                }
                assertEquals("ok", result)
            }
            return body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content
        }
        assertNull(effort(settings, PromptEnhancementMode.CHAT))
        assertNull(effort(settings, PromptEnhancementMode.IMAGE))
        val explicit = settings.copy(
            chatEnhancementThinkingBudget = ReasoningLevel.HIGH.budgetTokens,
            imageEnhancementThinkingBudget = ReasoningLevel.XHIGH.budgetTokens,
        )
        assertEquals("high", effort(explicit, PromptEnhancementMode.CHAT))
        assertEquals("xhigh", effort(explicit, PromptEnhancementMode.IMAGE))
    }

    private suspend fun captureBody(params: TextGenerationParams): JsonObject = captureBody { client, account ->
        client.generateText(account, listOf(UIMessage.user("Auxiliary request")), params)
    }

    private suspend fun captureBody(request: suspend (ChatGPTProvider, ProviderSetting.ChatGPT) -> Unit): JsonObject {
        var captured: JsonObject? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("api.openai.com", request.url.host)
            assertEquals("/v1/responses", request.url.encodedPath)
            assertEquals("model-owned", request.header("X-Auxiliary-Test"))
            val buffer = Buffer()
            checkNotNull(request.body).writeTo(buffer)
            captured = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("Synthetic fixture")
                .body(("data: {\"type\":\"response.output_text.delta\",\"item_id\":\"msg\",\"delta\":\"ok\"}\n\n" +
                    "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp\",\"status\":\"completed\"}}\n\n")
                    .toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val credential = OAuthCredential(
            accessToken = "synthetic-access", protocolVersion = 1, clientId = "synthetic-client",
            issuer = "https://auth.openai.com", subject = "synthetic-subject",
            grantedScopes = setOf("chatgpt.tokens.use.direct"),
        )
        try {
            request(ChatGPTProvider(client) { _, _ -> credential }, chatGPT.copy(credential = credential))
            return checkNotNull(captured).also {
                assertEquals("true", it.getValue("stream").jsonPrimitive.content)
                assertEquals("false", it.getValue("store").jsonPrimitive.content)
            }
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
