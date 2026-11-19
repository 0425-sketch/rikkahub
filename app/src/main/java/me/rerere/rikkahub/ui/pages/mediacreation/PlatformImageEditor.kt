package me.rerere.rikkahub.ui.pages.mediacreation

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Redo
import me.rerere.hugeicons.stroke.Undo
import me.rerere.mediagen.provider.providers.openaiplatform.PlatformImageCapabilities
import me.rerere.rikkahub.data.files.PlatformImageFiles
import me.rerere.rikkahub.data.model.MediaCreationParams
import me.rerere.ui.sketch.SketchEditCanvas
import me.rerere.ui.sketch.SketchEditLayer
import me.rerere.ui.sketch.SketchEditShape
import java.io.File

internal data class PlatformImageEdit(
    val base: File,
    val references: List<File>,
    val mask: File?,
    val markup: File?,
    val prompt: String,
    val params: MediaCreationParams,
    val forceHistory: Boolean,
)

private enum class EditTool(val label: String) { Whole("全图"), Mask("涂选局部"), Markup("圈画标注") }

/** [onSubmit] returns after copying all input files into the new record. Exceptions leave this editor open. */
@Composable
internal fun PlatformImageEditor(
    base: File,
    draftDir: File,
    modelId: String,
    initialParams: MediaCreationParams,
    onDismiss: () -> Unit,
    onSubmit: suspend (PlatformImageEdit) -> Unit,
    historyImages: List<File> = emptyList(),
    historyAvailable: Boolean = false,
    initialReferences: List<File> = emptyList(),
    initialPrompt: String = "",
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preview by remember(base) { mutableStateOf<Pair<PlatformImageFiles.ImageInfo, Bitmap>?>(null) }
    var error by remember(base) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var params by remember(base) { mutableStateOf(initialParams) }
    var showParams by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var forceHistory by remember { mutableStateOf(false) }
    var prompt by remember(base) { mutableStateOf(initialPrompt) }
    var tool by remember(base) { mutableStateOf(EditTool.Whole) }
    val brush = remember { SliderState(value = 16f, trackRange = 2f..64f) }
    var markShape by remember { mutableStateOf(SketchEditShape.Freehand) }
    var showShapes by remember { mutableStateOf(false) }
    val mask = remember(base) { SketchEditLayer() }
    val markup = remember(base) { SketchEditLayer() }
    val layer = if (tool == EditTool.Mask) mask else markup
    val references = remember(base) { mutableStateListOf<File>().apply { addAll(initialReferences.distinct() - base) } }
    val imported = remember(base) { mutableListOf<File>() }
    val compatibleHistory = remember(historyImages, base) {
        historyImages.filter { it != base && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }.distinct()
    }
    DisposableEffect(base) { onDispose { imported.forEach(File::delete) } }
    LaunchedEffect(base) {
        try {
            preview = withContext(Dispatchers.IO) {
                val info = PlatformImageFiles.info(base)
                info to PlatformImageFiles.preview(base, info)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "无法打开底图。"
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(15)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        if (references.size + uris.size > 15) {
            error = "最多可添加 15 张参考图，另含一张底图。"
        } else scope.launch {
            busy = true
            error = null
            try {
                for (uri in uris) {
                    val file = withContext(Dispatchers.IO) { PlatformImageFiles.importImage(context, uri, draftDir) }
                    imported += file
                    references += file
                }
                forceHistory = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "参考图导入失败。"
            } finally {
                busy = false
            }
        }
    }

    fun submit() {
        if (busy) return
        val (info, _) = preview ?: return
        val useMask = tool == EditTool.Mask
        val useMarkup = tool == EditTool.Markup
        val invalid = platformParameterError(modelId, true, params, mask = useMask, markup = useMarkup)
        if (invalid != null) {
            error = invalid
            return
        }
        busy = true
        error = null
        scope.launch {
            val temporary = mutableListOf<File>()
            try {
                val edit = withContext(Dispatchers.IO) {
                    val baseUpload = if (useMask) PlatformImageFiles.maskBase(base, info, draftDir) else base
                    if (baseUpload != base) temporary += baseUpload
                    val overlay = if (useMask || useMarkup) {
                        if (useMarkup) require(info.size.width.toLong() * info.size.height <= PlatformImageFiles.MAX_MARKUP_PIXELS) {
                            "标注要求底图不超过 16,777,216 像素。"
                        }
                        val bitmap = layer.render(info.size.width, info.size.height, useMask)
                        try {
                            PlatformImageFiles.writePng(bitmap, draftDir, useMask).also(temporary::add)
                        } finally {
                            bitmap.recycle()
                        }
                    } else null
                    PlatformImageEdit(
                        base = baseUpload, references = references.toList(), mask = overlay.takeIf { useMask },
                        markup = overlay.takeIf { useMarkup }, prompt = prompt.trim(), params = params,
                        forceHistory = forceHistory && references.isEmpty(),
                    )
                }
                onSubmit(edit)
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "编辑提交失败。"
            } catch (e: OutOfMemoryError) {
                error = "手机内存不足以处理这张原尺寸图片。可使用较小的底图再试。"
            } finally {
                temporary.forEach(File::delete)
                busy = false
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("编辑这张图", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    if (tool != EditTool.Whole) {
                        IconButton(onClick = layer::undo, enabled = !busy && layer.canUndo) {
                            Icon(HugeIcons.Undo, "撤销")
                        }
                        IconButton(onClick = layer::redo, enabled = !busy && layer.canRedo) {
                            Icon(HugeIcons.Redo, "重做")
                        }
                    }
                    TextButton(onClick = { showParams = true }, enabled = !busy) { Text("参数") }
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") }
                }
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    preview?.let { (info, bitmap) ->
                        SketchEditCanvas(
                            background = remember(bitmap) { bitmap.asImageBitmap() },
                            imageSize = info.size,
                            layer = layer,
                            brushWidth = brush.value.dp,
                            drawing = tool != EditTool.Whole,
                            enabled = !busy,
                            shape = if (tool == EditTool.Markup) markShape else SketchEditShape.Freehand,
                            mask = tool == EditTool.Mask,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (busy || (preview == null && error == null)) CircularProgressIndicator()
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (value in EditTool.entries) {
                        val supported = when (value) {
                            EditTool.Whole -> true
                            EditTool.Mask -> params.platformOptions.outputFormat != "gif"
                            EditTool.Markup -> PlatformImageCapabilities.forModel(modelId).markup
                        }
                        FilterChip(
                            selected = tool == value,
                            onClick = { tool = value; error = null },
                            enabled = supported && !busy,
                            label = { Text(value.label) },
                        )
                    }
                }
                if (tool != EditTool.Whole) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (tool == EditTool.Markup) Box {
                            TextButton(onClick = { showShapes = true }, enabled = !busy) { Text(markShape.label()) }
                            DropdownMenu(expanded = showShapes, onDismissRequest = { showShapes = false }) {
                                SketchEditShape.entries.forEach { shape ->
                                    DropdownMenuItem(text = { Text(shape.label()) }, onClick = {
                                        markShape = shape
                                        showShapes = false
                                    })
                                }
                            }
                        } else Text("笔刷", style = MaterialTheme.typography.labelSmall)
                        Slider(
                            state = brush, onValueChange = { brush.value = it }, onValueChangeFinished = {},
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp), enabled = !busy,
                        )
                        Text("双指缩放或移动", style = MaterialTheme.typography.labelSmall)
                    }
                }

                if (references.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item { EditorThumbnail(base, "底图") }
                        itemsIndexed(references, key = { _, file -> file.path }) { index, file ->
                            EditorThumbnail(file, "参考 ${index + 1} ×", enabled = !busy, onClick = { references.remove(file) })
                        }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }, enabled = references.size < 15 && !busy) { Text("添加参考图") }
                    if (compatibleHistory.isNotEmpty()) {
                        TextButton(onClick = { showHistory = true }, enabled = !busy) { Text("选择旧版") }
                    }
                    if (historyAvailable && references.isEmpty()) FilterChip(
                        selected = forceHistory, onClick = { forceHistory = !forceHistory }, enabled = !busy,
                        label = { Text("理解历史指代") },
                    )
                }
                OutlinedTextField(
                    value = prompt, onValueChange = { prompt = it }, enabled = !busy, maxLines = 3,
                    label = { Text("描述要做的改动") }, modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                Button(
                    onClick = ::submit,
                    enabled = !busy && preview != null && prompt.isNotBlank() && (tool == EditTool.Whole || layer.canUndo),
                    modifier = Modifier.align(Alignment.End),
                ) { Text("生成新版本") }
            }
        }
    }
    if (showParams) PlatformImageParamsSheet(
        modelId = modelId, editing = true, params = params, onParamsChange = { params = it },
        onDismiss = { showParams = false }, mask = tool == EditTool.Mask, markup = tool == EditTool.Markup,
    )
    if (showHistory) AlertDialog(
        onDismissRequest = { showHistory = false },
        title = { Text("选择旧版作为参考") },
        text = {
            LazyVerticalGrid(columns = GridCells.Adaptive(78.dp)) {
                itemsIndexed(compatibleHistory, key = { _, file -> file.path }) { index, file ->
                    val selected = file in references
                    EditorThumbnail(file, if (selected) "✓ ${index + 1}" else "图 ${index + 1}", selected || references.size < 15) {
                        if (selected) references.remove(file) else references.add(file)
                        forceHistory = false
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showHistory = false }) { Text("完成") } },
    )
}

@Composable
private fun EditorThumbnail(file: File, label: String, enabled: Boolean = true, onClick: (() -> Unit)? = null) {
    val content: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
            MediaThumbnail(file = file, isVideo = false, modifier = Modifier.size(52.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.medium, content = content)
    } else content()
}

private fun SketchEditShape.label() = when (this) {
    SketchEditShape.Freehand -> "画笔"
    SketchEditShape.Ellipse -> "椭圆"
    SketchEditShape.Arrow -> "箭头"
}
