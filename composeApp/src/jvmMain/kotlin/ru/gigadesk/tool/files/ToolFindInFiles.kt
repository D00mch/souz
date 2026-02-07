package ru.gigadesk.tool.files

import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.runBlocking
import ru.gigadesk.giga.objectMapper
import ru.gigadesk.tool.BadInputException
import ru.gigadesk.tool.FewShotExample
import ru.gigadesk.tool.InputParamDescription
import ru.gigadesk.tool.ReturnParameters
import ru.gigadesk.tool.ReturnProperty
import ru.gigadesk.tool.ToolRunBashCommand
import ru.gigadesk.tool.ToolSetup
import ru.gigadesk.db.ConfigStore
import ru.gigadesk.db.SettingsProviderImpl
import java.io.File

class ToolFindInFiles(private val filesToolUtil: FilesToolUtil) : ToolSetup<ToolFindInFiles.Input> {
    data class Input(
        @InputParamDescription("Relative path to search for files. Defaults to user HOME. Try to avoid using ~ or HOME")
        val path: String = FilesToolUtil.homeDirectory.absolutePath,
        @InputParamDescription("A text substring we are searching for")
        val query: String,
    )

    override val name: String = "SearchFileContent"
    override val description: String = """
        Search for files with the CONTENT (text) matching the specified query.
        Returns the content line and file path.
        
        WARNING: Do NOT use this to find a file path by its name. Use 'FindFilesByName' for that.
        Only use this if you know the file content but not the location.
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
        val path = filesToolUtil.applyDefaultEnvs(input.path)
        val base = File(path)
        if (!filesToolUtil.isPathSafe(base)) {
            throw BadInputException("Forbidden directory: $path. User explicitly restricted this path. Inform him")
        }
        val script = filesToolUtil.resourceAsText("scripts/find_in_files.sh")
        val result = ToolRunBashCommand.sh(script, path, input.query)
            .lineSequence()
            .windowed(size = 2, step = 2, partialWindows = false)
            .mapNotNull { (filePath, matchingContent) ->
                val file = File(filePath)
                if (filesToolUtil.isPathSafe(file)) {
                    listOf(filePath, matchingContent)
                } else {
                    null
                }
            }
            .toList()
        return objectMapper.writeValueAsString(result)
    }
}

fun main() {
    val filesToolUtil = FilesToolUtil(SettingsProviderImpl(ConfigStore))
    val tool = ToolFindInFiles(filesToolUtil)
    val result = tool.invoke(ToolFindInFiles.Input("~/wiki", " vr "))
    println("result: $result")
    val results: List<List<String>> = objectMapper.readValue(result)
    results.forEach { (path, _) ->
        val safe = filesToolUtil.isPathSafe(File(path))
        if (safe) {
            println("Safe!: $path")
        } else {
            println("!Safe: $path")
        }
    }
}
