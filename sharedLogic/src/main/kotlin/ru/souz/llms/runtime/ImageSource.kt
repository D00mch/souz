package ru.souz.llms.runtime

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.Base64
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

/** Request-local pixels. Never persist these values in sessions or tool results. */
class ImageSource(val bytes: ByteArray) {
    val mimeType: String
    init {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "image_size_exceeded" }
        mimeType = MemoryCacheImageInputStream(bytes.inputStream()).use { stream ->
            val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull()
                ?: error("image_format_unsupported")
            try {
                reader.input = stream
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                require(width in 1..16384 && height in 1..16384 && width.toLong() * height <= 40_000_000) { "image_dimensions_exceeded" }
                when (reader.formatName.lowercase()) {
                    "png" -> "image/png"
                    "jpeg", "jpg" -> "image/jpeg"
                    "webp" -> "image/webp"
                    else -> error("image_format_unsupported")
                }
            } finally { reader.dispose() }
        }
    }

    fun dataUrl(): String = "data:$mimeType;base64," + Base64.getEncoder().encodeToString(bytes)

    fun forVision(): String {
        val image = MemoryCacheImageInputStream(bytes.inputStream()).use { stream ->
            val reader = ImageIO.getImageReaders(stream).next()
            try { reader.input = stream; reader.read(0) } finally { reader.dispose() }
        }
        val scale = minOf(1.0, 2048.0 / maxOf(image.width, image.height))
        if (scale == 1.0) return dataUrl()
        val resized = BufferedImage((image.width * scale).toInt().coerceAtLeast(1),
            (image.height * scale).toInt().coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
        resized.createGraphics().let { graphics ->
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                graphics.drawImage(image, 0, 0, resized.width, resized.height, null)
            } finally { graphics.dispose() }
        }
        val output = ByteArrayOutputStream()
        javax.imageio.stream.MemoryCacheImageOutputStream(output).use { check(ImageIO.write(resized, "png", it)) }
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray())
    }

    companion object {
        const val MAX_BYTES = 20_000_000
        const val MAX_IMAGES = 5
        const val MAX_ENCODED_BYTES = (MAX_BYTES + 2) / 3 * 4
        val MIME_TYPES: Set<String> = setOf("image/png", "image/jpeg", "image/webp")
        fun read(input: InputStream): ImageSource = ImageSource(input.readNBytes(MAX_BYTES + 1))
    }
}
