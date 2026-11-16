package me.rerere.rikkahub.data.datastore

import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.serialization.json.JsonArray
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.ChatGPTModelCapabilities
import me.rerere.ai.provider.ChatGPTRegistration
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGPTBuiltInToolsMigrationTest {
    private val legacyTools = setOf(BuiltInTools.Search, BuiltInTools.UrlContext, BuiltInTools.ImageGeneration)

    @Test
    fun `normalization removes only incompatible ChatGPT switches and preserves identity and image input`() {
        val model = Model(
            modelId = "account-model",
            displayName = "My model",
            tools = legacyTools,
            inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
            customHeaders = listOf(CustomHeader("X-Example", "value")),
            contextLength = 250_000,
        )
        val credential = OAuthCredential(accessToken = "synthetic-session", refreshToken = "synthetic-renewal")
        val registration = ChatGPTRegistration(clientId = "oaiapp_synthetic", subject = "synthetic-user")
        val chatGPT = ProviderSetting.ChatGPT(models = listOf(model), credential = credential, chatGPTRegistration = registration)
        val openAI = ProviderSetting.OpenAI(models = listOf(model.copy()), useResponseApi = true)
        val google = ProviderSetting.Google(models = listOf(model.copy()))
        val settings = Settings(providers = listOf(chatGPT, openAI, google), titlePrompt = "Keep this prompt")

        val updated = settings.normalized()
        val actual = updated.providers.single { it.id == chatGPT.id } as ProviderSetting.ChatGPT
        assertEquals(model.copy(tools = setOf(BuiltInTools.Search)), actual.models.single())
        assertSame(credential, actual.credential)
        assertEquals(registration, actual.chatGPTRegistration)
        assertEquals(openAI, updated.providers.single { it.id == openAI.id })
        assertEquals(google, updated.providers.single { it.id == google.id })
        assertEquals(settings.titlePrompt, updated.titlePrompt)
        assertTrue(updated.chatGPTToolMigrationNoticePending)
    }

    @Test
    fun `migration follows effective provider overrides without changing other routes`() {
        val overriddenToChatGPT = Model(
            modelId = "official-route",
            tools = legacyTools,
            providerOverwrite = ProviderSetting.ChatGPT(),
        )
        val overriddenToGoogle = Model(
            modelId = "google-route",
            tools = legacyTools,
            providerOverwrite = ProviderSetting.Google(),
        )
        val openAI = ProviderSetting.OpenAI(models = listOf(overriddenToChatGPT))
        val chatGPT = ProviderSetting.ChatGPT(models = listOf(overriddenToGoogle))

        val updated = Settings(providers = listOf(openAI, chatGPT)).withCompatibleChatGPTBuiltInTools()
        assertEquals(setOf(BuiltInTools.Search), updated.providers[0].models.single().tools)
        assertEquals(overriddenToChatGPT.providerOverwrite, updated.providers[0].models.single().providerOverwrite)
        assertEquals(chatGPT, updated.providers[1])
    }

    @Test
    fun `migration notice survives persistence until acknowledged and does not reappear on later launches`() {
        val chatGPT = ProviderSetting.ChatGPT(models = listOf(Model(modelId = "old", tools = legacyTools)))
        val oldStored = mutablePreferencesOf().apply { putSettings(Settings(providers = listOf(chatGPT))) }
        val migrated = oldStored.toSettings().normalized()
        assertTrue(migrated.chatGPTToolMigrationNoticePending)

        val migratedStored = mutablePreferencesOf().apply { putSettings(migrated) }
        val afterRestart = migratedStored.toSettings().normalized()
        assertTrue(afterRestart.chatGPTToolMigrationNoticePending)
        val acknowledged = afterRestart.copy(chatGPTToolMigrationNoticePending = false).normalized()
        val acknowledgedStored = mutablePreferencesOf().apply { putSettings(acknowledged) }
        val reopened = acknowledgedStored.toSettings().normalized()
        assertFalse(reopened.chatGPTToolMigrationNoticePending)
        assertEquals(setOf(BuiltInTools.Search), reopened.providers.single { it.id == chatGPT.id }.models.single().tools)
    }

    @Test
    fun `explicit model search limitation is migrated without touching advanced custom request bodies`() {
        val advanced = listOf(CustomBody("tools", JsonArray(emptyList())))
        val model = Model(
            modelId = "no-search",
            tools = legacyTools,
            customBodies = advanced,
            chatGPTCapabilities = ChatGPTModelCapabilities(supportsWebSearch = false),
        )
        val provider = ProviderSetting.ChatGPT(models = listOf(model))
        val updated = Settings(providers = listOf(provider)).withCompatibleChatGPTBuiltInTools()
        val migrated = updated.providers.single().models.single()
        assertTrue(migrated.tools.isEmpty())
        assertEquals(advanced, migrated.customBodies)
        assertTrue(updated.chatGPTToolMigrationNoticePending)
    }

    @Test
    fun `compatible providers are unchanged and never produce migration notice`() {
        val providers = listOf(
            ProviderSetting.ChatGPT(models = listOf(Model(tools = setOf(BuiltInTools.Search)))),
            ProviderSetting.OpenAI(models = listOf(Model(tools = legacyTools)), useResponseApi = true),
            ProviderSetting.Google(models = listOf(Model(tools = legacyTools))),
        )
        val source = Settings(providers = providers)
        assertSame(source, source.withCompatibleChatGPTBuiltInTools())
        assertFalse(source.chatGPTToolMigrationNoticePending)
    }
}
