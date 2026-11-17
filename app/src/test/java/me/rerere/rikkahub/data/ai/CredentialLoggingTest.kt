package me.rerere.rikkahub.data.ai

import me.rerere.common.android.Logging
import me.rerere.common.android.CredentialRedactor
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.google.GoogleProvider
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import okio.BufferedSink
import okio.Buffer
import java.io.IOException

class CredentialLoggingTest {
    private val logcat = mutableListOf<String>()
    private var received: Request? = null
    private val client = OkHttpClient.Builder()
        .addInterceptor(RequestLoggingInterceptor())
        .addInterceptor(SafeHttpLoggingInterceptor { logcat += it })
        .addInterceptor { chain ->
            received = chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Set-Cookie", "private-session=synthetic-cookie")
                .header("x-request-id", "test-request")
                .body("{}".toResponseBody())
                .build()
        }
        .build()

    @Before fun enableLogging() {
        Logging.clear()
        Logging.setRequestLoggingEnabled(true)
    }

    @After fun disableLogging() {
        Logging.setRequestLoggingEnabled(false)
        Logging.clear()
    }

    @Test fun `token exchanges and authorization hints never reach either logger`() {
        val requests = listOf(
            Request.Builder().url("https://auth.openai.com/api/accounts/oauth/token")
                .post(FormBody.Builder().add("refresh_token", "synthetic-refresh-secret").build()).build(),
            Request.Builder().url("https://auth.openai.com/api/accounts/authorize?id_token_hint=synthetic-id-secret").build(),
            Request.Builder().url("https://auth.openai.com/api/accounts/oauth/revoke")
                .post(FormBody.Builder().add("token", "synthetic-revoke-secret").build()).build(),
            Request.Builder().url("https://example.test/token")
                .post(FormBody.Builder().add("code_verifier", "synthetic-pkce-secret").build()).build(),
            Request.Builder().url("https://example.test/callback?code=synthetic-code-secret").build(),
        )
        requests.forEach { request ->
            client.newCall(request).execute().use { assertEquals(200, it.code) }
            assertEquals(request, received)
        }
        assertTrue(Logging.getRequestLogs().isEmpty())
        assertTrue(logcat.isEmpty())
    }

    @Test fun `public inference diagnostics redact credentials without changing requests`() {
        val request = Request.Builder().url("https://api.openai.com/v1/models")
            .header("authorization", "Bearer synthetic-access-secret")
            .header("Cookie", "synthetic-request-cookie")
            .header("X-Request-Id", "visible-request")
            .build()
        client.newCall(request).execute().close()
        assertEquals("Bearer synthetic-access-secret", received!!.header("Authorization"))
        val entry = Logging.getRequestLogs().single()
        assertEquals(200, entry.responseCode)
        assertEquals("visible-request", entry.requestHeaders["X-Request-Id"])
        assertEquals("test-request", entry.responseHeaders["x-request-id"])
        val diagnostics = entry.toString() + logcat.joinToString("\n")
        listOf("synthetic-access-secret", "synthetic-request-cookie", "synthetic-cookie").forEach {
            assertFalse("Credentials reached a logger", diagnostics.contains(it))
        }
    }

    @Test fun `both Google authorization paths keep real credentials but redact all diagnostics`() = runBlocking {
        val provider = GoogleProvider(client)
        val developer = "developer-private-credential"
        provider.listModels(ProviderSetting.Google(apiKey = developer))
        assertEquals(developer, received!!.header("x-goog-api-key"))
        assertFalse(Logging.getRecentLogs().toString().contains(developer))
        assertFalse(logcat.joinToString().contains(developer))

        Logging.clear()
        logcat.clear()
        val vertex = "vertex-private-credential"
        provider.listModels(ProviderSetting.Google(apiKey = vertex, vertexAI = true, useServiceAccount = false))
        assertEquals(vertex, received!!.url.queryParameter("key"))
        assertEquals("aiplatform.googleapis.com", received!!.url.host)
        assertFalse(Logging.getRecentLogs().toString().contains(vertex))
        assertFalse(logcat.joinToString().contains(vertex))
        assertTrue(Logging.getRequestLogs().single().url.contains("key="))
    }

    @Test fun `off avoids all diagnostic body work and leaves actual request body intact`() {
        var writes = 0
        var lengthReads = 0
        val body = object : RequestBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength(): Long { lengthReads++; return 15 }
            override fun writeTo(sink: BufferedSink) { writes++; sink.writeUtf8("{\"hello\":\"yes\"}") }
        }
        Logging.setRequestLoggingEnabled(false)
        val request = Request.Builder().url("https://example.test/inference").post(body).build()
        client.newCall(request).execute().close()
        assertSame(body, received!!.body)
        assertEquals(0, writes)
        assertEquals(0, lengthReads)
        assertTrue(logcat.isEmpty())
        assertTrue(Logging.getRecentLogs().isEmpty())
        val networkCopy = Buffer().also { received!!.body!!.writeTo(it) }.readUtf8()
        assertEquals("{\"hello\":\"yes\"}", networkCopy)
        assertEquals(1, writes)
    }

    @Test fun `auth JSON payload is omitted before it is inspected`() {
        val body = object : RequestBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength(): Long = error("Auth body must not be measured for logging")
            override fun writeTo(sink: BufferedSink) = error("Auth body must not be copied for logging")
        }
        val request = Request.Builder().url("https://accounts.example.test/oauth/token").post(body).build()
        client.newCall(request).execute().close()
        assertSame(body, received!!.body)
        assertTrue(Logging.getRecentLogs().isEmpty())
        assertTrue(logcat.isEmpty())
    }

    @Test fun `ordinary JSON keeps business fields and usage while credentials redact in log copy only`() {
        val raw = """{"code":"business-code","key":"column","usage":{"input_tokens":17,"cached_tokens":0},"apiKey":"private-api-key","password":"private-password"}"""
        val request = Request.Builder().url("https://example.test/inference")
            .post(raw.toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().close()
        assertEquals(raw, Buffer().also { received!!.body!!.writeTo(it) }.readUtf8())
        val logged = Logging.getRequestLogs().single().requestBody!!
        assertTrue(logged.contains("business-code"))
        assertTrue(logged.contains("\"cached_tokens\":0"))
        assertFalse(logged.contains("private-api-key"))
        assertFalse(logged.contains("private-password"))
    }

    @Test fun `thrown original error propagates and credential echoes are redacted including causes`() {
        val secret = "arbitrary-upstream-key"
        val original = IOException("server rejected $secret", IllegalArgumentException("access_token=cause-secret"))
        val failing = OkHttpClient.Builder()
            .addInterceptor(RequestLoggingInterceptor())
            .addInterceptor(SafeHttpLoggingInterceptor { logcat += it })
            .addInterceptor { throw original }.build()
        val request = Request.Builder().url("https://example.test/inference?key=$secret").build()
        val thrown = runCatching { failing.newCall(request).execute() }.exceptionOrNull()
        assertSame(original, thrown)
        Logging.diagnostic("Provider", original) { "Failed" }
        val exported = Logging.getRecentLogs().toString() + logcat.joinToString()
        assertFalse(exported.contains(secret))
        assertFalse(exported.contains("cause-secret"))
        assertTrue(exported.contains(CredentialRedactor.REDACTED))
    }

    @Test fun `oversized and streaming bodies are not copied for logging`() {
        val body = object : RequestBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = CredentialRedactor.MAX_LOG_CHARS.toLong() + 1
            override fun writeTo(sink: BufferedSink) = error("Oversized diagnostics must not copy the body")
        }
        client.newCall(Request.Builder().url("https://example.test/inference").post(body).build()).execute().close()
        assertTrue(Logging.getRequestLogs().single().requestBody!!.contains("omitted"))
    }

    @Test fun `enabling logging during Google response cannot collect old request echoes`() = runBlocking {
        listOf(false, true).forEach { initiallyEnabled ->
            Logging.setRequestLoggingEnabled(initiallyEnabled)
            logcat.clear()
            val secret = "opaque-unlabelled-credential"
            val switching = OkHttpClient.Builder()
                .addInterceptor(RequestLoggingInterceptor())
                .addInterceptor(SafeHttpLoggingInterceptor { logcat += it })
                .addInterceptor { chain ->
                    assertEquals(secret, chain.request().header("x-goog-api-key"))
                    Logging.setRequestLoggingEnabled(false)
                    Logging.setRequestLoggingEnabled(true)
                    logcat.clear()
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK")
                        .body("""{"models":[],"message":"invalid credential $secret"}""".toResponseBody())
                        .build()
                }.build()
            GoogleProvider(switching).listModels(ProviderSetting.Google(apiKey = secret))
            assertTrue("Provider must not backfill a request across logging toggles", Logging.getRecentLogs().isEmpty())
            assertTrue(logcat.isEmpty())
        }
    }
}
