package me.rerere.rikkahub.data.ai

import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor

/** Credentials must not enter logcat, exported request logs, or authorization URL diagnostics. */
internal object CredentialLogPolicy {
    val sensitiveHeaders = setOf(
        "Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie", "X-Api-Key", "Api-Key",
    )

    private val sensitiveParameters = setOf(
        "access_token", "refresh_token", "id_token", "id_token_hint", "token", "code", "code_verifier",
        "client_secret", "assertion",
    )

    fun omit(request: Request): Boolean {
        if (request.url.host == "auth.openai.com") return true
        if (request.url.queryParameterNames.any { it.lowercase() in sensitiveParameters }) return true
        val form = request.body as? FormBody ?: return false
        return (0 until form.size).any { form.name(it).lowercase() in sensitiveParameters }
    }

    fun headers(headers: Headers): Map<String, String> = headers.names().associateWith { name ->
        if (sensitiveHeaders.any { it.equals(name, ignoreCase = true) }) "[REDACTED]" else headers[name].orEmpty()
    }
}

/** Keep ordinary HTTP diagnostics while never passing OAuth exchanges to a logger. */
class SafeHttpLoggingInterceptor(
    logger: HttpLoggingInterceptor.Logger = HttpLoggingInterceptor.Logger.DEFAULT,
) : Interceptor {
    private val delegate = HttpLoggingInterceptor(logger).apply {
        CredentialLogPolicy.sensitiveHeaders.forEach(::redactHeader)
        level = HttpLoggingInterceptor.Level.HEADERS
    }

    override fun intercept(chain: Interceptor.Chain): Response =
        if (CredentialLogPolicy.omit(chain.request())) chain.proceed(chain.request()) else delegate.intercept(chain)
}
