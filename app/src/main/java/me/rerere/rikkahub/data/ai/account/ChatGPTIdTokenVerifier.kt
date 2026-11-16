package me.rerere.rikkahub.data.ai.account

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyOperation
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.common.http.await
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest

/** A temporary key-service failure must not invalidate an otherwise retained OAuth session. */
internal class ChatGPTSigningKeysUnavailableException : IOException(
    "OpenAI signing keys are temporarily unavailable; please try again"
)

/** Claims are exposed only after the signature and all required identity checks succeed. */
internal data class ChatGPTVerifiedIdentity(
    val issuer: String,
    val subject: String,
    val email: String? = null,
    val accountId: String? = null,
    val plan: String? = null,
    val sessionId: String? = null,
)

/**
 * OpenAI currently advertises RS256 in its OIDC discovery document. The key endpoint is fixed:
 * untrusted JWT headers cannot choose a remote key URL. No token or rejected claim is logged.
 */
internal class ChatGPTIdTokenVerifier(
    private val client: OkHttpClient,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private data class CachedKeys(val keys: JWKSet, val fetchedAt: Long)

    private val keyLock = Mutex()
    private var cache: CachedKeys? = null

    suspend fun verify(
        idToken: String,
        clientId: String,
        nonce: String?,
        expectedSubject: String? = null,
        expectedNonce: String? = null,
    ): ChatGPTVerifiedIdentity {
        require(clientId.isNotBlank() && clientId != "dynamic_agent_client") {
            "ChatGPT registration is incomplete"
        }
        require(nonce != null || !expectedSubject.isNullOrBlank()) {
            "A new ChatGPT sign-in requires a nonce"
        }
        try {
            require(idToken.length <= MAX_TOKEN_LENGTH) { "ID token is too large" }
            val jwt = SignedJWT.parse(idToken)
            require(jwt.header.algorithm == JWSAlgorithm.RS256) { "Unsupported ID token algorithm" }
            require(jwt.header.criticalParams.isNullOrEmpty()) { "Unsupported ID token header" }
            val kid = jwt.header.keyID?.takeIf { it.isNotBlank() }
                ?: error("ID token has no signing key identifier")
            val key = findKey(kid)
            require(jwt.verify(RSASSAVerifier(key.toRSAPublicKey()))) { "ID token signature is invalid" }

            val claims = jwt.jwtClaimsSet
            require(claims.issuer == ISSUER) { "ID token issuer is invalid" }
            val audience = claims.audience
            require(clientId in audience) { "ID token audience is invalid" }
            val authorizedParty = claims.getStringClaim("azp")
            require(
                (authorizedParty == null && audience.size == 1) || authorizedParty == clientId
            ) { "ID token authorized party is invalid" }

            val now = nowMillis()
            val expiresAt = claims.expirationTime?.time ?: error("ID token has no expiration")
            val issuedAt = claims.issueTime?.time ?: error("ID token has no issue time")
            require(expiresAt > now - CLOCK_SKEW_MS) { "ID token has expired" }
            require(issuedAt <= now + CLOCK_SKEW_MS && issuedAt < expiresAt) {
                "ID token issue time is invalid"
            }
            require(claims.notBeforeTime?.time?.let { it <= now + CLOCK_SKEW_MS } != false) {
                "ID token is not yet valid"
            }
            if (nonce != null) {
                require(nonce.isNotBlank() && constantTimeEquals(nonce, claims.getStringClaim("nonce"))) {
                    "ID token nonce is invalid"
                }
            } else {
                // OIDC refresh tokens may omit nonce. If present, it must match the original
                // authorization; a refreshed token must not silently replace the saved nonce.
                val refreshedNonce = claims.getStringClaim("nonce")
                require(
                    refreshedNonce == null ||
                        (!expectedNonce.isNullOrBlank() && constantTimeEquals(expectedNonce, refreshedNonce))
                ) { "Refreshed ID token nonce is invalid" }
            }
            val subject = claims.subject?.takeIf { it.isNotBlank() }
                ?: error("ID token has no subject")
            require(expectedSubject == null || constantTimeEquals(expectedSubject, subject)) {
                "ChatGPT account changed; credentials were not replaced"
            }
            val sid = claims.getStringClaim("sid")
            require(sid == null || sid.isNotBlank()) { "ID token session identifier is invalid" }
            val auth = claims.getJSONObjectClaim("https://api.openai.com/auth")
            val profile = claims.getJSONObjectClaim("https://api.openai.com/profile")
            return ChatGPTVerifiedIdentity(
                issuer = ISSUER,
                subject = subject,
                email = claims.getStringClaim("email")?.takeIf { it.isNotBlank() }
                    ?: (profile?.get("email") as? String)?.takeIf { it.isNotBlank() },
                accountId = (auth?.get("chatgpt_account_id") as? String)?.takeIf { it.isNotBlank() },
                plan = (auth?.get("chatgpt_plan_type") as? String)?.takeIf { it.isNotBlank() },
                sessionId = sid,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChatGPTSigningKeysUnavailableException) {
            throw e
        } catch (_: Exception) {
            // Library parse exceptions may embed rejected claims. Never expose those in UI or logs.
            throw IllegalStateException("ChatGPT ID token verification failed; please sign in again")
        }
    }

    private suspend fun findKey(kid: String): RSAKey = keyLock.withLock {
        val now = nowMillis()
        val cached = cache
        if (cached != null && now >= cached.fetchedAt && now - cached.fetchedAt < KEY_CACHE_MS) {
            selectKey(cached.keys, kid)?.let { return@withLock it }
        }
        // Refresh once when an unfamiliar kid appears, including while the cached set is fresh.
        val request = Request.Builder().url(JWKS_ENDPOINT).header("Accept", "application/json").build()
        val keys = try {
            client.newCall(request).await().use { response ->
                // This is a public, fixed JWKS URL. HTTP errors (including 429/5xx), truncated
                // bodies and invalid service responses say nothing about the account's validity.
                check(response.isSuccessful) { "Unable to retrieve OpenAI signing keys" }
                val source = response.body.source()
                source.request(MAX_JWKS_BYTES + 1)
                require(source.buffer.size <= MAX_JWKS_BYTES) { "OpenAI signing key response is too large" }
                val bytes = source.readByteArray()
                JWKSet.parse(bytes.toString(Charsets.UTF_8))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Avoid retaining transport messages or malformed server content in the exception chain.
            throw ChatGPTSigningKeysUnavailableException()
        }
        cache = CachedKeys(keys, now)
        selectKey(keys, kid) ?: error("OpenAI signing key is unavailable")
    }

    private fun selectKey(keys: JWKSet, kid: String): RSAKey? = keys.keys
        .filterIsInstance<RSAKey>()
        .filter { key ->
            key.keyID == kid &&
                (key.algorithm == null || key.algorithm == JWSAlgorithm.RS256) &&
                (key.keyUse == null || key.keyUse == KeyUse.SIGNATURE) &&
                (key.keyOperations == null || KeyOperation.VERIFY in key.keyOperations) &&
                !key.isPrivate && key.size() >= 2048
        }
        .singleOrNull()

    private fun constantTimeEquals(expected: String, actual: String?): Boolean =
        actual != null && MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())

    companion object {
        const val ISSUER = "https://auth.openai.com"
        private const val JWKS_ENDPOINT = "$ISSUER/.well-known/jwks.json"
        private const val CLOCK_SKEW_MS = 5_000L
        private const val KEY_CACHE_MS = 60 * 60_000L
        private const val MAX_TOKEN_LENGTH = 65_536
        private const val MAX_JWKS_BYTES = 1_048_576L
    }
}
