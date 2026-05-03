package ru.souz.tool.files

import ru.souz.llms.ToolInvocationMeta
import ru.souz.tool.*

class ToolFindTextInFiles(private val filesToolUtil: FilesToolUtil) : ToolSetup<ToolFindTextInFiles.Input> {
    data class Input(
        @InputParamDescription("Directory path to search in (recursive)")
        val path: String = "~",
        @InputParamDescription("Text to search for inside files")
        val text: String
    )
    override val name = "FindTextInFiles"
    override val description = "Search for a specific text across all files in a directory (recursively) " +
            "and return matching file paths."
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Find TODO markers",
            params = mapOf("path" to ".", "text" to "TODO")
        ),
        FewShotExample(
            request = "Сколько main функций в проекте",
            params = mapOf("path" to ".", "text" to "fun main(")
        )
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Array of matching file paths")
        )
    )

    override fun invoke(input: Input, meta: ToolInvocationMeta): String {
        val baseDir = filesToolUtil.resolveSafeExistingDirectory(input.path)
        val basePath = java.nio.file.Path.of(baseDir.path)
        val matchedFiles = filesToolUtil.listDescendants(baseDir, includeHidden = false)
            .asSequence()
            .filter { it.isRegularFile }
            .filter { runCatching { filesToolUtil.readUtf8TextFile(it).contains(input.text) }.getOrDefault(false) }
            .map { basePath.relativize(java.nio.file.Path.of(it.path)).toString().replace('\\', '/') }
            .toList()

        return matchedFiles.joinToString(",", prefix = "[", postfix = "]")
    }
}
