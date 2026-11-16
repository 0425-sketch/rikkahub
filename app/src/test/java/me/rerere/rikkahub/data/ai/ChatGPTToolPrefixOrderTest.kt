package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.skills.SkillMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File

class ChatGPTToolPrefixOrderTest {
    @Test
    fun `ChatGPT tool guidance order is stable after catalog refresh while other providers retain order`() {
        val tools = listOf("zeta", "alpha").map { name ->
            Tool(name, "tool", systemPrompt = { _, _ -> "Guidance for $name" }, execute = { emptyList() })
        }
        val first = tools.forStableChatGPTPrefix(ProviderSetting.ChatGPT())
        val second = tools.reversed().forStableChatGPTPrefix(ProviderSetting.ChatGPT())
        assertEquals(listOf("alpha", "zeta"), first.map { it.name })
        assertEquals(first.map { it.systemPrompt(Model(), emptyList()) }, second.map { it.systemPrompt(Model(), emptyList()) })
        assertSame(tools, tools.forStableChatGPTPrefix(ProviderSetting.Google()))
        assertSame(tools, tools.forStableChatGPTPrefix(ProviderSetting.OpenAI()))
    }

    @Test
    fun `filesystem skill enumeration cannot reorder ChatGPT system guidance`() {
        val skills = listOf("zeta", "alpha").map { SkillMetadata(name = it, description = "Description $it", skillDir = File("/$it")) }
        val enabled = setOf("alpha", "zeta")
        fun prompt(catalog: List<SkillMetadata>, stable: Boolean) = createSkillTools(enabled, catalog, stable)
            .single().systemPrompt(Model(), emptyList())
        assertEquals(prompt(skills, true), prompt(skills.reversed(), true))
        assertNotEquals(prompt(skills, false), prompt(skills.reversed(), false))
        assertTrueOrder(prompt(skills, true))
    }

    private fun assertTrueOrder(prompt: String) {
        org.junit.Assert.assertTrue(prompt.indexOf("<name>alpha</name>") < prompt.indexOf("<name>zeta</name>"))
    }
}
