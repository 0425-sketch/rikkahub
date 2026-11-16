package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting

/** OAuth token sets stay on this installation, never in archives or browser settings snapshots. */
internal fun Settings.withoutChatGPTCredentials(): Settings = copy(
    providers = providers.map { it.withoutChatGPTCredentials() },
)

private fun ProviderSetting.withoutChatGPTCredentials(): ProviderSetting {
    val portableModels = models.map { model ->
        val overwrite = model.providerOverwrite ?: return@map model
        model.copy(providerOverwrite = overwrite.withoutChatGPTCredentials())
    }
    val provider = if (portableModels == models) this else copyProvider(models = portableModels)
    return if (provider is ProviderSetting.ChatGPT) provider.copy(credential = null) else provider
}
