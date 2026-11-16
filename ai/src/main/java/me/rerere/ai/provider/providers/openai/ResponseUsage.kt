package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import me.rerere.ai.core.TokenUsage
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull

/** Shared by streamed terminal events and complete Responses JSON. Missing is not zero. */
internal fun parseResponsesUsage(usage: JsonObject?): TokenUsage? {
    if (usage == null) return null
    fun JsonObject.count(key: String) = get(key)?.jsonPrimitiveOrNull?.intOrNull?.takeIf { it >= 0 }
    val input = usage.count("input_tokens")
    val details = usage["input_tokens_details"]?.jsonObjectOrNull
    val cached = details?.count("cached_tokens")
    val written = details?.count("cache_write_tokens") ?: usage.count("cache_write_tokens")
    return TokenUsage(
        promptTokens = input ?: 0,
        completionTokens = usage.count("output_tokens") ?: 0,
        totalTokens = usage.count("total_tokens") ?: 0,
        cachedTokens = cached ?: 0,
        promptTokensReported = input != null,
        cachedTokensReported = cached != null,
        cacheWriteTokens = written,
    )
}

/** Preserve cache-read field presence for the existing API-key dialects as well. */
internal fun parseChatCompletionsUsage(usage: JsonObject?): TokenUsage? {
    if (usage == null) return null
    fun JsonObject.count(key: String) = get(key)?.jsonPrimitiveOrNull?.intOrNull?.takeIf { it >= 0 }
    val input = usage.count("prompt_tokens")
    val cached = usage["prompt_tokens_details"]?.jsonObjectOrNull?.count("cached_tokens")
        ?: usage.count("cached_tokens")
        ?: usage.count("prompt_cache_hit_tokens")
    return TokenUsage(
        promptTokens = input ?: 0,
        completionTokens = usage.count("completion_tokens") ?: 0,
        totalTokens = usage.count("total_tokens") ?: 0,
        cachedTokens = cached ?: 0,
        promptTokensReported = input != null,
        cachedTokensReported = cached != null,
    )
}
