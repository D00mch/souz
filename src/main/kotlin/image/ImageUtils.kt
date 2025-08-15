package com.dumch.image

import com.dumch.image.ImageUtils.screenshotJpegBytes
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Utility object for capturing desktop screenshots and compressing images.
 */
object ImageUtils {
    const val DESKTOP_SCREENSHOT_QUALITY = 0.85f

    /**
     * Compress JPEG bytes with the given [quality].
     * [quality] should be between 0f (maximum compression) and 1f (minimum compression).
     */
    fun compressJpeg(rgbImage: BufferedImage, quality: Float = DESKTOP_SCREENSHOT_QUALITY): ByteArray {
        val scaleDown = quality < 1f
        val finalImage = if (scaleDown) {
            val newWidth = rgbImage.width / 2
            val newHeight = rgbImage.height / 2
            BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB).apply {
                createGraphics().drawImage(
                    rgbImage,
                    0, 0, newWidth, newHeight,
                    0, 0, rgbImage.width, rgbImage.height,
                    null
                )
            }
        } else {
            rgbImage
        }

        val baos = ByteArrayOutputStream()
        try {
            ImageIO.createImageOutputStream(baos).use { ios ->
                val writer = ImageIO.getImageWritersByFormatName("jpg").next().apply {
                    output = ios
                }
                writer.write(
                    null,
                    IIOImage(finalImage, null, null),
                    writer.defaultWriteParam.apply {
                        compressionMode = ImageWriteParam.MODE_EXPLICIT
                        compressionQuality = quality.coerceIn(0f, 1f)
                    }
                )
                writer.dispose()
            }
            return baos.toByteArray()
        } finally {
            baos.close()
        }
    }

    fun screenshotJpegBytes(
        rect: Rectangle? = null,
        quality: Float = DESKTOP_SCREENSHOT_QUALITY,
    ): ByteArray {
        val tempFile = File.createTempFile("screenshot", ".png")
        try {
            tempFile.deleteOnExit()

            val processBuilder = if (rect == null) {
                ProcessBuilder("screencapture", "-x", tempFile.absolutePath)
            } else {
                ProcessBuilder(
                    "screencapture", "-x", "-R",
                    "${rect.x},${rect.y},${rect.width},${rect.height}",
                    tempFile.absolutePath
                )
            }

            val process = processBuilder.start()
            process.waitFor()

            val img = ImageIO.read(tempFile)
            val rgbImage: BufferedImage = BufferedImage(
                img.width, img.height, BufferedImage.TYPE_INT_RGB
            ).apply {
                createGraphics().drawImage(img, 0, 0, null)
            }
            compressJpeg(rgbImage, quality)
        } finally {
            tempFile.delete()
            throw kotlinx.io.IOException("Failed to make screenshot")
        }
    }
}

fun main() {
    val screenshot = screenshotJpegBytes()
    File("desktop.jpg").writeBytes(screenshot)
}

