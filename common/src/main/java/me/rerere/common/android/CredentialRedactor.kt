package me.rerere.common.android

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Request
import java.util.Base64

/** Only call on diagnostic copies. Never use the result to construct a real model request. */
object CredentialRedactor {
    const val REDACTED = "[REDACTED]"
    const val MAX_LOG_CHARS = 65_536

    val sensitiveHeaders = setOf(
        "Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie", "X-Api-Key", "Api-Key",
        "x-goog-api-key", "X-Auth-Token", "X-Access-Token", "X-Amz-Security-Token",
        "Ocp-Apim-Subscription-Key", "X-XAI-Token-Auth", "X-Subscription-Token",
    )
    private val credentialFields = setOf(
        "apikey", "accesstoken", "refreshtoken", "idtoken", "idtokenhint", "codeverifier",
        "clientsecret", "assertion", "password", "privatekey", "devicecode", "usercode",
    )
    private val contextualFields = setOf("key", "token", "code")
    private val authPaths = setOf("oauth", "oauth2", "authorize", "authorization", "token", "revoke", "register", "registration", "callback", "login")
    private val json = Json { isLenient = true }
    private val fieldNames = "api[_-]?key|access[_-]?token|refresh[_-]?token|id[_-]?token(?:[_-]?hint)?|code[_-]?verifier|client[_-]?secret|assertion|password|private[_-]?key|device[_-]?code|user[_-]?code"
    private val credentialAssignment = Regex("(?i)([\"']?(?:$fieldNames)[\"']?\\s*[:=]\\s*)(\"(?:\\\\.|[^\"\\\\])*\"|'[^']*'|[^\\s,;&}]+)")
    private val queryAssignment = Regex("(?i)([?&](?:$fieldNames|key|token|code)=)[^&#\\s\"']*")
    private val headerLine = Regex("(?im)([\"']?\\b(?:authorization|proxy-authorization|cookie|set-cookie|x-goog-api-key|x-api-key|api-key|x-auth-token|x-access-token|x-amz-security-token|ocp-apim-subscription-key|x-xai-token-auth|x-subscription-token|[a-z0-9-]+-(?:api-key|auth-token|secret))[\"']?\\s*[:=]\\s*)[^\\r\\n]+")
    private val contextualAssignment = Regex("(?i)([\"']?(?:key|token|code)[\"']?\\s*[:=]\\s*)(\"(?:\\\\.|[^\"\\\\])*\"|'[^']*'|[^\\s,;&}]+)")
    private val authenticationMarker = Regex("(?i)[\"']?(?:grant[_-]?type|code[_-]?verifier|client[_-]?secret)[\"']?\\s*[:=]")
    private val bearer = Regex("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9_~+/.=-]+")
    private val privateKey = Regex("-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----[\\s\\S]*?(?:-----END (?:[A-Z]+ )?PRIVATE KEY-----|$)")
    private val recognizableKey = Regex("\\b(?:sk-[A-Za-z0-9_-]{8,}|AIza[A-Za-z0-9_-]{12,}|eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)")
    private val urlUserInfo = Regex("(?i)(https?://)[^\\s/@]+@")

    private fun normalized(name: String) = name.lowercase().replace("_", "").replace("-", "")

    fun isSensitiveHeader(name: String): Boolean = sensitiveHeaders.any { it.equals(name, true) } ||
        name.lowercase().let { it.endsWith("-api-key") || it.endsWith("-auth-token") || it.endsWith("-secret") }

    fun headers(headers: Headers): Map<String, String> = headers.names().associateWith { name ->
        if (isSensitiveHeader(name)) REDACTED else text(headers[name].orEmpty())
    }

    fun headers(headers: Map<String, String>): Map<String, String> = headers.mapValues { (name, value) ->
        if (isSensitiveHeader(name)) REDACTED else text(value)
    }

    fun url(url: HttpUrl): String = url.newBuilder().apply {
        if (url.username.isNotEmpty()) username(REDACTED)
        if (url.password.isNotEmpty()) password(REDACTED)
        url.queryParameterNames.filter { normalized(it) in credentialFields + contextualFields }.forEach {
            setQueryParameter(it, REDACTED)
        }
    }.build().toString()

    /** Authentication payloads are excluded before reading or serializing any request body. */
    fun omit(request: Request): Boolean {
        if (request.url.host == "auth.openai.com" || request.url.pathSegments.any { it.lowercase() in authPaths }) return true
        if (request.url.queryParameterNames.any { normalized(it) in credentialFields - "apikey" + setOf("token", "code") }) return true
        val form = request.body as? FormBody ?: return false
        return (0 until form.size).any { normalized(form.name(it)) in credentialFields + contextualFields + "granttype" }
    }

    /** Per-call values also remove server/exception echoes which have lost their field names. */
    fun secrets(request: Request): List<String> = buildList {
        if (request.url.password.isNotEmpty()) add(request.url.password)
        request.headers.forEach { (name, value) ->
            if (isSensitiveHeader(name)) {
                add(value)
                if (name.equals("Cookie", true) && value.length <= 4096) {
                    // Never silently drop a late credential. The complete bounded list is checked by the scope.
                    value.split(';').forEach { cookie ->
                        cookie.substringAfter('=', "").trim().removeSurrounding("\"")
                            .takeIf { it.isNotEmpty() }?.let(::add)
                    }
                }
                if (name.equals("Authorization", true) || name.equals("Proxy-Authorization", true)) {
                    add(value.substringAfter(' ', value))
                    if (value.startsWith("Basic ", true) && value.length <= 4096) {
                        runCatching { Base64.getDecoder().decode(value.substringAfter(' ')) }.getOrNull()?.let { decoded ->
                            // OkHttp defaults to ISO-8859-1; some providers explicitly request UTF-8.
                            listOf(Charsets.ISO_8859_1, Charsets.UTF_8).forEach { charset ->
                                String(decoded, charset).substringAfter(':', "").takeIf { it.isNotEmpty() }?.let(::add)
                            }
                        }
                    }
                }
            }
        }
        request.url.queryParameterNames.filter { normalized(it) in credentialFields + contextualFields }.forEach {
            addAll(request.url.queryParameterValues(it).filterNotNull())
        }
    }.filter { it.isNotBlank() && it != REDACTED }.distinct().sortedByDescending { it.length }

    private fun sanitizeJson(element: JsonElement, authentication: Boolean, depth: Int = 0): JsonElement {
        if (depth >= 64) return JsonPrimitive("[Diagnostic omitted: nesting exceeds 64 levels]")
        return when (element) {
            is JsonObject -> {
                val auth = authentication || element.keys.any { normalized(it) in setOf("granttype", "codeverifier", "clientsecret", "refreshtoken", "accesstoken", "idtoken", "tokenendpointauthmethod") }
                JsonObject(element.mapValues { (key, value) ->
                    if (isSensitiveHeader(key) || normalized(key) in credentialFields || (auth && normalized(key) in contextualFields)) JsonPrimitive(REDACTED)
                    else sanitizeJson(value, auth, depth + 1)
                })
            }
            is JsonArray -> JsonArray(element.map { sanitizeJson(it, authentication, depth + 1) })
            is JsonPrimitive -> if (element.isString) JsonPrimitive(textPatterns(element.contentOrNull.orEmpty())) else element
        }
    }

    private fun textPatterns(value: String): String = value
        .replace(privateKey, REDACTED)
        .replace(urlUserInfo) { "${it.groupValues[1]}$REDACTED@" }
        .replace(headerLine) { "${it.groupValues[1]}$REDACTED" }
        .replace(bearer) { "${it.groupValues[1]} $REDACTED" }
        .replace(queryAssignment) { "${it.groupValues[1]}$REDACTED" }
        .replace(credentialAssignment) { "${it.groupValues[1]}\"$REDACTED\"" }
        .replace(recognizableKey, REDACTED)

    fun text(value: String, secrets: List<String> = emptyList(), authentication: Boolean = false): String {
        // Fail closed for oversized copies: do not slice through a credential or parse huge image payloads.
        if (value.length > MAX_LOG_CHARS) return "[Diagnostic omitted: exceeds $MAX_LOG_CHARS characters]"
        return try {
            redactBounded(value, secrets, authentication)
        } catch (_: Exception) {
            "[Diagnostic omitted: redaction failed]"
        } catch (_: StackOverflowError) {
            "[Diagnostic omitted: redaction nesting limit]"
        }
    }

    private fun redactBounded(value: String, secrets: List<String>, authentication: Boolean): String {
        var safe = value
        secrets.forEach { safe = safe.replace(it, REDACTED) }
        val trimmed = safe.trimStart()
        val arrayValue = trimmed.drop(1).trimStart().firstOrNull()
        val appearsJson = trimmed.startsWith('{') ||
            (trimmed.startsWith('[') && arrayValue != null && arrayValue in "\"{[0123456789-]tfn")
        val parsed = if (appearsJson) {
            runCatching { json.parseToJsonElement(safe) }.getOrElse {
                return "[Diagnostic omitted: malformed or excessively nested JSON]"
            }
        } else null
        return if (parsed != null) sanitizeJson(parsed, authentication).toString() else {
            val result = textPatterns(safe)
            if (authentication || authenticationMarker.containsMatchIn(safe)) {
                result.replace(contextualAssignment) { "${it.groupValues[1]}\"$REDACTED\"" }
            } else result
        }
    }
}
