package me.rerere.ai.provider.providers.chatgpt

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.OAuthCredential
import okhttp3.Request
import java.security.MessageDigest

/** Stable across credential rotation/process restarts, isolated by OIDC account and saved conversation. */
internal fun chatGPTSessionAffinity(credential: OAuthCredential, sessionId: String?): String? {
    if (sessionId.isNullOrBlank()) return null
    val identity = JsonArray(listOf(
        "rikkahub.siwc.session.v1", credential.issuer.orEmpty(), credential.subject.orEmpty(), sessionId,
    ).map(::JsonPrimitive)).toString()
    return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

/** session_id is an SIWC-managed affinity field, not the former Codex session-id header. */
internal fun Request.Builder.configureChatGPTSessionAffinity(
    credential: OAuthCredential,
    sessionId: String?,
    configuredValues: List<String>,
) {
    val automatic = chatGPTSessionAffinity(credential, sessionId)
    require(configuredValues.all { it == automatic }) {
        "ChatGPT 的 session_id 是应用按账号和会话管理的请求头，不能由高级自定义请求覆盖。请移除提供商、助手或模型中的 session_id 设置；应用会自动保持同一会话稳定，并隔离不同账号与会话。"
    }
    removeHeader("session_id")
    automatic?.let { header("session_id", it) }
}
