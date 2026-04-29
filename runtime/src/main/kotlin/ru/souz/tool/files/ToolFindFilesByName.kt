package ru.souz.tool.files

import ru.souz.llms.restJsonMapper
import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolSetup
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

        Mechanism: recursive filesystem traversal inside the allowed home subtree.
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

    override fun invoke(input: Input): String {
        val path = filesToolUtil.applyDefaultEnvs(input.path)
        val base = File(path)
        if (!filesToolUtil.isPathSafe(base)) {
            throw BadInputException("Forbidden directory: $path. User explicitly restricted this path. Inform him")
        }
        if (!base.exists() || !base.isDirectory) {
            throw BadInputException("Invalid directory path: ${input.path}")
        }
        val needle = input.fileName.trim().lowercase()
        if (needle.isBlank()) {
            throw BadInputException("fileName must not be empty")
        }

        val result = base.walkTopDown()
            .onEnter { file ->
                file == base || (filesToolUtil.isPathSafe(file) && !file.name.startsWith('.'))
            }
            .filter { it.isFile && it.name.lowercase().contains(needle) }
            .map { it.canonicalPath }
            .take(MAX_RESULTS)
            .toList()

        return restJsonMapper.writeValueAsString(result)
    }

    private companion object {
        const val MAX_RESULTS = 200
    }
}
