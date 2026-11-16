package me.rerere.rikkahub.data.ai.account

import me.rerere.ai.provider.ChatGPTRegistration
import me.rerere.ai.provider.OAuthCredential
import me.rerere.oauth.OAuthCallback
import me.rerere.oauth.OAuthHttpClient

/** OpenAI's public Sign in with ChatGPT flow for locally running open-source applications. */
internal object ChatGPTAuth {
    const val ISSUER = "https://auth.openai.com"
    const val INITIAL_CLIENT_ID = "dynamic_agent_client"
    const val TOKEN_ENDPOINT = "$ISSUER/api/accounts/oauth/token"
    const val RESOURCE = "https://api.openai.com/v1"
    const val DISCOVERY_ENDPOINT = "$ISSUER/.well-known/openid-configuration"
    const val CALLBACK_PORT = 0
    const val CALLBACK_PATH = "/auth/callback"
    const val CALLBACK_HOST = "127.0.0.1"
    const val SCOPE = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    val TERMINAL_REFRESH_ERRORS = setOf(
        "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
        "refresh_token_invalidated", "refresh_token_reused",
    )

    fun authorizationRequest(
        pkce: OAuthHttpClient.Pkce,
        state: String,
        nonce: String,
        redirectUri: String,
        hostId: String,
        registration: ChatGPTRegistration? = null,
        previous: OAuthCredential? = null,
        requestPlanConsent: Boolean = false,
    ) = OAuthHttpClient.AuthorizationRequest(
        authorizationEndpoint = "$ISSUER/api/accounts/authorize",
        clientId = registration?.clientId ?: INITIAL_CLIENT_ID,
        redirectUri = redirectUri,
        pkce = pkce,
        state = state,
        scope = SCOPE,
        resources = listOf(RESOURCE),
        additionalParameters = buildMap {
            put("ext_agent_host_id", hostId)
            put("nonce", nonce)
            if (registration == null) put("agent_name_hint", "RikkaHub")
            if (registration != null) {
                previous?.takeIf { it.isOfficialChatGPT && it.clientId == registration.clientId }?.idToken
                    ?.let { put("id_token_hint", it) }
                registration.email?.let { put("login_hint", it) }
            }
            if (requestPlanConsent) put("prompt", "consent")
        },
    )

    fun callbackClientId(callback: OAuthCallback, expectedState: String, registration: ChatGPTRegistration?): String {
        require(callback.state == expectedState) { "ChatGPT authorization state did not match" }
        if (callback.error != null) {
            error(if (callback.error == "access_denied") "ChatGPT authorization was declined" else "ChatGPT authorization failed")
        }
        require(!callback.code.isNullOrBlank()) { "No authorization code returned" }
        val issued = callback.clientId ?: registration?.clientId
        require(!issued.isNullOrBlank() && issued != INITIAL_CLIENT_ID && issued.matches(Regex("[A-Za-z0-9_-]{1,200}"))) {
            "ChatGPT registration did not return an issued client ID"
        }
        require(registration == null || issued == registration.clientId) { "ChatGPT registration changed during authorization" }
        return issued
    }

    fun codeRequest(clientId: String, code: String, verifier: String, redirectUri: String) =
        OAuthHttpClient.AuthorizationCodeTokenRequest(
            tokenEndpoint = TOKEN_ENDPOINT, clientId = clientId, code = code,
            codeVerifier = verifier, redirectUri = redirectUri, resources = listOf(RESOURCE),
        )

    fun refreshRequest(clientId: String, refreshToken: String) = OAuthHttpClient.RefreshTokenRequest(
        tokenEndpoint = TOKEN_ENDPOINT,
        clientId = clientId,
        refreshToken = refreshToken,
        resources = listOf(RESOURCE),
    )

    /** Only verified identity reaches this function. Never derive authorization from an access-token payload. */
    fun toCredential(
        token: OAuthHttpClient.TokenResponse,
        clientId: String,
        identity: ChatGPTVerifiedIdentity,
        previous: OAuthCredential? = null,
        now: Long = System.currentTimeMillis(),
        nonce: String? = previous?.idTokenNonce,
    ): OAuthCredential {
        require(token.accessToken.isNotBlank() && token.tokenType.equals("Bearer", ignoreCase = true)) {
            "ChatGPT returned an unsupported token response"
        }
        val expiresIn = token.expiresIn
        require(expiresIn != null && expiresIn > 0 && expiresIn <= 31_536_000L) {
            "ChatGPT token response has no valid expiry"
        }
        require(previous == null || (previous.isOfficialChatGPT && previous.clientId == clientId &&
            previous.issuer == identity.issuer && previous.subject == identity.subject)) { "ChatGPT identity changed" }
        val scopes = token.scope?.split(Regex("\\s+"))?.filter(String::isNotBlank)?.toSet()
            ?: previous?.grantedScopes.orEmpty()
        return OAuthCredential(
            accessToken = token.accessToken,
            refreshToken = token.refreshToken ?: previous?.refreshToken,
            expiresAt = now + expiresIn * 1000,
            accountId = identity.accountId,
            email = identity.email,
            plan = identity.plan,
            protocolVersion = 1,
            clientId = clientId,
            issuer = identity.issuer,
            subject = identity.subject,
            idToken = token.idToken ?: previous?.idToken,
            idTokenNonce = nonce,
            grantedScopes = scopes,
            tokenType = token.tokenType,
            savedAt = now,
        )
    }

    fun registration(credential: OAuthCredential): ChatGPTRegistration {
        require(credential.isOfficialChatGPT)
        return ChatGPTRegistration(credential.clientId!!, credential.issuer!!, credential.subject, credential.email)
    }

    fun planDisplayName(plan: String): String = when (plan.lowercase()) {
        "free" -> "Free"
        "go" -> "Go"
        "plus" -> "Plus"
        "pro" -> "Pro (More)"
        "prolite" -> "Pro"
        "promax" -> "Pro (Max)"
        "team" -> "Team"
        "self_serve_business_prolite" -> "Self Serve Business ProLite"
        "self_serve_business_usage_based" -> "Self Serve Business Usage Based"
        "business" -> "Business"
        "ent26", "enterprise", "hc" -> "Enterprise"
        "enterprise_cbp_automation" -> "Enterprise (Automation)"
        "enterprise_cbp_usage_based" -> "Enterprise CBP Usage Based"
        "edu", "education" -> "Edu"
        "edu_plus" -> "Edu Plus"
        "edu_pro" -> "Edu Pro"
        else -> plan.split('_').filter { it.isNotEmpty() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
    }
}
