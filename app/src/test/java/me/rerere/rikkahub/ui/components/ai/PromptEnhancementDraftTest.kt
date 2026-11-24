package me.rerere.rikkahub.ui.components.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.EmptyPromptEnhancementResult
import me.rerere.rikkahub.ui.hooks.ChatInputState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class PromptEnhancementDraftTest {
    private val original = "  先只读\n\n等我确认再做。\n"
    private val input = ChatInputState().apply { setMessageText(original) }
    private fun editor() = PromptEnhancementDraft(input.textContent, { input.draftEpoch }, { input.editRevision })
    private val text get() = input.textContent.text.toString()

    @Test
    fun `success changes only the text and undo restores the original including whitespace`() = runBlocking {
        val attachment = UIMessagePart.Image("file:///reference.png")
        input.messageContent = listOf(attachment)
        val draft = editor()

        draft.enhance {
            assertEquals(original, it)
            "新稿"
        }

        assertEquals(listOf(UIMessagePart.Text("新稿"), attachment), input.getContents())
        assertTrue(draft.canUndo)
        draft.undo()
        assertEquals(listOf(UIMessagePart.Text(original), attachment), input.getContents())
        assertFalse(draft.canUndo)
    }

    @Test
    fun `blank drafts and repeated clicks during a request make no additional calls`() = runBlocking {
        val draft = editor()
        val result = CompletableDeferred<String>()
        val pending = launch(start = CoroutineStart.UNDISPATCHED) { draft.enhance { result.await() } }
        assertTrue(draft.running)
        draft.enhance { error("Duplicate request") }
        result.complete("新稿")
        pending.join()
        assertEquals("新稿", text)
        assertFalse(draft.running)

        input.setMessageText(" \n ")
        draft.enhance { error("Blank draft sent") }
        assertEquals(" \n ", text)
        assertFalse(draft.running)
    }

    @Test
    fun `consecutive rewrites undo only the latest change`() = runBlocking {
        val draft = editor()
        draft.enhance { "第一稿\n" }
        draft.enhance {
            assertEquals("第一稿\n", it)
            "第二稿"
        }
        draft.undo()
        assertEquals("第一稿\n", text)
        draft.undo()
        assertEquals("第一稿\n", text)
    }

    @Test
    fun `adopting a conflicting result preserves the latest manual text for undo`() = runBlocking {
        val draft = editor()
        draft.enhance {
            input.setMessageText("正在补充")
            "建议稿"
        }
        assertEquals("正在补充", text)
        assertEquals("建议稿", draft.suggestion)
        input.setMessageText("最终补充\n")
        draft.accept()
        assertEquals("建议稿", text)
        assertNull(draft.suggestion)
        draft.undo()
        assertEquals("最终补充\n", text)
    }

    @Test
    fun `editing away and back before completion still allows dismissing the suggestion`() = runBlocking {
        val draft = editor()
        draft.enhance {
            input.setMessageText("临时修改")
            input.setMessageText(original)
            "建议稿"
        }
        assertEquals("建议稿", draft.suggestion)
        draft.dismiss()
        assertEquals(original, text)
        assertNull(draft.suggestion)
        assertFalse(draft.canUndo)
    }

    @Test
    fun `undo cannot overwrite later typing even if the text matches the enhanced result again`() = runBlocking {
        val draft = editor()
        draft.enhance { "新稿" }
        input.setMessageText("新稿已改")
        input.setMessageText("新稿")
        assertFalse(draft.canUndo)
        draft.undo()
        assertEquals("新稿", text)
    }

    @Test
    fun `a failed or blank reply leaves the text intact and allows retry`() {
        val draft = editor()
        assertThrows(IOException::class.java) {
            runBlocking { draft.enhance { throw IOException("offline") } }
        }
        assertEquals(original, text)
        assertFalse(draft.running)
        assertThrows(EmptyPromptEnhancementResult::class.java) {
            runBlocking { draft.enhance { " \n " } }
        }
        assertEquals(original, text)
        assertFalse(draft.running)
        assertNull(draft.suggestion)
        assertFalse(draft.canUndo)
        runBlocking { draft.enhance { "重试成功" } }
        assertEquals("重试成功", text)
    }

    @Test
    fun `a late reply cannot change another draft or its running request`() = runBlocking {
        val oldDraft = editor()
        val oldResult = CompletableDeferred<String>()
        val oldJob = launch(start = CoroutineStart.UNDISPATCHED) { oldDraft.enhance { oldResult.await() } }
        input.replaceDraftText(original)
        val newDraft = editor()
        val newResult = CompletableDeferred<String>()
        val newJob = launch(start = CoroutineStart.UNDISPATCHED) { newDraft.enhance { newResult.await() } }

        oldResult.complete("旧请求")
        oldJob.join()
        assertFalse(oldDraft.isCurrent)
        assertNull(oldDraft.suggestion)
        assertEquals(original, text)
        assertTrue(newDraft.running)
        newResult.complete("新请求")
        newJob.join()
        assertEquals("新请求", text)
    }

    @Test
    fun `stale undo and pending suggestions cannot change a replacement draft`() = runBlocking {
        val first = editor()
        first.enhance { "相同内容" }
        input.replaceDraftText("相同内容")
        first.undo()
        assertEquals("相同内容", text)

        val second = editor()
        second.enhance {
            input.setMessageText("刚刚修改")
            "上一份草稿的建议"
        }
        assertEquals("上一份草稿的建议", second.suggestion)
        input.replaceDraftText("另一份草稿")
        second.accept()
        assertEquals("另一份草稿", text)
        assertNull(second.suggestion)
    }

    @Test
    fun `cancelled editor ignores a reply even when the provider ignores cancellation`() = runBlocking {
        val draft = editor()
        lateinit var response: Continuation<String>
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            draft.enhance { suspendCoroutine { response = it } }
        }
        job.cancel()
        response.resume("迟到的回复")
        job.join()

        assertTrue(job.isCancelled)
        assertFalse(draft.running)
        assertEquals(original, text)
        assertNull(draft.suggestion)
        assertFalse(draft.canUndo)
    }
}
