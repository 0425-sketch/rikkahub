package me.rerere.rikkahub.data.ai

import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import me.rerere.common.android.CredentialRedactor
import me.rerere.oauth.DiagnosticHttpInterceptor
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer

class RequestLoggingInterceptor : DiagnosticHttpInterceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!Logging.isRequestLoggingEnabled() || CredentialLogPolicy.omit(chain.request())) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val startTime = System.currentTimeMillis()
        val diagnostic = Logging.requestScope(request)
        if (!diagnostic.isActive()) return chain.proceed(request)
        val safeUrl = CredentialRedactor.url(request.url)

        val requestHeaders = CredentialLogPolicy.headers(request.headers)
        val requestBody = request.body?.let { body ->
            val length = runCatching { body.contentLength() }.getOrDefault(-1)
            if (body.isOneShot() || body.isDuplex() || length !in 0..CredentialRedactor.MAX_LOG_CHARS.toLong()) {
                "[Diagnostic body omitted: one-shot, streaming, unknown size or too large]"
            } else runCatching {
                val buffer = Buffer()
                body.writeTo(buffer)
                diagnostic.redact(buffer.readUtf8())
            }.getOrElse { "[Diagnostic body unavailable]" }
        }

        val response: Response
        var error: String? = null

        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            if (diagnostic.isActive()) {
                error = diagnostic.redact(e.stackTraceToString())
                diagnostic.logRequest(
                    LogEntry.RequestLog(
                        tag = "HTTP",
                        url = safeUrl,
                        method = request.method,
                        requestHeaders = requestHeaders,
                        requestBody = requestBody,
                        error = error
                    )
                )
            }
            throw e
        }

        if (!diagnostic.isActive()) return response
        val durationMs = System.currentTimeMillis() - startTime
        val responseHeaders = CredentialLogPolicy.headers(response.headers)

        diagnostic.logRequest(
            LogEntry.RequestLog(
                tag = "HTTP",
                url = safeUrl,
                method = request.method,
                requestHeaders = requestHeaders,
                requestBody = requestBody,
                responseCode = response.code,
                responseHeaders = responseHeaders,
                durationMs = durationMs,
                error = error
            )
        )

        return response
    }

}
