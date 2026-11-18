package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.agent.AgentEvent
import me.rerere.ai.agent.AgentFinishReason
import me.rerere.ai.agent.AgentHooks
import me.rerere.ai.agent.AgentRequest
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.agent.runAgent
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.chatgpt.ChatGPTProvider
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic protocol and Bing fixtures: no real credentials or claim of phone-network coverage. */
class SearchToolRoundTripTest {
    @Test
    fun `actual client search preserves arguments results and history through ChatGPT continuation`() = runBlocking {
        val query = "海滨公园新馆开放 + \"新闻\" & 本地"
        val arguments = buildJsonObject { put("query", query) }
        val requests = mutableListOf<JsonObject>()
        var searchCount = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val (body, mediaType) = when (request.url.host) {
                "www.bing.com" -> {
                    searchCount++
                    assertEquals(query, request.url.queryParameter("q"))
                    assertEquals(setOf("q"), request.url.queryParameterNames)
                    """<ol id="b_results"><li class="b_algo"><h2><a href="https://example.org/news?q=1&amp;lang=zh">海滨公园 &amp; 新闻</a></h2><div class="b_caption"><p>原始摘要：新馆开放。</p></div></li></ol>""" to "text/html; charset=utf-8"
                }
                "api.openai.com" -> {
                    assertEquals("/v1/responses", request.url.encodedPath)
                    val buffer = Buffer()
                    checkNotNull(request.body).writeTo(buffer)
                    requests += Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                    val events = if (requests.size == 1) {
                        listOf(
                            buildJsonObject {
                                put("type", "response.output_item.done")
                                put("item", buildJsonObject {
                                    put("type", "function_call")
                                    put("id", "fc_search")
                                    put("call_id", "call_search")
                                    put("namespace", "rikkahub")
                                    put("name", "search_web")
                                    put("arguments", arguments.toString())
                                })
                            }.toString(),
                            completed("resp_tool"),
                        )
                    } else {
                        check(requests.size == 2) { "Unexpected extra model request" }
                        listOf(
                            """{"type":"response.output_text.delta","item_id":"msg_final","delta":"已读取搜索结果。"}""",
                            completed("resp_final"),
                        )
                    }
                    events.joinToString("") { "data: $it\n\n" } to "text/event-stream"
                }
                else -> error("Unexpected external service ${request.url.host}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("Synthetic fixture")
                .body(body.toResponseBody(mediaType.toMediaType())).build()
        }.build()
        SearchService.init(client)
        try {
            val credential = OAuthCredential(
                accessToken = "synthetic-access", protocolVersion = 1, clientId = "synthetic-client",
                issuer = "https://auth.openai.com", subject = "synthetic-subject",
                grantedScopes = setOf("chatgpt.tokens.use.direct"),
            )
            val setting = ProviderSetting.ChatGPT(credential = credential)
            val model = Model(modelId = "test-available-model", abilities = listOf(ModelAbility.TOOL))
            val tools = createSearchTools(Settings(
                searchServices = listOf(SearchServiceOptions.BingLocalOptions()), searchServiceSelected = 0,
            )).toList()
            val searchTool = tools.single { it.name == "search_web" }
            val schema = searchTool.parameters() as InputSchema.Obj
            assertEquals(listOf("query"), schema.required)
            assertEquals(setOf("query"), schema.properties.keys)
            @Suppress("UNCHECKED_CAST")
            val provider = ChatGPTProvider(client) { _, _ -> credential } as Provider<ProviderSetting>
            val history = listOf(
                UIMessage.system("请保留历史并使用客户端搜索结果。"),
                UIMessage.user("上一轮问题"),
                UIMessage.assistant("上一轮回答"),
                UIMessage.user("查询公园公告以验证搜索结果。"),
            )
            val events = withTimeout(10_000) {
                runAgent(
                    AgentRequest(provider, setting, history, tools, maxSteps = 3),
                    object : AgentHooks {
                        override suspend fun prepareStep(messages: List<UIMessage>): StepRequest {
                            // Persist/reload the real UI model at every step, as conversation history does.
                            val restored = Json.decodeFromString<List<UIMessage>>(Json.encodeToString(messages))
                            return StepRequest(restored, TextGenerationParams(model, reasoningLevel = ReasoningLevel.AUTO))
                        }
                    },
                ).toList()
            }
            val finished = events.last() as AgentEvent.Finished
            assertEquals(AgentFinishReason.Completed, finished.reason)
            assertEquals(1, searchCount)
            assertEquals(2, requests.size)
            val call = finished.message.getTools().single()
            assertEquals("search_web", call.toolName)
            assertEquals("call_search", call.toolCallId)
            assertEquals(arguments, call.inputAsJson())
            assertTrue(call.isExecuted)
            assertEquals("已读取搜索结果。", finished.message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text })

            val declaration = requests.first().getValue("tools").jsonArray.single().jsonObject
            assertEquals("namespace", declaration.getValue("type").jsonPrimitive.content)
            assertEquals("rikkahub", declaration.getValue("name").jsonPrimitive.content)
            val function = declaration.getValue("tools").jsonArray.single().jsonObject
            assertEquals("search_web", function.getValue("name").jsonPrimitive.content)
            assertEquals("string", function.getValue("parameters").jsonObject.getValue("properties").jsonObject.getValue("query").jsonObject.getValue("type").jsonPrimitive.content)

            val continuation = requests.last()
            assertFalse("previous_response_id" in continuation)
            val input = continuation.getValue("input").jsonArray.map { it.jsonObject }
            assertTrue(input.any { it["role"]?.jsonPrimitive?.content == "developer" })
            assertEquals(2, input.count { it["role"]?.jsonPrimitive?.content == "user" })
            assertTrue(input.any { it["role"]?.jsonPrimitive?.content == "assistant" && "上一轮回答" in it.toString() })
            val replayedCall = input.single { it["type"]?.jsonPrimitive?.content == "function_call" }
            assertEquals("rikkahub", replayedCall.getValue("namespace").jsonPrimitive.content)
            assertEquals("search_web", replayedCall.getValue("name").jsonPrimitive.content)
            assertEquals("call_search", replayedCall.getValue("call_id").jsonPrimitive.content)
            assertEquals(arguments, Json.parseToJsonElement(replayedCall.getValue("arguments").jsonPrimitive.content))
            val replayedOutput = input.single { it["type"]?.jsonPrimitive?.content == "function_call_output" }
            assertEquals("call_search", replayedOutput.getValue("call_id").jsonPrimitive.content)
            assertTrue(input.indexOf(replayedCall) < input.indexOf(replayedOutput))
            assertEquals(
                call.output.filterIsInstance<UIMessagePart.Text>().single().text,
                replayedOutput.getValue("output").jsonPrimitive.content,
            )
            val payload = Json.parseToJsonElement(replayedOutput.getValue("output").jsonPrimitive.content).jsonObject
            assertNotNull(payload["retrievedAt"])
            val item = payload.getValue("items").jsonArray.single().jsonObject
            assertEquals("海滨公园 & 新闻", item.getValue("title").jsonPrimitive.content)
            assertEquals("https://example.org/news?q=1&lang=zh", item.getValue("url").jsonPrimitive.content)
            assertEquals("原始摘要：新馆开放。", item.getValue("text").jsonPrimitive.content)
            assertEquals(1, item.getValue("index").jsonPrimitive.int)
            assertEquals(6, item.getValue("id").jsonPrimitive.content.length)
        } finally {
            SearchService.init(OkHttpClient())
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    private fun completed(id: String) = """{"type":"response.completed","response":{"id":"$id","status":"completed","model":"test-available-model"}}"""
}
