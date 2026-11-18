package me.rerere.ai.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.core.ReasoningLevel
import kotlin.uuid.Uuid

@Serializable
data class Model(
    val modelId: String = "",
    val displayName: String = "",
    val id: Uuid = Uuid.random(),
    val type: ModelType = ModelType.CHAT,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val inputModalities: List<Modality> = listOf(Modality.TEXT),
    val outputModalities: List<Modality> = listOf(Modality.TEXT),
    val abilities: List<ModelAbility> = emptyList(),
    val tools: Set<BuiltInTools> = emptySet(),
    val providerOverwrite: ProviderSetting? = null,
    // 上下文窗口大小(token)
    val contextLength: Int = 1_000_000,
    // Optional account catalog metadata; other provider routes do not use it.
    val chatGPTCapabilities: ChatGPTModelCapabilities? = null,
    // Legacy settings have no provenance, so their editable fields keep the user's saved values.
    val chatGPTCatalogFields: Set<ChatGPTCatalogField> = emptySet(),
)

@Serializable
enum class ChatGPTCatalogField {
    INPUT_MODALITIES, OUTPUT_MODALITIES, ABILITIES, CONTEXT_LENGTH;

    companion object {
        val capabilities = setOf(INPUT_MODALITIES, OUTPUT_MODALITIES, ABILITIES)
        val all = capabilities + CONTEXT_LENGTH
    }
}

@Serializable
data class ChatGPTModelCapabilities(
    val reasoningEfforts: List<String> = emptyList(),
    val defaultReasoningEffort: String? = null,
    val supportsReasoningSummary: Boolean? = null,
    val supportsWebSearch: Boolean? = null,
)

/** AUTO leaves the server default untouched; explicit levels require account catalog evidence. */
fun Model.supportedChatGPTReasoningLevels(): List<ReasoningLevel> = ReasoningLevel.entries.filter {
    it == ReasoningLevel.AUTO || it.effort in chatGPTCapabilities?.reasoningEfforts.orEmpty()
}

/**
 * 按空格分隔的关键词筛选模型：忽略大小写，每个关键词都要命中模型 ID 或显示名称的一部分，
 * 关键词之间不要求顺序。没有关键词时返回原列表。
 */
fun List<Model>.filterByKeywords(query: String): List<Model> {
    val keywords = query.split(" ").filter { it.isNotBlank() }
    if (keywords.isEmpty()) return this
    return filter { model ->
        keywords.all { keyword ->
            model.modelId.contains(keyword, ignoreCase = true) ||
                model.displayName.contains(keyword, ignoreCase = true)
        }
    }
}

@Serializable
enum class ModelType {
    CHAT,
    IMAGE,
    EMBEDDING,
}

@Serializable
enum class Modality {
    TEXT,
    IMAGE,
}

@Serializable
enum class ModelAbility {
    TOOL,
    REASONING,
}

// 模型(提供商)提供的内置工具选项
@Serializable
sealed class BuiltInTools {
    // https://ai.google.dev/gemini-api/docs/google-search?hl=zh-cn
    @Serializable
    @SerialName("search")
    data object Search : BuiltInTools()

    // https://ai.google.dev/gemini-api/docs/url-context?hl=zh-cn
    @Serializable
    @SerialName("url_context")
    data object UrlContext : BuiltInTools()

    @Serializable
    @SerialName("image_generation")
    data object ImageGeneration : BuiltInTools()
}
