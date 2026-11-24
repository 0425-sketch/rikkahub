package me.rerere.rikkahub.ui.pages.setting

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ChatGPTModelCapabilities
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PromptEnhancementSettingsPageTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun choosingDifferentBudgetsAndResettingTheIncompatibleOneAffectsOnlyItsRule() {
        val model = Model(chatGPTCapabilities = ChatGPTModelCapabilities(reasoningEfforts = listOf("low", "medium")))
        val lowOnly = Model(chatGPTCapabilities = ChatGPTModelCapabilities(reasoningEfforts = listOf("low")))
        var settings by mutableStateOf(
            Settings(
                providers = listOf(ProviderSetting.ChatGPT(models = listOf(model, lowOnly))),
                promptEnhancementModelId = model.id,
            )
        )
        compose.setContent {
            MaterialTheme {
                PromptSettingsPage(settings, { update -> settings = update(settings) }, PaddingValues(0.dp))
            }
        }

        fun choose(card: String, effort: String) {
            val group = hasTestTag(card)
            compose.onNode(hasScrollAction()).performScrollToNode(group)
            val pick = hasText(app.getString(R.string.setting_provider_page_reasoning))
            compose.onNode(pick and hasAnyAncestor(group)).performClick()
            compose.onNodeWithText(app.getString(R.string.reasoning_chatgpt_effort, effort)).performClick()
            Espresso.pressBack()
        }

        choose("CHAT_ENHANCEMENT", "low")
        compose.runOnIdle {
            assertEquals(ReasoningLevel.LOW.budgetTokens, settings.chatEnhancementThinkingBudget)
            assertEquals(null, settings.imageEnhancementThinkingBudget)
        }
        choose("IMAGE_ENHANCEMENT", "medium")
        compose.runOnIdle {
            assertEquals(ReasoningLevel.LOW.budgetTokens, settings.chatEnhancementThinkingBudget)
            assertEquals(ReasoningLevel.MEDIUM.budgetTokens, settings.imageEnhancementThinkingBudget)
            settings = settings.copy(promptEnhancementModelId = lowOnly.id)
        }

        val image = hasTestTag("IMAGE_ENHANCEMENT")
        compose.onNode(hasScrollAction()).performScrollToNode(image)
        val warning = app.getString(R.string.reasoning_chatgpt_saved_unsupported, app.getString(R.string.reasoning_medium))
        compose.onNode(hasText(warning) and hasAnyAncestor(image)).assertExists()
        val reset = hasContentDescription(app.getString(R.string.setting_model_page_reset_to_default))
        compose.onNode(reset and hasAnyAncestor(image)).performClick()
        compose.runOnIdle {
            assertEquals(ReasoningLevel.LOW.budgetTokens, settings.chatEnhancementThinkingBudget)
            assertEquals(null, settings.imageEnhancementThinkingBudget)
        }
    }
}
