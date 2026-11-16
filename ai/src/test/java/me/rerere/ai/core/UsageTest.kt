package me.rerere.ai.core

import me.rerere.ai.util.json
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageTest {
    @Test
    fun `snapshot merge preserves absent fields but accepts explicitly reported cache zero`() {
        val first = TokenUsage(promptTokens = 2048, cachedTokens = 1024, cacheWriteTokens = 512)
        val missing = first.merge(TokenUsage(completionTokens = 30))
        assertEquals(2048, missing.promptTokens)
        assertEquals(1024, missing.cachedTokens)
        assertTrue(missing.cachedTokensReported)
        assertEquals(512, missing.cacheWriteTokens)

        val final = missing.merge(TokenUsage(promptTokens = 2048, cachedTokensReported = true, cacheWriteTokens = 0))
        assertEquals(0, final.cachedTokens)
        assertTrue(final.cachedTokensReported)
        assertEquals(0, final.cacheWriteTokens)
        assertEquals(30, final.completionTokens)
        assertEquals(2078, final.totalTokens)
    }

    @Test
    fun `reported zero input does not reuse a previous positive snapshot`() {
        val updated = TokenUsage(promptTokens = 2048).merge(TokenUsage(promptTokensReported = true))
        assertEquals(0, updated.promptTokens)
        assertTrue(updated.promptTokensReported)
    }

    @Test
    fun `cross request totals distinguish complete zero and partial cache statistics`() {
        val first = TokenUsage(promptTokens = 2048, cachedTokens = 1024, cacheWriteTokens = 32)
        val complete = first.sum(TokenUsage(promptTokens = 3072, cachedTokensReported = true, cacheWriteTokens = 0))!!
        assertEquals(5120, complete.promptTokens)
        assertEquals(1024, complete.cachedTokens)
        assertTrue(complete.promptTokensReported)
        assertTrue(complete.cachedTokensReported)
        assertEquals(32, complete.cacheWriteTokens)

        val partial = first.sum(TokenUsage(promptTokens = 3072))!!
        assertEquals(1024, partial.cachedTokens)
        assertFalse(partial.cachedTokensReported)
        assertNull(partial.cacheWriteTokens)
        assertFalse(first.sum(TokenUsage())!!.promptTokensReported)
    }

    @Test
    fun `unknown latest context is not exposed as a definite zero or an earlier request`() {
        val message = UIMessage.user("synthetic").copy(
            usage = TokenUsage(promptTokens = 4096, cachedTokens = 2048),
            lastUsage = TokenUsage(),
        )
        assertNull(message.contextTokens())
        assertEquals(0, message.copy(lastUsage = TokenUsage(promptTokensReported = true)).contextTokens())
    }

    @Test
    fun `legacy stored zero remains unknown and explicit new zero survives reload`() {
        val legacy = json.decodeFromString<TokenUsage>("""{"promptTokens":2048,"cachedTokens":0}""")
        assertTrue(legacy.promptTokensReported)
        assertFalse(legacy.cachedTokensReported)
        assertNull(legacy.cacheWriteTokens)
        val positive = json.decodeFromString<TokenUsage>("""{"promptTokens":2048,"cachedTokens":1024}""")
        assertTrue(positive.cachedTokensReported)
        val explicit = legacy.copy(cachedTokensReported = true, cacheWriteTokens = 0)
        assertEquals(explicit, json.decodeFromString<TokenUsage>(json.encodeToString(explicit)))
    }
}
