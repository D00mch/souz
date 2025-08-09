package com.dumch.image

import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
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
    /** Capture the entire desktop and return the image as JPEG bytes. */
    fun captureDesktop(robot: Robot): ByteArray {
        val screenSize = Toolkit.getDefaultToolkit().screenSize
        val captureRect = Rectangle(screenSize)
        val image = robot.createScreenCapture(captureRect)
        val output = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val ios = ImageIO.createImageOutputStream(output)
        writer.output = ios
        val param = writer.defaultWriteParam
        if (param.canWriteCompressed()) {
            param.compressionMode = ImageWriteParam.MODE_EXPLICIT
            param.compressionQuality = 1f
        }
        writer.write(null, IIOImage(image, null, null), param)
        writer.dispose()
        ios.close()
        return output.toByteArray()
    }

    /**
     * Compress JPEG bytes with the given [quality].
     * [quality] should be between 0f (maximum compression) and 1f (minimum compression).
     */
    fun compressJpeg(jpegBytes: ByteArray, quality: Float = 0.8f): ByteArray {
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
}

fun main() {
    val screenshot = ImageUtils.captureDesktop(Robot())
    val jpeg = ImageUtils.compressJpeg(screenshot)
    File("desktop.jpg").writeBytes(jpeg)
}

