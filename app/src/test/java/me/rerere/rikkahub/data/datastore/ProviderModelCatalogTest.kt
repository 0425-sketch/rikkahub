package me.rerere.rikkahub.data.datastore

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ChatGPTCatalogField
import me.rerere.ai.provider.ChatGPTModelCapabilities
import me.rerere.ai.provider.ChatGPTRegistration
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.supportedChatGPTReasoningLevels
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ProviderModelCatalogTest {
    private val remote = Model(
        modelId = "fixture-unlisted",
        inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
        abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
        chatGPTCapabilities = ChatGPTModelCapabilities(reasoningEfforts = listOf("high")),
        contextLength = 32_768,
        chatGPTCatalogFields = ChatGPTCatalogField.all,
    )

    @Test
    fun `selection keeps account declarations while API providers keep registry defaults`() {
        assertSame(remote, remote.forCatalogSelection(ProviderSetting.ChatGPT()))
        val api = remote.forCatalogSelection(ProviderSetting.OpenAI())
        assertEquals(listOf(Modality.TEXT), api.inputModalities)
        assertEquals(emptyList<ModelAbility>(), api.abilities)
    }

    @Test
    fun `refresh enriches old models by slug without replacing saved preferences or references`() {
        val legacy = JsonInstant.decodeFromString<Model>("""{"modelId":"fixture-unlisted"}""").copy(
            displayName = "My name",
            inputModalities = listOf(Modality.TEXT),
            customHeaders = listOf(CustomHeader("X-Example", "example")),
        )
        val automatic = remote.copy(modelId = "automatic", contextLength = 0)
        val override = legacy.copy(providerOverwrite = ProviderSetting.OpenAI())
        val notListed = Model(modelId = "local")
        val provider = ProviderSetting.ChatGPT(models = listOf(legacy, automatic, override, notListed))
        val request = provider.copy(models = emptyList())
        val latest = Settings(providers = listOf(provider), chatModelId = legacy.id)
        val catalog = listOf(remote, remote.copy(modelId = automatic.modelId))
        val updated = latest.withChatGPTModelCatalog(request, catalog)
        val saved = updated.providers.single().models
        assertEquals(legacy.id, updated.chatModelId)
        assertEquals(legacy.copy(chatGPTCapabilities = remote.chatGPTCapabilities), saved[0])
        assertEquals(listOf(ReasoningLevel.AUTO, ReasoningLevel.HIGH), saved[0].supportedChatGPTReasoningLevels())
        assertEquals(automatic.copy(contextLength = remote.contextLength), saved[1])
        assertSame(override, saved[2])
        assertSame(notListed, saved[3])
        assertSame(updated, updated.withChatGPTModelCatalog(request, catalog))
    }

    @Test
    fun `a legacy text only model can explicitly adopt account capabilities without touching its window or ID`() {
        val local = Model(
            modelId = remote.modelId,
            displayName = "Original",
            contextLength = 1_000_000,
            customHeaders = listOf(CustomHeader("X-Example", "example")),
        )
        val restored = local.useAccountCapabilities(remote)
        assertEquals(
            local.copy(
                chatGPTCatalogFields = ChatGPTCatalogField.capabilities,
                chatGPTCapabilities = remote.chatGPTCapabilities,
                inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
                abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            ),
            restored,
        )
        val limited = remote.copy(abilities = listOf(ModelAbility.TOOL), inputModalities = listOf(Modality.TEXT))
        assertEquals(limited.abilities, restored.withCatalogMetadata(limited).abilities)
    }

    @Test
    fun `manual input and window survive new catalog values while untouched abilities track changes`() {
        val edited = remote.copy(
            chatGPTCatalogFields = ChatGPTCatalogField.all - ChatGPTCatalogField.INPUT_MODALITIES - ChatGPTCatalogField.CONTEXT_LENGTH,
            inputModalities = listOf(Modality.TEXT),
            contextLength = 70_000,
        )
        val next = remote.copy(abilities = listOf(ModelAbility.TOOL), contextLength = 127_000)
        assertEquals(edited.copy(abilities = next.abilities), edited.withCatalogMetadata(next))
    }

    @Test
    fun `a late catalog cannot overwrite another account with the same provider ID`() {
        val first = ProviderSetting.ChatGPT(
            models = listOf(Model(modelId = remote.modelId)),
            chatGPTRegistration = ChatGPTRegistration("first", subject = "a"),
        )
        val second = first.copy(chatGPTRegistration = ChatGPTRegistration("second", subject = "b"))
        val latest = Settings(providers = listOf(second))
        assertSame(latest, latest.withChatGPTModelCatalog(first, listOf(remote)))
    }
}
