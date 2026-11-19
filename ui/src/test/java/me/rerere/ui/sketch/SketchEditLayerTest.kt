package me.rerere.ui.sketch

import android.app.Application
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SketchEditLayerTest {
    @Test
    fun `ellipse and arrow markup render on transparent background without leaking the base`() {
        val layer = SketchEditLayer()
        layer.start(Offset(10f, 10f), 4f, SketchEditShape.Ellipse)
        layer.extend(Offset(50f, 30f))
        layer.finish()
        layer.start(Offset(60f, 15f), 4f, SketchEditShape.Arrow)
        layer.extend(Offset(95f, 15f))
        layer.finish()
        val markup = layer.render(100, 40, mask = false)
        assertEquals(255, Color.alpha(markup.getPixel(10, 20)))
        assertEquals(0, Color.alpha(markup.getPixel(30, 20)))
        assertEquals(255, Color.alpha(markup.getPixel(80, 15)))
        assertTrue(Color.alpha(markup.getPixel(85, 20)) > 0)
        markup.recycle()
    }

    @Test
    fun `panned zoomed stroke exports transparent mask hole and isolated markup at same pixels`() {
        val layer = SketchEditLayer()
        val fit = EditViewport.fit(IntSize(200, 200), IntSize(100, 40))
        val zoom = fit.transform(Offset(80f, 84f), Offset(13f, 28f), 3f, fit.scale)
        layer.start(zoom.toImage(Offset(93f, 112f)), 30f / zoom.scale)
        layer.extend(zoom.toImage(Offset(153f, 112f)))
        layer.finish()
        val mask = layer.render(100, 40, mask = true)
        val markup = layer.render(100, 40, mask = false)
        assertEquals(0, Color.alpha(mask.getPixel(45, 12)))
        assertEquals(255, Color.alpha(mask.getPixel(45, 6)))
        assertEquals(255, Color.alpha(markup.getPixel(45, 12)))
        assertEquals(0, Color.alpha(markup.getPixel(45, 6)))
        mask.recycle()
        markup.recycle()

        layer.undo()
        assertFalse(layer.canUndo)
        assertTrue(layer.canRedo)
        layer.redo()
        assertTrue(layer.canUndo)
        assertFalse(layer.canRedo)
        layer.start(Offset(0f, 0f), 8f)
        layer.cancel() // second finger joined a pinch
        val restored = layer.render(100, 40, mask = true)
        assertEquals(255, Color.alpha(restored.getPixel(0, 0)))
        assertEquals(0, Color.alpha(restored.getPixel(45, 12)))
        restored.recycle()
    }
}
