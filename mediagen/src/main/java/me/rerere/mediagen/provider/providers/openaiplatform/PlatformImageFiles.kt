package me.rerere.mediagen.provider.providers.openaiplatform

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile
import java.io.StringReader
import java.nio.ByteBuffer
import javax.xml.parsers.SAXParserFactory

private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10)
private val JPEG = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())

private fun ByteArray.matches(prefix: ByteArray, offset: Int = 0) =
    size >= offset + prefix.size && prefix.indices.all { this[offset + it] == prefix[it] }

private fun rasterImageFormat(bytes: ByteArray): String? = when {
    bytes.matches(PNG) -> "png"
    bytes.matches(JPEG) -> "jpeg"
    bytes.matches("RIFF".toByteArray()) && bytes.matches("WEBP".toByteArray(), 8) -> "webp"
    bytes.matches("GIF89a".toByteArray()) || bytes.matches("GIF87a".toByteArray()) -> "gif"
    else -> null
}

internal fun rasterImageFormat(file: File): String? {
    if (file.length() < 12) return null
    return DataInputStream(file.inputStream()).use { rasterImageFormat(ByteArray(12).also(it::readFully)) }
}

// Stop on the document element. SVG may start with a BOM, XML declaration, comments or a doctype.
private class SvgRoot(val matches: Boolean) : SAXException()

internal fun matchesImageFormat(bytes: ByteArray, format: String): Boolean {
    if (format != "svg") return rasterImageFormat(bytes) == format
    if (rasterImageFormat(bytes) != null) return false
    val parser = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser()
    val handler = object : DefaultHandler() {
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource = InputSource(StringReader(""))

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            throw SvgRoot(localName == "svg" && (uri.isNullOrEmpty() || uri == "http://www.w3.org/2000/svg"))
        }
    }
    return try {
        parser.parse(InputSource(bytes.inputStream()).apply { encoding = "UTF-8" }, handler)
        false
    } catch (found: SvgRoot) {
        found.matches
    } catch (_: SAXException) {
        false
    }
}

private fun pngSize(file: File, requireAlpha: Boolean = false): Pair<Int, Int> {
    require(rasterImageFormat(file) == "png") { "遮罩和标注必须为 PNG 文件" }
    return DataInputStream(file.inputStream()).use { input ->
        val header = ByteArray(26)
        input.readFully(header)
        require(header.matches("IHDR".toByteArray(), 12)) { "PNG 缺少尺寸信息" }
        if (requireAlpha && header[25].toInt() !in setOf(4, 6)) {
            require(hasPngChunkBeforeImageData(file, 0x74524e53)) { "遮罩或标注 PNG 必须包含透明通道" }
        }
        val fields = ByteBuffer.wrap(header)
        fields.getInt(16) to fields.getInt(20)
    }
}

// A palette, grayscale or RGB PNG can encode transparency in a tRNS chunk before its first IDAT.
private fun hasPngChunkBeforeImageData(file: File, chunkType: Int): Boolean = RandomAccessFile(file, "r").use { input ->
    input.seek(8)
    while (input.filePointer + 8 <= input.length()) {
        val length = input.readInt().toLong() and 0xffffffffL
        when (input.readInt()) {
            chunkType -> return@use true
            0x49444154 -> return@use false // IDAT
        }
        input.seek(input.filePointer + length + 4)
    }
    false
}

internal fun MultipartBody.Builder.addOverlay(name: String, file: File, base: File) {
    val mask = name == "mask"
    val maxBytes = (if (mask) 4L else 50L) * 1024 * 1024
    require(file.isFile && file.length() in 1 until maxBytes) { "$name 文件大小超出限制" }
    val overlaySize = pngSize(file, requireAlpha = true)
    val (width, height) = overlaySize
    require(width > 0 && height > 0 && (mask || width.toLong() * height <= 16_777_216)) { "标注尺寸超出限制" }
    // The editor prepares an upright PNG for masks. Markup permits the original EXIF-oriented image.
    val baseSize = if (mask) {
        pngSize(base)
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(base.path, bounds)
        if (ExifInterface(base).rotationDegrees in setOf(90, 270)) bounds.outHeight to bounds.outWidth
        else bounds.outWidth to bounds.outHeight
    }
    require(overlaySize == baseSize) { "遮罩或标注须与底图方向及像素尺寸一致" }
    addFormDataPart(name, file.name, file.asRequestBody("image/png".toMediaType()))
}

/** Animation is signalled before the first image data in both PNG and WebP. */
fun isAnimatedPlatformImage(file: File, mime: String): Boolean {
    if (mime == "image/png") return hasPngChunkBeforeImageData(file, 0x6163544c) // acTL
    if (mime != "image/webp") return false
    return RandomAccessFile(file, "r").use { input ->
        input.seek(12)
        while (input.filePointer + 8 <= input.length()) {
            val type = input.readInt()
            val length = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
            when (type) {
                0x414e494d -> return@use true // ANIM
                0x56503820, 0x5650384c -> return@use false // VP8 / VP8L
            }
            input.seek(input.filePointer + length + length % 2)
        }
        false
    }
}
