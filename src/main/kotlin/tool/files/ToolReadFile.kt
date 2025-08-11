package com.dumch.tool.files

import com.dumch.tool.*
import java.io.File

object ToolReadFile : ToolSetup<ToolReadFile.Input> {
    override val name = "ReadFile"
    override val description = "Retrieve the contents of a specified file using a relative path. " +
            "Use this to read a file's contents. Avoid using it with directory paths"
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Read the README file",
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
        val file = File(path)
        if (!file.exists() || file.isDirectory) {
            throw BadInputException("Invalid file path: $path")
        }
        return file.readText()
    }

    data class Input(
        @InputParamDescription("A relative path pointing to a file in the project directory")
        val path: String
    )
}
