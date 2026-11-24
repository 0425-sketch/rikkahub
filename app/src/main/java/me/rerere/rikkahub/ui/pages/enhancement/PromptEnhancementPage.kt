package me.rerere.rikkahub.ui.pages.enhancement

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ProviderManager
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.PromptEnhancementMode
import me.rerere.rikkahub.data.ai.UsageRecorder
import me.rerere.rikkahub.data.ai.enhancePrompt
import me.rerere.rikkahub.ui.components.ai.promptEnhancementErrorMessage
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.getText
import me.rerere.ui.toast.ToastType
import org.koin.compose.koinInject

// Retain long text across rotations in this navigation entry without adding it to savedInstanceState.
private class PromptEnhancementPageState : ViewModel() {
    var source by mutableStateOf("")
        private set
    var result by mutableStateOf("")
    var revision = 0L
        private set

    fun updateSource(text: String) {
        if (source == text) return
        source = text
        revision++
        result = ""
    }
}

@Composable
fun PromptEnhancementPage() {
    val page = viewModel { PromptEnhancementPageState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings = LocalSettings.current
    val providers: ProviderManager = koinInject()
    val usage: UsageRecorder = koinInject()
    val clipboard = LocalClipboard.current
    val toaster = LocalToaster.current
    var running by remember { mutableStateOf(false) }

    fun enhance() {
        if (running || page.source.isBlank()) return
        val submittedRevision = page.revision
        val submittedText = page.source
        running = true
        page.result = ""
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val rewritten = enhancePrompt(settings, providers, PromptEnhancementMode.CHAT, submittedText, usage)
                currentCoroutineContext().ensureActive()
                if (submittedRevision == page.revision) page.result = rewritten
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (submittedRevision == page.revision) {
                    toaster.show(context.getString(e.promptEnhancementErrorMessage()), ToastType.Error)
                }
            } finally {
                running = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.prompt_enhancement_page_title)) },
                navigationIcon = { BackButton() },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = page.source,
                onValueChange = page::updateSource,
                label = { Text(stringResource(R.string.prompt_enhancement_page_source)) },
                modifier = Modifier.fillMaxWidth().testTag("prompt_enhancement_source"),
                minLines = 4,
                maxLines = 10,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = {
                    val whenClicked = page.revision
                    scope.launch {
                        val pasted = clipboard.getClipEntry()?.clipData?.getText()
                        if (!pasted.isNullOrEmpty() && whenClicked == page.revision) page.updateSource(pasted)
                    }
                }) {
                    Text(stringResource(R.string.translator_page_paste))
                }
                Button(
                    onClick = ::enhance,
                    enabled = page.source.isNotBlank() && !running,
                    modifier = Modifier.testTag("prompt_enhancement_run"),
                ) {
                    Text(stringResource(if (running) R.string.prompt_enhancement_working else R.string.prompt_enhancement_page_run))
                }
            }
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.prompt_enhancement_page_result),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            enabled = page.result.isNotEmpty(),
                            onClick = {
                                val text = page.result
                                if (text.isNotEmpty()) {
                                    scope.launch {
                                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, text)))
                                        toaster.show(context.getString(R.string.copied), ToastType.Success)
                                    }
                                }
                            },
                        ) { Text(stringResource(R.string.prompt_enhancement_page_copy)) }
                    }
                    if (page.result.isNotEmpty()) {
                        SelectionContainer {
                            Text(page.result, modifier = Modifier.testTag("prompt_enhancement_result"))
                        }
                    }
                }
            }
        }
    }
}
