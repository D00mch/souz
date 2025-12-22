package ru.gigadesk.tool.files

import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.runBlocking
import ru.gigadesk.giga.objectMapper
import ru.gigadesk.tool.FewShotExample
import ru.gigadesk.tool.InputParamDescription
import ru.gigadesk.tool.ReturnParameters
import ru.gigadesk.tool.ReturnProperty
import ru.gigadesk.tool.ToolRunBashCommand
import ru.gigadesk.tool.ToolSetup
import java.io.File

object ToolFindInFiles : ToolSetup<ToolFindInFiles.Input> {
    data class Input(
        @InputParamDescription("Relative path to search for files. Defaults to user HOME. Try to avoid using ~ or HOME")
        val path: String = FilesToolUtil.homeDirectory.absolutePath,
        @InputParamDescription("A text substring we are searching for")
        val query: String,
    )

    override val name: String = "FindInFiles"
    override val description: String = """
        Search for files with the content matching the specified query. Use ListFiles function to get the idea where to
        search next. Don't search ~ or HOME, it will take a lot of time
    """.trimIndent()
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

    override fun invoke(input: Input): String = runBlocking { suspendInvoke(input) }

    override suspend fun suspendInvoke(input: Input): String {
        val path = FilesToolUtil.applyDefaultEnvs(input.path)
        val script = FilesToolUtil.resourceAsText("scripts/find_in_files.sh")
        val result = ToolRunBashCommand.sh(script, path, input.query)
            .lineSequence()
            .windowed(size = 2, step = 2, partialWindows = false)
            .map { (filePath, matchingContent) -> listOf(filePath, matchingContent) }
            .toList()
        return objectMapper.writeValueAsString(result)
    }
}

fun main() {
    val result = ToolFindInFiles.invoke(ToolFindInFiles.Input("~/wiki", " vr "))
    println("result: $result")
    val results: List<List<String>> = objectMapper.readValue(result)
    results.forEach { (path, _) ->
        val safe = FilesToolUtil.isPathSafe(File(path))
        if (safe) {
            println("Safe!: $path")
        } else {
            println("!Safe: $path")
        }
    }
}