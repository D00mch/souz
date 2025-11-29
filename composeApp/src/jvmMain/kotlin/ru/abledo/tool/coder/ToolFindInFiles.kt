package ru.abledo.tool.coder

import ch.qos.logback.core.testUtil.FileToBufferUtil
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.runBlocking
import ru.abledo.giga.objectMapper
import ru.abledo.tool.FewShotExample
import ru.abledo.tool.InputParamDescription
import ru.abledo.tool.ReturnParameters
import ru.abledo.tool.ReturnProperty
import ru.abledo.tool.ToolRunBashCommand
import ru.abledo.tool.ToolSetup
import ru.abledo.tool.files.FilesToolUtil
import java.io.File

object ToolFindInFiles : ToolSetup<ToolFindInFiles.Input> {
    data class Input(
        @InputParamDescription("Relative path to search for files. Defaults to user HOME")
        val path: String = FilesToolUtil.homeDirectory.absolutePath,
        @InputParamDescription("A text substring we are searching for")
        val query: String,
    )

    override val name: String = "FindInFiles"
    override val description: String = "Search for files with the content matching the specified query"
    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Do I wave written articles related to VR",
            params = mapOf(
                "path" to "${'$'}HOME",
                "queries" to "VR"
            )
        )
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty(
                "object", """JSON array of arrays of file path and matching line. Example:
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
    val result = ToolFindInFiles.invoke(ToolFindInFiles.Input("HOME", " vr "))
    println("result: $result")
    val results: List<List<String>> = objectMapper.readValue(result)
    results.forEach { (path, query) ->
        val safe = FilesToolUtil.isPathSafe(File(path))
        if (safe) {
            println("Safe!: $path")
        } else {
            println("!Safe: $path")
        }
    }
}