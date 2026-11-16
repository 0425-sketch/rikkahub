package me.rerere.ai.provider.providers.chatgpt

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Synthetic local protocol fixtures, never real subscription cache measurements. */
class ChatGPTCacheDiagnosticsTest {
    private fun body(input: String = """{"role":"developer","content":"secret-system"},{"role":"user","content":"secret-question"}""", tool: String = "secret-tool") =
        json.parseToJsonElement("""{"model":"synthetic-model","stream":true,"store":false,"reasoning":{"effort":"high"},"prompt_cache_key":"secret-session","tools":[{"name":"$tool"}],"input":[$input]}""").jsonObject

    private fun CacheDiagnosticRecorder.requests(account: String) = export(account).getValue("requests").jsonArray.map { it.jsonObject }

    @Test
    fun `export only retains bounded redacted hashes for current account`() {
        val recorder = CacheDiagnosticRecorder(capacity = 2, salt = ByteArray(32) { 5 })
        recorder.begin("account-A", body())
        recorder.begin("account-B", body())
        recorder.begin("account-A", body())
        val exported = recorder.export("account-A").toString()
        for (secret in listOf("secret-system", "secret-question", "secret-session", "secret-tool", "account-A", "account-B")) {
            assertFalse("Raw secret leaked: $secret", exported.contains(secret))
        }
        assertEquals(1, recorder.requests("account-A").size)
        assertEquals(1, recorder.requests("account-B").size)
        assertTrue(recorder.requests("other-account").isEmpty())
        assertTrue(recorder.export(null).getValue("requests").jsonArray.isEmpty())
        assertEquals(recorder.fingerprint("same"), recorder.fingerprint("same"))
        assertNotEquals(recorder.fingerprint("same"), CacheDiagnosticRecorder(salt = ByteArray(32) { 6 }).fingerprint("same"))
    }

    @Test
    fun `missing terminal stats are unknown and explicit zero replaces previous snapshot`() {
        val recorder = CacheDiagnosticRecorder()
        val unknown = recorder.begin("account", body())
        recorder.usage(unknown, TokenUsage(promptTokens = 20, promptTokensReported = true))
        recorder.serverDiagnostics(unknown, json.parseToJsonElement("""{"usage":{"input_tokens":20}}""").jsonObject)
        recorder.finish(unknown, "completed")
        val row = recorder.requests("account").single()
        assertEquals(JsonNull, row["cached_tokens"])
        assertEquals(JsonNull, row["cache_write_tokens"])
        assertEquals("false", row.getValue("cached_tokens_reported").jsonPrimitive.content)
        assertEquals("true", row.getValue("usage_object_reported").jsonPrimitive.content)

        val absent = recorder.begin("account", body())
        recorder.usage(absent, TokenUsage()) // Strict SSE decoder emits an unknown placeholder.
        recorder.serverDiagnostics(absent, JsonObject(emptyMap()))
        val missingObject = recorder.requests("account").last()
        assertEquals("false", missingObject.getValue("usage_object_reported").jsonPrimitive.content)
        assertEquals("true", missingObject.getValue("decoded_usage_received").jsonPrimitive.content)
        assertEquals(JsonNull, missingObject["cached_tokens"])

        val explicit = recorder.begin("account", body())
        recorder.usage(explicit, TokenUsage(promptTokens = 2048, cachedTokens = 1024, cacheWriteTokens = 1024))
        recorder.usage(explicit, TokenUsage(promptTokens = 0, cachedTokens = 0, promptTokensReported = true, cachedTokensReported = true, cacheWriteTokens = 0))
        recorder.finish(explicit, "completed")
        recorder.finish(explicit, "not_completed")
        val zero = recorder.requests("account").last()
        assertEquals("0", zero.getValue("cached_tokens").jsonPrimitive.content)
        assertEquals("0", zero.getValue("input_tokens").jsonPrimitive.content)
        assertEquals("0", zero.getValue("cache_write_tokens").jsonPrimitive.content)
        assertEquals("true", zero.getValue("cached_tokens_reported").jsonPrimitive.content)
        assertEquals("completed", zero.getValue("outcome").jsonPrimitive.content)
    }

    @Test
    fun `comparison distinguishes append-only tool history from modified prefix and tools`() {
        val recorder = CacheDiagnosticRecorder()
        val original = body()
        recorder.begin("account", original, "stable-session")
        recorder.begin("account", body("""{"role":"developer","content":"secret-system"},{"role":"user","content":"secret-question"},{"type":"function_call","call_id":"secret-call","arguments":"secret-arguments"},{"type":"function_call_output","call_id":"secret-call","output":"secret-result"}"""), "stable-session")
        val append = recorder.requests("account").last()
        assertEquals("2", append.getValue("matching_input_items").jsonPrimitive.content)
        assertEquals(JsonNull, append["first_changed_input_index"])
        assertTrue(append.getValue("changed_request_fields").jsonArray.isEmpty())
        recorder.begin("account", body("""{"role":"developer","content":"changed-system"},{"role":"user","content":"secret-question"}""", tool = "changed-tool"), "stable-session")
        val changed = recorder.requests("account").last()
        assertEquals("0", changed.getValue("first_changed_input_index").jsonPrimitive.content)
        assertEquals(listOf("tools"), changed.getValue("changed_request_fields").jsonArray.map { it.jsonPrimitive.content })
        assertFalse(recorder.export("account").toString().contains("secret-call"))
        assertFalse(recorder.export("account").toString().contains("secret-arguments"))
        assertFalse(recorder.export("account").toString().contains("secret-result"))
    }

    @Test
    fun `canonical object key order does not create a false prefix difference`() {
        val recorder = CacheDiagnosticRecorder()
        recorder.begin("account", body(), "stable-session")
        recorder.begin("account", body("""{"content":"secret-system","role":"developer"},{"content":"secret-question","role":"user"}"""), "stable-session")
        assertEquals("2", recorder.requests("account").last().getValue("matching_input_items").jsonPrimitive.content)
        assertEquals(JsonNull, recorder.requests("account").last()["first_changed_input_index"])
    }

    @Test
    fun `account partition survives token refresh but isolates different subject and provider`() {
        val credential = credential()
        val setting = ProviderSetting.ChatGPT(credential = credential)
        val account = ChatGPTCacheDiagnostics.accountKey(setting, credential)
        assertEquals(account, ChatGPTCacheDiagnostics.accountKey(setting, credential.copy(
            accessToken = "new-access", refreshToken = "new-refresh", accountId = "newly-populated-profile",
            email = "updated@example.invalid", plan = "updated-plan",
        )))
        assertNotEquals(account, ChatGPTCacheDiagnostics.accountKey(setting, credential.copy(subject = "other-subject")))
        assertNotEquals(account, ChatGPTCacheDiagnostics.accountKey(ProviderSetting.ChatGPT(credential = credential), credential))
    }

    @Test
    fun `same client session detects changed wire cache key and retains max effort`() {
        val recorder = CacheDiagnosticRecorder()
        val first = recorder.begin("account", body(), "secret-client-session")
        val modified = JsonObject(body() + mapOf(
            "prompt_cache_key" to JsonPrimitive("secret-new-wire-key"),
            "reasoning" to JsonObject(mapOf("effort" to JsonPrimitive("max"))),
        ))
        recorder.begin("account", modified, "secret-client-session")
        val records = recorder.requests("account")
        val second = records.last()
        assertEquals(first.toString(), second.getValue("compared_to_sequence").jsonPrimitive.content)
        assertEquals(records.first()["session_id_hash"], second["session_id_hash"])
        assertNotEquals(records.first()["prompt_cache_key_hash"], second["prompt_cache_key_hash"])
        assertEquals(setOf("reasoning", "prompt_cache_key"), second.getValue("changed_request_fields").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals("max", second.getValue("reasoning_effort").jsonPrimitive.content)
        assertFalse(recorder.export("account").toString().contains("secret-client-session"))
        assertFalse(recorder.export("account").toString().contains("secret-new-wire-key"))
    }

    @Test
    fun `auxiliary requests without sessions do not group by identical wire cache keys`() {
        val recorder = CacheDiagnosticRecorder()
        recorder.begin("account", body(), "main-session")
        recorder.begin("account", body())
        recorder.begin("account", body())
        recorder.begin("account", body(), "other-session")
        for (request in recorder.requests("account").drop(1)) {
            assertEquals(JsonNull, request["compared_to_sequence"])
            assertEquals(JsonNull, request["matching_input_items"])
        }
        assertEquals(JsonNull, recorder.requests("account")[1]["session_id_hash"])
    }

    @Test
    fun `duplicate terminal response cannot overwrite first usage presence or diagnostic type`() {
        val recorder = CacheDiagnosticRecorder()
        val request = recorder.begin("account", body())
        recorder.serverDiagnostics(request, json.parseToJsonElement("""{"usage":{"input_tokens":4096},"prompt_cache_diagnostics":{"type":"cache_hit"}}""").jsonObject)
        recorder.serverDiagnostics(request, JsonObject(emptyMap()))
        val result = recorder.requests("account").single()
        assertEquals("true", result.getValue("usage_object_reported").jsonPrimitive.content)
        assertEquals("cache_hit", result.getValue("server_diagnostic_type").jsonPrimitive.content)
    }

    @Test
    fun `header body and returned key fingerprints share a comparison domain without raw values`() {
        val recorder = CacheDiagnosticRecorder()
        val request = recorder.begin("account", body(), "client-session", finalSessionIdHeader = "secret-session")
        recorder.serverDiagnostics(request, json.parseToJsonElement("""{"prompt_cache_key":"secret-session"}""").jsonObject)
        val row = recorder.requests("account").single()
        assertEquals(row["prompt_cache_key_hash"], row["request_session_id_header_hash"])
        assertEquals(row["prompt_cache_key_hash"], row["response_prompt_cache_key_hash"])
        assertEquals("true", row.getValue("response_prompt_cache_key_matches_session_id_header").jsonPrimitive.content)
        assertEquals("true", row.getValue("response_prompt_cache_key_matches_request").jsonPrimitive.content)
        assertFalse(recorder.export("account").toString().contains("secret-session"))
    }

    @Test
    fun `actual final HTTP body and terminal usage are observed without changing the request`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(sseResponse())
        server.start()
        val client = clientFor(server, headerOverride = "secret-actual-wire-header")
        val credential = credential()
        val setting = ProviderSetting.ChatGPT(credential = credential)
        try {
            val provider = ChatGPTProvider(client) { _, _ -> credential }
            withTimeout(5_000) {
                provider.streamText(setting, listOf(UIMessage.user("secret-chat")), TextGenerationParams(
                    Model(modelId = "synthetic-model"), sessionId = "secret-session", reasoningLevel = ReasoningLevel.AUTO,
                    customBody = listOf(CustomBody("parallel_tool_calls", JsonPrimitive(false))),
                )).toList()
            }
            val wire = server.takeRequest(5, TimeUnit.SECONDS)!!
            val captured = json.parseToJsonElement(wire.body.readUtf8()).jsonObject
            assertEquals("false", captured.getValue("store").jsonPrimitive.content)
            assertEquals("true", captured.getValue("stream").jsonPrimitive.content)
            assertEquals("false", captured.getValue("parallel_tool_calls").jsonPrimitive.content)
            assertEquals("secret-actual-wire-header", wire.getHeader("session_id"))
            val exported = ChatGPTCacheDiagnostics.export(setting)
            val row = exported.getValue("requests").jsonArray.single().jsonObject
            assertEquals("4096", row.getValue("input_tokens").jsonPrimitive.content)
            assertEquals("2048", row.getValue("cached_tokens").jsonPrimitive.content)
            assertEquals("1024", row.getValue("cache_write_tokens").jsonPrimitive.content)
            assertEquals("cache_hit", row.getValue("server_diagnostic_type").jsonPrimitive.content)
            assertEquals("completed", row.getValue("outcome").jsonPrimitive.content)
            assertEquals("true", row.getValue("usage_object_reported").jsonPrimitive.content)
            assertEquals("true", row.getValue("request_session_id_header_present").jsonPrimitive.content)
            assertNotEquals(row["prompt_cache_key_hash"], row["request_session_id_header_hash"])
            assertEquals("missing", row.getValue("response_prompt_cache_key_state").jsonPrimitive.content)
            assertEquals(JsonNull, row["response_prompt_cache_key_hash"])
            for (secret in listOf("secret-chat", "secret-session", "secret-access", "secret-subject", "secret-response-id", "secret-server-message", "secret-actual-wire-header")) {
                assertFalse(exported.toString().contains(secret))
            }
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            server.shutdown()
        }
    }

    @Test
    fun `server returned cache keys are independently observed across turns and missing stays unknown`() = runBlocking {
        val server = MockWebServer()
        listOf("secret-session", "secret-server-key-changed", null).forEach { server.enqueue(sseResponse(it)) }
        server.start()
        val client = clientFor(server)
        val credential = credential()
        val setting = ProviderSetting.ChatGPT(credential = credential)
        try {
            val provider = ChatGPTProvider(client) { _, _ -> credential }
            repeat(3) { turn -> withTimeout(5_000) {
                provider.streamText(setting, listOf(UIMessage.user("secret-turn-$turn")), TextGenerationParams(
                    Model(modelId = "synthetic-model"), sessionId = "secret-session", reasoningLevel = ReasoningLevel.AUTO,
                )).toList()
            } }
            repeat(3) {
                val wire = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals(chatGPTSessionAffinity(credential, "secret-session"), wire.getHeader("session_id"))
                assertEquals("secret-session", json.parseToJsonElement(wire.body.readUtf8()).jsonObject.getValue("prompt_cache_key").jsonPrimitive.content)
            }
            val rows = ChatGPTCacheDiagnostics.export(setting).getValue("requests").jsonArray.map { it.jsonObject }
            assertEquals(3, rows.size)
            assertEquals("true", rows[0].getValue("response_prompt_cache_key_matches_request").jsonPrimitive.content)
            assertEquals("provided", rows[1].getValue("response_prompt_cache_key_state").jsonPrimitive.content)
            assertEquals("true", rows[1].getValue("response_prompt_cache_key_changed").jsonPrimitive.content)
            assertEquals("false", rows[1].getValue("response_prompt_cache_key_matches_request").jsonPrimitive.content)
            assertEquals("missing", rows[2].getValue("response_prompt_cache_key_state").jsonPrimitive.content)
            assertEquals(JsonNull, rows[2]["response_prompt_cache_key_hash"])
            assertEquals(JsonNull, rows[2]["response_prompt_cache_key_changed"])
            assertEquals(JsonNull, rows[2]["response_prompt_cache_key_matches_request"])
            assertEquals(rows[1]["sequence"], rows[2]["response_prompt_cache_key_compared_to_sequence"])
            assertFalse(ChatGPTCacheDiagnostics.export(setting).toString().contains("secret-server-key-changed"))
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            server.shutdown()
        }
    }

    @Test
    fun `authentication retry records each wire attempt and terminal usage only once`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        server.enqueue(sseResponse("secret-session"))
        server.start()
        val client = clientFor(server)
        val credential = credential()
        val setting = ProviderSetting.ChatGPT(credential = credential)
        try {
            val provider = ChatGPTProvider(client) { _, rejected ->
                if (rejected == null) credential else credential.copy(accessToken = "secret-refreshed-access")
            }
            withTimeout(5_000) {
                provider.streamText(setting, listOf(UIMessage.user("secret-chat")), TextGenerationParams(
                    Model(modelId = "synthetic-model"), sessionId = "secret-session", reasoningLevel = ReasoningLevel.AUTO,
                )).toList()
            }
            assertEquals("Bearer secret-access", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
            assertEquals("Bearer secret-refreshed-access", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
            val rows = ChatGPTCacheDiagnostics.export(setting).getValue("requests").jsonArray.map { it.jsonObject }
            assertEquals(2, rows.size)
            assertEquals("1", rows[0].getValue("wire_attempt_number").jsonPrimitive.content)
            assertEquals("2", rows[1].getValue("wire_attempt_number").jsonPrimitive.content)
            assertEquals(rows[0]["logical_request_sequence"], rows[1]["logical_request_sequence"])
            assertEquals(rows[0]["sequence"], rows[1]["previous_attempt_sequence"])
            assertEquals("not_completed", rows[0].getValue("outcome").jsonPrimitive.content)
            assertEquals(JsonNull, rows[0]["cached_tokens"])
            assertEquals("completed", rows[1].getValue("outcome").jsonPrimitive.content)
            assertEquals("2048", rows[1].getValue("cached_tokens").jsonPrimitive.content)
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            server.shutdown()
        }
    }

    private fun clientFor(server: MockWebServer, headerOverride: String? = null): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            // Local transport rewrite only: proceed through the real network interceptor and socket.
            val request = chain.request().newBuilder().url(server.url("/v1/responses"))
            headerOverride?.let { request.header("session_id", it) }
            chain.proceed(request.build())
        }.build()

    private fun sseResponse(returnedKey: String? = null): MockResponse {
        val cacheKeyField = returnedKey?.let { ",\"prompt_cache_key\":${JsonPrimitive(it)}" }.orEmpty()
        val event = """{"type":"response.completed","response":{"id":"secret-response-id","status":"completed","model":"synthetic-model"$cacheKeyField,"usage":{"input_tokens":4096,"input_tokens_details":{"cached_tokens":2048,"cache_write_tokens":1024},"output_tokens":4,"total_tokens":4100},"prompt_cache_diagnostics":{"type":"cache_hit","reason":"secret-server-message"}}}"""
        val duplicate = """{"type":"response.completed","response":{"id":"secret-response-id","status":"completed"}}"""
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $event\n\ndata: $duplicate\n\n")
    }

    private fun credential() = OAuthCredential(
        accessToken = "secret-access", protocolVersion = 1, clientId = "synthetic-client",
        issuer = "https://auth.openai.com", subject = "secret-subject",
        grantedScopes = setOf("chatgpt.tokens.use.direct"),
    )
}
