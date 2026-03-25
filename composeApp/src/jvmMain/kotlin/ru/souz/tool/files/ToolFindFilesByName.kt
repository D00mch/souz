package ru.souz.tool.files

import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.runBlocking
import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolRunBashCommand
import ru.souz.tool.ToolActionDescriptor
import ru.souz.tool.ToolActionKind
import ru.souz.tool.ToolActionValueFormatter
import ru.souz.tool.ToolSetup
import ru.souz.db.ConfigStore
import ru.souz.db.SettingsProviderImpl
import ru.souz.giga.gigaJsonMapper
import java.io.File

class ToolFindFilesByName(private val filesToolUtil: FilesToolUtil) : ToolSetup<ToolFindFilesByName.Input> {
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

    override fun describeAction(input: Input): ToolActionDescriptor? =
        ToolActionKind.FIND_FILE.textAction(input.fileName)

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

        return gigaJsonMapper.writeValueAsString(result)
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
        val fileList: List<String> = gigaJsonMapper.readValue(resultJson)

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
