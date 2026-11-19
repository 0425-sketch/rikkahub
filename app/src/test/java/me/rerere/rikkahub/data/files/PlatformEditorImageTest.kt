package me.rerere.rikkahub.data.files

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlatformEditorImageTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `rotated phone original is preserved and its full size mask input agrees with sampled preview`() {
        val file = folder.newFile("phone.jpg")
        val source = Bitmap.createBitmap(2050, 64, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.BLUE)
        for (y in 0 until 64) for (x in 0 until 160) source.setPixel(x, y, Color.RED)
        file.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 98, it)) }
        source.recycle()
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val bytes = file.readBytes()
        val dir = folder.newFolder("draft")
        val imported = PlatformImageFiles.importImage(RuntimeEnvironment.getApplication(), Uri.fromFile(file), dir)
        assertArrayEquals(bytes, imported.readBytes())
        val info = PlatformImageFiles.info(imported)
        assertEquals(64, info.size.width)
        assertEquals(2050, info.size.height)
        val preview = PlatformImageFiles.preview(imported, info)
        val base = PlatformImageFiles.maskBase(imported, info, dir)
        val upload = BitmapFactory.decodeFile(base.path)
        assertTrue(preview.height < upload.height)
        assertEquals(info.size.width, upload.width)
        assertEquals(info.size.height, upload.height)
        for (bitmap in listOf(preview, upload)) {
            val top = bitmap.getPixel(bitmap.width / 2, bitmap.height / 32)
            val bottom = bitmap.getPixel(bitmap.width / 2, bitmap.height * 3 / 4)
            assertTrue(Color.red(top) > Color.blue(top))
            assertTrue(Color.blue(bottom) > Color.red(bottom))
            bitmap.recycle()
        }
        assertArrayEquals(bytes, imported.readBytes())
        assertFalse(base == imported)
    }
}
