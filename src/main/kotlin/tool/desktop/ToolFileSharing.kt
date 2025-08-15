package com.dumch.tool.desktop

import com.dumch.giga.GigaChatAPI
import com.dumch.tool.InputParamDescription
import com.dumch.giga.GigaRestChatAPI
import com.dumch.tool.ToolSetup
import com.dumch.tool.FewShotExample
import com.dumch.tool.ReturnParameters
import com.dumch.tool.ReturnProperty
import com.dumch.tool.ToolSetupWithAttachments
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.File

class ToolFileSharing(
    private val api: GigaChatAPI = GigaRestChatAPI.INSTANCE,
) : ToolSetupWithAttachments<ToolFileSharing.Input> {
    private val l = LoggerFactory.getLogger(ToolFileSharing::class.java)

    data class Input(
        @InputParamDescription("Action to perform")
        val action: Action,
        @InputParamDescription("File Id to download from GigaChat")
        val fileId: String,
        @InputParamDescription("File to upload path")
        val filePath: String,
        )

    enum class Action {
        upload,
        download,
    }

    override val name: String = "FileSharing"
    override val description: String = "Allows to upload files to GigaChat and save generated files by its file_id from GigaChat"
    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Загрузить файл",
            params = mapOf("action" to Action.upload)
        ),
        FewShotExample(
            request = "Скачать файл",
            params = mapOf("action" to Action.download)
        )
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Uploaded/DownLoaded")
        )
    )

    private val lastAttachments = ArrayList<String>()
    override val attachments: List<String>
        get() = lastAttachments

    override fun invoke(input: Input): String = runBlocking { suspendInvoke(input) }

    override suspend fun suspendInvoke(input: Input): String {
        val file = File(input.filePath)

        return when (input.action) {
            Action.upload -> api.uploadFile(file).id
            Action.download -> api.downloadFile(input.fileId) ?: "File not found"
        }
    }

}

fun main() {
    val t = ToolFileSharing()
    //t.invoke(ToolFileSharing.Input(ToolFileSharing.Action.upload, "$HOME/Pictures/портрет.jpeg"))
    val result = t.invoke(ToolFileSharing.Input(ToolFileSharing.Action.download, "8991d551-db71-467b-8654-aa87632a4743", "/Users/duxx/Downloads"))
    println(result)
}