package com.dumch.tool.desktop

import com.dumch.giga.GigaAuth
import com.dumch.giga.GigaChatAPI
import com.dumch.tool.InputParamDescription
import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.ToolSetup
import kotlinx.coroutines.runBlocking
import java.io.File

class ToolDesktopScreenShot(
    private val bash: ToolRunBashCommand,
    private val api: GigaChatAPI = GigaChatAPI(GigaAuth)
) : ToolSetup<ToolDesktopScreenShot.Input> {

    override val name: String = "DesktopScreenShot"
    override val description: String = "Takes a screenshot of selected desktop and uploads it to GigaChat"

    override fun invoke(input: Input): String {
        val file = File.createTempFile("screenshot", ".jpg")
        try {
            bash.invoke(
                ToolRunBashCommand.Input(
                    """screencapture -x -D ${input.path} -t jpg ${file.absolutePath}""".trimIndent()
                )
            )
            val resp = runBlocking { api.uploadImage(file) }
            return resp.id
        } finally {
            file.delete()
        }
    }

    data class Input(
        @InputParamDescription("Desktop number to capture, e.g., '1' for the primary display by default")
        val path: String = "1"
    )
}
