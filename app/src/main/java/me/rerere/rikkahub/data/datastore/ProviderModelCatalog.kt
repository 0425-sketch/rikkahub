package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ChatGPTCatalogField
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.registry.ModelRegistry

/** Use the same capability mapping for the preview and both add actions. */
internal fun Model.forCatalogSelection(provider: ProviderSetting): Model =
    if (provider is ProviderSetting.ChatGPT) this else copy(
        inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(modelId),
        outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(modelId),
        abilities = ModelRegistry.MODEL_ABILITIES.getData(modelId),
    )

/** Apply the result to the latest settings. Fetching the catalog may race edits, sign-out or account changes. */
internal fun Settings.withChatGPTModelCatalog(requested: ProviderSetting.ChatGPT, catalog: List<Model>): Settings {
    val provider = providers.find { it.id == requested.id } as? ProviderSetting.ChatGPT ?: return this
    if (provider.chatGPTRegistration != requested.chatGPTRegistration ||
        provider.credential?.subject != requested.credential?.subject ||
        provider.credential?.clientId != requested.credential?.clientId
    ) return this

    val bySlug = catalog.associateBy { it.modelId }
    val models = provider.models.map { saved ->
        val listed = bySlug[saved.modelId] ?: return@map saved
        saved.withCatalogMetadata(listed)
    }
    if (models == provider.models) return this
    return copy(providers = providers.map { if (it.id == provider.id) provider.copy(models = models) else it })
}

/** A user edit opts only that field out of automatic updates. */
internal fun Model.withCatalogMetadata(listed: Model): Model =
    if (providerOverwrite != null || listed.providerOverwrite != null) this else copy(
        chatGPTCapabilities = listed.chatGPTCapabilities,
        inputModalities = if (ChatGPTCatalogField.INPUT_MODALITIES in chatGPTCatalogFields) listed.inputModalities else inputModalities,
        outputModalities = if (ChatGPTCatalogField.OUTPUT_MODALITIES in chatGPTCatalogFields) listed.outputModalities else outputModalities,
        abilities = if (ChatGPTCatalogField.ABILITIES in chatGPTCatalogFields) listed.abilities else abilities,
        contextLength = if (ChatGPTCatalogField.CONTEXT_LENGTH in chatGPTCatalogFields) listed.contextLength else contextLength,
    )

/** Explicitly adopt account capabilities without changing the user's context window preference. */
internal fun Model.useAccountCapabilities(listed: Model): Model =
    copy(chatGPTCatalogFields = chatGPTCatalogFields + ChatGPTCatalogField.capabilities).withCatalogMetadata(listed)
