package ru.abledo.tool.files

import ru.abledo.tool.*
import java.io.File

object ToolDeleteFile : ToolSetup<ToolDeleteFile.Input> {
    data class Input(
        @InputParamDescription("The path of the file to delete")
        val path: String
    )
    override val name = "DeleteFile"
    override val description = "Deletes a file at the given path."
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Удали temp.txt",
            params = mapOf("path" to "temp.txt")
        )
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Deletion status")
        )
    )

    override fun invoke(input: Input): String {
        val file = File(input.path)
        if (!file.exists() || file.isDirectory) {
            throw BadInputException("Invalid file path: ${input.path}")
        }
        FilesToolUtil.requirePathIsSave(file)
        file.delete()
        return "File deleted at ${input.path}"
    }
}
