package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ChatGPTRegistration
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SettingsCredentialPrivacyTest {
    @Test fun `portable settings strip current and legacy ChatGPT tokens while preserving other data`() {
        val registration = ChatGPTRegistration(clientId = "oaiapp_test", subject = "verified-user")
        val current = ProviderSetting.ChatGPT(
            name = "My account",
            models = listOf(Model(modelId = "server-model", displayName = "Server model")),
            credential = OAuthCredential(
                accessToken = "synthetic-access-secret",
                refreshToken = "synthetic-refresh-secret",
                idToken = "synthetic-id-secret",
                clientId = registration.clientId,
                issuer = registration.issuer,
                subject = registration.subject,
                protocolVersion = 1,
            ),
            chatGPTRegistration = registration,
        )
        val legacy = ProviderSetting.ChatGPT(credential = OAuthCredential(accessToken = "synthetic-legacy-secret"))
        val apiKey = ProviderSetting.OpenAI(name = "Existing API provider", apiKey = "existing-api-key")
        val grok = ProviderSetting.Grok(credential = OAuthCredential(accessToken = "existing-other-account"))
        val source = Settings(providers = listOf(current, legacy, apiKey, grok), titlePrompt = "Keep my setting")
        val portable = source.withoutChatGPTCredentials()
        val serialized = JsonInstant.encodeToString(portable)
        listOf("synthetic-access-secret", "synthetic-refresh-secret", "synthetic-id-secret", "synthetic-legacy-secret")
            .forEach { assertFalse("ChatGPT credentials reached a portable snapshot", serialized.contains(it)) }
        val restored = JsonInstant.decodeFromString<Settings>(serialized)
        assertNull((restored.providers[0] as ProviderSetting.ChatGPT).credential)
        assertNull((restored.providers[1] as ProviderSetting.ChatGPT).credential)
        assertEquals(registration, (restored.providers[0] as ProviderSetting.ChatGPT).chatGPTRegistration)
        assertEquals(current.models, restored.providers[0].models)
        assertEquals(source.assistants, restored.assistants)
        assertEquals(source.titlePrompt, restored.titlePrompt)
        assertEquals(apiKey, restored.providers[2])
        assertEquals(grok, restored.providers[3])
        assertSame(current.credential, (source.providers[0] as ProviderSetting.ChatGPT).credential)
    }

    @Test fun `model provider overrides cannot smuggle a token into backups`() {
        val override = ProviderSetting.ChatGPT(credential = OAuthCredential(accessToken = "nested-secret"))
        val source = Settings(providers = listOf(ProviderSetting.OpenAI(
            apiKey = "existing-api-key",
            models = listOf(Model(modelId = "configured-model", providerOverwrite = override)),
        )))
        val portable = source.withoutChatGPTCredentials()
        assertFalse(JsonInstant.encodeToString(portable).contains("nested-secret"))
        val provider = portable.providers.single() as ProviderSetting.OpenAI
        assertEquals("existing-api-key", provider.apiKey)
        assertEquals("configured-model", provider.models.single().modelId)
        assertNull((provider.models.single().providerOverwrite as ProviderSetting.ChatGPT).credential)
    }

}
