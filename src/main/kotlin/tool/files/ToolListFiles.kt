package com.dumch.tool.files

import com.dumch.tool.*
import java.io.File

object ToolListFiles : ToolSetup<ToolListFiles.Input> {
    data class Input(
        @InputParamDescription("Relative path to list files from")
        val path: String = "."
    )
    override val name = "ListFiles"
    override val description = "Runs bash ls command at a given path. Dot (.) means current directory"
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "List files in current folder",
            params = mapOf("path" to ".")
        )
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Array of file paths")
        )
    )

    override fun invoke(input: Input): String {
        val dirPath = input.path
        val base = File(dirPath)
        if (!base.exists() || !base.isDirectory) {
            throw BadInputException("Invalid directory path: $dirPath")
        }
        val files = base.walkTopDown()
            .filter { it != base }
            .map { file ->
                val relPath = file.relativeTo(base).path
                if (file.isDirectory) "$relPath/" else relPath
            }

        return files.joinToString(",", prefix = "[", postfix = "]")
    }
}
