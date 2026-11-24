package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.supportedChatGPTReasoningLevels
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_CHAT_ENHANCEMENT_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_IMAGE_ENHANCEMENT_PROMPT
import me.rerere.rikkahub.data.ai.transformers.transformThinkTags
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.supportsEnhancementReasoning
import me.rerere.rikkahub.data.datastore.supportsPromptEnhancement
import kotlin.time.Clock
import kotlin.uuid.Uuid

enum class PromptEnhancementMode { CHAT, IMAGE }

class PromptEnhancementModelUnavailable : IllegalStateException()
class PromptEnhancementReasoningUnavailable : IllegalStateException()
class EmptyPromptEnhancementResult : IllegalStateException()

suspend fun enhancePrompt(
    settings: Settings,
    providerManager: ProviderManager,
    mode: PromptEnhancementMode,
    draft: String,
    usageRecorder: UsageRecorder,
): String = enhancePrompt(settings, mode, draft) { provider, messages, params ->
    usageRecorder.wrap(providerManager.getProviderByType(provider), UsageTag(UsageSource.PROMPT_ENHANCEMENT))
        .generateText(provider, messages, params)
}

internal suspend fun enhancePrompt(
    settings: Settings,
    mode: PromptEnhancementMode,
    draft: String,
    generate: suspend (ProviderSetting, List<UIMessage>, TextGenerationParams) -> TextGenerationResult,
): String {
    require(draft.isNotBlank())
    val providers = settings.providers.filter { it.enabled }
    val model = settings.promptEnhancementModelId?.let { providers.findModelById(it) }
        ?.takeIf { it.supportsPromptEnhancement() } ?: throw PromptEnhancementModelUnavailable()
    val provider = model.findProvider(providers) ?: throw PromptEnhancementModelUnavailable()
    val (prompt, thinkingBudget) = when (mode) {
        PromptEnhancementMode.CHAT -> settings.chatEnhancementPrompt.ifBlank { DEFAULT_CHAT_ENHANCEMENT_PROMPT } to
            settings.chatEnhancementThinkingBudget
        PromptEnhancementMode.IMAGE -> settings.imageEnhancementPrompt.ifBlank { DEFAULT_IMAGE_ENHANCEMENT_PROMPT } to
            settings.imageEnhancementThinkingBudget
    }
    val level = if (model.supportsEnhancementReasoning(provider)) {
        ReasoningLevel.fromBudgetTokens(thinkingBudget)
    } else {
        ReasoningLevel.AUTO
    }
    if (provider is ProviderSetting.ChatGPT && level !in model.supportedChatGPTReasoningLevels()) {
        throw PromptEnhancementReasoningUnavailable()
    }
    // A multimodal model may normally request images or use hosted search. Only request text for this draft.
    val textModel = model.copy(tools = emptySet(), outputModalities = listOf(Modality.TEXT))
    val result = generate(
        provider,
        listOf(UIMessage.system(prompt), UIMessage.user(draft)),
        backgroundTextGenerationParams(textModel, Uuid.random(), level),
    )
    val message = listOf(result.message).transformThinkTags(Clock.System.now(), generationFinished = true).single()
    return message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        .ifBlank { throw EmptyPromptEnhancementResult() }
}
