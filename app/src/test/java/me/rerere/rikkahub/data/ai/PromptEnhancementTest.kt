package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_CHAT_ENHANCEMENT_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_IMAGE_ENHANCEMENT_PROMPT
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.uuid.Uuid

class PromptEnhancementTest {
    private val route = ProviderSetting.Google()
    private val model = Model(
        modelId = "enhancer",
        providerOverwrite = route,
        outputModalities = listOf(Modality.TEXT, Modality.IMAGE),
        tools = setOf(BuiltInTools.Search, BuiltInTools.ImageGeneration),
        customHeaders = listOf(CustomHeader("X-Gateway", "fixture")),
    )
    private val owner = ProviderSetting.OpenAI(models = listOf(model))
    private val settings = Settings(
        providers = listOf(owner),
        promptEnhancementModelId = model.id,
        chatEnhancementPrompt = "我的聊天规则",
        imageEnhancementPrompt = "我的生图规则",
    )
    private val draft = "  第一行\n\n第二行  "

    @Test
    fun `each entry sends exactly its rule and the verbatim draft through the same text model`() = runBlocking {
        for ((mode, rule) in listOf(
            PromptEnhancementMode.CHAT to settings.chatEnhancementPrompt,
            PromptEnhancementMode.IMAGE to settings.imageEnhancementPrompt,
        )) {
            val enhanced = enhancePrompt(settings, mode, draft) { provider, messages, params ->
                assertEquals(route.id, provider.id)
                assertEquals(model.id, params.model.id)
                assertEquals(model.customHeaders, params.customHeaders)
                assertTrue(params.tools.isEmpty())
                assertTrue(params.model.tools.isEmpty())
                assertEquals(listOf(Modality.TEXT), params.model.outputModalities)
                assertEquals(listOf(MessageRole.SYSTEM, MessageRole.USER), messages.map { it.role })
                assertEquals(
                    listOf(listOf(UIMessagePart.Text(rule)), listOf(UIMessagePart.Text(draft))),
                    messages.map { it.parts },
                )
                reply(UIMessagePart.Reasoning("推理内容"), UIMessagePart.Text("草稿一\n草稿二"))
            }
            assertEquals("草稿一\n草稿二", enhanced)
        }
        assertEquals(setOf(BuiltInTools.Search, BuiltInTools.ImageGeneration), model.tools)
    }

    @Test
    fun `blank custom rules fall back independently`() = runBlocking {
        val blank = settings.copy(chatEnhancementPrompt = " \n", imageEnhancementPrompt = "")
        for ((mode, default) in listOf(
            PromptEnhancementMode.CHAT to DEFAULT_CHAT_ENHANCEMENT_PROMPT,
            PromptEnhancementMode.IMAGE to DEFAULT_IMAGE_ENHANCEMENT_PROMPT,
        )) {
            enhancePrompt(blank, mode, draft) { _, messages, _ ->
                assertEquals(default, messages.first().toText())
                reply(UIMessagePart.Text("草稿"))
            }
        }
    }

    @Test
    fun `missing deleted disabled and non-text models never call the provider`() {
        fun withModel(replacement: Model) = settings.copy(providers = listOf(owner.copy(models = listOf(replacement))))
        val invalidSettings = listOf(
            settings.copy(promptEnhancementModelId = null),
            settings.copy(promptEnhancementModelId = Uuid.random()),
            settings.copy(providers = listOf(owner.copy(enabled = false))),
            withModel(model.copy(type = ModelType.EMBEDDING)),
            withModel(model.copy(outputModalities = listOf(Modality.IMAGE))),
        )
        for (invalid in invalidSettings) {
            assertThrows(PromptEnhancementModelUnavailable::class.java) {
                runBlocking {
                    enhancePrompt(invalid, PromptEnhancementMode.CHAT, draft) { _, _, _ -> error("unexpected request") }
                }
            }
        }
    }

    @Test
    fun `whitespace-only draft never calls the provider`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                enhancePrompt(settings, PromptEnhancementMode.CHAT, " \n") { _, _, _ -> error("unexpected request") }
            }
        }
    }

    @Test
    fun `blank replies and reasoning without draft text cannot replace a draft`() {
        val invalid = listOf(
            reply(UIMessagePart.Text(" \n ")),
            reply(UIMessagePart.Reasoning("thought")),
            reply(UIMessagePart.Text("<think>thought</think>")),
        )
        for (response in invalid) {
            assertThrows(EmptyPromptEnhancementResult::class.java) {
                runBlocking { enhancePrompt(settings, PromptEnhancementMode.IMAGE, draft) { _, _, _ -> response } }
            }
        }
    }

    @Test
    fun `provider failure and cancellation propagate to the caller`() {
        for (failure in listOf(IOException("offline"), CancellationException("switched draft"))) {
            val actual = assertThrows(Exception::class.java) {
                runBlocking { enhancePrompt(settings, PromptEnhancementMode.CHAT, draft) { _, _, _ -> throw failure } }
            }
            assertEquals(failure.javaClass, actual.javaClass)
            assertEquals(failure.message, actual.message)
        }
    }

    private fun reply(vararg parts: UIMessagePart) = TextGenerationResult(
        id = "fixture",
        model = model.modelId,
        message = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList()),
    )
}
