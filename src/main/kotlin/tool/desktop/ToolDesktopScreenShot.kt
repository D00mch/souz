package com.dumch.tool.desktop

import com.dumch.giga.GigaAuth
import com.dumch.giga.GigaChatAPI
import com.dumch.image.ImageUtils
import com.dumch.tool.InputParamDescription
import com.dumch.tool.ToolSetupWithAttachments
import kotlinx.coroutines.runBlocking
import java.io.File

class ToolDesktopScreenShot(
    private val api: GigaChatAPI = GigaChatAPI(GigaAuth)
) : ToolSetupWithAttachments<ToolDesktopScreenShot.Input> {

    override val name: String = "DesktopScreenShot"
    override val description: String = "Captures desktop screenshot and uploads it to GigaChat, returning image id"

    private var lastAttachments: List<String> = emptyList()
    override val attachments: List<String>
        get() = lastAttachments

    override fun invoke(input: Input): String {
        val screenshot = ImageUtils.captureDesktop()
        val file = File.createTempFile("screenshot", ".jpg")
        file.writeBytes(ImageUtils.compressJpeg(screenshot))
        val upload = runBlocking { api.uploadImage(file) }
        lastAttachments = listOf(upload.id)
        return upload.id
    }

    data class Input(
        @InputParamDescription("Desktop number to capture, e.g., '1' for the primary display by default")
        val path: String = "1"
    )
}

