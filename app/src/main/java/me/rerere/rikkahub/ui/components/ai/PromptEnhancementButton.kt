package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ProviderManager
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.EmptyPromptEnhancementResult
import me.rerere.rikkahub.data.ai.PromptEnhancementMode
import me.rerere.rikkahub.data.ai.PromptEnhancementModelUnavailable
import me.rerere.rikkahub.data.ai.UsageRecorder
import me.rerere.rikkahub.data.ai.enhancePrompt
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.ui.components.Tooltip
import me.rerere.ui.toast.ToastType
import org.koin.compose.koinInject

// All editor reads and writes take place on the UI thread. Check the live owner and
// revision again at each write, even when Compose has yet to redraw the controls.
internal class PromptEnhancementDraft(
    private val field: TextFieldState,
    private val draftEpoch: () -> Long,
    private val editRevision: () -> Long,
) {
    private val owner = draftEpoch()
    private data class Edit(val before: String, val after: String, val revision: Long)

    private var lastEdit by mutableStateOf<Edit?>(null)
    var running by mutableStateOf(false)
        private set
    var suggestion by mutableStateOf<String?>(null)
        private set

    val isCurrent get() = owner == draftEpoch()
    val canUndo get() = isCurrent && lastEdit?.let {
        it.revision == editRevision() && field.text.contentEquals(it.after)
    } == true

    suspend fun enhance(rewrite: suspend (String) -> String) {
        if (!isCurrent || running || field.text.isBlank()) return
        val original = field.text.toString()
        val revision = editRevision()
        running = true
        try {
            val rewritten = rewrite(original)
            currentCoroutineContext().ensureActive()
            if (!isCurrent) return
            if (rewritten.isBlank()) throw EmptyPromptEnhancementResult()
            if (editRevision() == revision && field.text.contentEquals(original)) {
                replace(rewritten)
            } else {
                suggestion = rewritten
            }
        } finally {
            running = false
        }
    }

    fun accept() {
        if (isCurrent) suggestion?.let(::replace)
        dismiss()
    }

    fun dismiss() {
        suggestion = null
    }

    fun undo() {
        if (canUndo) lastEdit?.let { field.setTextAndPlaceCursorAtEnd(it.before) }
        lastEdit = null
    }

    private fun replace(rewritten: String) {
        val before = field.text.toString()
        field.setTextAndPlaceCursorAtEnd(rewritten)
        lastEdit = Edit(before, rewritten, editRevision())
    }
}

@Composable
internal fun PromptEnhancementButton(
    mode: PromptEnhancementMode,
    textState: TextFieldState,
    draftEpoch: () -> Long,
    editRevision: () -> Long,
) {
    key(textState, draftEpoch(), mode) {
        val editor = remember { PromptEnhancementDraft(textState, draftEpoch, editRevision) }
        val scope = rememberCoroutineScope()
        val settings = LocalSettings.current
        val providers: ProviderManager = koinInject()
        val usage: UsageRecorder = koinInject()
        val context = LocalContext.current
        val toaster = LocalToaster.current
        var reviewing by remember { mutableStateOf(false) }
        val suggestion = editor.suggestion

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (suggestion != null) {
                TextButton(onClick = { reviewing = true }) {
                    Text(stringResource(R.string.prompt_enhancement_review))
                }
            } else {
                val label = stringResource(
                    if (editor.running) R.string.prompt_enhancement_working else R.string.prompt_enhancement
                )
                Tooltip(tooltip = { Text(label) }) {
                    IconButton(
                        enabled = !editor.running && textState.text.isNotBlank(),
                        onClick = {
                            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                try {
                                    editor.enhance { draft -> enhancePrompt(settings, providers, mode, draft, usage) }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    currentCoroutineContext().ensureActive()
                                    if (editor.isCurrent) {
                                        val message = when (e) {
                                            is PromptEnhancementModelUnavailable -> R.string.prompt_enhancement_select_model
                                            is EmptyPromptEnhancementResult -> R.string.prompt_enhancement_empty
                                            else -> R.string.prompt_enhancement_failed
                                        }
                                        toaster.show(context.getString(message), ToastType.Error)
                                    }
                                }
                            }
                        },
                    ) {
                        if (editor.running) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp).semantics { contentDescription = label },
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(HugeIcons.Sparkles, contentDescription = label, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                if (editor.canUndo && !editor.running) {
                    TextButton(onClick = editor::undo) { Text(stringResource(R.string.prompt_enhancement_undo)) }
                }
            }
        }
        if (reviewing && suggestion != null) {
            AlertDialog(
                onDismissRequest = { reviewing = false },
                title = { Text(stringResource(R.string.prompt_enhancement_conflict)) },
                text = {
                    Text(suggestion, modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()))
                },
                confirmButton = {
                    TextButton(onClick = {
                        editor.accept()
                        reviewing = false
                    }) {
                        Text(stringResource(R.string.prompt_enhancement_apply))
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        editor.dismiss()
                        reviewing = false
                    }) {
                        Text(stringResource(R.string.prompt_enhancement_keep))
                    }
                },
            )
        }
    }
}
