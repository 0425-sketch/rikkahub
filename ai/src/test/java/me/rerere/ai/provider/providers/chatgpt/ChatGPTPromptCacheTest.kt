package me.rerere.ai.provider.providers.chatgpt

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ChatGPTModelCapabilities
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.provider.providers.openai.ResponseProviderCapabilities
import me.rerere.ai.ui.OpenAIReasoningMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Client request stability only: these fixtures do not claim server cache hits. */
class ChatGPTPromptCacheTest {
    private val api = ResponseAPI(OkHttpClient())
    private val model = Model(
        modelId = "synthetic-cache-model", abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
        chatGPTCapabilities = ChatGPTModelCapabilities(reasoningEfforts = listOf("high")),
    )
    private val longPrefix = (1..4096).joinToString(" ") { "stable-reference-$it" }
    private val initial = listOf(UIMessage.system(longPrefix), UIMessage.user("First question"))

    private fun params(tools: List<Tool> = emptyList(), custom: List<CustomBody> = emptyList()) =
        TextGenerationParams(model, reasoningLevel = ReasoningLevel.HIGH, tools = tools,
            sessionId = "persisted-conversation-id", customBody = custom)

    private fun request(messages: List<UIMessage> = initial, params: TextGenerationParams = params()) =
        api.buildRequestBody(chatGPTCapabilities(model), messages, params, stream = true)

    private fun advanced(key: String, value: String) = CustomBody(key, json.parseToJsonElement(value))

    @Test
    fun `ordinary text append and persisted reload preserve complete input prefix and cache key`() {
        val first = request()
        val history = initial + UIMessage.assistant("First answer") + UIMessage.user("Follow-up only")
        val reloaded = json.decodeFromString<List<UIMessage>>(json.encodeToString(history))
        val second = request(reloaded)
        val third = request(reloaded + UIMessage.assistant("Second answer") + UIMessage.user("Another follow-up"))
        assertPrefix(first, second)
        assertPrefix(second, third)
        for (body in listOf(first, second, third)) {
            assertEquals(JsonPrimitive("persisted-conversation-id"), body["prompt_cache_key"])
            assertEquals(JsonPrimitive(false), body["store"])
            assertEquals(JsonPrimitive(true), body["stream"])
            assertFalse("prompt_cache_options" in body)
            assertFalse("prompt_cache_retention" in body)
            assertFalse("prompt_cache_diagnostics" in body)
        }
    }

    @Test
    fun `tool execution history and encrypted reasoning survive append without rewriting prefix`() {
        val tools = listOf(tool("search_web"))
        val first = request(params = params(tools))
        val response = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Reasoning(
                reasoning = "visible summary",
                metadata = OpenAIReasoningMetadata(reasoningId = "rs-stable", encryptedContent = "synthetic-encrypted-history").toMetadata(),
            ),
            UIMessagePart.Tool(
                toolCallId = "call-stable", toolName = "search_web", input = "{\"query\":\"test\"}",
                output = listOf(UIMessagePart.Text("Original tool output")),
            ),
        ))
        val history = json.decodeFromString<List<UIMessage>>(json.encodeToString(initial + response))
        val second = request(history, params(tools))
        val third = request(history + UIMessage.assistant("Search answer") + UIMessage.user("More detail"), params(tools))
        assertPrefix(first, second)
        assertPrefix(second, third)
        assertEquals(first["tools"].toString(), third["tools"].toString())
        assertEquals(first["reasoning"], third["reasoning"])
        val items = second.getValue("input").jsonArray.map { it.jsonObject }
        assertEquals("synthetic-encrypted-history", items.single { it["type"] == JsonPrimitive("reasoning") }.getValue("encrypted_content").jsonPrimitive.content)
        assertEquals("call-stable", items.single { it["type"] == JsonPrimitive("function_call") }.getValue("call_id").jsonPrimitive.content)
        assertEquals("Original tool output", items.single { it["type"] == JsonPrimitive("function_call_output") }.getValue("output").jsonPrimitive.content)
    }

    @Test
    fun `equivalent refreshed tool catalog and object order have identical serialized ChatGPT tools`() {
        val firstTools = listOf(tool("zeta", reverseProperties = false), tool("alpha"))
        val refreshedTools = listOf(tool("alpha"), tool("zeta", reverseProperties = true))
        val first = request(params = params(firstTools))
        val second = request(params = params(refreshedTools))
        assertEquals(first.getValue("tools").toString(), second.getValue("tools").toString())
        val functions = first.getValue("tools").jsonArray.single().jsonObject.getValue("tools").jsonArray
        assertEquals(listOf("alpha", "zeta"), functions.map { it.jsonObject.getValue("name").jsonPrimitive.content })
        assertTrue(functions.all { it.jsonObject.getValue("strict") == JsonPrimitive(false) })
        // Ordinary API-key routes retain their configured order and original default hooks.
        val keyFirst = api.buildRequestBody(ResponseProviderCapabilities(), initial, params(firstTools), true)
        val keySecond = api.buildRequestBody(ResponseProviderCapabilities(), initial, params(refreshedTools), true)
        assertNotEquals(keyFirst.getValue("tools").toString(), keySecond.getValue("tools").toString())
        assertEquals("zeta", keyFirst.getValue("tools").jsonArray.first().jsonObject.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun `explicit caching without valid final content breakpoint is a targeted error`() {
        val custom = listOf(advanced("prompt_cache_options", """{"mode":"explicit"}"""))
        val error = assertThrows(IllegalArgumentException::class.java) { request(params = params(custom = custom)) }
        assertTrue(error.message.orEmpty().contains("没有有效内容断点"))
        for (input in listOf(
            """[{"role":"developer","content":"no block marker"}]""",
            """[{"type":"additional_tools","prompt_cache_breakpoint":{"mode":"explicit"},"content":[]}]""",
            """[{"role":"user","content":[{"type":"input_text","text":"stable","prompt_cache_breakpoint":{"mode":"implicit"}}]}]""",
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                prepareChatGPTRequest(json.parseToJsonElement("""{"input":$input,"instructions":"not a breakpoint","prompt_cache_options":{"mode":"explicit"}}""").jsonObject)
            }
        }
        for (item in listOf(
            """{"role":"developer","content":[{"type":"input_text","text":"stable","prompt_cache_breakpoint":{"mode":"explicit"}}]}""",
            """{"type":"function_call_output","call_id":"call","output":[{"type":"input_text","text":"result","prompt_cache_breakpoint":{"mode":"explicit"}}]}""",
        )) {
            val body = json.parseToJsonElement("""{"input":[$item],"prompt_cache_options":{"mode":"explicit"}}""").jsonObject
            assertEquals(body["prompt_cache_options"], prepareChatGPTRequest(body)["prompt_cache_options"])
        }
    }

    @Test
    fun `custom cache fields preserve intentional override without adding unverified options`() {
        val selected = request(params = params(custom = listOf(
            advanced("prompt_cache_key", "\"shared-accounting-key\""),
            advanced("prompt_cache_options", """{"mode":"explicit"}"""),
            advanced("prompt_cache_options", """{"mode":"implicit"}"""),
        )))
        assertEquals(JsonPrimitive("shared-accounting-key"), selected["prompt_cache_key"])
        assertEquals("implicit", selected.getValue("prompt_cache_options").jsonObject.getValue("mode").jsonPrimitive.content)
        assertThrows(IllegalArgumentException::class.java) {
            request(params = params(custom = listOf(advanced("prompt_cache_options", """{"mode":"automatic"}"""))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            request(params = params(custom = listOf(advanced("prompt_cache_diagnostics", "true"))))
        }
    }

    private fun assertPrefix(first: JsonObject, next: JsonObject) {
        val before = first.getValue("input").jsonArray
        val after = next.getValue("input").jsonArray
        assertEquals(before.toString(), JsonArray(after.take(before.size)).toString())
        assertEquals(first["prompt_cache_key"], next["prompt_cache_key"])
    }

    private fun tool(name: String, reverseProperties: Boolean = false) = Tool(
        name = name, description = "Stable tool $name",
        parameters = { InputSchema.Obj(
            properties = buildJsonObject {
                val fields = listOf("query", "language").let { if (reverseProperties) it.reversed() else it }
                fields.forEach { put(it, buildJsonObject { put("type", "string"); put("description", "Stable $it") }) }
            },
            required = listOf("query"),
        ) },
        execute = { listOf(UIMessagePart.Text("result")) },
    )
}
