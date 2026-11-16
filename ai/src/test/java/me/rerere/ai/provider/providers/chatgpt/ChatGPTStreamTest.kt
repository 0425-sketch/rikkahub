package me.rerere.ai.provider.providers.chatgpt

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.CustomHeader
import okhttp3.Call
import okhttp3.EventListener
import okio.Source
import okio.Timeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ChatGPTModelCapabilities
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.provider.providers.openai.ResponseApiStreamDecoder
import me.rerere.ai.provider.providers.openai.ResponseProviderCapabilities
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.json
import me.rerere.common.http.sse.newEventSource
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Protocol fixtures are synthetic; these tests do not claim real account/plan access. */
class ChatGPTStreamTest {
    private fun decoder() = ResponseApiStreamDecoder(
        requireCompletedResponse = true,
        functionToolNamespace = "rikkahub",
        errorMapper = ::parseChatGPTError,
    )

    private fun event(data: String) = SseEvent(data = data)

    private fun completed(id: String = "resp_test") = event(
        """{"type":"response.completed","response":{"id":"$id","status":"completed","model":"test-available-model"}}""",
    )

    @Test
    fun `partial text followed by usage error never produces successful finish`() {
        for (code in listOf("subscription_sharing_usage_limit_exceeded", "subscription_sharing_usage_unavailable")) {
            val decoder = decoder()
            val partial = decoder.accept(event(
                """{"type":"response.output_text.delta","item_id":"msg_partial","delta":"Partial"}""",
            ))
            assertFalse(partial.completed)
            assertTrue(partial.chunks.any { it is StreamChunk.TextDelta })
            assertFalse(partial.chunks.any { it is StreamChunk.Finish })

            val error = assertThrows(Exception::class.java) {
                decoder.accept(event(
                    """{"type":"response.failed","response":{"status":"failed","error":{"code":"$code","message":"Synthetic plan failure"}}}""",
                ))
            }
            assertTrue(error.message.orEmpty().contains(code))
            assertTrue(decoder.onClosed().none { it is StreamChunk.Finish })
        }
    }

    @Test
    fun `explicit stream error preserves unsupported capability code and parameter`() {
        val decoder = decoder()
        val error = assertThrows(Exception::class.java) {
            decoder.accept(event(
                """{"type":"error","code":"subscription_sharing_unsupported_capability","param":"tools[0]","message":"Unsupported tool"}""",
            ))
        }
        assertTrue(error.message.orEmpty().contains("subscription_sharing_unsupported_capability"))
        assertTrue(error.message.orEmpty().contains("tools[0]"))
        assertTrue(decoder.onClosed().none { it is StreamChunk.Finish })
    }

    @Test
    fun `incomplete response is an error even when text and usage arrived`() {
        val decoder = decoder()
        decoder.accept(event("""{"type":"response.output_text.delta","item_id":"msg_partial","delta":"Partial"}"""))
        assertThrows(Exception::class.java) {
            decoder.accept(event(
                """{"type":"response.incomplete","response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"usage":{"input_tokens":10,"output_tokens":2,"total_tokens":12}}}""",
            ))
        }
        assertTrue(decoder.onClosed().none { it is StreamChunk.Finish })
    }

    @Test
    fun `closing stream or DONE before completed never fabricates success`() {
        val disconnected = decoder()
        disconnected.accept(event("""{"type":"response.output_text.delta","item_id":"msg_partial","delta":"Partial"}"""))
        assertThrows(Exception::class.java) { disconnected.onClosed() }

        val prematureDone = decoder()
        assertThrows(Exception::class.java) { prematureDone.accept(event("[DONE]")) }
    }

    @Test
    fun `SSE event label alone cannot turn a text delta into completed inference`() {
        val decoder = decoder()
        val result = runCatching {
            decoder.accept(SseEvent(
                event = "response.completed",
                data = """{"type":"response.output_text.delta","item_id":"msg_wrong_label","delta":"Partial"}""",
            ))
        }
        result.getOrNull()?.let {
            assertFalse(it.completed)
            assertTrue(it.chunks.none { chunk -> chunk is StreamChunk.Finish })
            assertThrows(Exception::class.java) { decoder.onClosed() }
        }
        // Rejecting the inconsistent envelope immediately is also a valid failure result.
    }

    @Test
    fun `completed response emits finish exactly once and later close is harmless`() {
        val decoder = decoder()
        val result = decoder.accept(completed("resp_success"))
        assertTrue(result.completed)
        val finish = result.chunks.filterIsInstance<StreamChunk.Finish>().single()
        assertEquals("completed", finish.finishReason)
        assertEquals("resp_success", finish.responseId)
        assertTrue(decoder.onClosed().isEmpty())
        assertTrue(decoder.accept(event("[DONE]")).chunks.isEmpty())
    }

    @Test
    fun `SSE parser survives byte fragmentation and preserves unicode until completed`() {
        val wire = """
            event: response.output_text.delta
            data: {"type":"response.output_text.delta","item_id":"msg_split","delta":"你好🌏"}

            event: response.completed
            data: {"type":"response.completed","response":{"id":"resp_split","status":"completed"}}


        """.trimIndent()
        val bytes = wire.toByteArray(Charsets.UTF_8)
        val source = object : ForwardingSource(Buffer().write(bytes)) {
            override fun read(sink: Buffer, byteCount: Long): Long = super.read(sink, minOf(byteCount, 3L))
        }.buffer()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(object : ResponseBody() {
                    override fun contentType() = "text/event-stream".toMediaType()
                    override fun contentLength() = bytes.size.toLong()
                    override fun source() = source
                })
                .build()
        }.build()
        val decoder = decoder()
        val chunks = mutableListOf<StreamChunk>()
        val ended = CountDownLatch(1)
        var failure: Throwable? = null
        val stream = client.newEventSource(
            Request.Builder().url("https://api.openai.com/v1/responses").build(),
            object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    try {
                        chunks += decoder.accept(SseEvent(id = id, event = type, data = data)).chunks
                    } catch (error: Throwable) {
                        failure = error
                        ended.countDown()
                    }
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    failure = t ?: IllegalStateException("Synthetic SSE failure")
                    ended.countDown()
                }

                override fun onClosed(eventSource: EventSource) {
                    try {
                        chunks += decoder.onClosed()
                    } catch (error: Throwable) {
                        failure = error
                    } finally {
                        ended.countDown()
                    }
                }
            },
        )
        try {
            assertTrue("fragmented stream timed out", ended.await(10, TimeUnit.SECONDS))
            assertEquals(null, failure)
            assertEquals("你好🌏", chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text })
            assertEquals("resp_split", chunks.filterIsInstance<StreamChunk.Finish>().single().responseId)
        } finally {
            stream.cancel()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `foreign tool namespace is rejected before local execution`() {
        val decoder = decoder()
        assertThrows(Exception::class.java) {
            decoder.accept(event(
                """{"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","id":"fc_foreign","call_id":"call_foreign","namespace":"unregistered","name":"increment","arguments":"{}"}}""",
            ))
        }
    }

    @Test
    fun `function call executes locally and result plus full history are replayed for continuation`() = runTest {
        val api = ResponseAPI(OkHttpClient())
        // A previously saved unsupported hosted switch must not disrupt client tool round trips.
        val model = Model(modelId = "test-available-model", abilities = listOf(ModelAbility.TOOL), tools = setOf(BuiltInTools.ImageGeneration), chatGPTCapabilities = ChatGPTModelCapabilities(reasoningEfforts = listOf("xhigh")))
        var executionCount = 0
        val increment = Tool(
            name = "increment",
            description = "Add one to x locally",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("x", buildJsonObject { put("type", "integer") })
                    },
                    required = listOf("x"),
                )
            },
            execute = { input ->
                executionCount++
                listOf(UIMessagePart.Text((input.jsonObject.getValue("x").jsonPrimitive.int + 1).toString()))
            },
        )
        val params = TextGenerationParams(model = model, tools = listOf(increment), sessionId = "local-test", reasoningLevel = ReasoningLevel.XHIGH)
        var history = listOf(UIMessage.system("Use the client tool."), UIMessage.user("Increment 41."))
        val firstBody = api.buildRequestBody(CHATGPT_CAPABILITIES, history, params, stream = true)
        assertEquals("xhigh", firstBody.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
        val namespace = firstBody.getValue("tools").jsonArray.single().jsonObject
        assertEquals("namespace", namespace.getValue("type").jsonPrimitive.content)
        assertEquals("rikkahub", namespace.getValue("name").jsonPrimitive.content)
        assertEquals("increment", namespace.getValue("tools").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)

        val firstDecoder = decoder()
        val firstHandler = StreamChunkHandler(model)
        val events = listOf(
            event("""{"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","namespace":"rikkahub","name":"increment","arguments":""}}"""),
            event("""{"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"{\"x\":"}"""),
            event("""{"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"41}"}"""),
            event("""{"type":"response.function_call_arguments.done","item_id":"fc_1","arguments":"{\"x\":41}"}"""),
            event("""{"type":"response.output_item.done","output_index":0,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","namespace":"rikkahub","name":"increment","arguments":"{\"x\":41}"}}"""),
            completed("resp_tool"),
        )
        events.forEach { item ->
            firstDecoder.accept(item).chunks.forEach { history = firstHandler.handle(history, it) }
        }
        assertTrue(firstDecoder.onClosed().isEmpty())
        val call = history.last().parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("increment", call.toolName)
        assertEquals("call_1", call.toolCallId)
        assertEquals("{\"x\":41}", call.input)
        assertFalse(call.isExecuted)
        val tool = params.tools.single { it.name == call.toolName }
        val output = tool.execute(call.inputAsJson())
        assertEquals(1, executionCount)
        history = history.dropLast(1) + history.last().copy(parts = listOf(call.copy(output = output)))

        // A restart/history reload must preserve enough information for the next stateless request.
        history = json.decodeFromString<List<UIMessage>>(json.encodeToString(history))
        val nextBody = api.buildRequestBody(CHATGPT_CAPABILITIES, history, params, stream = true)
        assertEquals(firstBody["reasoning"], nextBody["reasoning"])
        assertEquals(firstBody["prompt_cache_key"], nextBody["prompt_cache_key"])
        assertEquals("false", nextBody.getValue("store").jsonPrimitive.content)
        assertEquals("true", nextBody.getValue("stream").jsonPrimitive.content)
        assertFalse("previous_response_id" in nextBody)
        val replay = nextBody.getValue("input").jsonArray.map { it.jsonObject }
        assertTrue(replay.any { it["role"]?.jsonPrimitive?.content == "developer" })
        assertTrue(replay.any { it["role"]?.jsonPrimitive?.content == "user" })
        val replayedCall = replay.single { it["type"]?.jsonPrimitive?.content == "function_call" }
        assertEquals("rikkahub", replayedCall.getValue("namespace").jsonPrimitive.content)
        assertEquals("increment", replayedCall.getValue("name").jsonPrimitive.content)
        assertEquals(call.inputAsJson(), json.parseToJsonElement(replayedCall.getValue("arguments").jsonPrimitive.content))
        val replayedOutput = replay.single { it["type"]?.jsonPrimitive?.content == "function_call_output" }
        assertEquals("call_1", replayedOutput.getValue("call_id").jsonPrimitive.content)
        assertEquals("42", replayedOutput.getValue("output").jsonPrimitive.content)
        assertTrue(replay.indexOf(replayedCall) < replay.indexOf(replayedOutput))

        val secondDecoder = decoder()
        val secondHandler = StreamChunkHandler(model)
        listOf(
            event("""{"type":"response.output_text.delta","item_id":"msg_final","delta":"The result is "}"""),
            event("""{"type":"response.output_text.delta","item_id":"msg_final","delta":"42."}"""),
            completed("resp_answer"),
        ).forEach { item ->
            secondDecoder.accept(item).chunks.forEach { history = secondHandler.handle(history, it) }
        }
        assertEquals("The result is 42.", history.last().parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text })
        assertNotNull(history.last().finishedAt)
        assertEquals(1, executionCount)
        assertTrue(secondDecoder.onClosed().isEmpty())
    }

    @Test
    fun `done snapshot repairs missing argument suffix and supports done only calls`() {
        for (withStart in listOf(true, false)) {
            val decoder = decoder()
            val chunks = mutableListOf<StreamChunk>()
            if (withStart) {
                chunks += decoder.accept(event("""{"type":"response.output_item.added","item":{"type":"function_call","id":"fc_done","call_id":"call_done","namespace":"rikkahub","name":"increment","arguments":""}}""")).chunks
                chunks += decoder.accept(event("""{"type":"response.function_call_arguments.delta","item_id":"fc_done","delta":"{\"x\":"}""")).chunks
            }
            chunks += decoder.accept(event("""{"type":"response.output_item.done","item":{"type":"function_call","id":"fc_done","call_id":"call_done","namespace":"rikkahub","name":"increment","arguments":"{\"x\":41}"}}""")).chunks
            chunks += decoder.accept(completed()).chunks
            val handler = StreamChunkHandler(Model(modelId = "test-model"))
            val history = chunks.fold(listOf(UIMessage.user("test"))) { messages, chunk -> handler.handle(messages, chunk) }
            val tool = history.last().getTools().single()
            assertEquals("increment", tool.toolName)
            assertEquals("{\"x\":41}", tool.input)
            assertEquals("call_done", tool.toolCallId)
        }
    }

    @Test
    fun `conflicting tool snapshot and missing tool final event cannot execute`() {
        for (finishWithSnapshot in listOf(true, false)) {
            val decoder = decoder()
            decoder.accept(event("""{"type":"response.output_item.added","item":{"type":"function_call","id":"fc_bad","call_id":"call_bad","namespace":"rikkahub","name":"increment","arguments":"{\"x\":1}"}}"""))
            assertThrows(Exception::class.java) {
                if (finishWithSnapshot) {
                    decoder.accept(event("""{"type":"response.output_item.done","item":{"type":"function_call","id":"fc_bad","call_id":"call_bad","namespace":"rikkahub","name":"increment","arguments":"{\"x\":2}"}}"""))
                } else {
                    decoder.accept(completed())
                }
            }
        }
    }

    @Test
    fun `cancelling provider flow cancels HTTP and public route strips old identity headers`() = runBlocking {
        val cancelled = CountDownLatch(1)
        var captured: Request? = null
        val wire = Buffer().writeUtf8("data: {\"type\":\"response.output_text.delta\",\"item_id\":\"msg\",\"delta\":\"partial\"}\n\n")
        val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (wire.size > 0) return wire.read(sink, byteCount)
                cancelled.await(5, TimeUnit.SECONDS)
                return -1
            }
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        }.buffer()
        val client = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) { cancelled.countDown() }
            })
            .addInterceptor { chain ->
                captured = chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body(object : ResponseBody() {
                        override fun contentType() = "text/event-stream".toMediaType()
                        override fun contentLength() = -1L
                        override fun source() = source
                    }).build()
            }.build()
        val credential = OAuthCredential(
            accessToken = "synthetic-access", protocolVersion = 1, clientId = "synthetic-client",
            issuer = "https://auth.openai.com", subject = "synthetic-subject",
            grantedScopes = setOf("chatgpt.tokens.use.direct"),
        )
        val provider = ChatGPTProvider(client) { _, _ -> credential }
        val setting = ProviderSetting.ChatGPT(
            credential = credential,
            customHeaders = listOf(CustomHeader("ChatGPT-Account-ID", "old"), CustomHeader("originator", "old")),
        )
        try {
            val first = withTimeout(5_000) {
                provider.streamText(setting, listOf(UIMessage.user("hello")), TextGenerationParams(Model(modelId = "available-model"), reasoningLevel = ReasoningLevel.AUTO)).first()
            }
            assertTrue(first is StreamChunk.TextStart)
            assertTrue("HTTP call was not cancelled", cancelled.await(5, TimeUnit.SECONDS))
            assertEquals("https://api.openai.com/v1/responses", captured?.url.toString())
            assertEquals("Bearer synthetic-access", captured?.header("Authorization"))
            assertEquals(null, captured?.header("ChatGPT-Account-ID"))
            assertEquals(null, captured?.header("originator"))
            assertEquals(null, captured?.header("session-id"))
        } finally {
            cancelled.countDown()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `ordinary API key Responses keeps flat tools and prior end semantics`() {
        val api = ResponseAPI(OkHttpClient())
        val params = TextGenerationParams(
            model = Model(modelId = "test-model", abilities = listOf(ModelAbility.TOOL)),
            tools = listOf(Tool("local_tool", "Local", execute = { emptyList() })),
        )
        val body = api.buildRequestBody(ResponseProviderCapabilities(), listOf(UIMessage.user("hello")), params, stream = true)
        val tool = body.getValue("tools").jsonArray.single().jsonObject
        assertEquals("function", tool.getValue("type").jsonPrimitive.content)
        assertEquals("local_tool", tool.getValue("name").jsonPrimitive.content)
        assertFalse("namespace" in tool)

        val normal = ResponseApiStreamDecoder()
        assertTrue(normal.accept(event("[DONE]")).chunks.any { it is StreamChunk.Finish })
        val incomplete = ResponseApiStreamDecoder().accept(event(
            """{"type":"response.incomplete","response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"}}}""",
        ))
        assertEquals("incomplete:max_output_tokens", incomplete.chunks.filterIsInstance<StreamChunk.Finish>().single().finishReason)
    }
}
