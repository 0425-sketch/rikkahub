package me.rerere.oauth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class OAuthLoopbackCallbackServerTest {
    private val httpClient = OkHttpClient()

    @Test
    fun `callback is delivered only to matching state`() = runBlocking {
        val server = OAuthLoopbackCallbackServer()
        val session = server.openSession("expected-state")
        try {
            assertTrue(session.redirectUri.startsWith("http://localhost:"))
            execute("${session.redirectUri}?code=wrong-code&state=wrong-state").use { response ->
                assertEquals(400, response.code)
            }

            val callbackAndClose = async(Dispatchers.IO) {
                session.awaitCallback(2.seconds).also { session.close() }
            }
            withContext(Dispatchers.IO) {
                execute("${session.redirectUri}?code=auth-code&state=expected-state").use { response ->
                    assertEquals(200, response.code)
                    assertTrue(response.body.string().contains("Authorization complete"))
                }
            }

            val callback = callbackAndClose.await()
            assertEquals("auth-code", callback?.code)
            assertEquals("expected-state", callback?.state)
            assertFalse(callback?.error != null)
        } finally {
            session.close()
        }
    }

    @Test
    fun `official callback preserves client id and rejects ambiguous parameters`() = runBlocking {
        val server = OAuthLoopbackCallbackServer(callbackPath = "/auth/callback", redirectHost = "127.0.0.1")
        val session = server.openSession("expected")
        try {
            assertTrue(session.redirectUri.startsWith("http://127.0.0.1:"))
            for (query in listOf("code=a&state=expected&state=other", "code=a&state=expected&client_id=a&client_id=b", "code=a&error=access_denied&state=expected")) {
                withContext(Dispatchers.IO) { execute("${session.redirectUri}?$query").use { assertEquals(400, it.code) } }
            }
            withContext(Dispatchers.IO) { execute("${session.redirectUri}?code=a&state=expected&client_id=oaiapp_new").use { assertEquals(200, it.code) } }
            assertEquals("oaiapp_new", session.awaitCallback(2.seconds)?.clientId)
        } finally {
            session.close()
        }
    }

    @Test
    fun `immediate session close flushes complete callback responses without retry`() = runBlocking {
        val noRetryClient = httpClient.newBuilder().retryOnConnectionFailure(false).build()
        for (host in listOf("localhost", "127.0.0.1")) {
            repeat(6) { attempt ->
                val state = "state-$host-$attempt"
                val server = OAuthLoopbackCallbackServer(redirectHost = host)
                val session = server.openSession(state)
                try {
                    val callbackAndClose = async(Dispatchers.IO) {
                        session.awaitCallback(2.seconds).also { session.close() }
                    }
                    withContext(Dispatchers.IO) {
                        noRetryClient.newCall(Request.Builder()
                            .url("${session.redirectUri}?code=auth-code&state=$state")
                            .build()).execute().use { response ->
                            assertEquals(200, response.code)
                            val html = response.body.string()
                            assertTrue(html.contains("Authorization complete"))
                            assertTrue(html.trimEnd().endsWith("</html>"))
                        }
                    }
                    val callback = callbackAndClose.await()
                    assertEquals("auth-code", callback?.code)
                    assertEquals(state, callback?.state)
                    assertFalse(callback?.error != null)
                } finally {
                    session.close()
                }
            }
        }
    }

    private fun execute(url: String) = httpClient.newCall(
        Request.Builder()
            .url(url)
            .build()
    ).execute()
}
