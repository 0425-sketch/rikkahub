package me.rerere.rikkahub.ui.hooks

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.ui.components.ai.PromptEnhancementDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatInputStateTest {
    private fun editing(parts: List<UIMessagePart>) = ChatInputState().apply {
        editingMessage = Uuid.random()
        setContents(parts)
    }

    @Test
    fun `editing away and back changes the revision while moving the cursor does not`() {
        val state = ChatInputState()
        state.setMessageText("原文")
        val revision = state.editRevision
        val epoch = state.draftEpoch

        state.textContent.edit {
            selection = TextRange(0)
            with(state.trackTextEdits) { transformInput() }
        }
        assertEquals(revision, state.editRevision)
        state.textContent.edit {
            append("补充")
            with(state.trackTextEdits) { transformInput() }
        }
        val typedRevision = state.editRevision
        assertTrue(typedRevision > revision)
        state.setMessageText("原文")

        assertTrue(state.editRevision > typedRevision)
        assertEquals(epoch, state.draftEpoch)
    }

    @Test
    fun `clearing or replacing a draft retires its identity even for identical text`() {
        val state = ChatInputState()
        state.setMessageText("同一段文字")
        val originalEpoch = state.draftEpoch
        state.replaceDraftText("同一段文字")
        val replacementEpoch = state.draftEpoch
        state.setContents(listOf(UIMessagePart.Text("同一段文字")))
        val historyEpoch = state.draftEpoch
        state.clearInput()

        assertTrue(originalEpoch < replacementEpoch)
        assertTrue(replacementEpoch < historyEpoch)
        assertTrue(historyEpoch < state.draftEpoch)
    }

    @Test
    fun `enhancement and undo only change the text of the open draft`() = runTest {
        val original = "第一段\n\n第二段\n"
        val image = UIMessagePart.Image("file:///draft-image.png")
        val state = editing(listOf(UIMessagePart.Text(original), image))
        val messageId = state.editingMessage
        val enhancer = PromptEnhancementDraft(state.textContent, { state.draftEpoch }, { state.editRevision })

        enhancer.enhance { draft ->
            assertEquals(original, draft)
            "整理后的文字"
        }
        assertEquals(listOf(UIMessagePart.Text("整理后的文字"), image), state.getContents())
        enhancer.undo()
        assertEquals(listOf(UIMessagePart.Text(original), image), state.getContents())
        assertEquals(messageId, state.editingMessage)
    }

    @Test
    fun `reasoning and text message allows editing reasoning in place`() {
        val reasoning = UIMessagePart.Reasoning("old thought")
        val state = editing(listOf(reasoning, UIMessagePart.Text("answer")))

        assertNotNull(state.editingReasoning)
        assertEquals(emptyList<UIMessagePart>(), state.messageContent)

        state.editingReasoning!!.setTextAndPlaceCursorAtEnd("new thought")
        state.textContent.setTextAndPlaceCursorAtEnd("new answer")

        assertEquals(
            listOf(reasoning.copy(reasoning = "new thought"), UIMessagePart.Text("new answer")),
            state.getContents(),
        )
    }

    @Test
    fun `blank reasoning is dropped`() {
        val state = editing(listOf(UIMessagePart.Reasoning("old thought"), UIMessagePart.Text("answer")))

        state.editingReasoning!!.setTextAndPlaceCursorAtEnd("  ")

        assertEquals(listOf(UIMessagePart.Text("answer")), state.getContents())
    }

    @Test
    fun `signed reasoning is not editable and kept as is`() {
        val reasoning = UIMessagePart.Reasoning(
            reasoning = "thought",
            metadata = buildJsonObject { put("signature", JsonPrimitive("sig")) },
        )
        val state = editing(listOf(reasoning, UIMessagePart.Text("answer")))

        assertNull(state.editingReasoning)
        state.textContent.setTextAndPlaceCursorAtEnd("new answer")

        assertEquals(listOf(reasoning, UIMessagePart.Text("new answer")), state.getContents())
    }

    @Test
    fun `message with other parts is not editable`() {
        val state = editing(
            listOf(
                UIMessagePart.Reasoning("thought"),
                UIMessagePart.Text("answer"),
                UIMessagePart.Image("file:///image.png"),
            )
        )

        assertNull(state.editingReasoning)
    }

    @Test
    fun `clear input resets reasoning editing`() {
        val state = editing(listOf(UIMessagePart.Reasoning("thought"), UIMessagePart.Text("answer")))

        state.clearInput()

        assertNull(state.editingReasoning)
    }
}
