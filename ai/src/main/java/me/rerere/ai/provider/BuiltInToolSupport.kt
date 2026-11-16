package me.rerere.ai.provider

enum class BuiltInToolUnavailableReason {
    CHATGPT_IMAGE_GENERATION,
    CHATGPT_URL_CONTEXT,
    MODEL_WEB_SEARCH,
    RESPONSES_REQUIRED,
    PROVIDER_ROUTE,
}

data class BuiltInToolSupport(
    val supported: Boolean,
    val reason: BuiltInToolUnavailableReason? = null,
)

/** The effective route, rather than the provider containing the model, controls hosted tools. */
fun ProviderSetting.builtInToolSupport(model: Model, tool: BuiltInTools): BuiltInToolSupport {
    val effectiveProvider = model.providerOverwrite ?: this
    val reason = when (effectiveProvider) {
        is ProviderSetting.ChatGPT -> when (tool) {
            BuiltInTools.ImageGeneration -> BuiltInToolUnavailableReason.CHATGPT_IMAGE_GENERATION
            BuiltInTools.UrlContext -> BuiltInToolUnavailableReason.CHATGPT_URL_CONTEXT
            BuiltInTools.Search -> BuiltInToolUnavailableReason.MODEL_WEB_SEARCH
                .takeIf { model.chatGPTCapabilities?.supportsWebSearch == false }
        }

        // Keep Gemini's existing choices. Image output still follows the configured output modalities.
        is ProviderSetting.Google -> null
        is ProviderSetting.OpenAI -> when {
            !effectiveProvider.useResponseApi -> BuiltInToolUnavailableReason.RESPONSES_REQUIRED
            tool == BuiltInTools.UrlContext -> BuiltInToolUnavailableReason.PROVIDER_ROUTE
            else -> null
        }

        is ProviderSetting.Claude, is ProviderSetting.Grok ->
            BuiltInToolUnavailableReason.PROVIDER_ROUTE.takeIf { tool != BuiltInTools.Search }
    }
    return BuiltInToolSupport(supported = reason == null, reason = reason)
}

/** Remove only saved incompatible switches on the ChatGPT plan route, retaining all other model data. */
fun Model.withCompatibleChatGPTTools(parentProvider: ProviderSetting): Model {
    if ((providerOverwrite ?: parentProvider) !is ProviderSetting.ChatGPT) return this
    val compatible = tools.filterTo(linkedSetOf()) { parentProvider.builtInToolSupport(this, it).supported }
    return if (compatible == tools) this else copy(tools = compatible)
}
