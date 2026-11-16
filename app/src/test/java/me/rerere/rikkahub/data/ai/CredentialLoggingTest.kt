package me.rerere.rikkahub.data.ai

import me.rerere.common.android.Logging
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
}
