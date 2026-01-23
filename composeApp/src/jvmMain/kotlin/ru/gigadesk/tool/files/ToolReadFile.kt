package ru.gigadesk.tool.files

import ru.gigadesk.tool.*
import java.io.File

object ToolReadFile : ToolSetup<ToolReadFile.Input> {
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
        val file = File(FilesToolUtil.applyDefaultEnvs(path))
        if (!file.exists() || file.isDirectory) {
            throw BadInputException("Invalid file path: $path")
        }
        // Limit to 20KB to avoid crashing LLM context
        val limit = 20 * 1024
        val content = file.readText()
        return if (content.length > limit) {
            content.take(limit) + "\n\n... [File content truncated. Total size: ${content.length} chars. Use specific tools to read sections.]"
        } else {
            content
        }
    }
}
