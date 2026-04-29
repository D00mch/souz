package ru.souz.tool.files

import ru.souz.llms.restJsonMapper
import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolSetup
import java.io.File

class ToolFindFolders(
    private val filesToolUtil: FilesToolUtil
) : ToolSetup<ToolFindFolders.Input> {
    override val name: String = "FindFolders"
    override val description: String = "Searches for folders in the allowed file system subtree. " +
        "Returns a JSON list of absolute paths found. Filters out restricted directories."

    data class Input(
        @InputParamDescription("Name of the folder to search for (e.g. 'Downloads', 'Project X')")
        val name: String
    )

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Найди папку с документами",
            params = mapOf("name" to "Documents")
        ),
        FewShotExample(
            request = "Где лежит папка Telegram?",
            params = mapOf("name" to "Telegram")
        )
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("array", "JSON list of folder paths")
        )
    )

    override fun invoke(input: Input): String {
        val needle = input.name.trim().lowercase()
        if (needle.isBlank()) {
            throw BadInputException("name must not be empty")
        }

        val paths = FilesToolUtil.homeDirectory.walkTopDown()
            .onEnter { file ->
                file == FilesToolUtil.homeDirectory || (filesToolUtil.isPathSafe(file) && !file.name.startsWith('.'))
            }
            .filter { it.isDirectory && it.name.lowercase().contains(needle) }
            .map { it.canonicalPath }
            .toList()

        val sorted = paths.sortedWith(
            compareBy<String> { File(it).name.equals(input.name, ignoreCase = true).not() }
                .thenBy { it.length }
        )
        return restJsonMapper.writeValueAsString(sorted.take(MAX_RESULTS))
    }

    private companion object {
        const val MAX_RESULTS = 50
    }
}
