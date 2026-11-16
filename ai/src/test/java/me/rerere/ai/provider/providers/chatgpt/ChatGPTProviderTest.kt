package me.rerere.ai.provider.providers.chatgpt

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.toList
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.BuiltInTools
import org.junit.Assert.assertThrows
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Modality
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.provider.supportsBuiltInSearch
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGPTProviderTest {
    private val api = ResponseAPI(OkHttpClient())

    private fun chatGPTBody(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        sessionId: String? = "session-1",
    ) = api.buildRequestBody(CHATGPT_CAPABILITIES, messages, params.copy(sessionId = sessionId), stream = true)

    @Test
    fun `system prompt becomes leading developer message instead of instructions`() {
        val body = chatGPTBody(
            messages = listOf(UIMessage.system("Be brief."), UIMessage.user("hello")),
            params = TextGenerationParams(model = Model(modelId = "gpt-5.5")),
        )

        assertFalse("instructions" in body)
        val input = body["input"]!!.jsonArray
        val developer = input[0].jsonObject
        assertEquals("developer", developer["role"]!!.jsonPrimitive.content)
        assertEquals("Be brief.", developer["content"]!!.jsonPrimitive.content)
        assertEquals("user", input[1].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `no developer message without system prompt`() {
        val body = chatGPTBody(
            messages = listOf(UIMessage.user("hello")),
            params = TextGenerationParams(model = Model(modelId = "gpt-5.5")),
        )

        val input = body["input"]!!.jsonArray
        assertEquals(1, input.size)
        assertEquals("user", input[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `streams without storage and drops unsupported parameters`() {
        val body = chatGPTBody(
            messages = listOf(UIMessage.user("hello")),
            // 非推理模型名，ResponseAPI 会带上 temperature/top_p
            params = TextGenerationParams(
                model = Model(modelId = "custom-model"),
                temperature = 0.7f,
                topP = 0.9f,
                maxTokens = 1024,
            ),
        )

        assertEquals(true, body["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(false, body["store"]!!.jsonPrimitive.content.toBoolean())
        assertFalse("max_output_tokens" in body)
        assertFalse("temperature" in body)
        assertFalse("top_p" in body)
        // 路由字段保持在最前面
        assertEquals(listOf("model", "stream", "store"), body.keys.take(3))
    }

    @Test
    fun `always includes encrypted reasoning and prompt cache key`() {
        val body = chatGPTBody(
            messages = listOf(UIMessage.user("hello")),
            params = TextGenerationParams(model = Model(modelId = "gpt-5.5")),
            sessionId = "conversation-42",
        )

        assertEquals(
            listOf("reasoning.encrypted_content"),
            body["include"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("conversation-42", body["prompt_cache_key"]!!.jsonPrimitive.content)
    }

    @Test
    fun `keeps include from reasoning models and omits missing cache key`() {
        val body = chatGPTBody(
            messages = listOf(UIMessage.user("hello")),
            params = TextGenerationParams(
                model = Model(modelId = "gpt-5.5", abilities = listOf(ModelAbility.REASONING)),
            ),
            sessionId = null,
        )

        assertEquals(1, body["include"]!!.jsonArray.size)
        // AUTO effort and no advertised reasoning summary do not invent a capability.
        assertFalse("summary" in (body["reasoning"]?.jsonObject.orEmpty()))
        assertNull(body["prompt_cache_key"])
    }

    @Test
    fun `parses visible account models`() {
        val models = parseChatGPTModels(
            """
            {
              "models": [
                {
                  "slug": "gpt-5.5",
                  "display_name": "GPT-5.5",
                  "visibility": "list",
                  "supported_reasoning_levels": [{"effort": "low", "description": "fast"}],
                  "input_modalities": ["text", "image"],
                  "context_window": 272000
                },
                {
                  "slug": "gpt-5.5-mini",
                  "display_name": "GPT-5.5 mini",
                  "visibility": "list",
                  "supported_reasoning_levels": [],
                  "input_modalities": ["text"]
                },
                {
                  "slug": "internal-model",
                  "display_name": "Internal",
                  "visibility": "hide",
                  "supported_reasoning_levels": []
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(listOf("gpt-5.5", "gpt-5.5-mini"), models.map { it.modelId })
        val full = models[0]
        assertEquals("GPT-5.5", full.displayName)
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), full.inputModalities)
        assertEquals(listOf(ModelAbility.TOOL, ModelAbility.REASONING), full.abilities)
        assertEquals(272000, full.contextLength)
        val mini = models[1]
        assertEquals(listOf(Modality.TEXT), mini.inputModalities)
        assertEquals(listOf(ModelAbility.TOOL), mini.abilities)
    }

    @Test
    fun `does not expose hidden models or infer image capability`() {
        val models = parseChatGPTModels(
            """{"models": [{"slug": "a", "display_name": "A", "visibility": "hide"}]}"""
        )

        assertTrue(models.isEmpty())
        val listed = parseChatGPTModels("""{"models":[{"slug":"b","visibility":"list"}]}""")
        assertEquals(listOf(Modality.TEXT), listed.single().inputModalities)
    }

    @Test
    fun `chatgpt provider round trips through provider list serialization`() {
        val provider = ProviderSetting.ChatGPT(
            credential = OAuthCredential(accessToken = "access", refreshToken = "refresh", accountId = "acc"),
        )

        val encoded = json.encodeToString<List<ProviderSetting>>(listOf(provider))
        assertTrue(encoded.contains("\"type\":\"chatgpt\""))
        val decoded = json.decodeFromString<List<ProviderSetting>>(encoded).single()

        assertTrue(decoded is ProviderSetting.ChatGPT)
        assertEquals(provider.credential, (decoded as ProviderSetting.ChatGPT).credential)
    }

    @Test
    fun `credential is masked in toString`() {
        val credential = OAuthCredential(accessToken = "access-token-secret", refreshToken = "refresh-token-secret")

        assertFalse(credential.toString().contains("access-token-secret"))
        assertFalse(credential.toString().contains("refresh-token-secret"))
    }

    @Test
    fun `built in search support by provider type`() {
        assertTrue(ProviderSetting.ChatGPT().supportsBuiltInSearch)
        assertTrue(ProviderSetting.Google().supportsBuiltInSearch)
        assertTrue(ProviderSetting.Claude().supportsBuiltInSearch)
        assertTrue(ProviderSetting.OpenAI(useResponseApi = true).supportsBuiltInSearch)
        assertFalse(ProviderSetting.OpenAI(useResponseApi = false).supportsBuiltInSearch)
    }

    @Test
    fun `catalog reasoning capabilities survive persistence and govern requests`() {
        val model = parseChatGPTModels("""{"models":[{"visibility":"list","slug":"reasoning-model","supported_reasoning_levels":[{"effort":"low"},{"effort":"high"}],"default_reasoning_level":"low","supports_reasoning_summary_parameter":false,"supports_search_tool":false}]}""").single()
        val restored = json.decodeFromString<Model>(json.encodeToString(model))
        assertEquals(model.chatGPTCapabilities, restored.chatGPTCapabilities)
        val defaultBody = api.buildRequestBody(chatGPTCapabilities(restored), listOf(UIMessage.user("hello")), TextGenerationParams(restored), true)
        // App's default OFF must not invent an unsupported `none` effort.
        assertNull(defaultBody["reasoning"])
        val highBody = api.buildRequestBody(chatGPTCapabilities(restored), listOf(UIMessage.user("hello")), TextGenerationParams(restored, reasoningLevel = ReasoningLevel.HIGH), true)
        assertEquals("high", highBody.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
        assertFalse("summary" in highBody.getValue("reasoning").jsonObject)
        assertThrows(IllegalStateException::class.java) {
            api.buildRequestBody(chatGPTCapabilities(restored), listOf(UIMessage.user("hello")), TextGenerationParams(restored, reasoningLevel = ReasoningLevel.MAX), true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            api.buildRequestBody(chatGPTCapabilities(restored), listOf(UIMessage.user("hello")), TextGenerationParams(restored.copy(tools = setOf(BuiltInTools.Search))), true)
        }
    }

    @Test
    fun `custom body cannot reenable unsupported plan parameters`() {
        val body = chatGPTBody(
            listOf(UIMessage.user("hello")),
            TextGenerationParams(
                model = Model(modelId = "available-model"),
                customBody = listOf("temperature", "max_output_tokens", "previous_response_id", "background", "metadata")
                    .map { CustomBody(it, JsonPrimitive("unsupported")) },
            ),
        )
        assertFalse(body.keys.any { it in setOf("temperature", "max_output_tokens", "previous_response_id", "background", "metadata") })
        assertEquals("true", body.getValue("stream").jsonPrimitive.content)
        assertEquals("false", body.getValue("store").jsonPrimitive.content)
    }

    @Test
    fun `custom overrides cannot erase history or replace routed model and client tools`() {
        for (key in listOf("model", "input", "tools", "stream", "store")) {
            assertThrows(IllegalArgumentException::class.java) {
                chatGPTBody(
                    listOf(UIMessage.user("hello")),
                    TextGenerationParams(Model(modelId = "available-model"), customBody = listOf(CustomBody(key, JsonPrimitive("bad")))),
                )
            }
        }
    }

    @Test
    fun `unsupported hosted image tools fail before request`() {
        assertThrows(IllegalArgumentException::class.java) {
            chatGPTBody(
                listOf(UIMessage.user("draw")),
                TextGenerationParams(Model(modelId = "available-model", tools = setOf(BuiltInTools.ImageGeneration))),
            )
        }
    }

    @Test
    fun `missing models structure is an error instead of fabricated choices`() {
        assertThrows(IllegalStateException::class.java) { parseChatGPTModels("""{"data":[{"id":"not-a-plan-catalog"}]}""") }
    }

    @Test
    fun `admission errors preserve detail status request id and response shape`() {
        val body = json.parseToJsonElement("""{"detail":"Synthetic admission restriction"}""")
        val error = parseChatGPTError(body, 403, "req_test") as ChatGPTApiException
        assertEquals(403, error.status)
        assertEquals("req_test", error.requestId)
        assertEquals(body, error.responseBody)
        assertTrue(error.message.orEmpty().contains("Synthetic admission restriction"))
    }

    @Test
    fun `legacy or missing direct scope cannot send model or inference requests`() = runTest {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor {
            requests++
            error("No HTTP request should be made")
        }.build()
        val denied = listOf(
            OAuthCredential(accessToken = "legacy-test"),
            OAuthCredential(accessToken = "identity-only", protocolVersion = 1, clientId = "registered", issuer = "https://auth.openai.com", subject = "test-subject", idToken = "synthetic-id", grantedScopes = setOf("openid")),
        )
        for (credential in denied) {
            val provider = ChatGPTProvider(client) { _, _ -> credential }
            val setting = ProviderSetting.ChatGPT(credential = credential)
            assertTrue(runCatching { provider.listModels(setting) }.isFailure)
            assertTrue(runCatching {
                provider.streamText(setting, listOf(UIMessage.user("hello")), TextGenerationParams(Model(modelId = "available-model"))).toList()
            }.isFailure)
        }
        assertEquals(0, requests)
    }
}
