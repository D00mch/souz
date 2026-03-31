package ru.souz.tool.desktop

import ru.souz.llms.LLMChatAPI
import ru.souz.service.image.ImageUtils
import ru.souz.tool.*
import kotlinx.coroutines.runBlocking
import org.kodein.di.DI
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.souz.di.mainDiModule
import java.io.File

class ToolDesktopScreenShot(
    private val api: LLMChatAPI,
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
    val di = DI.invoke { import(mainDiModule) }
    val api: LLMChatAPI by di.instance()
    val id = ToolDesktopScreenShot(api).invoke(ToolDesktopScreenShot.Input("1"))
    l.info(id)
}
