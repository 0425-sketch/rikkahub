package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class MemoryConsolidationModelTest {
    private val chat = Model(modelId = "chat", tools = setOf(BuiltInTools.Search))
    private val fast = Model(modelId = "fast", abilities = listOf(ModelAbility.TOOL))
    private val memory = Model(modelId = "memory", abilities = listOf(ModelAbility.TOOL))
    private val assistant = Assistant(chatModelId = chat.id, reasoningLevel = ReasoningLevel.HIGH)
    private val settings = Settings(
        assistants = listOf(assistant), assistantId = assistant.id, chatModelId = chat.id,
        providers = listOf(ProviderSetting.ChatGPT(models = listOf(chat, fast, memory))),
        fastModelId = fast.id, fastModelReasoningLevel = ReasoningLevel.LOW,
        memoryModelId = memory.id, memoryModelReasoningLevel = ReasoningLevel.MEDIUM,
    )

    @Test
    fun `configured memory and fast models use their own reasoning level`() {
        assertEquals(memory to ReasoningLevel.MEDIUM, settings.consolidationModel(null, assistant))
        assertEquals(
            fast to ReasoningLevel.LOW,
            settings.copy(memoryModelId = null).consolidationModel(null, assistant),
        )
    }

    @Test
    fun `chat fallback uses persisted conversation model and level instead of fast model level`() {
        val conversationAssistant = Assistant(chatModelId = chat.id, reasoningLevel = ReasoningLevel.OFF)
        val conversation = Conversation(
            assistantId = conversationAssistant.id, messageNodes = emptyList(),
            config = ConversationConfig(chatModelId = memory.id, reasoningLevel = ReasoningLevel.XHIGH),
        )
        val actual = settings.copy(
            assistants = listOf(assistant, conversationAssistant),
            memoryModelId = null, fastModelId = Uuid.random(),
        ).consolidationModel(conversation, assistant)

        assertEquals(memory to ReasoningLevel.XHIGH, actual)
    }

    @Test
    fun `chat fallback without a conversation pairs assistant model with assistant reasoning`() {
        val actual = settings.copy(memoryModelId = null, fastModelId = Uuid.random())
            .consolidationModel(null, assistant)
        assertEquals(chat.copy(tools = emptySet()) to ReasoningLevel.HIGH, actual)
    }

    @Test
    fun `fallback reasoning correction follows effective ChatGPT route and preserves other providers`() {
        val conversation = Conversation(
            assistantId = assistant.id, messageNodes = emptyList(),
            config = ConversationConfig(chatModelId = chat.id, reasoningLevel = ReasoningLevel.XHIGH),
        )
        val cases = listOf(
            Triple(ProviderSetting.OpenAI(), null, false),
            Triple(ProviderSetting.Google(), null, false),
            Triple(ProviderSetting.ChatGPT(), ProviderSetting.OpenAI(), false),
            Triple(ProviderSetting.ChatGPT(), ProviderSetting.Google(), false),
            Triple(ProviderSetting.OpenAI(), ProviderSetting.ChatGPT(), true),
            Triple(ProviderSetting.Google(), ProviderSetting.ChatGPT(), true),
        )
        cases.forEach { (parent, providerOverride, usesChatGPT) ->
            val fallbackModel = chat.copy(providerOverwrite = providerOverride)
            val current = settings.copy(
                memoryModelId = null, fastModelId = Uuid.random(),
                providers = listOf(parent.copyProvider(models = listOf(fallbackModel))),
            )
            assertEquals(
                fallbackModel.copy(tools = emptySet()) to
                    if (usesChatGPT) ReasoningLevel.XHIGH else ReasoningLevel.LOW,
                current.consolidationModel(conversation, assistant),
            )
            assertEquals(
                fallbackModel.copy(tools = emptySet()) to
                    if (usesChatGPT) ReasoningLevel.HIGH else ReasoningLevel.LOW,
                current.consolidationModel(null, assistant),
            )
        }
    }
}
