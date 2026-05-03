package ru.souz.tool.files

import ru.souz.llms.restJsonMapper
import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolSetup

class ToolFindInFiles(private val filesToolUtil: FilesToolUtil) : ToolSetup<ToolFindInFiles.Input> {
    data class Input(
        @InputParamDescription("Relative path to search for files. Defaults to user HOME. Try to avoid using ~ or HOME")
        val path: String = "~",
        @InputParamDescription("A text substring we are searching for")
        val query: String,
    )

    override val name: String = "SearchFileContent"
    override val description: String = "Search for files with the CONTENT (text) matching the specified query. " +
        "Returns the content line and file path. Only use this if you know the file content but not the location."
    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Do I wave written articles related to VR",
            params = mapOf(
                "path" to "~",
                "queries" to "VR"
            )
        )
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty(
                "string", """JSON array of arrays of file path and matching line. Example:
```json
[
    ["/Users/m1/wiki/vr_article.md", "Занимаюсь спортом в VR уже 4 года"]
    ["/Users/m1/Downloads/tmp.txt", "VRVRVR"]
]
```""".trimMargin()
            )
        )
    )

    override fun invoke(input: Input): String {
        val base = filesToolUtil.resolveSafeExistingDirectory(input.path)
        val needle = input.query.trim()
        if (needle.isBlank()) {
            throw BadInputException("query must not be empty")
        }
        val needleLower = needle.lowercase()
        val result = ArrayList<List<String>>()

        filesToolUtil.listDescendants(base, includeHidden = false)
            .asSequence()
            .filter { it.isRegularFile && (it.sizeBytes ?: Long.MAX_VALUE) <= MAX_TEXT_FILE_BYTES }
            .forEach { file ->
                if (result.size >= MAX_RESULTS) return@forEach
                val content = runCatching { filesToolUtil.readUtf8TextFile(file) }.getOrNull() ?: return@forEach
                if (!content.contains(needle, ignoreCase = true)) return@forEach

                content.lineSequence().forEach { line ->
                    if (result.size >= MAX_RESULTS) return@forEach
                    if (line.lowercase().contains(needleLower)) {
                        result += listOf(file.path, line.trim())
                    }
                }
            }
        return restJsonMapper.writeValueAsString(result)
    }

    private companion object {
        const val MAX_RESULTS = 200
        const val MAX_TEXT_FILE_BYTES = 1_000_000L
    }
}
