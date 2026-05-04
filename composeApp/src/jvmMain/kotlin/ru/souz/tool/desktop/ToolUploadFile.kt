package ru.souz.tool.desktop

import ru.souz.llms.ToolInvocationMeta

import ru.souz.llms.LLMChatAPI
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolSetupWithAttachments
import kotlinx.coroutines.runBlocking
import org.kodein.di.DI
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.souz.di.mainDiModule
import java.io.File

class ToolUploadFile(
    private val api: LLMChatAPI,
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

    override fun invoke(input: Input, meta: ToolInvocationMeta): String = runBlocking { suspendInvoke(input, meta) }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String {
        val file = File(input.filePath)
        val upload = api.uploadFile(file)
        lastAttachments.clear()
        lastAttachments.add(upload.id)
        l.info("Uploaded file ${'$'}{file.name} with id ${'$'}{upload.id}")
        return upload.id
    }
}

fun main() {
    val di = DI.invoke { import(mainDiModule) }
    val api: LLMChatAPI by di.instance()
    val id = ToolUploadFile(api).invoke(ToolUploadFile.Input("/path/to/file"), ToolInvocationMeta.localDefault())
    println(id)
}
