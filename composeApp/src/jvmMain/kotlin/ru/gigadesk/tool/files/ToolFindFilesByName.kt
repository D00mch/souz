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

open class ToolFindFilesByName(private val filesToolUtil: FilesToolUtil) : ToolSetup<ToolFindFilesByName.Input> {
    data class Input(
        @InputParamDescription("Relative or absolute path to limit the search. Defaults to user HOME.")
        val path: String = FilesToolUtil.homeDirectory.absolutePath,

        @InputParamDescription("The name or partial name of the file we are searching for.")
        val fileName: String,
    )

    override val name: String = "FindFilesByName"
    override val description: String = """
        [PRIMARY TOOL for Finding Files]
        Search for the PATH of a file by its name (or partial name).
        Use this when the user asks "Find file X" or "Where is file Y".
        
        Mechanism: uses macOS Spotlight (mdfind). Fast and recursive.
    """.trimIndent()

    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Find where the 'budget_2024.xlsx' is located",
            params = mapOf(
                "path" to "~",
                "fileName" to "budget_2024"
            )
        )
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty(
                "string", """JSON array of file paths. Example:
```json
[
    "/Users/m1/Documents/Work/budget_2024.xlsx",
    "/Users/m1/Downloads/budget_2024_v2.xlsx"
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

        val script = "mdfind -onlyin \"$1\" -name \"$2\""

        val result = ToolRunBashCommand.sh(script, path, input.fileName)
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()

        return objectMapper.writeValueAsString(result)
    }
}

fun main() {
    val filesToolUtil = FilesToolUtil(SettingsProviderImpl(ConfigStore))
    val tool = ToolFindFilesByName(filesToolUtil)
    val searchInput = ToolFindFilesByName.Input(
        path = "~",
        fileName = "родмап"
    )

    println("Running search for: ${searchInput.fileName} in ${searchInput.path}...")

    val resultJson = tool.invoke(searchInput)
    println("Raw JSON result: $resultJson")

    try {
        val fileList: List<String> = objectMapper.readValue(resultJson)

        if (fileList.isEmpty()) {
            println("No files found.")
        } else {
            println("Found ${fileList.size} files:")
            fileList.take(5).forEach { println(" - $it") } // Показываем первые 5
            if (fileList.size > 5) println("... and ${fileList.size - 5} more.")
        }
    } catch (e: Exception) {
        println("Error parsing result: ${e.message}")
    }
}
