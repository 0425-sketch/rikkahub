package me.rerere.rikkahub.data.ai

import me.rerere.common.android.CredentialRedactor
import me.rerere.common.android.Logging
import me.rerere.oauth.DiagnosticHttpInterceptor
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor

/** Authentication is excluded before any diagnostic body read; copies use one shared policy. */
internal object CredentialLogPolicy {
    val sensitiveHeaders get() = CredentialRedactor.sensitiveHeaders
    fun omit(request: Request) = CredentialRedactor.omit(request)
    fun headers(headers: Headers) = CredentialRedactor.headers(headers)
}

/** The request itself is never rewritten. Even URLs and thrown exception text use a redacted copy. */
class SafeHttpLoggingInterceptor(
    private val logger: HttpLoggingInterceptor.Logger = HttpLoggingInterceptor.Logger.DEFAULT,
) : DiagnosticHttpInterceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!Logging.isRequestLoggingEnabled() || CredentialLogPolicy.omit(request)) return chain.proceed(request)
        val diagnostic = Logging.requestScope(request)
        val safeUrl = CredentialRedactor.url(request.url)
        val delegate = HttpLoggingInterceptor { line ->
            diagnostic.writeIfActive(line.replace(request.url.toString(), safeUrl), logger::log)
        }.apply {
            request.headers.names().filter(CredentialRedactor::isSensitiveHeader).forEach(::redactHeader)
            CredentialLogPolicy.sensitiveHeaders.forEach(::redactHeader)
            level = HttpLoggingInterceptor.Level.HEADERS
        }
        return delegate.intercept(chain)
    }
}
