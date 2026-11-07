package ru.souz.llms.runtime

import com.drew.imaging.jpeg.JpegMetadataReader
import com.drew.metadata.exif.ExifReader
import com.drew.metadata.exif.ExifIFD0Directory
import java.awt.geom.AffineTransform
import ru.souz.llms.LlmProvider
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBuffer
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class InvalidImageException(val code: String, cause: Exception? = null) : IllegalArgumentException(code, cause)

/** Decoded and validated request-local image. Persist neither pixels nor vision projections. */
class ImageSource private constructor(
    val bytes: ByteArray,
    val mimeType: String,
    private val visionBytes: ByteArray?,
) {
    fun dataUrl(): String = dataUrl(mimeType, bytes)

    suspend fun forVision(provider: LlmProvider? = null): String {
        // Re-project only when the selected protocol requires a smaller encoded image.
        val limit = if (provider == LlmProvider.ANTHROPIC) ANTHROPIC_ENCODED_LIMIT else MAX_ENCODED_BYTES
        val encodedSize = ((visionBytes ?: bytes).size + 2L) / 3 * 4
        return if (encodedSize > limit) read(bytes, limit).forVision(provider)
        else visionBytes?.let { dataUrl("image/png", it) } ?: dataUrl()
    }

    companion object {
        const val MAX_BYTES = 20_000_000
        const val ANTHROPIC_ENCODED_LIMIT = 10_000_000
        const val MAX_IMAGES = 5
        const val MAX_ENCODED_BYTES = (MAX_BYTES + 2) / 3 * 4
        val MIME_TYPES: Set<String> = setOf("image/png", "image/jpeg", "image/webp")
        private const val VISION_EDGE = 2048
        private const val MAX_DECODED_BYTES = 64L * 1024 * 1024
        private val decoders = Semaphore(2)

        suspend fun read(input: InputStream): ImageSource = read(
            runInterruptible(Dispatchers.IO) { input.readNBytes(MAX_BYTES + 1) },
        )

        suspend fun read(bytes: ByteArray): ImageSource = read(bytes, MAX_ENCODED_BYTES)

        private suspend fun read(bytes: ByteArray, encodedLimit: Int): ImageSource {
            if (bytes.isEmpty() || bytes.size > MAX_BYTES) throw InvalidImageException("image_size_exceeded")
            return decoders.withPermit {
                withContext(Dispatchers.Default) { decode(bytes, encodedLimit) }
            }
        }

        private fun decode(bytes: ByteArray, encodedLimit: Int): ImageSource = try {
            MemoryCacheImageInputStream(bytes.inputStream()).use { stream ->
                val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull()
                    ?: throw InvalidImageException("image_format_unsupported")
                try {
                    reader.setInput(stream, true, true)
                    val mime = when (reader.formatName.lowercase()) {
                        "png" -> "image/png"
                        "jpeg", "jpg" -> "image/jpeg"
                        "webp" -> "image/webp"
                        else -> throw InvalidImageException("image_format_unsupported")
                    }
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    if (width !in 1..16384 || height !in 1..16384 || width.toLong() * height > 40_000_000) {
                        throw InvalidImageException("image_dimensions_exceeded")
                    }
                    val destination = reader.getImageTypes(0).asSequence().firstOrNull()
                        ?: throw InvalidImageException("image_format_unsupported")
                    val pixelBytes = maxOf(destination.pixelBytes(), reader.getRawImageType(0)?.pixelBytes() ?: 0)
                    // Budget the ORIGINAL raster: some decoders allocate it internally even when subsampling.
                    if (width.toLong() * height * pixelBytes > MAX_DECODED_BYTES) {
                        throw InvalidImageException("image_decoded_size_exceeded")
                    }
                    reader.addIIOReadWarningListener { _, _ -> throw InvalidImageException("image_decode_failed") }
                    val parameters = reader.defaultReadParam.apply {
                        destinationType = destination
                        val sample = (maxOf(width, height) / VISION_EDGE).coerceAtLeast(1)
                        setSourceSubsampling(sample, sample, 0, 0)
                    }
                    val orientation = if (mime == "image/jpeg") JpegMetadataReader
                        .readMetadata(bytes.inputStream(), listOf(ExifReader()))
                        .getFirstDirectoryOfType(ExifIFD0Directory::class.java)
                        ?.getInteger(ExifIFD0Directory.TAG_ORIENTATION)?.takeIf { it in 1..8 } ?: 1 else 1
                    val image = reader.read(0, parameters)
                    try {
                        var edge = VISION_EDGE
                        var vision = if (maxOf(width, height) > edge || orientation != 1 ||
                            (bytes.size + 2L) / 3 * 4 > encodedLimit) image.resizeForVision(orientation, edge) else null
                        while (vision != null && (vision.size + 2L) / 3 * 4 > encodedLimit) {
                            edge = (edge * 3 / 4).coerceAtLeast(1)
                            vision = image.resizeForVision(orientation, edge)
                        }
                        ImageSource(bytes, mime, vision)
                    } finally {
                        image.flush()
                    }
                } finally {
                    reader.dispose()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (invalid: InvalidImageException) {
            throw invalid
        } catch (error: Exception) {
            // Decoder exceptions describe untrusted bytes. JVM errors are deliberately not caught.
            throw InvalidImageException("image_decode_failed", error)
        }

        private fun ImageTypeSpecifier.pixelBytes(): Long = maxOf(
            4L, // Includes expansion of palette/greyscale pixels by a decoder.
            (colorModel.pixelSize + 7L) / 8,
            sampleModel.numDataElements.toLong() * DataBuffer.getDataTypeSize(sampleModel.dataType) / 8,
        )

        private fun BufferedImage.resizeForVision(orientation: Int, edge: Int): ByteArray {
            val scale = minOf(1.0, edge.toDouble() / maxOf(width, height))
            val rotated = orientation in 5..8
            val resized = BufferedImage(
                ((if (rotated) height else width) * scale).toInt().coerceAtLeast(1),
                ((if (rotated) width else height) * scale).toInt().coerceAtLeast(1),
                BufferedImage.TYPE_INT_ARGB,
            )
            try {
                val graphics = resized.createGraphics()
                try {
                    graphics.setRenderingHint(
                        RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BICUBIC,
                    )
                    val w = width.toDouble()
                    val h = height.toDouble()
                    val transform = when (orientation) {
                        2 -> AffineTransform(-1.0, 0.0, 0.0, 1.0, w, 0.0)
                        3 -> AffineTransform(-1.0, 0.0, 0.0, -1.0, w, h)
                        4 -> AffineTransform(1.0, 0.0, 0.0, -1.0, 0.0, h)
                        5 -> AffineTransform(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
                        6 -> AffineTransform(0.0, 1.0, -1.0, 0.0, h, 0.0)
                        7 -> AffineTransform(0.0, -1.0, -1.0, 0.0, h, w)
                        8 -> AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, w)
                        else -> AffineTransform()
                    }
                    graphics.scale(scale, scale)
                    graphics.drawImage(this, transform, null)
                } finally {
                    graphics.dispose()
                }
                val output = ByteArrayOutputStream()
                MemoryCacheImageOutputStream(output).use { check(ImageIO.write(resized, "png", it)) }
                return output.toByteArray()
            } finally {
                resized.flush()
            }
        }

        private fun dataUrl(mime: String, bytes: ByteArray): String =
            "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)
    }
}
