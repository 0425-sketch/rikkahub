package me.rerere.rikkahub.data.ai.account

import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ChatGPTRegistration
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderSetting
import me.rerere.oauth.OAuthCallback
import me.rerere.oauth.OAuthHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class ChatGPTAuthTest {
    private val identity = ChatGPTVerifiedIdentity(ChatGPTAuth.ISSUER, "subject", "user@example.com")
    private val registration = ChatGPTRegistration("oaiapp_registered", subject = "subject", email = "user@example.com")
    private fun token(scope: String? = ChatGPTAuth.SCOPE) = OAuthHttpClient.TokenResponse(
        accessToken = "test-access", refreshToken = "test-refresh", idToken = "verified-by-caller",
        expiresIn = 3600, scope = scope,
    )
    private fun credential() = ChatGPTAuth.toCredential(token(), registration.clientId, identity, now = 1_000)

    @Test
    fun `new registration sends nonce resource and stable host without Codex parameters`() {
        val url = OAuthHttpClient(OkHttpClient()).buildAuthorizationUrl(ChatGPTAuth.authorizationRequest(
            OAuthHttpClient.Pkce("verifier", "challenge"), "state", "nonce",
            "http://127.0.0.1:54321/auth/callback", "urn:uuid:host",
        )).toHttpUrl()
        assertEquals("/api/accounts/authorize", url.encodedPath)
        assertEquals("dynamic_agent_client", url.queryParameter("client_id"))
        assertEquals("RikkaHub", url.queryParameter("agent_name_hint"))
        assertEquals("urn:uuid:host", url.queryParameter("ext_agent_host_id"))
        assertEquals("nonce", url.queryParameter("nonce"))
        assertEquals(ChatGPTAuth.RESOURCE, url.queryParameter("resource"))
        assertEquals(ChatGPTAuth.SCOPE, url.queryParameter("scope"))
        assertNull(url.queryParameter("originator"))
        assertNull(url.queryParameter("prompt"))
    }

    @Test
    fun `reauthorization uses saved client and hints and consent only when explicitly requested`() {
        val request = ChatGPTAuth.authorizationRequest(
            OAuthHttpClient.Pkce("v", "c"), "new-state", "new-nonce", "http://127.0.0.1:1234/auth/callback",
            "urn:uuid:host", registration, credential(), requestPlanConsent = true,
        )
        assertEquals(registration.clientId, request.clientId)
        assertFalse(request.additionalParameters.containsKey("agent_name_hint"))
        assertEquals("verified-by-caller", request.additionalParameters["id_token_hint"])
        assertEquals("consent", request.additionalParameters["prompt"])
    }

    @Test
    fun `callback validates state errors and issued client without trusting callback scopes`() {
        fun callback(client: String? = null, state: String = "s", error: String? = null) =
            OAuthCallback("code", state, error, null, client)
        assertEquals("oaiapp_new", ChatGPTAuth.callbackClientId(callback("oaiapp_new"), "s", null))
        assertEquals(registration.clientId, ChatGPTAuth.callbackClientId(callback(), "s", registration))
        for (invalid in listOf(callback(), callback("dynamic_agent_client"), callback("x", "bad"), callback("x", error = "access_denied"))) {
            assertTrue(runCatching { ChatGPTAuth.callbackClientId(invalid, "s", null) }.isFailure)
        }
        assertTrue(runCatching { ChatGPTAuth.callbackClientId(callback("different"), "s", registration) }.isFailure)
    }

    @Test
    fun `actual token grant is required and omitted initial scope does not enable inference`() {
        assertTrue(credential().hasChatGPTPlanAccess)
        assertFalse(ChatGPTAuth.toCredential(token("openid email"), registration.clientId, identity).hasChatGPTPlanAccess)
        assertFalse(ChatGPTAuth.toCredential(token(null), registration.clientId, identity).hasChatGPTPlanAccess)
        assertFalse(OAuthCredential("legacy").isOfficialChatGPT)
        val decoded = Json.decodeFromString<OAuthCredential>("""{"accessToken":"legacy","accountId":"codex"}""")
        assertFalse(decoded.hasChatGPTPlanAccess)
    }

    @Test
    fun `refresh rotates atomically and preserves omitted scope refresh and id token`() {
        val old = credential()
        val refreshed = ChatGPTAuth.toCredential(
            token(null).copy(accessToken = "rotated", refreshToken = null, idToken = null),
            registration.clientId, identity, old, now = 5_000,
        )
        assertEquals("test-refresh", refreshed.refreshToken)
        assertEquals(old.idToken, refreshed.idToken)
        assertEquals(old.grantedScopes, refreshed.grantedScopes)
        assertEquals(3_605_000, refreshed.expiresAt)
        val limited = ChatGPTAuth.toCredential(token("openid").copy(refreshToken = "new-refresh"), registration.clientId, identity, old)
        assertEquals("new-refresh", limited.refreshToken)
        assertFalse(limited.hasChatGPTPlanAccess)
        assertTrue(runCatching { ChatGPTAuth.toCredential(token(), "wrong-client", identity, old) }.isFailure)
        assertTrue(runCatching { ChatGPTAuth.toCredential(token(), registration.clientId, identity.copy(subject = "wrong"), old) }.isFailure)
    }

    @Test
    fun `exchange and refresh use form resource and issued client`() {
        val request = ChatGPTAuth.refreshRequest(registration.clientId, "refresh")
        assertEquals(OAuthHttpClient.TokenEncoding.FORM, request.encoding)
        assertEquals(ChatGPTAuth.TOKEN_ENDPOINT, request.tokenEndpoint)
        assertEquals(listOf(ChatGPTAuth.RESOURCE), request.resources)
        assertEquals(registration.clientId, request.clientId)
        assertNull(request.scope)
        val code = ChatGPTAuth.codeRequest(registration.clientId, "code", "verifier", "http://127.0.0.1:54321/auth/callback")
        assertEquals(listOf(ChatGPTAuth.RESOURCE), code.resources)
        assertEquals("http://127.0.0.1:54321/auth/callback", code.redirectUri)
    }

    @Test
    fun `credential and registration survive serialization without granting legacy access`() {
        val original = ProviderSetting.ChatGPT(credential = credential().copy(idTokenNonce = "original-nonce"), chatGPTRegistration = registration)
        val restored = Json.decodeFromString<ProviderSetting.ChatGPT>(Json.encodeToString(original))
        assertEquals(original, restored)
        assertTrue(restored.credential!!.hasChatGPTPlanAccess)
        assertEquals("original-nonce", restored.credential!!.idTokenNonce)
        val signedOut = restored.copy(credential = null)
        val reopened = Json.decodeFromString<ProviderSetting.ChatGPT>(Json.encodeToString(signedOut))
        assertNull(reopened.credential)
        assertEquals(registration, reopened.chatGPTRegistration)
    }

    @Test
    fun `ui edits retain latest credential and registration and api key behavior`() {
        val stored = ProviderSetting.ChatGPT(credential = credential(), chatGPTRegistration = registration)
        val edited = stored.copy(name = "Renamed", credential = OAuthCredential("stale"), chatGPTRegistration = null)
        val merged = edited.preserveCredential(stored) as ProviderSetting.ChatGPT
        assertEquals("Renamed", merged.name)
        assertEquals(stored.credential, merged.credential)
        assertEquals(registration, merged.chatGPTRegistration)
        val apiKey = ProviderSetting.OpenAI(apiKey = "key")
        assertSame(apiKey, apiKey.preserveCredential(ProviderSetting.OpenAI()))
        assertFalse(credential().toString().contains("test-access"))
        assertFalse(token().toString().contains("test-refresh"))
    }
}
