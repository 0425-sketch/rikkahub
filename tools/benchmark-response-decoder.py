#!/usr/bin/env python3
"""Compare the actual old/new Responses decoders with identical prebuilt SSE events.

Run after loading the normal Android/JDK environment. The baseline is read from
Git and compiled under a temporary class name; tracked sources are never replaced.
The benchmark excludes UI immutable-state updates and event fixture construction.
"""

import argparse
import hashlib
import json
import pathlib
import re
import statistics
import subprocess
import tempfile


ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = "ai/src/main/java/me/rerere/ai/provider/providers/openai/ResponseApiStreamDecoder.kt"
BASELINE = "929c17c88f349dd9b8b08f81e87b4bab35457041"

BENCHMARK = r'''
package me.rerere.ai.provider.providers.openai

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.stream.StreamChunkDecoder
import me.rerere.ai.ui.StreamChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseDecoderBenchmark {
    @Test
    fun compareValidationAccumulators() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        check(bean.isThreadAllocatedMemorySupported && bean.isCurrentThreadCpuTimeSupported)
        bean.isThreadAllocatedMemoryEnabled = true
        bean.isThreadCpuTimeEnabled = true
        println("STREAM_BENCH environment java=${System.getProperty("java.version")} vm=${System.getProperty("java.vm.name")} os=${System.getProperty("os.name")} arch=${System.getProperty("os.arch")} processors=${Runtime.getRuntime().availableProcessors()} maxHeap=${Runtime.getRuntime().maxMemory()}")
        for (bytes in listOf(64 * 1024, 256 * 1024, 1024 * 1024)) {
            val fixture = events(bytes)
            val factories = listOf<Pair<String, () -> StreamChunkDecoder>>(
                "baseline" to { BaselineResponseApiStreamDecoder(requireCompletedResponse = true, functionToolNamespace = "rikkahub") },
                "appendable" to { ResponseApiStreamDecoder(requireCompletedResponse = true, functionToolNamespace = "rikkahub") },
            )
            fun replay(factory: () -> StreamChunkDecoder) {
                val decoder = factory()
                var finishes = 0
                var emittedChars = 0
                fixture.forEach { event ->
                    decoder.accept(event).chunks.forEach {
                        if (it is StreamChunk.Finish) finishes++
                        if (it is StreamChunk.ToolCallDelta) emittedChars += it.inputDelta.length
                    }
                }
                assertEquals(1, finishes)
                assertEquals(2 * bytes, emittedChars)
                assertTrue(decoder.onClosed().isEmpty())
            }
            repeat(3) { factories.forEach { (_, factory) -> replay(factory) } }
            // Alternate execution order each round to reduce ordering/JIT bias.
            repeat(5) { sample ->
                val ordered = if (sample % 2 == 0) factories else factories.reversed()
                ordered.forEach { (name, factory) ->
                    val thread = Thread.currentThread().id
                    val allocated = bean.getThreadAllocatedBytes(thread)
                    val cpu = bean.currentThreadCpuTime
                    val start = System.nanoTime()
                    replay(factory)
                    val elapsed = System.nanoTime() - start
                    val cpuDelta = bean.currentThreadCpuTime - cpu
                    val allocatedDelta = bean.getThreadAllocatedBytes(thread) - allocated
                    println("STREAM_BENCH bytesPerTool=$bytes tools=2 deltaChars=512 sample=$sample implementation=$name wallNs=$elapsed cpuNs=$cpuDelta allocatedBytes=$allocatedDelta")
                }
            }
        }
    }

    private fun events(bytes: Int): List<SseEvent> = buildList {
        val inputs = listOf('x', 'y').map { "{\"data\":\"${it.toString().repeat(bytes - 11)}\"}" }
        fun item(type: String, tool: Int, args: String) = SseEvent(data = buildJsonObject {
            put("type", type)
            put("output_index", tool)
            put("item", buildJsonObject {
                put("type", "function_call")
                put("id", "fc_$tool")
                put("call_id", "call_$tool")
                put("name", "tool_$tool")
                put("namespace", "rikkahub")
                put("arguments", args)
            })
        }.toString())
        inputs.indices.forEach { add(item("response.output_item.added", it, "")) }
        val chunks = inputs.map { it.chunked(512) }
        repeat(chunks.first().size) { index ->
            chunks.indices.forEach { tool ->
                add(SseEvent(data = buildJsonObject {
                    put("type", "response.function_call_arguments.delta")
                    put("item_id", "fc_$tool")
                    put("delta", chunks[tool][index])
                }.toString()))
            }
        }
        inputs.indices.reversed().forEach { tool ->
            add(SseEvent(data = buildJsonObject {
                put("type", "response.function_call_arguments.done")
                put("item_id", "fc_$tool")
                put("arguments", inputs[tool])
            }.toString()))
            add(item("response.output_item.done", tool, inputs[tool]))
        }
        add(SseEvent(data = """{"type":"response.completed","response":{"status":"completed"}}"""))
    }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", default=BASELINE)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    baseline_commit = subprocess.check_output(
        ["git", "rev-parse", f"{args.baseline}^{{commit}}"], cwd=ROOT, text=True
    ).strip()
    source = subprocess.check_output(
        ["git", "show", f"{baseline_commit}:{SOURCE}"], cwd=ROOT, text=True
    )
    with tempfile.TemporaryDirectory(prefix="response-decoder-benchmark-") as temporary:
        directory = pathlib.Path(temporary)
        sources = directory / "sources"
        sources.mkdir()
        (sources / "BaselineResponseApiStreamDecoder.kt").write_text(
            source.replace("internal class ResponseApiStreamDecoder(", "internal class BaselineResponseApiStreamDecoder("),
            encoding="utf-8",
        )
        (sources / "ResponseDecoderBenchmark.kt").write_text(BENCHMARK, encoding="utf-8")
        init = directory / "benchmark.init.gradle"
        init.write_text(
            "gradle.beforeProject { project ->\n"
            "  if (project.path == ':ai') {\n"
            "    project.afterEvaluate {\n"
            f"      project.android.sourceSets.getByName('test').kotlin.srcDir({json.dumps(str(sources))})\n"
            "      project.tasks.withType(Test).configureEach {\n"
            "        maxHeapSize = '768m'\n"
            "        testLogging.showStandardStreams = true\n"
            "        outputs.upToDateWhen { false }\n"
            "      }\n"
            "    }\n"
            "  }\n"
            "}\n",
            encoding="utf-8",
        )
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("w", encoding="utf-8") as output:
            output.write(f"baseline_commit={baseline_commit}\n")
            output.write("current_commit=" + subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True))
            output.write("baseline_source_sha256=" + hashlib.sha256(source.encode()).hexdigest() + "\n")
            output.write("current_source_sha256=" + hashlib.sha256((ROOT / SOURCE).read_bytes()).hexdigest() + "\n")
            output.flush()
            result = subprocess.run(
                [str(ROOT / "gradlew"), ":ai:testDebugUnitTest", "--tests",
                 "me.rerere.ai.provider.providers.openai.ResponseDecoderBenchmark",
                 "--no-configuration-cache", "--no-build-cache", "--max-workers=2", "-I", str(init)],
                cwd=ROOT, stdout=output, stderr=subprocess.STDOUT,
            )
        print(args.output)
        if result.returncode == 0:
            pattern = re.compile(
                r"STREAM_BENCH bytesPerTool=(\d+) tools=2 deltaChars=512 sample=(\d+) "
                r"implementation=(\w+) wallNs=(\d+) cpuNs=(\d+) allocatedBytes=(\d+)"
            )
            groups = {}
            log = args.output.read_text(encoding="utf-8")
            for size, sample, name, wall, cpu, allocated in pattern.findall(log):
                groups.setdefault((int(size), name), []).append({
                    "sample": int(sample), "wall_ms": int(wall) / 1e6,
                    "cpu_ms": int(cpu) / 1e6, "allocated_bytes": int(allocated),
                })
            summary = []
            for (size, name), samples in sorted(groups.items()):
                if len(samples) != 5:
                    raise RuntimeError(f"Expected five samples, got {len(samples)} for {size}/{name}")
                summary.append({
                    "bytes_per_tool": size, "tools": 2, "delta_chars": 512,
                    "implementation": name, "samples": samples,
                    "median": {key: statistics.median(row[key] for row in samples)
                               for key in ("wall_ms", "cpu_ms", "allocated_bytes")},
                })
            if len(summary) != 6:
                raise RuntimeError("Benchmark output is incomplete")
            summary_path = args.output.with_suffix(".json")
            summary_path.write_text(json.dumps({
                "baseline_commit": baseline_commit,
                "environment": next(line.strip() for line in log.splitlines() if "STREAM_BENCH environment" in line),
                "results": summary,
            }, indent=2) + "\n", encoding="utf-8")
            print(summary_path)
        raise SystemExit(result.returncode)


if __name__ == "__main__":
    main()
