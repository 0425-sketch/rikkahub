package me.rerere.rikkahub.data.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.ui.unit.IntSize
import androidx.exifinterface.media.ExifInterface
import java.io.File
import me.rerere.mediagen.provider.providers.openaiplatform.isAnimatedPlatformImage
import kotlin.uuid.Uuid

/** Files used by the Platform image editor. All calls that touch a file run on an IO dispatcher. */
internal object PlatformImageFiles {
    private const val MAX_BYTES = 50L * 1024 * 1024
    const val MAX_MARKUP_PIXELS = 16_777_216L

    data class ImageInfo(val mime: String, val size: IntSize, val rotation: Int, val flipped: Boolean)

    fun info(file: File): ImageInfo {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "底图或参考图必须是可读取的静态位图。" }
        require(bounds.outMimeType != "image/gif") { "请选择静态图片作为底图或参考图。GIF 仅可作为输出格式。" }
        val exif = ExifInterface(file)
        val rotation = exif.rotationDegrees
        val size = if (rotation == 90 || rotation == 270) {
            IntSize(bounds.outHeight, bounds.outWidth)
        } else {
            IntSize(bounds.outWidth, bounds.outHeight)
        }
        return ImageInfo(bounds.outMimeType, size, rotation, exif.isFlipped)
    }

    /** JPEG/PNG/WebP bytes are copied intact, including EXIF. Android-decodable phone photos use upright PNG. */
    fun importImage(context: Context, uri: Uri, dir: File): File {
        dir.mkdirs()
        val temporary = File.createTempFile("import-", ".part", dir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var size = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        size += read
                        require(size < MAX_BYTES) { "每张输入图片须小于 50 MB。" }
                        output.write(buffer, 0, read)
                    }
                }
            } ?: error("无法打开所选图片。")
            val info = info(temporary)
            val extension = when (info.mime) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> null
            }
            if (extension == null) return uprightPng(temporary, info, dir)
            require(!isAnimatedPlatformImage(temporary, info.mime)) { "参考图必须是静态图片。" }
            return File(dir, "${Uuid.random()}.$extension").also {
                check(temporary.renameTo(it)) { "无法保存参考图。" }
            }
        } finally {
            temporary.delete()
        }
    }

    /** The sampled preview is drawn into the exact [ImageInfo.size], independent of sample-size rounding. */
    fun preview(file: File, info: ImageInfo, maxEdge: Int = 2048): Bitmap {
        require(maxEdge > 0)
        var sample = 1
        while (maxOf(info.size.width, info.size.height) / sample > maxEdge) sample *= 2
        return decodeUpright(file, info, sample)
    }

    fun maskBase(file: File, info: ImageInfo, dir: File): File =
        if (info.mime == "image/png" && info.rotation == 0 && !info.flipped) file
        else uprightPng(file, info, dir)

    private fun uprightPng(file: File, info: ImageInfo, dir: File): File {
        val bitmap = decodeUpright(file, info, 1)
        return try {
            writePng(bitmap, dir, mask = false)
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeUpright(file: File, info: ImageInfo, sample: Int): Bitmap {
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("无法读取原图像素。")
        if (!info.flipped && info.rotation == 0) return decoded
        // ExifInterface specifies a horizontal reflection followed by clockwise rotation for all eight orientations.
        val matrix = Matrix().apply {
            if (info.flipped) postScale(-1f, 1f)
            postRotate(info.rotation.toFloat())
        }
        return try {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        } finally {
            decoded.recycle()
        }
    }

    fun writePng(bitmap: Bitmap, dir: File, mask: Boolean): File {
        dir.mkdirs()
        val target = File(dir, "${Uuid.random()}.png")
        var complete = false
        try {
            target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "无法写入 PNG。" } }
            val limit = if (mask) 4L * 1024 * 1024 else MAX_BYTES
            require(target.length() < limit) { if (mask) "遮罩须小于 4 MB。" else "上传的 PNG 须小于 50 MB。" }
            complete = true
            return target
        } finally {
            if (!complete) target.delete()
        }
    }
}
