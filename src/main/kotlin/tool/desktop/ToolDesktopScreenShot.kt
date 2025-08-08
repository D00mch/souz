package com.dumch.tool.desktop

import com.dumch.giga.GigaAuth
import com.dumch.giga.GigaChatAPI
import com.dumch.image.ImageUtils
import com.dumch.tool.InputParamDescription
import com.dumch.tool.ToolSetupWithAttachments
import kotlinx.coroutines.runBlocking
import java.awt.Robot
import java.io.File

class ToolDesktopScreenShot(
    private val api: GigaChatAPI = GigaChatAPI(GigaAuth),
    private val robot: Robot = Robot(),
) : ToolSetupWithAttachments<ToolDesktopScreenShot.Input> {

    override val name: String = "DesktopScreenShot"
    override val description: String = "Captures desktop screenshot and uploads it to GigaChat, returning image id. " +
            "Use it to see what's on desktop"

    private val lastAttachments = ArrayList<String>()
    override val attachments: List<String>
        get() = lastAttachments

    override fun invoke(input: Input): String = runBlocking { suspendInvoke(input) }

    override suspend fun suspendInvoke(input: Input): String {
        try {
            val screenshot = ImageUtils.captureDesktop(robot)
            val file = File.createTempFile("screenshot", ".jpg")
            file.writeBytes(ImageUtils.compressJpeg(screenshot))
            val upload = api.uploadImage(file)
            lastAttachments.clear()
            lastAttachments.add(upload.id)
            return upload.id
        } catch (e: Exception) {
            return "Error in DesktopScreenShot: ${e.message}"
                .also { println(it) }
        }
    }

    data class Input(
        @InputParamDescription("Desktop number to capture, e.g., '1' for the primary display by default")
        val path: String = "1"
    )
}

fun main() {
    val id = ToolDesktopScreenShot().invoke(ToolDesktopScreenShot.Input("1"))
    println(id)
}
