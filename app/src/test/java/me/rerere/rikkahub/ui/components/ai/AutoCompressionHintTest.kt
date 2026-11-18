package me.rerere.rikkahub.ui.components.ai

import me.rerere.rikkahub.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoCompressionHintTest {
    @Test
    fun `identifies each missing threshold input and treats reported zero as known`() {
        assertEquals(R.string.chat_page_compress_missing_window_and_usage, autoCompressionHint(null, null))
        assertEquals(R.string.chat_page_compress_missing_window, autoCompressionHint(null, 0))
        assertEquals(R.string.chat_page_compress_missing_usage, autoCompressionHint(128_000, null))
        assertNull(autoCompressionHint(128_000, 0))
    }
}
