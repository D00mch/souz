package com.dumch.image

import com.dumch.image.ImageUtils.screenshotJpegBytes
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
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
    fun compressJpeg(jpegBytes: ByteArray, quality: Float = DESKTOP_SCREENSHOT_QUALITY): ByteArray {
        val inputImage = ImageIO.read(ByteArrayInputStream(jpegBytes))
        val compressed = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val ios = ImageIO.createImageOutputStream(compressed)
        writer.output = ios
        val param = writer.defaultWriteParam
        if (param.canWriteCompressed()) {
            param.compressionMode = ImageWriteParam.MODE_EXPLICIT
            param.compressionQuality = quality.coerceIn(0f, 1f)
        }
        writer.write(null, IIOImage(inputImage, null, null), param)
        writer.dispose()
        ios.close()
        return compressed.toByteArray()
    }

    fun screenshotJpegBytes(
        rect: Rectangle? = null,
        quality: Float = DESKTOP_SCREENSHOT_QUALITY
    ): ByteArray {
        val area = rect ?: Rectangle(Toolkit.getDefaultToolkit().screenSize)
        val img: BufferedImage = Robot().createScreenCapture(area)

        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val baos = ByteArrayOutputStream()
        val ios = ImageIO.createImageOutputStream(baos)
        writer.output = ios

        val p: ImageWriteParam = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality.coerceIn(0f, 1f) // lower -> smaller file
        }

        writer.write(null, IIOImage(img, null, null), p)
        ios.close(); writer.dispose()
        return baos.toByteArray()
    }
}

fun main() {
    val screenshot = screenshotJpegBytes()
    File("desktop.jpg").writeBytes(screenshot)
}

