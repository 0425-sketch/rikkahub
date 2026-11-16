package me.rerere.ai.provider.providers.chatgpt

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Real local HTTP egress with synthetic credentials/responses, not SIWC cache measurements. */
class ChatGPTSessionAffinityTest {
    private val credential = OAuthCredential(
        accessToken = "synthetic-initial", refreshToken = "synthetic-refresh", protocolVersion = 1,
        clientId = "synthetic-registered-client", issuer = "https://auth.openai.com", subject = "synthetic-account-a",
        grantedScopes = setOf("chatgpt.tokens.use.direct"),
    )
    private val model = Model(modelId = "synthetic-model")
    private fun params(session: String? = "persisted-conversation-a") = TextGenerationParams(
        model, reasoningLevel = ReasoningLevel.AUTO, sessionId = session,
    )

    @Test
    fun `stable account identity and saved session determine affinity without tokens or optional profile fields`() {
        val initial = chatGPTSessionAffinity(credential, "conversation")
        assertTrue(initial!!.matches(Regex("[0-9a-f]{64}")))
        assertEquals(initial, chatGPTSessionAffinity(credential.copy(
            accessToken = "rotated", refreshToken = "rotated-refresh", expiresAt = 100,
            accountId = "newly-filled-optional-profile", email = "updated@example.invalid", plan = "changed-plan",
        ), "conversation"))
        assertNotEquals(initial, chatGPTSessionAffinity(credential.copy(subject = "different-account"), "conversation"))
        assertNotEquals(initial, chatGPTSessionAffinity(credential, "different-conversation"))
        assertNull(chatGPTSessionAffinity(credential, null))
        assertNull(chatGPTSessionAffinity(credential, ""))
    }

    @Test
    fun `actual HTTP preserves affinity through tool continuation credential refresh and serialized reload`() = runBlocking {
        withServer { server, client ->
            server.enqueue(completed())
            server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":{\"message\":\"expired\"}}"))
            server.enqueue(completed())
            server.enqueue(completed())
            val setting = ProviderSetting.ChatGPT(credential = credential)
            val provider = ChatGPTProvider(client) { _, rejected ->
                if (rejected == null) credential else credential.copy(accessToken = "synthetic-rotated", accountId = "optional-profile")
            }
            val requestParams = params()
            val initial = listOf(UIMessage.system("Stable instructions"), UIMessage.user("Use the client tool"))
            provider.streamText(setting, initial, requestParams).toList()
            val history = initial + UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
                UIMessagePart.Tool(toolCallId = "call-stable", toolName = "client_tool", input = "{}",
                    output = listOf(UIMessagePart.Text("actual synthetic tool result"))),
            ))
            provider.streamText(setting, history, requestParams).toList()
            // A fresh provider and deserialized parameters model the process-restart boundary.
            val reloaded = json.decodeFromString<TextGenerationParams>(json.encodeToString(requestParams))
            ChatGPTProvider(client) { _, _ -> credential.copy(accessToken = "synthetic-rotated") }
                .streamText(setting, history + UIMessage.user("Continue"), reloaded).toList()
            val requests = List(4) { take(server) }
            val expected = chatGPTSessionAffinity(credential, requestParams.sessionId)
            requests.forEach {
                assertEquals(listOf(expected), it.headers.values("session_id"))
                assertNull(it.getHeader("session-id"))
                val body = body(it)
                assertEquals(JsonPrimitive(requestParams.sessionId), body["prompt_cache_key"])
                assertEquals(JsonPrimitive(false), body["store"])
                assertEquals(JsonPrimitive(true), body["stream"])
                assertFalse("prompt_cache_retention" in body)
            }
            assertEquals("Bearer synthetic-initial", requests[1].getHeader("Authorization"))
            assertEquals("Bearer synthetic-rotated", requests[2].getHeader("Authorization"))
            assertEquals(requests[1].body.readUtf8(), requests[2].body.readUtf8())
        }
    }

    @Test
    fun `provider and request custom session headers follow precedence without duplicate affinity or silent conflict`() = runBlocking {
        withServer { server, client ->
            server.enqueue(completed())
            val expected = checkNotNull(chatGPTSessionAffinity(credential, params().sessionId))
            val setting = ProviderSetting.ChatGPT(credential = credential, customHeaders = listOf(
                CustomHeader("SESSION_ID", "stale-provider-override"),
                CustomHeader("session-id", "legacy-codex"),
                CustomHeader("ChatGPT-Account-ID", "legacy-account"),
            ))
            val provider = ChatGPTProvider(client) { _, _ -> credential }
            val selected = params().copy(
                customHeaders = listOf(CustomHeader("SeSsIoN_Id", expected), CustomHeader("session_id", expected)),
                customBody = listOf(CustomBody("prompt_cache_key", JsonPrimitive("deliberate-body-key"))),
            )
            provider.streamText(setting, listOf(UIMessage.user("test")), selected).toList()
            val sent = take(server)
            assertEquals(listOf(expected), sent.headers.values("session_id"))
            assertNull(sent.getHeader("session-id"))
            assertNull(sent.getHeader("ChatGPT-Account-ID"))
            assertEquals(JsonPrimitive("deliberate-body-key"), body(sent)["prompt_cache_key"])
            for (headers in listOf(emptyList(), listOf(CustomHeader("session_id", expected), CustomHeader("SESSION_ID", "conflict")))) {
                val failure = runCatching {
                    provider.streamText(setting, listOf(UIMessage.user("test")), params().copy(customHeaders = headers)).toList()
                }.exceptionOrNull()
                assertTrue(failure?.message.orEmpty().contains("session_id"))
                assertTrue(failure?.message.orEmpty().contains("移除"))
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `different accounts and conversations have isolated final HTTP headers while sessionless requests omit them`() = runBlocking {
        withServer { server, client ->
            repeat(4) { server.enqueue(completed()) }
            suspend fun send(account: OAuthCredential, session: String?) {
                ChatGPTProvider(client) { _, _ -> account }.streamText(
                    ProviderSetting.ChatGPT(credential = account), listOf(UIMessage.user("test")), params(session),
                ).toList()
            }
            send(credential, "conversation-a")
            send(credential.copy(subject = "synthetic-account-b"), "conversation-a")
            send(credential, "conversation-b")
            send(credential, null)
            val headers = List(4) { take(server).getHeader("session_id") }
            assertEquals(3, headers.take(3).toSet().size)
            assertTrue(headers.take(3).all { it != null })
            assertNull(headers.last())
        }
    }

    @Test
    fun `ordinary API key Responses do not gain SIWC affinity and retain caller header behavior`() = runBlocking {
        withServer { server, client ->
            repeat(2) { server.enqueue(completed()) }
            val api = ResponseAPI(client)
            val ordinary = ProviderSetting.OpenAI(apiKey = "synthetic-key", baseUrl = server.url("/v1").toString())
            api.streamText(ordinary, listOf(UIMessage.user("test")), params()).toList()
            api.streamText(ordinary.copy(customHeaders = listOf(CustomHeader("session_id", "caller-kept"))),
                listOf(UIMessage.user("test")), params()).toList()
            assertNull(take(server).getHeader("session_id"))
            assertEquals("caller-kept", take(server).getHeader("session_id"))
        }
    }

    private fun completed() = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
        "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"synthetic-response\",\"status\":\"completed\"}}\n\n",
    )
    private fun take(server: MockWebServer): RecordedRequest = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
    private fun body(request: RecordedRequest): JsonObject = json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject
    private suspend fun withServer(block: suspend (MockWebServer, OkHttpClient) -> Unit) {
        val server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            // Only tests redirect the official route to a real loopback socket. No scripted interceptor response.
            chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
        }.build()
        try { withTimeout(10_000) { block(server, client) } }
        finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            server.shutdown()
        }
    }
}
