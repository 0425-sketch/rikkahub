package me.rerere.common.android

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Credentials
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialRedactorTest {
    @After fun reset() = Logging.setRequestLoggingEnabled(false)

    @Test fun `all credential headers are case insensitive in structured and ordinary logs`() {
        (CredentialRedactor.sensitiveHeaders + "x-custom-auth-token" + "x-client-secret").forEach { header ->
            val secret = "synthetic-private-value"
            val name = header.uppercase()
            val headers = CredentialRedactor.headers(Headers.Builder().add(name, secret).build())
            assertEquals(CredentialRedactor.REDACTED, headers[name])
            assertFalse(CredentialRedactor.text("$name: $secret").contains(secret))
            assertFalse(CredentialRedactor.text("""{"$name":"$secret"}""").contains(secret))
            assertFalse(CredentialRedactor.text("Error response: {\"$name\":\"$secret\"}").contains(secret))
            assertFalse(CredentialRedactor.text("$name=$secret").contains(secret))
        }
    }

    @Test fun `authentication fields redact in JSON forms URLs and nested errors`() {
        val names = listOf("key", "api_key", "apiKey", "access_token", "refresh_token", "id_token",
            "id_token_hint", "token", "code", "code_verifier", "client_secret", "assertion", "password", "private_key")
        names.forEach { name ->
            val secret = "synthetic-$name-credential"
            assertFalse(CredentialRedactor.text("""{"$name":"$secret"}""", authentication = true).contains(secret))
            assertFalse(CredentialRedactor.text("$name=$secret", authentication = true).contains(secret))
            assertFalse(CredentialRedactor.text("https://example.test/path?$name=$secret&visible=yes").contains(secret))
        }
        val error = IllegalStateException("token response", IllegalArgumentException("refresh_token=secret-cause"))
        Logging.setRequestLoggingEnabled(true)
        Logging.diagnostic("test", error) { "OAuth failure" }
        assertFalse(Logging.getRecentLogs().toString().contains("secret-cause"))
        assertTrue(Logging.getTextLogs().single().message.contains("IllegalArgumentException"))
    }

    @Test fun `ordinary business code key and token usage are retained`() {
        val input = """{"code":"business-code","key":"business-key","token":42,"usage":{"input_tokens":13,"cached_tokens":0,"total_tokens":19},"tools":[{"parameters":{"code":"sample","key":"column"}}]}"""
        assertEquals(Json.parseToJsonElement(input), Json.parseToJsonElement(CredentialRedactor.text(input)))
        val auth = """{"grant_type":"authorization_code","code":"private-code","key":"private-key","usage":{"input_tokens":13}}"""
        val safe = Json.parseToJsonElement(CredentialRedactor.text(auth)).jsonObject
        assertEquals(CredentialRedactor.REDACTED, safe["code"]!!.jsonPrimitive.content)
        assertEquals("13", safe["usage"]!!.jsonObject["input_tokens"]!!.jsonPrimitive.content)
    }

    @Test fun `mentioning OAuth in an ordinary chat body does not make business fields credentials`() {
        val body = """{"messages":[{"content":"Explain oauth and authorization_code","code":"sample-code","key":"sample-key"}],"code":"business-code","key":"business-key","token":42}"""
        Logging.setRequestLoggingEnabled(true)
        Logging.diagnostic("Provider") { "generateText: ${CredentialRedactor.text(body)}" }
        val log = Logging.getTextLogs().single().message
        assertTrue(log.contains("\"code\":\"business-code\""))
        assertTrue(log.contains("\"key\":\"business-key\""))
        assertTrue(log.contains("\"code\":\"sample-code\""))
        assertTrue(log.contains("\"key\":\"sample-key\""))
        assertTrue(log.contains("\"token\":42"))
    }

    @Test fun `off does not invoke diagnostic serialization or exception formatting`() {
        Logging.setRequestLoggingEnabled(false)
        var serialized = false
        val error = object : RuntimeException() {
            override fun toString(): String = kotlin.error("Throwable must not be formatted when off")
        }
        Logging.diagnostic("provider", error) { serialized = true; "body" }
        Logging.log("provider", "access_token=off-secret")
        assertFalse(serialized)
        assertTrue(Logging.getRecentLogs().isEmpty())
        Logging.setRequestLoggingEnabled(true)
        Logging.diagnostic("provider") { "on" }
        assertEquals(1, Logging.getRecentLogs().size)
        Logging.setRequestLoggingEnabled(false)
        assertTrue(Logging.getRecentLogs().isEmpty())
    }

    @Test fun `unlabelled request credentials are removed from later errors and export`() {
        Logging.setRequestLoggingEnabled(true)
        Logging.rememberCredentials(listOf("arbitrary-server-echo"))
        Logging.diagnostic("provider", IllegalStateException("rejected arbitrary-server-echo")) { "Failed" }
        assertFalse(Logging.getRecentLogs().toString().contains("arbitrary-server-echo"))
        assertFalse(Logging.sanitizeForExport("rejected arbitrary-server-echo").contains("arbitrary-server-echo"))
        Logging.setRequestLoggingEnabled(false)
        assertEquals("arbitrary-server-echo", Logging.sanitizeForExport("arbitrary-server-echo"))
    }

    @Test fun `oversized diagnostics fail closed and records remain bounded`() {
        val raw = "a".repeat(CredentialRedactor.MAX_LOG_CHARS) + "secret"
        assertTrue(CredentialRedactor.text(raw).contains("omitted"))
        assertFalse(CredentialRedactor.text(raw).contains("secret"))
        Logging.setRequestLoggingEnabled(true)
        repeat(200) { Logging.log("test", "entry $it") }
        assertEquals(100, Logging.getRecentLogs().size)
    }

    @Test fun `redaction dictionary refuses oversized values and fails closed instead of evicting in flight credentials`() {
        Logging.setRequestLoggingEnabled(true)
        Logging.rememberCredentials(listOf("x".repeat(4097)))
        assertTrue(Logging.sanitizeForExport("anything").contains("capacity exceeded"))
        Logging.setRequestLoggingEnabled(false)
        Logging.setRequestLoggingEnabled(true)
        Logging.rememberCredentials((1..129).map { "credential-$it" })
        assertTrue(Logging.sanitizeForExport("credential-1").contains("capacity exceeded"))
        Logging.setRequestLoggingEnabled(false)
        assertEquals("anything", Logging.sanitizeForExport("anything"))
    }

    @Test fun `URL userinfo and nested diagnostic payloads cannot expose credentials`() {
        val request = Request.Builder().url("https://account:private-url-password@example.test/path").build()
        assertFalse(CredentialRedactor.url(request.url).contains("private-url-password"))
        assertFalse(CredentialRedactor.text("Failed at ${request.url}").contains("private-url-password"))
        assertTrue(CredentialRedactor.secrets(request).contains("private-url-password"))
        val nested = "[".repeat(512) + "\"buried-content\"" + "]".repeat(512)
        val safe = CredentialRedactor.text(nested)
        assertFalse(safe.contains("buried-content"))
        assertTrue(safe.contains("omitted"))
    }

    @Test fun `request scope never starts midway and is invalidated across off on toggles`() {
        val request = Request.Builder().url("https://example.test/models")
            .header("x-goog-api-key", "opaque-private-value").build()
        Logging.setRequestLoggingEnabled(false)
        val offScope = Logging.requestScope(request)
        Logging.setRequestLoggingEnabled(true)
        var evaluated = false
        offScope.diagnostic("provider") { evaluated = true; "opaque-private-value" }
        assertFalse(evaluated)
        val oldScope = Logging.requestScope(request)
        Logging.setRequestLoggingEnabled(false)
        Logging.setRequestLoggingEnabled(true)
        oldScope.diagnostic("provider") { evaluated = true; "opaque-private-value" }
        assertFalse(evaluated)
        assertTrue(Logging.getRecentLogs().isEmpty())
        val freshScope = Logging.requestScope(request)
        freshScope.diagnostic("provider") { "Server rejected opaque-private-value" }
        assertFalse(Logging.getRecentLogs().toString().contains("opaque-private-value"))
    }

    @Test fun `Basic passwords cookie values and Brave subscription credentials are removed from scoped echoes`() {
        val values = listOf("opaque-basic-password", "opaque-cookie-one", "opaque-cookie-two", "opaque-brave-key")
        val request = Request.Builder().url("https://example.test/search")
            .header("Proxy-Authorization", Credentials.basic("user", values[0]))
            .header("Cookie", "session=${values[1]}; other=\"${values[2]}\"")
            .header("X-Subscription-Token", values[3]).build()
        Logging.setRequestLoggingEnabled(true)
        val diagnostic = Logging.requestScope(request)
        diagnostic.diagnostic("provider", IllegalArgumentException("Rejected ${values.joinToString()}")) { "Error response" }
        val exported = Logging.getRecentLogs().toString()
        values.forEach { assertFalse(exported.contains(it)) }
        assertEquals(CredentialRedactor.REDACTED, CredentialRedactor.headers(request.headers)["X-Subscription-Token"])
    }

    @Test fun `unscoped exceptions omit arbitrary messages but retain cause types and stack locations`() {
        Logging.setRequestLoggingEnabled(true)
        val cause = IllegalArgumentException("opaque-unknown-secret")
        val error = IllegalStateException("opaque-unknown-secret", cause)
        Logging.diagnostic("chat", error) { "Provider failed" }
        val exported = Logging.getTextLogs().single().message
        assertFalse(exported.contains("opaque-unknown-secret"))
        assertTrue(exported.contains("IllegalStateException"))
        assertTrue(exported.contains("IllegalArgumentException"))
        assertTrue(exported.contains("CredentialRedactorTest"))
        assertFalse(Logging.safeThrowable(error).contains("opaque-unknown-secret"))
    }

    @Test fun `late cookie value is never silently skipped when earlier values repeat`() {
        val secret = "last-private-value"
        val cookie = (1..128).joinToString(";") { "c$it=same" } + ";last=$secret"
        val request = Request.Builder().url("https://example.test/models").header("Cookie", cookie).build()
        Logging.setRequestLoggingEnabled(true)
        Logging.requestScope(request).diagnostic("provider") { "invalid credential $secret" }
        assertFalse(Logging.getRecentLogs().toString().contains(secret))
    }

    @Test fun `Basic password echoes honor both OkHttp default and explicit UTF8 encoding`() {
        val password = "opaque-bäsic-password"
        listOf(Charsets.ISO_8859_1, Charsets.UTF_8).forEach { charset ->
            val request = Request.Builder().url("https://example.test/search")
                .header("Authorization", Credentials.basic("user", password, charset)).build()
            Logging.setRequestLoggingEnabled(true)
            Logging.requestScope(request).diagnostic("provider") { "invalid password $password" }
            assertFalse(Logging.getRecentLogs().toString().contains(password))
            Logging.setRequestLoggingEnabled(false)
        }
    }
}
