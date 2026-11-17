package me.rerere.ai.util

import me.rerere.common.android.DiagnosticScope
import me.rerere.common.android.CredentialRedactor

/** One persisted opt-in covers request/response diagnostics in debug and release builds alike. */
internal fun logRequestBody(diagnostic: DiagnosticScope, tag: String, name: String, body: () -> String) {
    diagnostic.diagnostic(tag) { "$name: ${CredentialRedactor.text(body())}" }
}
