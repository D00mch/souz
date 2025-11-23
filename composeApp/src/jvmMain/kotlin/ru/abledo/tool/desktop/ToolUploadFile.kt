package ru.abledo.tool.desktop

import ru.abledo.giga.GigaChatAPI
import ru.abledo.giga.GigaRestChatAPI
import ru.abledo.tool.FewShotExample
import ru.abledo.tool.InputParamDescription
import ru.abledo.tool.ReturnParameters
import ru.abledo.tool.ReturnProperty
import ru.abledo.tool.ToolSetupWithAttachments
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.File

class ToolUploadFile(
    private val api: GigaChatAPI = GigaRestChatAPI.INSTANCE,
) : ToolSetupWithAttachments<ToolUploadFile.Input> {
    private val l = LoggerFactory.getLogger(ToolUploadFile::class.java)

    data class Input(
        @InputParamDescription("File to upload path")
        val filePath: String,
    )

    override val name: String = "UploadFile"
    override val description: String = "Uploads file to GigaChat for analysis (logs, reports, etc.) and returns file id"
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Проанализируй файл report",
            params = mapOf("filePath" to "/path/to/report.csv")
        )
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Uploaded file id")
        )
    )

    private val lastAttachments = ArrayList<String>()
    override val attachments: List<String>
        get() = lastAttachments

    override fun invoke(input: Input): String = runBlocking { suspendInvoke(input) }

    override suspend fun suspendInvoke(input: Input): String {
        val file = File(input.filePath)
        val upload = api.uploadFile(file)
        lastAttachments.clear()
        lastAttachments.add(upload.id)
        l.info("Uploaded file ${'$'}{file.name} with id ${'$'}{upload.id}")
        return upload.id
    }
}

fun main() {
    val id = ToolUploadFile().invoke(ToolUploadFile.Input("/path/to/file"))
    println(id)
}
