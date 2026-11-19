package me.rerere.rikkahub.ui.pages.mediacreation

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.compose.runtime.RememberObserver
import androidx.core.content.FileProvider
import coil3.ImageLoader
import coil3.asDrawable
import coil3.compose.asPainter
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.files.addMediaImageDecoders
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import kotlin.io.encoding.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
class MediaOriginalFormatTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun `two frame GIF decodes as an animation and exports the original`() = runBlocking {
        val file = File(app.filesDir, "result.gif").apply { writeBytes(Base64.decode(TWO_FRAME_GIF)) }
        val image = decode(file)
        val drawable = image.asDrawable(app.resources)
        assertTrue("GIF must retain animation in the Coil preview", drawable is Animatable)
        withContext(Dispatchers.Main) {
            val painter = image.asPainter(app) as RememberObserver
            painter.onRemembered()
            try {
                assertTrue("The media preview should start GIF playback", (drawable as Animatable).isRunning)
            } finally {
                painter.onForgotten()
            }
            assertTrue("Leaving the preview should stop GIF playback", !(drawable as Animatable).isRunning)
        }
        assertEquals(2, image.width)
        verifyExport(file, "image/gif", MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_PICTURES)
    }

    @Test
    fun `native SVG renders its path and exports the vector source`() = runBlocking {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16"><path fill="#ff0000" d="M0 0h16v16H0z"/></svg>"""
        val file = File(app.filesDir, "result.svg").apply { writeText(svg) }
        val image = decode(file)
        assertEquals(Color.RED, image.toBitmap(16, 16).getPixel(8, 8))
        verifyExport(file, "image/svg+xml", MediaStore.Downloads.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_DOWNLOADS)
    }

    private suspend fun decode(file: File): coil3.Image {
        val loader = ImageLoader.Builder(app).components { addMediaImageDecoders() }.build()
        return try {
            when (val result = loader.execute(ImageRequest.Builder(app).data(file).size(Size.ORIGINAL).build())) {
                is ErrorResult -> throw AssertionError("Failed to preview ${file.name}", result.throwable)
                is SuccessResult -> result.image
            }
        } finally {
            loader.shutdown()
        }
    }

    private suspend fun verifyExport(file: File, mime: String, collection: Uri, directory: String) {
        val store = ExportCollection(File(app.cacheDir, "saved.${file.extension}"))
        store.attachInfo(app, ProviderInfo().apply { authority = MediaStore.AUTHORITY })
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, store)
        val saved = saveMediaToGallery(app, file, mime)
        assertEquals(Uri.withAppendedPath(collection, "1"), saved)
        assertEquals(directory, store.metadata.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals(mime, store.metadata.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertTrue(store.metadata.getAsString(MediaStore.MediaColumns.DISPLAY_NAME).endsWith(".${file.extension}"))
        assertEquals(0, store.metadata.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertArrayEquals(file.readBytes(), store.target.readBytes())

        // Robolectric gives each test a new filesDir but AndroidX caches roots per authority.
        val authority = "${app.packageName}.fileprovider"
        val info = requireNotNull(app.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA))
        val provider = FileProvider().apply { attachInfo(app, info) }
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val share = createShareMediaIntent(app, file, mime)
        assertEquals(Intent.ACTION_SEND, share.action)
        assertEquals(mime, share.type)
        assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val uri = requireNotNull(share.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals(uri, share.clipData?.getItemAt(0)?.uri)
        assertArrayEquals(file.readBytes(), app.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
    }

    /** A file-backed stand-in for Android's external MediaStore process. */
    private class ExportCollection(val target: File) : ContentProvider() {
        val metadata = ContentValues()
        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            metadata.putAll(requireNotNull(values))
            return Uri.withAppendedPath(uri, "1")
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
            metadata.putAll(requireNotNull(values))
            return 1
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(target, ParcelFileDescriptor.parseMode(mode))
        override fun query(uri: Uri, columns: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? = null
        override fun getType(uri: Uri): String? = metadata.getAsString(MediaStore.MediaColumns.MIME_TYPE)
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = if (target.delete()) 1 else 0
    }

    private companion object {
        // 2 × 2 GIF89a with distinct red and blue frames, a 100 ms delay and an infinite loop.
        const val TWO_FRAME_GIF = "R0lGODlhAgACAIEAAP8AAAAA/wAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQACgAAACwAAAAAAgACAAAIBgABCAQQEAAh+QQBCgABACwAAAAAAgACAIEAAP8AAAAAAAAAAAAIBgABCAQQEAA7"
    }
}
