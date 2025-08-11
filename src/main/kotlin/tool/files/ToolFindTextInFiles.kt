package com.dumch.tool.files

import com.dumch.tool.*
import java.io.File

object ToolFindTextInFiles : ToolSetup<ToolFindTextInFiles.Input> {
    override val name = "FindTextInFiles"
    override val description = "Search for a specific text across all files in a directory (recursively) " +
            "and return matching file paths."
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Find TODO markers",
            params = mapOf("path" to ".", "text" to "TODO")
        )
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Array of matching file paths")
        )
    )

    override fun invoke(input: Input): String {
        val baseDir = File(input.path)
        if (!baseDir.exists() || !baseDir.isDirectory) {
            throw BadInputException("Invalid directory path: ${input.path}")
        }
        val matchedFiles = baseDir.walkTopDown()
            .filter { it.isFile && it.readText().contains(input.text) }
            .map { it.relativeTo(baseDir).path }
            .toList()

        return matchedFiles.joinToString(",", prefix = "[", postfix = "]")
    }

    data class Input(
        @InputParamDescription("Directory path to search in (recursive)")
        val path: String = ".",
        @InputParamDescription("Text to search for inside files")
        val text: String
    )
}