package me.rerere.oauth

import okhttp3.Interceptor

/** Marks HTTP diagnostics so authentication clients can exclude them even after R8 renaming. */
interface DiagnosticHttpInterceptor : Interceptor
