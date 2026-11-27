package me.rerere.ai.core

import kotlinx.serialization.Serializable

@Serializable
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0,
    val totalTokens: Int = 0,
    // Older persisted zero values cannot tell an omitted field from a reported zero.
    // Positive legacy counts are still usable; new parsers preserve explicit zero presence.
    val promptTokensReported: Boolean = promptTokens > 0,
    val cachedTokensReported: Boolean = cachedTokens > 0,
    // Keep absent cache-write accounting unknown, without estimating from input or cache misses.
    val cacheWriteTokens: Int? = null,
    val completionTokensReported: Boolean = completionTokens > 0,
)

/**
 * 合并同一次请求内的 usage 快照。已明确汇报的输入/缓存零值也能覆盖旧快照；
 * 缺失字段保留已有值。跨请求（如工具调用各步）累计请用 [sum]。
 */
fun TokenUsage?.merge(other: TokenUsage): TokenUsage {
    val promptTokens = if (other.promptTokensReported) other.promptTokens else this?.promptTokens ?: 0
    val completionTokens = if (other.completionTokensReported) other.completionTokens else this?.completionTokens ?: 0
    val cachedTokens = if (other.cachedTokensReported) other.cachedTokens else this?.cachedTokens ?: 0
    return TokenUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = promptTokens + completionTokens,
        cachedTokens = cachedTokens,
        promptTokensReported = other.promptTokensReported || this?.promptTokensReported == true,
        completionTokensReported = other.completionTokensReported || this?.completionTokensReported == true,
        cachedTokensReported = other.cachedTokensReported || this?.cachedTokensReported == true,
        cacheWriteTokens = other.cacheWriteTokens ?: this?.cacheWriteTokens,
    )
}

/**
 * 累加多次请求的 usage，每次请求都会按完整上下文计费，因此 prompt 也直接相加。
 * 任一步缺少输入/缓存统计时，合计只能作为已知部分，不能据此计算全轮命中率。
 */
fun TokenUsage?.sum(other: TokenUsage?): TokenUsage? {
    if (this == null) return other
    if (other == null) return this
    return TokenUsage(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        cachedTokens = cachedTokens + other.cachedTokens,
        totalTokens = totalTokens + other.totalTokens,
        promptTokensReported = promptTokensReported && other.promptTokensReported,
        completionTokensReported = completionTokensReported && other.completionTokensReported,
        cachedTokensReported = cachedTokensReported && other.cachedTokensReported,
        cacheWriteTokens = if (cacheWriteTokens != null && other.cacheWriteTokens != null) {
            cacheWriteTokens + other.cacheWriteTokens
        } else null,
    )
}
