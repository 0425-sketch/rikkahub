package me.rerere.mediagen.provider.providers.openaiplatform

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.runBlocking
import me.rerere.mediagen.model.MediaGenerationInput
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.provider.MediaGenerationManager
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.providers.fakeClient
import me.rerere.mediagen.provider.providers.jsonResponse
import okhttp3.MultipartBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.io.encoding.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlatformMarkupUploadTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun markupMatchesOrientedPhotoAndUploadsOriginalJpegUnchanged() = runBlocking {
        val base = files.newFile("rotated.jpg")
        writeBitmap(base, 64, 48, Bitmap.CompressFormat.JPEG)
        ExifInterface(base).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val original = base.readBytes()
        val overlay = files.newFile("arrow.png")
        writeBitmap(overlay, 48, 64, Bitmap.CompressFormat.PNG)
        val marked = overlay.readBytes()

        val manager = MediaGenerationManager(fakeClient {
            val images = (it.body as MultipartBody).parts.filter { part -> part.body.contentType()?.type == "image" }
            assertEquals(listOf("image", "markup"), images.map { part ->
                part.headers!!["Content-Disposition"]!!.substringAfter("name=\"").substringBefore('"')
            })
            assertArrayEquals(original, Buffer().also(images[0].body::writeTo).readByteArray())
            assertArrayEquals(marked, Buffer().also(images[1].body::writeTo).readByteArray())
            jsonResponse(it, """{"data":[{"b64_json":"${Base64.encode(marked)}"}]}""")
        })
        val account = MediaGenerationProviderSetting.OpenAIPlatform(apiKey = "test")
        val request = MediaGenerationRequest(prompt = "根据箭头改底图", inputs = listOf(MediaGenerationInput.Image(base.path)),
            markupPath = overlay.path)
        manager.create(account, account.models.first(), request).getOrThrow()

        writeBitmap(overlay, 64, 48, Bitmap.CompressFormat.PNG)
        assertTrue(manager.create(account, account.models.first(), request).exceptionOrNull()!!.message!!.contains("尺寸"))
        assertArrayEquals(original, base.readBytes())
    }

    private fun writeBitmap(file: File, width: Int, height: Int, format: Bitmap.CompressFormat) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0, 0, Color.RED)
        try {
            file.outputStream().use { assertTrue(bitmap.compress(format, 97, it)) }
        } finally {
            bitmap.recycle()
        }
    }
}
