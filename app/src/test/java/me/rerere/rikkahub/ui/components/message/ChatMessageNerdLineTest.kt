package me.rerere.rikkahub.ui.components.message

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.sum
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMessageNerdLineTest {
    private val previous = TokenUsage(promptTokens = 50_000, cachedTokens = 40_000, completionTokens = 100)

    private fun nextStep() = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = emptyList(),
        usage = previous,
        lastUsage = TokenUsage(),
        stepCount = 2,
        // A previous tool step already finished; this timestamp cannot identify live generation.
        finishedAt = LocalDateTime(2026, 11, 17, 1, 0),
    )

    @Test
    fun `next tool step stays pending until its own usage arrives without borrowing previous counts`() {
        val streaming = nextStep()
        val pending = streaming.nerdStats(previous, generating = true)
        assertEquals(UsageValueStatus.Pending, pending.contextStatus)
        assertEquals(UsageValueStatus.Pending, pending.cacheReadStatus)
        assertEquals(0, pending.contextTokens)
        assertEquals(0, pending.lastUsage.cachedTokens)
        assertEquals(previous, pending.total)

        val terminal = TokenUsage(promptTokens = 52_000, cachedTokens = 49_000, completionTokens = 200)
        val finished = streaming.copy(lastUsage = terminal, usage = previous.sum(terminal))
        // The terminal usage chunk may render before the surrounding generation job stops.
        for (generating in listOf(true, false)) {
            val reported = finished.nerdStats(finished.usage!!, generating)
            assertEquals(UsageValueStatus.Reported, reported.contextStatus)
            assertEquals(UsageValueStatus.Reported, reported.cacheReadStatus)
            assertEquals(52_200, reported.contextTokens)
            assertEquals(49_000, reported.lastUsage.cachedTokens)
        }
    }

    @Test
    fun `stopped failed or reloaded message with no usage is unknown rather than forever pending`() {
        val streaming = nextStep()
        val stopped = streaming.nerdStats(previous, generating = false)
        assertEquals(UsageValueStatus.Unknown, stopped.contextStatus)
        assertEquals(UsageValueStatus.Unknown, stopped.cacheReadStatus)
        assertEquals(0, stopped.contextTokens)

        val restored = Json.decodeFromString<UIMessage>(Json.encodeToString(streaming))
        val reloaded = restored.nerdStats(restored.usage!!)
        assertEquals(UsageValueStatus.Unknown, reloaded.contextStatus)
        assertEquals(UsageValueStatus.Unknown, reloaded.cacheReadStatus)
    }

    @Test
    fun `explicit zero is reported during generation and after completion`() {
        val zero = TokenUsage(promptTokensReported = true, cachedTokensReported = true, cacheWriteTokens = 0)
        val message = nextStep().copy(lastUsage = zero)
        for (generating in listOf(true, false)) {
            val stats = message.nerdStats(previous, generating)
            assertEquals(UsageValueStatus.Reported, stats.contextStatus)
            assertEquals(UsageValueStatus.Reported, stats.cacheReadStatus)
            assertEquals(0, stats.contextTokens)
            assertEquals(0, stats.lastUsage.cachedTokens)
            assertEquals(0, stats.lastUsage.cacheWriteTokens)
        }
    }

    @Test
    fun `partially reported current usage keeps real context while cache waits independently`() {
        val message = nextStep().copy(lastUsage = TokenUsage(promptTokens = 500, completionTokens = 50))
        val active = message.nerdStats(previous, generating = true)
        assertEquals(UsageValueStatus.Reported, active.contextStatus)
        assertEquals(550, active.contextTokens)
        assertEquals(UsageValueStatus.Pending, active.cacheReadStatus)

        val ended = message.nerdStats(previous, generating = false)
        assertEquals(UsageValueStatus.Reported, ended.contextStatus)
        assertEquals(UsageValueStatus.Unknown, ended.cacheReadStatus)
    }

    @Test
    fun `legacy known usage and latest known usage of a partial total keep their existing values`() {
        val legacy = nextStep().copy(lastUsage = null)
        val legacyStats = legacy.nerdStats(previous)
        assertEquals(UsageValueStatus.Reported, legacyStats.contextStatus)
        assertEquals(50_100, legacyStats.contextTokens)
        assertEquals(40_000, legacyStats.lastUsage.cachedTokens)

        val partialTotal = previous.copy(promptTokensReported = false, cachedTokensReported = false)
        val current = nextStep().copy(lastUsage = previous, usage = partialTotal)
        val stats = current.nerdStats(partialTotal, generating = true)
        assertEquals(UsageValueStatus.Reported, stats.contextStatus)
        assertEquals(UsageValueStatus.Reported, stats.cacheReadStatus)
        assertEquals(50_100, stats.contextTokens)
        assertEquals(partialTotal, stats.total)
    }
}
