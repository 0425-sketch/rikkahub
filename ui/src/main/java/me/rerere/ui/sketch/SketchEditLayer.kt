package me.rerere.ui.sketch

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import kotlin.math.hypot

enum class SketchEditShape { Freehand, Ellipse, Arrow }

/** All strokes use upright, full-resolution input pixels. The preview bitmap can be sampled. */
@Stable
class SketchEditLayer {
    private class Stroke(val line: SketchStep.Erase, val shape: SketchEditShape, var end: Offset)
    private val lines = mutableStateListOf<Stroke>()
    private val undone = mutableStateListOf<Stroke>()
    private var active by mutableStateOf<Stroke?>(null)
    private var revision by mutableIntStateOf(0)
    val canUndo: Boolean get() = lines.isNotEmpty()
    val canRedo: Boolean get() = undone.isNotEmpty()

    fun undo() { lines.removeLastOrNull()?.let(undone::add) }
    fun redo() { undone.removeLastOrNull()?.let(lines::add) }

    internal fun start(point: Offset, width: Float, shape: SketchEditShape = SketchEditShape.Freehand) {
        active = Stroke(SketchStep.Erase(point, width), shape, point)
    }

    internal fun extend(point: Offset) {
        active?.let {
            it.end = point
            if (it.shape == SketchEditShape.Freehand) it.line.extendTo(point)
        }
        revision++
    }

    internal fun finish() {
        active?.let {
            it.line.finish()
            lines += it
            undone.clear()
        }
        active = null
    }

    internal fun cancel() { active = null }

    internal fun draw(canvas: AndroidCanvas, paint: Paint, preview: Boolean = false) {
        fun stroke(stroke: Stroke) {
            val line = stroke.line
            val start = line.start
            val end = stroke.end
            paint.strokeWidth = line.width
            if (start == end && !line.moved) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(start.x, start.y, line.width / 2f, paint)
                return
            }
            paint.style = Paint.Style.STROKE
            when (stroke.shape) {
                SketchEditShape.Freehand -> canvas.drawPath(line.path.asAndroidPath(), paint)
                SketchEditShape.Ellipse -> canvas.drawOval(
                    minOf(start.x, end.x), minOf(start.y, end.y),
                    maxOf(start.x, end.x), maxOf(start.y, end.y), paint,
                )
                SketchEditShape.Arrow -> {
                    val length = hypot(end.x - start.x, end.y - start.y)
                    val direction = (end - start) / length
                    val headLength = minOf(length / 2f, maxOf(line.width * 3f, length / 5f))
                    val neck = end - direction * headLength
                    val wing = Offset(-direction.y, direction.x) * (headLength / 2f)
                    canvas.drawLine(start.x, start.y, end.x, end.y, paint)
                    canvas.drawLine(neck.x + wing.x, neck.y + wing.y, end.x, end.y, paint)
                    canvas.drawLine(neck.x - wing.x, neck.y - wing.y, end.x, end.y, paint)
                }
            }
        }
        lines.forEach(::stroke)
        if (preview) {
            revision
            active?.let(::stroke)
        }
    }

    /** A mask is opaque except for the selected region. Markup contains only visible annotation ink. */
    fun render(width: Int, height: Int, mask: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bitmap)
        val paint = layerPaint()
        if (mask) {
            canvas.drawColor(android.graphics.Color.WHITE)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        draw(canvas, paint)
        return bitmap
    }
}

private fun layerPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color(0xffed303e).toArgb()
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
}

internal data class EditViewport(val scale: Float, val origin: Offset) {
    fun toImage(point: Offset): Offset = (point - origin) / scale

    fun transform(centroid: Offset, pan: Offset, zoom: Float, fit: Float): EditViewport {
        val nextScale = (scale * zoom).coerceIn(fit, fit * 16)
        return EditViewport(nextScale, centroid + pan - toImage(centroid) * nextScale)
    }

    fun within(view: IntSize, image: IntSize): EditViewport {
        fun constrain(start: Float, scaled: Float, visible: Int) =
            if (scaled <= visible) (visible - scaled) / 2 else start.coerceIn(visible - scaled, 0f)
        return copy(origin = Offset(
            constrain(origin.x, image.width * scale, view.width),
            constrain(origin.y, image.height * scale, view.height),
        ))
    }

    companion object {
        fun fit(view: IntSize, image: IntSize): EditViewport {
            val scale = minOf(view.width.toFloat() / image.width, view.height.toFloat() / image.height)
            return EditViewport(
                scale,
                Offset((view.width - image.width * scale) / 2f, (view.height - image.height * scale) / 2f),
            )
        }
    }
}

/** One finger draws in ink mode or pans in inspection mode. Two fingers always navigate. */
@Composable
fun SketchEditCanvas(
    background: ImageBitmap,
    imageSize: IntSize,
    layer: SketchEditLayer,
    brushWidth: Dp,
    drawing: Boolean,
    mask: Boolean,
    enabled: Boolean = true,
    shape: SketchEditShape = SketchEditShape.Freehand,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.clipToBounds()) {
        val viewSize = IntSize(constraints.maxWidth, constraints.maxHeight)
        if (viewSize.width == 0 || viewSize.height == 0) return@BoxWithConstraints
        val fit = remember(viewSize, imageSize) { EditViewport.fit(viewSize, imageSize) }
        var viewport by remember(fit) { mutableStateOf(fit) }
        val brush by rememberUpdatedState(brushWidth)
        val currentShape by rememberUpdatedState(shape)
        val paint = remember(mask) { layerPaint().apply { if (mask) alpha = 160 } }

        Canvas(Modifier.fillMaxSize().pointerInput(layer, drawing, fit, enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown()
                val first = viewport.toImage(down.position)
                var navigating = !drawing
                if (drawing && first.x in 0f..imageSize.width.toFloat() && first.y in 0f..imageSize.height.toFloat()) {
                    layer.start(first, brush.toPx() / viewport.scale, currentShape)
                }
                down.consume()
                try {
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } > 1) {
                            // A pinch must not leave a dot under the first finger.
                            layer.cancel()
                            navigating = true
                        }
                        if (navigating) {
                            if (event.changes.any { it.pressed && it.previousPressed }) {
                                viewport = viewport.transform(
                                    event.calculateCentroid(useCurrent = false),
                                    event.calculatePan(),
                                    event.calculateZoom(),
                                    fit.scale,
                                ).within(viewSize, imageSize)
                            }
                        } else {
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change != null && change.position != change.previousPosition) {
                                change.historical.forEach { layer.extend(viewport.toImage(it.position)) }
                                layer.extend(viewport.toImage(change.position))
                            }
                            if (change != null && !change.pressed) layer.finish()
                        }
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                } finally {
                    layer.cancel()
                }
            }
        }) {
            withTransform({
                translate(viewport.origin.x, viewport.origin.y)
                scale(viewport.scale, viewport.scale, Offset.Zero)
            }) {
                clipRect(right = imageSize.width.toFloat(), bottom = imageSize.height.toFloat()) {
                    drawImage(background, dstSize = imageSize)
                    if (drawing) drawIntoCanvas { layer.draw(it.nativeCanvas, paint, preview = true) }
                }
            }
        }
    }
}
