package com.dumch.tool.files

import com.dumch.tool.*
import java.io.File
import java.nio.file.Paths

object ToolReadFile : ToolSetup<ToolReadFile.Input> {
    data class Input(
        @InputParamDescription("Path to a file. Absolute paths are allowed; relative paths are resolved from the project directory")
        val path: String
    )
    override val name = "ReadFile"
    override val description = "Retrieve the contents of a specified file using a path. " +
            "Relative paths are resolved from the project directory. Avoid using it with directory paths"
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
        val base = Paths.get("").toAbsolutePath()
        val file = base.resolve(input.path).normalize().toFile()
        if (!file.exists() || !file.isFile) {
            throw BadInputException("Invalid file path: ${input.path}")
        }
        return file.readText()
    }
}

fun main() {
    val tool = ToolReadFile
    // Using a relative path (resolved from the project directory)
    println(tool.invoke(ToolReadFile.Input("src/test/resources/test.txt")))
    // Using an absolute path
    val absolute = java.io.File("src/test/resources/test.txt").absolutePath
    println(tool.invoke(ToolReadFile.Input(absolute)))
}
