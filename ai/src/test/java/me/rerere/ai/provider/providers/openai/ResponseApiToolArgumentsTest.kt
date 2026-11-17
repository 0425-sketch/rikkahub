package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.StreamChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseApiToolArgumentsTest {
    @Test
    fun `large parallel tool inputs preserve every delta and validate both terminal events`() {
        val decoder = decoder()
        val inputs = listOf(
            "{\"text\":\"${"abc你好🌏".repeat(32_768)}\"}",
            "{\"text\":\"${"second\\n".repeat(32_768)}\"}",
        )
        val emitted = List(inputs.size) { StringBuilder() }
        val chunks = inputs.map { it.chunked(127) }
        inputs.indices.forEach { decoder.accept(item("response.output_item.added", it, "")) }

        repeat(chunks.maxOf { it.size }) { index ->
            chunks.indices.forEach { tool ->
                chunks[tool].getOrNull(index)?.let { delta ->
                    val result = decoder.accept(arguments("response.function_call_arguments.delta", tool, delta))
                    assertFalse(result.completed)
                    result.chunks.filterIsInstance<StreamChunk.ToolCallDelta>().forEach {
                        assertEquals("call_$tool", it.id)
                        emitted[tool].append(it.inputDelta)
                    }
                }
            }
        }
        inputs.indices.reversed().forEach { tool ->
            for (event in listOf(
                arguments("response.function_call_arguments.done", tool, inputs[tool]),
                item("response.output_item.done", tool, inputs[tool]),
            )) {
                val result = decoder.accept(event)
                assertFalse(result.completed)
                assertTrue(result.chunks.filterIsInstance<StreamChunk.ToolCallDelta>().all { it.inputDelta.isEmpty() })
            }
            assertEquals(inputs[tool], emitted[tool].toString())
        }
        assertEquals(1, decoder.accept(completed()).chunks.filterIsInstance<StreamChunk.Finish>().size)
        assertTrue(decoder.onClosed().isEmpty())
    }

    @Test
    fun `terminal snapshots can supply missing suffix or use accumulated input without duplication`() {
        for (doneType in listOf("response.function_call_arguments.done", "response.output_item.done")) {
            for (includeSnapshot in listOf(false, true)) {
                val decoder = decoder()
                val full = "{\"value\":\"你好🌏\"}"
                val prefix = if (includeSnapshot) full.take(10) else full
                decoder.accept(item("response.output_item.added", 0, prefix))
                val result = decoder.accept(
                    if (doneType == "response.output_item.done") {
                        item(doneType, 0, full.takeIf { includeSnapshot })
                    } else {
                        arguments(doneType, 0, full.takeIf { includeSnapshot })
                    },
                )
                assertEquals(
                    full.removePrefix(prefix),
                    result.chunks.filterIsInstance<StreamChunk.ToolCallDelta>().joinToString("") { it.inputDelta },
                )
                assertTrue(decoder.accept(completed()).completed)
            }
        }
    }

    @Test
    fun `conflicting and invalid snapshots cannot finish or execute a tool`() {
        for (invalid in listOf("{\"value\":2}", "{\"value\":1", "[]", "")) {
            val decoder = decoder()
            decoder.accept(item("response.output_item.added", 0, "{\"value\":1"))
            assertThrows(Exception::class.java) {
                decoder.accept(arguments("response.function_call_arguments.done", 0, invalid))
            }
            assertTrue(decoder.onClosed().isEmpty())
            assertTrue(decoder.accept(completed()).chunks.isEmpty())
        }
    }

    @Test
    fun `one finished parallel tool cannot validate another partial call`() {
        val decoder = decoder()
        decoder.accept(item("response.output_item.added", 0, "{}"))
        decoder.accept(item("response.output_item.added", 1, "{\"partial\":"))
        decoder.accept(arguments("response.function_call_arguments.done", 0, "{}"))
        assertThrows(Exception::class.java) { decoder.accept(completed()) }
        assertTrue(decoder.onClosed().isEmpty())
    }

    @Test
    fun `delta after verification requires a new valid terminal event`() {
        val decoder = decoder()
        decoder.accept(item("response.output_item.added", 0, "{}"))
        decoder.accept(arguments("response.function_call_arguments.done", 0, "{}"))
        decoder.accept(arguments("response.function_call_arguments.delta", 0, " "))
        assertThrows(Exception::class.java) { decoder.accept(completed()) }
        assertTrue(decoder.onClosed().isEmpty())
    }

    @Test
    fun `only a validated terminal result exposes its already parsed response metadata`() {
        val decoder = decoder()
        assertEquals(null, decoder.accept(item("response.output_item.added", 0, "{}")).completedResponse)
        assertEquals(null, decoder.accept(arguments("response.function_call_arguments.done", 0, "{}")).completedResponse)
        val terminal = decoder.accept(completed())
        assertTrue(terminal.completed)
        assertEquals(buildJsonObject { put("status", "completed") }, terminal.completedResponse)
        assertEquals(null, decoder.accept(SseEvent(data = "[DONE]")).completedResponse)
    }

    @Test
    fun `disconnect stop and error do not turn partial arguments into a successful finish`() {
        for (termination in listOf("close", "done", "failed", "incomplete")) {
            val decoder = decoder()
            decoder.accept(item("response.output_item.added", 0, "{\"text\":\"${"x".repeat(128 * 1024)}"))
            assertThrows(Exception::class.java) {
                when (termination) {
                    "close" -> decoder.onClosed()
                    "done" -> decoder.accept(SseEvent(data = "[DONE]"))
                    else -> decoder.accept(SseEvent(data = """{"type":"response.$termination","response":{"status":"$termination"}}"""))
                }
            }
            assertTrue(decoder.onClosed().isEmpty())
        }
    }

    private fun decoder() = ResponseApiStreamDecoder(requireCompletedResponse = true, functionToolNamespace = "rikkahub")

    private fun completed() = SseEvent(data = """{"type":"response.completed","response":{"status":"completed"}}""")

    private fun item(type: String, tool: Int, input: String?) = SseEvent(data = buildJsonObject {
        put("type", type)
        put("output_index", tool)
        put("item", buildJsonObject {
            put("type", "function_call")
            put("id", "fc_$tool")
            put("call_id", "call_$tool")
            put("namespace", "rikkahub")
            put("name", "tool_$tool")
            input?.let { put("arguments", it) }
        })
    }.toString())

    private fun arguments(type: String, tool: Int, input: String?) = SseEvent(data = buildJsonObject {
        put("type", type)
        put("item_id", "fc_$tool")
        input?.let { put(if (type.endsWith(".delta")) "delta" else "arguments", it) }
    }.toString())
}
