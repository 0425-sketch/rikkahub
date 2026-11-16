package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseUsageTest {
    private fun usage(value: String): TokenUsage = parseResponsesUsage(json.parseToJsonElement(value).jsonObject)!!

    @Test
    fun `missing null and invalid cache fields are unknown while explicit zero is known`() {
        for (details in listOf("{}", "{\"cached_tokens\":null}", "{\"cached_tokens\":-1}", "{\"cached_tokens\":{}}")) {
            val actual = usage("""{"input_tokens":2048,"input_tokens_details":$details}""")
            assertTrue(actual.promptTokensReported)
            assertFalse(actual.cachedTokensReported)
            assertEquals(0, actual.cachedTokens)
            assertNull(actual.cacheWriteTokens)
        }
        val zero = usage("""{"input_tokens":0,"input_tokens_details":{"cached_tokens":0,"cache_write_tokens":0}}""")
        assertTrue(zero.promptTokensReported)
        assertTrue(zero.cachedTokensReported)
        assertEquals(0, zero.cachedTokens)
        assertEquals(0, zero.cacheWriteTokens)
        assertFalse(usage("{}").promptTokensReported)
    }

    @Test
    fun `cache read and write counts retain actual response fields without estimating`() {
        val nested = usage("""{"input_tokens":4096,"output_tokens":30,"total_tokens":4126,"input_tokens_details":{"cached_tokens":3072,"cache_write_tokens":768}}""")
        assertEquals(4096, nested.promptTokens)
        assertEquals(3072, nested.cachedTokens)
        assertEquals(768, nested.cacheWriteTokens)
        assertEquals(4126, nested.totalTokens)
        assertEquals(1024, usage("""{"cache_write_tokens":1024}""").cacheWriteTokens)
        // Writes are not inferred from cache misses or TTL detail counters.
        assertNull(usage("""{"input_tokens":4096,"input_tokens_details":{"cached_tokens":0,"cache_write_tokens_details":{"ttl_12h_tokens":500}}}""").cacheWriteTokens)
    }

    @Test
    fun `SIWC terminal usage survives SSE handling and message persistence once`() {
        val decoder = ResponseApiStreamDecoder(requireCompletedResponse = true)
        val event = SseEvent(data = """{"type":"response.completed","response":{"id":"synthetic","status":"completed","usage":{"input_tokens":4096,"output_tokens":16,"total_tokens":4112,"input_tokens_details":{"cached_tokens":3072,"cache_write_tokens":128}}}}""")
        val result = decoder.accept(event)
        assertTrue(result.completed)
        val handler = StreamChunkHandler(Model(modelId = "synthetic-model"))
        val messages = result.chunks.fold(listOf(UIMessage.user("synthetic input"))) { current, chunk -> handler.handle(current, chunk) }
        val restored = json.decodeFromString<List<UIMessage>>(json.encodeToString(messages))
        val actual = restored.last().usage!!
        assertEquals(4096, actual.promptTokens)
        assertEquals(3072, actual.cachedTokens)
        assertEquals(128, actual.cacheWriteTokens)
        assertTrue(actual.cachedTokensReported)
        assertEquals(1, result.chunks.filterIsInstance<StreamChunk.Usage>().size)
        assertTrue(decoder.accept(event).chunks.isEmpty())
        assertTrue(decoder.onClosed().isEmpty())
    }

    @Test
    fun `SIWC terminal missing whole usage emits unknown without changing generic Responses absence`() {
        val event = SseEvent(data = """{"type":"response.completed","response":{"status":"completed"}}""")
        val explicitUnknown = ResponseApiStreamDecoder(requireCompletedResponse = true).accept(event).chunks.filterIsInstance<StreamChunk.Usage>().single().usage
        assertFalse(explicitUnknown.promptTokensReported)
        assertFalse(explicitUnknown.cachedTokensReported)
        assertNull(explicitUnknown.cacheWriteTokens)
        assertTrue(ResponseApiStreamDecoder().accept(event).chunks.filterIsInstance<StreamChunk.Usage>().isEmpty())
    }

    @Test
    fun `API key cache dialects retain explicit zero presence and existing precedence`() {
        for (raw in listOf("{\"prompt_tokens_details\":{\"cached_tokens\":0}}", "{\"cached_tokens\":0}", "{\"prompt_cache_hit_tokens\":0}")) {
            val actual = parseChatCompletionsUsage(json.parseToJsonElement(raw).jsonObject)!!
            assertEquals(0, actual.cachedTokens)
            assertTrue(actual.cachedTokensReported)
        }
        assertFalse(parseChatCompletionsUsage(JsonObject(emptyMap()))!!.cachedTokensReported)
        assertEquals(0, parseChatCompletionsUsage(json.parseToJsonElement("""{"prompt_tokens_details":{"cached_tokens":0},"cached_tokens":100}""").jsonObject)!!.cachedTokens)
    }
}
