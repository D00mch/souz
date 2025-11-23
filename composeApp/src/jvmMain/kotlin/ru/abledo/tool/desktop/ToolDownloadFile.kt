package ru.abledo.tool.desktop

import ru.abledo.giga.GigaChatAPI
import ru.abledo.giga.GigaRestChatAPI
import ru.abledo.tool.FewShotExample
import ru.abledo.tool.InputParamDescription
import ru.abledo.tool.ReturnParameters
import ru.abledo.tool.ReturnProperty
import ru.abledo.tool.ToolSetup
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.awt.Desktop
import java.io.File

class ToolDownloadFile(
    private val api: GigaChatAPI = GigaRestChatAPI.INSTANCE,
) : ToolSetup<ToolDownloadFile.Input> {
    private val l = LoggerFactory.getLogger(ToolDownloadFile::class.java)

    data class Input(
        @InputParamDescription("File id to download from GigaChat")
        val fileId: String,
    )

    override val name: String = "DownloadFile"
    override val description: String = "Downloads file from GigaChat (for example, generated images) and opens it on the desktop"
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Скачай файл, который ты только что сгенерировал",
            params = mapOf("fileId" to "some_id")
        ),
        FewShotExample(
            request = "Сгенерируй картинку заката и пришли её мне",
            params = mapOf("fileId" to "generated_image_id")
        )
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "The path to the downloaded file or error message")
        )
    )

    override fun invoke(input: Input): String = runBlocking { suspendInvoke(input) }

    override suspend fun suspendInvoke(input: Input): String {
        val path = api.downloadFile(input.fileId)
        return if (path != null) {
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(File(path))
                }
            } catch (e: Exception) {
                l.error("Failed to open file", e)
            }
            path
        } else {
            "File not found"
        }
    }
}

fun main() {
    val path = ToolDownloadFile().invoke(ToolDownloadFile.Input("file_id"))
    println(path)
}
