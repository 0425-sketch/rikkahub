package me.rerere.ui.sketch

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class EditViewportTest {
    @Test
    fun `pinch and pan leave a selected source pixel under the fingers on a letterboxed portrait`() {
        val image = IntSize(1001, 2003)
        val fit = EditViewport.fit(IntSize(720, 960), image)
        val source = Offset(187f, 1381f)
        val screenPoint = fit.origin + source * fit.scale
        val pan = Offset(31f, -49f)
        val zoomed = fit.transform(screenPoint, pan, 2.5f, fit.scale)
        val mapped = zoomed.toImage(screenPoint + pan)
        assertEquals(source.x, mapped.x, 0.001f)
        assertEquals(source.y, mapped.y, 0.001f)
        assertEquals(zoomed.scale, 2.5f * fit.scale, 0.0001f)
    }
}
