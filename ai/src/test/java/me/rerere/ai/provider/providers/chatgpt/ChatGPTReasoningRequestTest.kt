package me.rerere.ai.provider.providers.chatgpt

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ChatGPTModelCapabilities
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.provider.providers.openai.ResponseProviderCapabilities
import me.rerere.ai.provider.supportedChatGPTReasoningLevels
import me.rerere.ai.ui.OpenAIReasoningMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Captured JSON uses synthetic catalog/credentials; it does not prove upstream account acceptance. */
class ChatGPTReasoningRequestTest {
    private val api = ResponseAPI(OkHttpClient())
    private val model = Model(
        modelId = "catalog-model",
        abilities = listOf(ModelAbility.REASONING, ModelAbility.TOOL),
        chatGPTCapabilities = ChatGPTModelCapabilities(
            reasoningEfforts = listOf("none", "low", "medium", "high", "xhigh", "max"),
            defaultReasoningEffort = "medium",
            supportsReasoningSummary = true,
            supportsWebSearch = true,
        ),
    )

    private fun body(
        level: ReasoningLevel = ReasoningLevel.AUTO,
        selectedModel: Model = model,
        custom: List<CustomBody> = emptyList(),
        messages: List<UIMessage> = listOf(UIMessage.user("test")),
    ): JsonObject = api.buildRequestBody(
        chatGPTCapabilities(selectedModel), messages,
        TextGenerationParams(selectedModel, reasoningLevel = level, customBody = custom, sessionId = "conversation-1"),
        stream = true,
    )

    private fun custom(key: String, value: String) = CustomBody(key, json.parseToJsonElement(value))

    @Test
    fun `actual HTTP bodies preserve every selected level after persistence and switches`() = runTest {
        val captured = CopyOnWriteArrayList<JsonObject>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            captured += json.parseToJsonElement(buffer.readUtf8()).jsonObject
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("data: {\"type\":\"response.completed\",\"response\":{\"id\":\"synthetic\",\"status\":\"completed\"}}\n\n".toResponseBody("text/event-stream".toMediaType()))
                .build()
        }.build()
        val credential = OAuthCredential(
            accessToken = "synthetic-access", protocolVersion = 1, clientId = "synthetic-client",
            issuer = "https://auth.openai.com", subject = "synthetic-subject",
            grantedScopes = setOf("chatgpt.tokens.use.direct"),
        )
        val provider = ChatGPTProvider(client) { _, _ -> credential }
        val restoredModel = json.decodeFromString<Model>(json.encodeToString(model))
        try {
            for (level in ReasoningLevel.entries) {
                val restoredLevel = json.decodeFromString<ReasoningLevel>(json.encodeToString(level))
                provider.streamText(
                    ProviderSetting.ChatGPT(credential = credential), listOf(UIMessage.user("test")),
                    TextGenerationParams(restoredModel, reasoningLevel = restoredLevel, sessionId = "same-conversation"),
                ).toList()
                val request = captured.last()
                assertEquals(level.name, level.effort.takeUnless { level == ReasoningLevel.AUTO }, request["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
                assertEquals("false", request.getValue("store").jsonPrimitive.content)
                assertEquals("true", request.getValue("stream").jsonPrimitive.content)
                assertEquals("same-conversation", request.getValue("prompt_cache_key").jsonPrimitive.content)
            }
            assertEquals(ReasoningLevel.entries.size, captured.size)
            val nextModel = restoredModel.copy(modelId = "different-catalog-model", chatGPTCapabilities = ChatGPTModelCapabilities(reasoningEfforts = listOf("low")))
            provider.streamText(
                ProviderSetting.ChatGPT(credential = credential), listOf(UIMessage.user("next model")),
                TextGenerationParams(nextModel, reasoningLevel = ReasoningLevel.LOW, sessionId = "same-conversation"),
            ).toList()
            assertEquals(nextModel.modelId, captured.last().getValue("model").jsonPrimitive.content)
            assertEquals("low", captured.last().getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
            assertFalse("summary" in captured.last().getValue("reasoning").jsonObject)
            val countBeforeInvalid = captured.size
            assertTrue(runCatching {
                provider.streamText(
                    ProviderSetting.ChatGPT(credential = credential), listOf(UIMessage.user("invalid level")),
                    TextGenerationParams(nextModel, reasoningLevel = ReasoningLevel.XHIGH),
                ).toList()
            }.isFailure)
            assertEquals(countBeforeInvalid, captured.size)
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `catalog governs OFF and every explicit level without silent downgrade`() {
        val limited = model.copy(chatGPTCapabilities = ChatGPTModelCapabilities(reasoningEfforts = listOf("low", "high")))
        assertEquals(listOf(ReasoningLevel.AUTO, ReasoningLevel.LOW, ReasoningLevel.HIGH), limited.supportedChatGPTReasoningLevels())
        assertNull(body(selectedModel = limited)["reasoning"])
        for (level in listOf(ReasoningLevel.OFF, ReasoningLevel.MEDIUM, ReasoningLevel.XHIGH, ReasoningLevel.MAX)) {
            val error = assertThrows(IllegalArgumentException::class.java) { body(level, limited) }
            assertTrue(error.message.orEmpty().contains(if (level == ReasoningLevel.OFF) "none" else level.effort))
        }
        val unknown = Model(modelId = "unknown-model")
        assertEquals(listOf(ReasoningLevel.AUTO), unknown.supportedChatGPTReasoningLevels())
        assertNull(body(selectedModel = unknown)["reasoning"])
        assertThrows(IllegalArgumentException::class.java) { body(ReasoningLevel.OFF, unknown) }
        // Catalog capability must not be lost when an editable generic ability flag is absent.
        assertEquals("xhigh", body(ReasoningLevel.XHIGH, model.copy(abilities = emptyList())).getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
    }

    @Test
    fun `final deep merge retains selected effort and rejects conflicting overrides`() {
        val merged = body(ReasoningLevel.HIGH, custom = listOf(custom("reasoning", """{"summary":"detailed"}""")))
        assertEquals("high", merged.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
        assertEquals("detailed", merged.getValue("reasoning").jsonObject.getValue("summary").jsonPrimitive.content)
        // Validate the effective higher-priority value, not an overwritten lower-priority body.
        val matching = body(ReasoningLevel.HIGH, custom = listOf(custom("reasoning", """{"effort":"low"}"""), custom("reasoning", """{"effort":"high"}""")))
        assertEquals("high", matching.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
        for (override in listOf("""{"effort":"low"}""", """{"effort":null}""", "null", "false")) {
            assertThrows(IllegalArgumentException::class.java) { body(ReasoningLevel.HIGH, custom = listOf(custom("reasoning", override))) }
        }
        assertThrows(IllegalArgumentException::class.java) { body(custom = listOf(custom("reasoning", """{"effort":"high"}"""))) }
    }

    @Test
    fun `summary follows actual catalog and encrypted history survives custom include`() {
        val unavailable = model.copy(chatGPTCapabilities = model.chatGPTCapabilities!!.copy(supportsReasoningSummary = false))
        assertFalse("summary" in body(ReasoningLevel.HIGH, unavailable).getValue("reasoning").jsonObject)
        for (field in listOf("summary", "generate_summary")) {
            assertThrows(IllegalArgumentException::class.java) {
                body(ReasoningLevel.HIGH, unavailable, custom = listOf(custom("reasoning", """{"$field":"auto"}""")))
            }
        }
        val encrypted = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Reasoning(
                reasoning = "not replayed as plaintext",
                metadata = OpenAIReasoningMetadata(reasoningId = "rs_1", encryptedContent = "synthetic-ciphertext").toMetadata(),
            )),
        )
        val history = json.decodeFromString<List<UIMessage>>(json.encodeToString(listOf(UIMessage.system("Keep context"), UIMessage.user("first"), encrypted, UIMessage.user("next"))))
        val request = body(ReasoningLevel.XHIGH, custom = listOf(custom("include", """["web_search_call.action.sources"]""")), messages = history)
        assertEquals(listOf("web_search_call.action.sources", "reasoning.encrypted_content"), request.getValue("include").jsonArray.map { it.jsonPrimitive.content })
        val input = request.getValue("input").jsonArray.map { it.jsonObject }
        assertEquals("developer", input.first().getValue("role").jsonPrimitive.content)
        val replay = input.single { it["type"] == JsonPrimitive("reasoning") }
        assertEquals("synthetic-ciphertext", replay.getValue("encrypted_content").jsonPrimitive.content)
        assertFalse("content" in replay)
        assertFalse("previous_response_id" in request)
        assertThrows(IllegalArgumentException::class.java) { body(custom = listOf(CustomBody("include", JsonNull))) }
    }

    @Test
    fun `advanced unsupported fields and stream storage conflicts are precise errors`() {
        for (field in listOf("background", "conversation", "max_output_tokens", "max_tool_calls", "metadata", "moderation", "multi_agent", "prompt", "prompt_cache_retention", "safety_identifier", "temperature", "top_logprobs", "top_p", "truncation", "user", "previous_response_id")) {
            val error = assertThrows(IllegalArgumentException::class.java) { body(custom = listOf(CustomBody(field, JsonPrimitive(1)))) }
            assertTrue(error.message.orEmpty().contains(field))
        }
        for ((field, value) in listOf("store" to "true", "stream" to "false", "stream" to "\"bad\"")) {
            val error = assertThrows(IllegalArgumentException::class.java) { body(custom = listOf(custom(field, value))) }
            assertTrue(error.message.orEmpty().contains(field))
        }
        val request = body(custom = listOf(custom("instructions", "\"Extra instruction\""), custom("prompt_cache_key", "\"advanced-cache-key\""), custom("parallel_tool_calls", "false"), custom("text", """{"verbosity":"low"}""")))
        assertEquals("Extra instruction", request.getValue("instructions").jsonPrimitive.content)
        assertEquals("advanced-cache-key", request.getValue("prompt_cache_key").jsonPrimitive.content)
        assertEquals("false", request.getValue("parallel_tool_calls").jsonPrimitive.content)
        assertEquals("low", request.getValue("text").jsonObject.getValue("verbosity").jsonPrimitive.content)
    }

    @Test
    fun `nested allowed tools rejects unsupported native tools without rejecting client functions`() {
        for (type in listOf("image_generation", "url_context", "file_search", "mcp", "code_interpreter", "computer")) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                body(custom = listOf(custom("tool_choice", """{"type":"allowed_tools","mode":"auto","tools":[{"type":"$type"}]}""")))
            }
            assertTrue(error.message.orEmpty().contains(type))
            assertTrue(error.message.orEmpty().contains("tool_choice.tools[0]"))
        }
        val allowed = custom("tool_choice", """{"type":"allowed_tools","mode":"required","tools":[{"type":"function","name":"search_web","namespace":"rikkahub"}]}""")
        assertEquals(allowed.value, body(custom = listOf(allowed))["tool_choice"])
        // A later scalar override replaces an obsolete lower-priority choice before validation.
        assertEquals(JsonPrimitive("auto"), body(custom = listOf(custom("tool_choice", """{"type":"image_generation"}"""), custom("tool_choice", "\"auto\"")))["tool_choice"])
    }

    @Test
    fun `ordinary API key keeps original reasoning and custom body precedence`() {
        val params = TextGenerationParams(
            model, reasoningLevel = ReasoningLevel.HIGH,
            customBody = listOf(custom("reasoning", """{"effort":"low","summary":"detailed"}"""), custom("include", "[]"), custom("store", "true"), custom("temperature", "0.25")),
        )
        val request = api.buildRequestBody(ResponseProviderCapabilities(), listOf(UIMessage.system("API instructions"), UIMessage.user("hello")), params, stream = false)
        assertEquals("low", request.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
        assertEquals("detailed", request.getValue("reasoning").jsonObject.getValue("summary").jsonPrimitive.content)
        assertEquals("true", request.getValue("store").jsonPrimitive.content)
        assertEquals("false", request.getValue("stream").jsonPrimitive.content)
        assertEquals("0.25", request.getValue("temperature").jsonPrimitive.content)
        assertTrue(request.getValue("include").jsonArray.isEmpty())
        assertEquals("API instructions", request.getValue("instructions").jsonPrimitive.content)
    }
}
