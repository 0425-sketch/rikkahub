package me.rerere.ai.provider.providers.chatgpt

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ChatGPTCacheDiagnosticModesTest {
    @After fun cleanup() { ChatGPTCacheDiagnostics.setMode(CacheDiagnosticMode.OFF) }

    private fun row(recorder: CacheDiagnosticRecorder) = recorder.export("account").getValue("requests").jsonArray.single().jsonObject
    private fun body() = json.parseToJsonElement("""{"model":"synthetic","input":[{"role":"user","content":"secret-content"}]}""").jsonObject

    @Test fun `new recorder is off and does not inspect even hostile body`() {
        val recorder = CacheDiagnosticRecorder()
        val body = JsonObject(object : Map<String, kotlinx.serialization.json.JsonElement> by emptyMap() {
            override fun get(key: String): kotlinx.serialization.json.JsonElement? = error("OFF must not inspect $key")
        })
        assertEquals(CacheDiagnosticRecorder.NO_RECORD, recorder.begin("account", body))
        assertNull(recorder.collectionToken())
        assertEquals("off", recorder.export("account").getValue("collection_mode").jsonPrimitive.content)
        assertTrue(recorder.export("account").getValue("requests").jsonArray.isEmpty())
        assertTrue(recorder.export("account").toString().contains("diagnostics_disabled_buffer_cleared"))
    }

    @Test fun `light never visits input or tools and keeps unknown separate from zero`() {
        var time = 1_000L
        val recorder = CacheDiagnosticRecorder(initialMode = CacheDiagnosticMode.LIGHT, now = { time })
        val values = body()
        val body = JsonObject(object : Map<String, kotlinx.serialization.json.JsonElement> by values {
            override fun get(key: String): kotlinx.serialization.json.JsonElement? {
                check(key !in setOf("input", "instructions", "tools", "prompt_cache_key")) { "LIGHT inspected $key" }
                return values[key]
            }
        })
        val id = recorder.begin("account", body, sessionId = "private-session")
        recorder.usage(id, TokenUsage(promptTokens = 0, promptTokensReported = true, cachedTokensReported = false))
        recorder.httpStatus(id, 200)
        recorder.serverDiagnostics(id, JsonObject(mapOf("usage" to JsonObject(emptyMap()), "prompt_cache_key" to JsonPrimitive("private-key"))))
        time = 1_125L
        recorder.finish(id, "completed")
        val row = row(recorder)
        assertEquals(JsonNull, row["input_hash"])
        assertEquals(JsonNull, row["input_item_count"])
        assertEquals(JsonNull, row["session_id_hash"])
        assertEquals(JsonNull, row["response_prompt_cache_key_hash"])
        assertEquals("not_collected", row.getValue("response_prompt_cache_key_state").jsonPrimitive.content)
        assertEquals("0", row.getValue("input_tokens").jsonPrimitive.content)
        assertEquals(JsonNull, row["cached_tokens"])
        assertEquals("125", row.getValue("duration_ms").jsonPrimitive.content)
        assertEquals("200", row.getValue("http_status").jsonPrimitive.content)
        assertTrue(row.toString().contains("content_fingerprints_not_collected"))
        assertFalse(row.toString().contains("private-"))
    }

    @Test fun `off clears buffers and invalidates in flight tokens even after reenabling`() {
        val recorder = CacheDiagnosticRecorder(initialMode = CacheDiagnosticMode.FULL)
        val token = recorder.collectionToken()!!
        val id = recorder.begin("account", body(), token = token)
        assertEquals(1, recorder.export("account").getValue("requests").jsonArray.size)
        recorder.setMode(CacheDiagnosticMode.OFF)
        recorder.setMode(CacheDiagnosticMode.FULL)
        assertEquals(CacheDiagnosticRecorder.NO_RECORD, recorder.begin("account", body(), token = token))
        recorder.usage(id, TokenUsage(promptTokens = 99))
        recorder.finish(id, "completed")
        recorder.serverDiagnostics(id, body())
        assertTrue(recorder.export("account").getValue("requests").jsonArray.isEmpty())
        recorder.begin("account", body(), token = recorder.collectionToken())
        assertEquals(1, recorder.export("account").getValue("requests").jsonArray.size)
    }

    @Test fun `fingerprint work holds no recorder lock and cannot commit across a mode change`() {
        val recorder = CacheDiagnosticRecorder(initialMode = CacheDiagnosticMode.FULL)
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val values = body()
        val pausedBody = JsonObject(object : Map<String, kotlinx.serialization.json.JsonElement> by values {
            override fun get(key: String): kotlinx.serialization.json.JsonElement? {
                if (key == "instructions") {
                    entered.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                }
                return values[key]
            }
        })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val collecting = executor.submit<Long> { recorder.begin("account", pausedBody) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // This would deadlock against the paused collector if hashing held the monitor.
            recorder.setMode(CacheDiagnosticMode.OFF)
            recorder.setMode(CacheDiagnosticMode.FULL)
            resume.countDown()
            assertEquals(CacheDiagnosticRecorder.NO_RECORD, collecting.get(5, TimeUnit.SECONDS))
            assertTrue(recorder.export("account").getValue("requests").jsonArray.isEmpty())
        } finally {
            resume.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun `full bounds huge string item count and depth without changing the body`() {
        val enormous = JsonObject(body() + ("input" to JsonArray(listOf(JsonObject(mapOf("image_url" to JsonPrimitive("data:image/png;base64," + "A".repeat(2_000_000))))))))
        val recorder = CacheDiagnosticRecorder(initialMode = CacheDiagnosticMode.FULL)
        val original = enormous.toString()
        recorder.begin("account", enormous)
        val limited = row(recorder)
        assertEquals(JsonNull, limited["input_hash"])
        assertEquals("true", limited.getValue("input_items_truncated").jsonPrimitive.content)
        assertTrue(limited.toString().contains("diagnostic_work_budget_exceeded"))
        assertEquals(original, enormous.toString())
        val itemRecorder = CacheDiagnosticRecorder(initialMode = CacheDiagnosticMode.FULL, maxInputItems = 2)
        itemRecorder.begin("account", JsonObject(body() + ("input" to JsonArray(List(3) { JsonPrimitive("item") }))))
        assertTrue(row(itemRecorder).toString().contains("input_item_limit_exceeded"))
        var deep: kotlinx.serialization.json.JsonElement = JsonPrimitive("secret")
        repeat(40) { deep = JsonArray(listOf(deep)) }
        val depthRecorder = CacheDiagnosticRecorder(initialMode = CacheDiagnosticMode.FULL)
        depthRecorder.begin("account", JsonObject(body() + ("input" to JsonArray(listOf(deep)))))
        assertTrue(row(depthRecorder).toString().contains("diagnostic_work_budget_exceeded"))
        val longKeys = JsonObject((0..50).associate { ("k".repeat(8192) + it) to JsonPrimitive(1) })
        val keysRecorder = CacheDiagnosticRecorder(initialMode = CacheDiagnosticMode.FULL)
        keysRecorder.begin("account", JsonObject(body() + ("input" to JsonArray(listOf(longKeys)))))
        assertTrue(row(keysRecorder).toString().contains("diagnostic_work_budget_exceeded"))
    }

    @Test fun `off light full send identical bodies and retain ordinary usage without reading body twice`() = runBlocking {
        val server = MockWebServer()
        val event = """{"type":"response.completed","response":{"id":"r","status":"completed","usage":{"input_tokens":32,"input_tokens_details":{"cached_tokens":0,"cache_write_tokens":4},"output_tokens":1}}}"""
        repeat(3) { server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $event\n\n")) }
        server.start()
        val writes = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val original = request.body!!
            // This transparent wrapper also exercises the changed-body diagnostic fallback.
            val counted = object : RequestBody() {
                override fun contentType() = original.contentType()
                override fun contentLength() = original.contentLength()
                override fun writeTo(sink: BufferedSink) { writes.incrementAndGet(); original.writeTo(sink) }
            }
            chain.proceed(request.newBuilder().url(server.url("/v1/responses")).post(counted).build())
        }.build()
        val credential = OAuthCredential(accessToken = "secret-token", protocolVersion = 1, clientId = "client", issuer = "https://auth.openai.com", subject = "subject", grantedScopes = setOf("chatgpt.tokens.use.direct"))
        val setting = ProviderSetting.ChatGPT(credential = credential)
        val provider = ChatGPTProvider(client) { _, _ -> credential }
        val sent = mutableListOf<String>()
        try {
            for (mode in CacheDiagnosticMode.entries) {
                ChatGPTCacheDiagnostics.setMode(mode)
                val chunks = withTimeout(5_000) { provider.streamText(setting, listOf(UIMessage.user("same-question")), TextGenerationParams(Model(modelId = "synthetic"), sessionId = "same-session", reasoningLevel = ReasoningLevel.AUTO)).toList() }
                val usage = chunks.filterIsInstance<StreamChunk.Usage>().last().usage
                assertEquals(32, usage.promptTokens)
                assertEquals(0, usage.cachedTokens)
                assertTrue(usage.cachedTokensReported)
                assertEquals(4, usage.cacheWriteTokens)
                val wire = server.takeRequest(5, TimeUnit.SECONDS)!!
                sent += wire.body.readUtf8()
                val rows = ChatGPTCacheDiagnostics.export(setting).getValue("requests").jsonArray
                if (mode == CacheDiagnosticMode.OFF) assertTrue(rows.isEmpty()) else {
                    assertEquals(1, rows.size)
                    assertTrue(rows.single().toString().contains("wire_body_replaced_by_interceptor"))
                    assertEquals("32", rows.single().jsonObject.getValue("input_tokens").jsonPrimitive.content)
                }
            }
            assertEquals(3, writes.get()) // One transport write each, never another diagnostic write.
            assertEquals(1, sent.toSet().size)
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            server.shutdown()
        }
    }
}
