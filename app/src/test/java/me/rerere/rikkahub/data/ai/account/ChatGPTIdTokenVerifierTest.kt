package me.rerere.rikkahub.data.ai.account

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

class ChatGPTIdTokenVerifierTest {
    private val calls = AtomicInteger()
    private var keys = JWKSet(primary.toPublicJWK()).toString()
    private var now = NOW
    private var httpStatus = 200
    private var networkFailure: IOException? = null
    private val verifier = ChatGPTIdTokenVerifier(
        OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
            assertEquals("https://auth.openai.com/.well-known/jwks.json", chain.request().url.toString())
            calls.incrementAndGet()
            networkFailure?.let { throw it }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(httpStatus)
                .message("OK")
                .body(keys.toResponseBody("application/json".toMediaType()))
                .build()
        }.build(),
        nowMillis = { now },
    )

    private fun claims(block: JWTClaimsSet.Builder.() -> Unit = {}): JWTClaimsSet = JWTClaimsSet.Builder()
        .issuer(ChatGPTIdTokenVerifier.ISSUER)
        .audience(CLIENT_ID)
        .subject("verified-user")
        .issueTime(Date(NOW - 60_000))
        .expirationTime(Date(NOW + 3_600_000))
        .claim("nonce", "expected-nonce")
        .claim("email", "user@example.test")
        .claim("https://api.openai.com/auth", mapOf("chatgpt_account_id" to "workspace", "chatgpt_plan_type" to "plus"))
        .apply(block)
        .build()

    private fun token(
        claims: JWTClaimsSet = claims(),
        signer: RSAKey = primary,
        header: JWSHeader = JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signer.keyID).build(),
    ): String = SignedJWT(header, claims).apply { sign(RSASSASigner(signer)) }.serialize()

    private suspend fun rejects(
        token: String,
        clientId: String = CLIENT_ID,
        nonce: String? = "expected-nonce",
        expectedSubject: String? = null,
        expectedNonce: String? = null,
    ) {
        val failure = runCatching {
            verifier.verify(token, clientId, nonce, expectedSubject, expectedNonce)
        }.exceptionOrNull()
        assertNotNull("Invalid identity token must be rejected", failure)
        assertFalse("Invalid identity is distinct from temporary key-service failure", failure is ChatGPTSigningKeysUnavailableException)
        assertFalse("Failure must never echo the token", failure!!.message.orEmpty().contains(token))
        assertFalse("Failure must not expose identity claims", failure.message.orEmpty().contains("user@example.test"))
    }

    @Test
    fun `verified signed identity exposes account info and caches trusted keys`() = runTest {
        val identity = verifier.verify(token(), CLIENT_ID, "expected-nonce")
        assertEquals("verified-user", identity.subject)
        assertEquals(ChatGPTIdTokenVerifier.ISSUER, identity.issuer)
        assertEquals("user@example.test", identity.email)
        assertEquals("workspace", identity.accountId)
        assertEquals("plus", identity.plan)
        verifier.verify(token(), CLIENT_ID, "expected-nonce")
        assertEquals(1, calls.get())
    }

    @Test
    fun `tampered signature and algorithm substitution are rejected`() = runTest {
        rejects(token(signer = rotated, header = JWSHeader.Builder(JWSAlgorithm.RS256).keyID(primary.keyID).build()))
        rejects(token(header = JWSHeader.Builder(JWSAlgorithm.RS384).keyID(primary.keyID).build()))
        val original = token().split('.')
        rejects("${original[0]}.${original[1]}.invalid")
        rejects("eyJhbGciOiJub25lIn0.e30.")
    }

    @Test
    fun `issuer audience expiration nonce and subject are validated`() = runTest {
        rejects(token(claims { issuer("https://attacker.example") }))
        rejects(token(claims { audience("other-client") }))
        rejects(token(claims { expirationTime(Date(NOW - 10_000)) }))
        rejects(token(claims { claim("nonce", "different-nonce") }))
        rejects(token(claims { subject("") }))
        rejects(token(claims { subject("other-user") }), expectedSubject = "verified-user")
        rejects(token(), clientId = "dynamic_agent_client")
        rejects(token(), nonce = null)
    }

    @Test
    fun `required temporal claims and authorized party are checked`() = runTest {
        rejects(token(claims { expirationTime(null) }))
        rejects(token(claims { issueTime(null) }))
        rejects(token(claims { issueTime(Date(NOW + 60_000)) }))
        rejects(token(claims { notBeforeTime(Date(NOW + 60_000)) }))
        rejects(token(claims { audience(listOf(CLIENT_ID, "other")) }))
        rejects(token(claims { claim("azp", "other") }))
        val identity = verifier.verify(
            token(claims {
                audience(listOf(CLIENT_ID, "other"))
                claim("azp", CLIENT_ID)
            }),
            CLIENT_ID,
            "expected-nonce",
        )
        assertEquals("verified-user", identity.subject)
    }

    @Test
    fun `small clock skew is accepted but malformed session identifiers are rejected`() = runTest {
        verifier.verify(token(claims { expirationTime(Date(NOW - 4_000)) }), CLIENT_ID, "expected-nonce")
        rejects(token(claims { claim("sid", " ") }))
        rejects(token(claims { claim("sid", 42) }))
        rejects(token(claims { claim("nonce", 42) }))
    }

    @Test
    fun `refresh may omit nonce only with matching saved subject`() = runTest {
        val refreshed = token(claims { claim("nonce", null) })
        assertEquals(
            "verified-user",
            verifier.verify(refreshed, CLIENT_ID, nonce = null, expectedSubject = "verified-user").subject,
        )
        rejects(refreshed, nonce = null, expectedSubject = "other-user")
    }

    @Test
    fun `refresh carrying nonce must match original authorization nonce`() = runTest {
        assertEquals(
            "verified-user",
            verifier.verify(
                token(), CLIENT_ID, nonce = null, expectedSubject = "verified-user", expectedNonce = "expected-nonce",
            ).subject,
        )
        rejects(token(), nonce = null, expectedSubject = "verified-user")
        rejects(token(), nonce = null, expectedSubject = "verified-user", expectedNonce = "different-nonce")
        rejects(token(claims { claim("nonce", "") }), nonce = null, expectedSubject = "verified-user", expectedNonce = "expected-nonce")
    }

    @Test
    fun `unfamiliar key triggers rotation refresh and missing key is rejected`() = runTest {
        verifier.verify(token(), CLIENT_ID, "expected-nonce")
        keys = JWKSet(listOf(primary.toPublicJWK(), rotated.toPublicJWK())).toString()
        verifier.verify(token(signer = rotated), CLIENT_ID, "expected-nonce")
        assertEquals(2, calls.get())
        rejects(token(header = JWSHeader.Builder(JWSAlgorithm.RS256).keyID("unknown").build()))
        assertEquals(3, calls.get())
    }

    @Test
    fun `expired cache refreshes and inappropriate key use fails closed`() = runTest {
        verifier.verify(token(), CLIENT_ID, "expected-nonce")
        now += 3_600_001
        keys = JWKSet(RSAKey.Builder(primary.toPublicJWK()).keyUse(KeyUse.ENCRYPTION).build()).toString()
        rejects(token(claims { expirationTime(Date(now + 60_000)) }))
        assertEquals(2, calls.get())
    }

    @Test
    fun `token supplied key URL is ignored`() = runTest {
        verifier.verify(
            token(header = JWSHeader.Builder(JWSAlgorithm.RS256).keyID(primary.keyID)
                .jwkURL(java.net.URI("https://attacker.example/keys")).build()),
            CLIENT_ID,
            "expected-nonce",
        )
        assertTrue(calls.get() == 1)
    }

    @Test
    fun `temporary JWKS http failures can be retried without invalidating token`() = runTest {
        val validToken = token()
        for (status in listOf(503, 429)) {
            httpStatus = status
            val failure = runCatching { verifier.verify(validToken, CLIENT_ID, "expected-nonce") }.exceptionOrNull()
            assertTrue(failure is ChatGPTSigningKeysUnavailableException)
        }
        httpStatus = 200
        assertEquals("verified-user", verifier.verify(validToken, CLIENT_ID, "expected-nonce").subject)
        assertEquals(3, calls.get())
    }

    @Test
    fun `JWKS transport failure is distinct from invalid signature and sanitized`() = runTest {
        networkFailure = IOException("sensitive transport diagnostics")
        val failure = runCatching { verifier.verify(token(), CLIENT_ID, "expected-nonce") }.exceptionOrNull()
        assertTrue(failure is ChatGPTSigningKeysUnavailableException)
        assertFalse(failure!!.message.orEmpty().contains("sensitive transport diagnostics"))
        assertEquals(null, failure.cause)
        networkFailure = null
        rejects(token(signer = rotated, header = JWSHeader.Builder(JWSAlgorithm.RS256).keyID(primary.keyID).build()))
        assertEquals("verified-user", verifier.verify(token(), CLIENT_ID, "expected-nonce").subject)
    }

    @Test
    fun `malformed JWKS service response preserves retriable failure classification`() = runTest {
        keys = "<html>temporarily unavailable</html>"
        val failure = runCatching { verifier.verify(token(), CLIENT_ID, "expected-nonce") }.exceptionOrNull()
        assertTrue(failure is ChatGPTSigningKeysUnavailableException)
        keys = JWKSet(primary.toPublicJWK()).toString()
        assertEquals("verified-user", verifier.verify(token(), CLIENT_ID, "expected-nonce").subject)
    }

    companion object {
        private const val NOW = 1_800_000_000_000L
        private const val CLIENT_ID = "oaiapp-test-client"
        // Generated only at test runtime; no production/private signing material is checked in.
        private val primary: RSAKey by lazy { RSAKeyGenerator(2048).keyID("key-one").generate() }
        private val rotated: RSAKey by lazy { RSAKeyGenerator(2048).keyID("key-two").generate() }
    }
}
