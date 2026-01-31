package ru.gigadesk.tool.files

import ru.gigadesk.tool.*
import java.io.File

class ToolReadFile(private val filesToolUtil: FilesToolUtil) : ToolSetup<ToolReadFile.Input> {
    data class Input(
        @InputParamDescription("A relative path pointing to a file in the project directory")
        val path: String
    )
    override val name = "ReadFile"
    override val description = "Retrieve the contents of a specified file using a relative path. " +
            "Use this to read a file's contents. Avoid using it with directory paths"
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Прочти README",
            params = mapOf("path" to "README.md")
        )
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "File contents")
        )
    )

    override fun invoke(input: Input): String {
        val path = input.path
        val fixedPath = filesToolUtil.applyDefaultEnvs(path)
        val file = File(fixedPath)
        if (!filesToolUtil.isPathSafe(file)) {
            throw ForbiddenFolder(fixedPath)
        }
        if (!file.exists() || file.isDirectory) {
            throw BadInputException("Invalid file path: $path")
        }
        val content = file.readText()
        if (content.length > 25000) {
            return "Error: content is too large, it will be difficult to correctly process so much data (limit 25000 chars)."
        }
        return content
    }
}
