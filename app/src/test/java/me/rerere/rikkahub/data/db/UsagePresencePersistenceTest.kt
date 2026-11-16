package me.rerere.rikkahub.data.db

import me.rerere.ai.core.TokenUsage
import me.rerere.rikkahub.data.db.dao.CacheUsageSummary
import me.rerere.rikkahub.ui.pages.stats.formatCacheHitRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsagePresencePersistenceTest {
    @Test
    fun `legacy message zero is unknown but new explicit zero survives database conversion`() {
        val old = TokenUsageConverter.toTokenUsage("""{"promptTokens":100,"cachedTokens":0}""")!!
        assertTrue(old.promptTokensReported)
        assertFalse(old.cachedTokensReported)
        assertNull(old.cacheWriteTokens)

        val current = TokenUsage(promptTokens = 100, cachedTokens = 0, cachedTokensReported = true, cacheWriteTokens = 0)
        assertEquals(current, TokenUsageConverter.toTokenUsage(TokenUsageConverter.fromTokenUsage(current)))
    }

    @Test
    fun `hit rate distinguishes unknown input empty scope and known zero`() {
        assertNull(CacheUsageSummary().hitRatePercent)
        assertNull(CacheUsageSummary(requestCount = 4, cacheReportedRequestCount = 1).hitRatePercent)
        val known = CacheUsageSummary(
            requestCount = 4, cacheReportedRequestCount = 1, cacheRateRequestCount = 1,
            cacheRatePromptTokens = 100, cacheRateCachedTokens = 0,
        )
        assertEquals(0.0, known.hitRatePercent!!, 0.0)
        assertTrue(known.cacheReadIsPartial)
        assertNull(known.copy(cacheRateCachedTokens = 101).hitRatePercent)
    }

    @Test
    fun `a positive tiny cache rate is never rendered as an exact zero`() {
        val summary = CacheUsageSummary(
            requestCount = 2, cacheReportedRequestCount = 2, cacheRateRequestCount = 2,
            cacheRatePromptTokens = 32_768, cacheRateCachedTokens = 1,
        )
        assertEquals("<0.1%", formatCacheHitRate(summary.hitRatePercent!!))
        assertFalse(summary.cacheReadIsPartial)
    }
}
