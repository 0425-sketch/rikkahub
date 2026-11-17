package me.rerere.common.android

import kotlinx.serialization.Serializable
import okhttp3.Request
import java.util.IdentityHashMap
import kotlin.uuid.Uuid

private const val MAX_RECENT_LOGS = 100

@Serializable
sealed class LogEntry {
    abstract val id: Uuid
    abstract val timestamp: Long
    abstract val tag: String

    @Serializable
    data class TextLog(
        override val id: Uuid = Uuid.random(),
        override val timestamp: Long = System.currentTimeMillis(),
        override val tag: String,
        val message: String
    ) : LogEntry()

    @Serializable
    data class RequestLog(
        override val id: Uuid = Uuid.random(),
        override val timestamp: Long = System.currentTimeMillis(),
        override val tag: String,
        val url: String,
        val method: String,
        val requestHeaders: Map<String, String> = emptyMap(),
        val requestBody: String? = null,
        val responseCode: Int? = null,
        val responseHeaders: Map<String, String> = emptyMap(),
        val durationMs: Long? = null,
        val error: String? = null
    ) : LogEntry()
}

object Logging {
    private val recentLogs = arrayListOf<LogEntry>()
    // Short-lived redaction dictionary for unlabeled server/error echoes, never an exported record.
    private val credentialValues = linkedSetOf<String>()
    private var redactionCapacityExceeded = false
    @Volatile
    private var requestLoggingEnabled = false
    private var generation = 0L

    fun log(tag: String, message: String) {
        if (!requestLoggingEnabled) return
        addLog(LogEntry.TextLog(tag = tag, message = redact(message)))
    }

    /** The supplier (including body serialization and exception formatting) is never run when off. */
    fun diagnostic(tag: String, error: Throwable? = null, message: () -> String) {
        if (!isRequestLoggingEnabled()) return
        // Without a request scope there is no reliable credential context for arbitrary error echoes.
        log(tag, message() + (error?.let { "\n${safeThrowable(it)}" } ?: ""))
    }

    fun requestScope(request: Request): DiagnosticScope = synchronized(recentLogs) {
        if (!requestLoggingEnabled || CredentialRedactor.omit(request)) return@synchronized DiagnosticScope.DISABLED
        val values = CredentialRedactor.secrets(request)
        rememberCredentials(values)
        DiagnosticScope(generation, values.takeIf { it.size <= 128 && it.all { value -> value.length <= 4096 } })
    }

    internal fun isGenerationActive(expected: Long): Boolean = synchronized(recentLogs) {
        requestLoggingEnabled && generation == expected
    }

    internal fun ifGenerationActive(expected: Long, block: () -> Unit) {
        synchronized(recentLogs) {
            if (requestLoggingEnabled && generation == expected) block()
        }
    }

    /** Exception messages can contain server echoes from requests which started while logging was off. */
    fun safeThrowable(error: Throwable): String = buildString {
        val seen = IdentityHashMap<Throwable, Boolean>()
        fun appendException(current: Throwable, label: String, depth: Int) {
            if (depth >= 16 || seen.put(current, true) != null) return
            appendLine("$label${current.javaClass.name} [message omitted without request context]")
            current.stackTrace.take(64).forEach { appendLine("  at $it") }
            current.suppressed.take(4).forEach { appendException(it, "Suppressed: ", depth + 1) }
            current.cause?.let { appendException(it, "Caused by: ", depth + 1) }
        }
        appendException(error, "", 0)
    }

    fun logRequest(entry: LogEntry.RequestLog) {
        if (!requestLoggingEnabled) return
        addLog(sanitize(entry))
    }

    fun isRequestLoggingEnabled(): Boolean = requestLoggingEnabled

    fun setRequestLoggingEnabled(enabled: Boolean) {
        synchronized(recentLogs) {
            if (requestLoggingEnabled != enabled) generation++
            requestLoggingEnabled = enabled
            if (!enabled) {
                recentLogs.clear()
                credentialValues.clear()
                redactionCapacityExceeded = false
            }
        }
    }

    fun rememberCredentials(values: List<String>) {
        synchronized(recentLogs) {
            if (!requestLoggingEnabled) return
            values.filter { it.isNotBlank() }.forEach {
                if (it.length > 4096 || (it !in credentialValues && credentialValues.size >= 128)) {
                    // Fail closed instead of evicting a credential still needed by an in-flight request.
                    redactionCapacityExceeded = true
                    return@forEach
                }
                credentialValues.remove(it)
                credentialValues.add(it)
            }
        }
    }

    private fun redact(value: String): String {
        val secrets = synchronized(recentLogs) {
            if (redactionCapacityExceeded) return "[Diagnostic omitted: credential redaction capacity exceeded; toggle logging to restart]"
            credentialValues.toList().sortedByDescending { it.length }
        }
        return CredentialRedactor.text(value, secrets)
    }

    fun sanitizeForExport(value: String): String = redact(value)

    private fun addLog(entry: LogEntry) {
        synchronized(recentLogs) {
            if (!requestLoggingEnabled) return
            recentLogs.add(0, entry)
            if (recentLogs.size > MAX_RECENT_LOGS) {
                recentLogs.removeLastOrNull()
            }
        }
    }

    fun getRecentLogs(): List<LogEntry> {
        synchronized(recentLogs) {
            return recentLogs.map(::sanitize)
        }
    }

    fun getTextLogs(): List<LogEntry.TextLog> {
        synchronized(recentLogs) {
            return recentLogs.filterIsInstance<LogEntry.TextLog>().map { sanitize(it) as LogEntry.TextLog }
        }
    }

    fun getRequestLogs(): List<LogEntry.RequestLog> {
        synchronized(recentLogs) {
            return recentLogs.filterIsInstance<LogEntry.RequestLog>().map { sanitize(it) as LogEntry.RequestLog }
        }
    }

    fun clear() {
        synchronized(recentLogs) {
            recentLogs.clear()
        }
    }

    /** Defense in depth at every UI/copy/export read, including old in-process entries. */
    private fun sanitize(entry: LogEntry): LogEntry = when (entry) {
        is LogEntry.TextLog -> entry.copy(message = redact(entry.message))
        is LogEntry.RequestLog -> entry.copy(
            url = redact(entry.url),
            requestHeaders = CredentialRedactor.headers(entry.requestHeaders).mapValues { redact(it.value) },
            requestBody = entry.requestBody?.let(::redact),
            responseHeaders = CredentialRedactor.headers(entry.responseHeaders).mapValues { redact(it.value) },
            error = entry.error?.let(::redact),
        )
    }
}

/** A request that started while off, or before a toggle, never starts collecting later. */
class DiagnosticScope internal constructor(
    private val generation: Long,
    private val secrets: List<String>?,
) {
    fun isActive(): Boolean = Logging.isGenerationActive(generation)

    fun redact(value: String): String = if (secrets == null) {
        "[Diagnostic omitted: request credential redaction capacity exceeded]"
    } else CredentialRedactor.text(value, secrets)

    fun diagnostic(tag: String, error: Throwable? = null, message: () -> String) {
        if (!isActive()) return
        val safe = redact(message() + (error?.let { "\n${it.stackTraceToString()}" } ?: ""))
        Logging.ifGenerationActive(generation) { Logging.log(tag, safe) }
    }

    fun logRequest(entry: LogEntry.RequestLog) {
        if (!isActive()) return
        val safe = entry.copy(
            url = redact(entry.url),
            requestHeaders = entry.requestHeaders.mapValues { redact(it.value) },
            requestBody = entry.requestBody?.let(::redact),
            responseHeaders = entry.responseHeaders.mapValues { redact(it.value) },
            error = entry.error?.let(::redact),
        )
        Logging.ifGenerationActive(generation) { Logging.logRequest(safe) }
    }

    fun writeIfActive(value: String, output: (String) -> Unit) {
        if (!isActive()) return
        val safe = redact(value)
        Logging.ifGenerationActive(generation) { output(safe) }
    }

    internal companion object {
        val DISABLED = DiagnosticScope(-1, emptyList())
    }
}
