package ru.gigadesk.tool.files

import ru.gigadesk.tool.*
import java.io.File

class ToolNewFile(private val filesToolUtil: FilesToolUtil) : ToolSetup<ToolNewFile.Input> {
    data class Input(
        @InputParamDescription("The path where the file will be created, including filename")
        val path: String,
        @InputParamDescription("The content to be written to the new file")
        val text: String
    )
    override val name = "NewFile"
    override val description = "Creates a new file at the given path with the provided content."
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Create notes.txt with greeting",
            params = mapOf("path" to "notes.txt", "text" to "Hello!\n")
        )
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Creation status")
        )
    )

    override fun invoke(input: Input): String {
        val fixedPath = filesToolUtil.applyDefaultEnvs(input.path)
        val file = File(fixedPath)
        if (!filesToolUtil.isPathSafe(file)) {
            throw ForbiddenFolder(fixedPath)
        }
        if (file.exists()) {
            throw BadInputException("File already exists: ${input.path}")
        }
        file.parentFile?.mkdirs()
        file.writeText(input.text)
        return "File created at ${input.path}"
    }
}
