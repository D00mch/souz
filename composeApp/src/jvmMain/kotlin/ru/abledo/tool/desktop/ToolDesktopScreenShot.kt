package ru.abledo.tool.desktop

import ru.abledo.giga.GigaChatAPI
import ru.abledo.giga.GigaRestChatAPI
import ru.abledo.image.ImageUtils
import ru.abledo.tool.*
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.File

class ToolDesktopScreenShot(
    private val api: GigaChatAPI = GigaRestChatAPI.INSTANCE,
) : ToolSetupWithAttachments<ToolDesktopScreenShot.Input> {
    private val l = LoggerFactory.getLogger(ToolDesktopScreenShot::class.java)

    data class Input(
        @InputParamDescription("Desktop number to capture, e.g., '1' for the primary display by default")
        val path: String = "1"
    )

    override val name: String = "DesktopScreenShot"
    override val description: String = "Captures desktop screenshot and uploads it to GigaChat, returning image id. " +
            "Use it to see what's on desktop and to analyze elements on the screen and its coordinates."
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Сделай скриншот",
            params = mapOf("path" to "1")
        ),
        FewShotExample(
            request = "Что видишь на экране?",
            params = mapOf("path" to "1")
        ),
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Uploaded image id")
        )
    )

    private val lastAttachments = ArrayList<String>()
    override val attachments: List<String>
        get() = lastAttachments

    override fun invoke(input: Input): String = runBlocking { suspendInvoke(input) }

    override suspend fun suspendInvoke(input: Input): String {
        try {
            val screenshot = ImageUtils.screenshotJpegBytes()
            val file = File.createTempFile("screenshot", ".jpg")
            file.writeBytes(screenshot)
            l.info("Uploading screenshot to GigaChat")
            val upload = api.uploadFile(file)
            lastAttachments.clear()
            lastAttachments.add(upload.id)
            return upload.id
        } catch (e: Exception) {
            l.error("DesktopScreenShot failed", e)
            throw RuntimeException("DesktopScreenShot failed: ${e.message}", e)
        }
    }
}

fun main() {
    val l = LoggerFactory.getLogger(ToolDesktopScreenShot::class.java)
    val id = ToolDesktopScreenShot().invoke(ToolDesktopScreenShot.Input("1"))
    l.info(id)
}
