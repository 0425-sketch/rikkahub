package me.rerere.ai.provider

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInToolSupportTest {
    private val allTools = linkedSetOf(BuiltInTools.Search, BuiltInTools.UrlContext, BuiltInTools.ImageGeneration)

    @Test
    fun `ChatGPT capability reasons distinguish hosted generation from vision`() {
        val provider = ProviderSetting.ChatGPT()
        val model = Model(inputModalities = listOf(Modality.TEXT, Modality.IMAGE))
        assertEquals(BuiltInToolUnavailableReason.CHATGPT_IMAGE_GENERATION, provider.builtInToolSupport(model, BuiltInTools.ImageGeneration).reason)
        assertEquals(BuiltInToolUnavailableReason.CHATGPT_URL_CONTEXT, provider.builtInToolSupport(model, BuiltInTools.UrlContext).reason)
        assertTrue(provider.builtInToolSupport(model, BuiltInTools.Search).supported)
        val noSearch = model.copy(chatGPTCapabilities = ChatGPTModelCapabilities(supportsWebSearch = false))
        assertEquals(BuiltInToolUnavailableReason.MODEL_WEB_SEARCH, provider.builtInToolSupport(noSearch, BuiltInTools.Search).reason)
    }

    @Test
    fun `API key Responses and Gemini retain their supported tool choices`() {
        val model = Model()
        val responses = ProviderSetting.OpenAI(useResponseApi = true)
        assertTrue(responses.builtInToolSupport(model, BuiltInTools.ImageGeneration).supported)
        assertTrue(responses.builtInToolSupport(model, BuiltInTools.Search).supported)
        assertFalse(responses.builtInToolSupport(model, BuiltInTools.UrlContext).supported)
        allTools.forEach {
            assertTrue(ProviderSetting.Google().builtInToolSupport(model, it).supported)
            assertEquals(BuiltInToolUnavailableReason.RESPONSES_REQUIRED, ProviderSetting.OpenAI(useResponseApi = false).builtInToolSupport(model, it).reason)
        }
        listOf(ProviderSetting.Claude(), ProviderSetting.Grok()).forEach {
            assertTrue(it.builtInToolSupport(model, BuiltInTools.Search).supported)
            assertFalse(it.builtInToolSupport(model, BuiltInTools.UrlContext).supported)
        }
    }

    @Test
    fun `effective provider override determines compatibility in both directions`() {
        val chatGPTParent = ProviderSetting.ChatGPT()
        val googleParent = ProviderSetting.Google()
        val overriddenToGoogle = Model(tools = allTools, providerOverwrite = googleParent)
        assertTrue(chatGPTParent.builtInToolSupport(overriddenToGoogle, BuiltInTools.ImageGeneration).supported)
        assertSame(overriddenToGoogle, overriddenToGoogle.withCompatibleChatGPTTools(chatGPTParent))
        val overriddenToChatGPT = Model(tools = allTools, providerOverwrite = chatGPTParent)
        assertFalse(googleParent.builtInToolSupport(overriddenToChatGPT, BuiltInTools.ImageGeneration).supported)
        assertEquals(setOf(BuiltInTools.Search), overriddenToChatGPT.withCompatibleChatGPTTools(googleParent).tools)
    }

    @Test
    fun `compatibility removes only incompatible saved switches`() {
        val original = Model(
            modelId = "model-from-account",
            displayName = "Personal model",
            tools = allTools,
            abilities = listOf(ModelAbility.TOOL),
            inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
            customHeaders = listOf(CustomHeader("X-Test", "value")),
            customBodies = listOf(CustomBody("custom", JsonPrimitive("keep"))),
        )
        val cleaned = original.withCompatibleChatGPTTools(ProviderSetting.ChatGPT())
        assertEquals(original.copy(tools = setOf(BuiltInTools.Search)), cleaned)
        assertSame(cleaned, cleaned.withCompatibleChatGPTTools(ProviderSetting.ChatGPT()))
        assertSame(original, original.withCompatibleChatGPTTools(ProviderSetting.OpenAI(useResponseApi = true)))
        assertSame(original, original.withCompatibleChatGPTTools(ProviderSetting.Google()))
    }
}
