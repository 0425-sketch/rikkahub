package me.rerere.ai.provider.providers.chatgpt

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

/** Re-run: :ai:testDebugUnitTest --tests '*CacheDiagnosticBenchmarkTest' --rerun-tasks */
class CacheDiagnosticBenchmarkTest {
    @Test fun compareBoundedDiagnosticCollection() {
        val body = JsonObject(mapOf("model" to JsonPrimitive("synthetic"), "input" to JsonArray(List(128) { index ->
            JsonObject(mapOf("role" to JsonPrimitive("user"), "content" to JsonPrimitive("$index:" + "x".repeat(1024))))
        })))
        val hugeImage = JsonObject(mapOf("model" to JsonPrimitive("synthetic"), "input" to JsonArray(listOf(
            JsonObject(mapOf("image_url" to JsonPrimitive("data:image/png;base64," + "A".repeat(2_000_000))))
        ))))
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        if (bean?.isThreadAllocatedMemorySupported == true) bean.isThreadAllocatedMemoryEnabled = true
        if (bean?.isThreadCpuTimeSupported == true) bean.isThreadCpuTimeEnabled = true
        fun measure(name: String, iterations: Int, run: () -> Unit) {
            repeat(5) { run() }
            val samples = List(3) {
                val allocated = bean?.getThreadAllocatedBytes(Thread.currentThread().id) ?: 0L
                val cpuStart = bean?.currentThreadCpuTime ?: 0L
                val start = System.nanoTime()
                repeat(iterations) { run() }
                val millis = (System.nanoTime() - start) / 1_000_000.0 / iterations
                val bytes = ((bean?.getThreadAllocatedBytes(Thread.currentThread().id) ?: 0L) - allocated) / iterations
                val cpuMillis = ((bean?.currentThreadCpuTime ?: 0L) - cpuStart) / 1_000_000.0 / iterations
                Triple(millis, bytes, cpuMillis)
            }
            println("CACHE_BENCH $name median_wall_ms=${samples.map { it.first }.sorted()[1]} median_thread_cpu_ms=${samples.map { it.third }.sorted()[1]} allocated_bytes_per_request=${samples.map { it.second }.sorted()[1]}")
        }
        println("CACHE_BENCH java=${System.getProperty("java.version")} vm=${System.getProperty("java.vm.name")} os=${System.getProperty("os.name")} arch=${System.getProperty("os.arch")} cpus=${Runtime.getRuntime().availableProcessors()} samples=3 warmup=5 thread_cpu_supported=${bean?.isThreadCpuTimeSupported} allocation_supported=${bean?.isThreadAllocatedMemorySupported}")
        for ((fixture, payload, repetitions) in listOf(Triple("128x1KiB", body, 30), Triple("2MB-image", hugeImage, 10))) {
            val old = LegacyCacheDiagnosticRecorder()
            measure("$fixture legacy-full", repetitions) { old.begin("account", payload, "session") }
            for (mode in CacheDiagnosticMode.entries) {
                val recorder = CacheDiagnosticRecorder(initialMode = mode)
                measure("$fixture ${mode.name.lowercase()}", repetitions) { recorder.begin("account", payload, "session") }
            }
        }
        if (bean?.isThreadContentionMonitoringSupported == true) bean.isThreadContentionMonitoringEnabled = true
        fun concurrent(name: String, run: () -> Unit) {
            val executor = Executors.newFixedThreadPool(4)
            try {
                val ready = CountDownLatch(4)
                val start = CountDownLatch(1)
                val tasks = List(4) { executor.submit<Pair<Long, Long>> {
                    ready.countDown()
                    start.await()
                    val id = Thread.currentThread().id
                    val blockedBefore = bean?.getThreadInfo(id)?.blockedTime ?: 0L
                    val cpuBefore = bean?.currentThreadCpuTime ?: 0L
                    repeat(10) { run() }
                    ((bean?.getThreadInfo(id)?.blockedTime ?: 0L) - blockedBefore) to
                        ((bean?.currentThreadCpuTime ?: 0L) - cpuBefore)
                } }
                check(ready.await(10, TimeUnit.SECONDS))
                val began = System.nanoTime()
                start.countDown()
                val results = tasks.map { it.get(30, TimeUnit.SECONDS) }
                println("CACHE_BENCH concurrent-128x1KiB $name workers=4 requests=40 wall_ms=${(System.nanoTime() - began) / 1_000_000.0} monitor_blocked_ms_sum=${results.sumOf { it.first }} thread_cpu_ms_sum=${results.sumOf { it.second } / 1_000_000.0}")
            } finally { executor.shutdownNow() }
        }
        val oldConcurrent = LegacyCacheDiagnosticRecorder()
        val newConcurrent = CacheDiagnosticRecorder(initialMode = CacheDiagnosticMode.FULL)
        concurrent("legacy-full") { oldConcurrent.begin("account", body, "session") }
        concurrent("full") { newConcurrent.begin("account", body, "session") }
    }
}
