package ru.souz.tool.files

import org.slf4j.LoggerFactory
import ru.souz.db.ConfigStore
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolRunBashCommand
import ru.souz.tool.ToolSetup
import java.io.File
import ru.souz.db.SettingsProviderImpl
import ru.souz.llms.restJsonMapper

class ToolFindFolders(
    private val bash: ToolRunBashCommand,
    private val filesToolUtil: FilesToolUtil
) : ToolSetup<ToolFindFolders.Input> {
    private val l = LoggerFactory.getLogger(ToolFindFolders::class.java)

    override val name: String = "FindFolders"
    override val description: String = "Searches for folders in the macOS file system using Spotlight. " +
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
        l.info("Searching for folder: ${input.name}")

        val spotlightSafeName = input.name
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")

        val exactQueryRaw = "kMDItemDisplayName == \"$spotlightSafeName\"c && kMDItemContentType == \"public.folder\""

        var paths = executeAndFilter(exactQueryRaw, limit = 100)

        if (paths.isEmpty()) {
            l.info("Exact safe match not found, trying partial search...")
            val partialQueryRaw = "kMDItemDisplayName == \"*$spotlightSafeName*\"c && kMDItemContentType == \"public.folder\""
            paths = executeAndFilter(partialQueryRaw, limit = 100)
        }

        val resultList = paths.take(20)
        return restJsonMapper.writeValueAsString(resultList)
    }

    private fun executeAndFilter(rawSpotlightQuery: String, limit: Int): List<String> {
        val shellSafeQuery = rawSpotlightQuery.replace("'", "'\\''")

        val output = bash.sh("mdfind '$shellSafeQuery' | head -n $limit")

        if (output.isBlank()) return emptyList()

        return output.lineSequence()
            .filter { it.isNotBlank() }
            .filter { path ->
                val isSafe = filesToolUtil.isPathSafe(File(path))
                if (!isSafe) {
                    l.debug("Skipping unsafe path: $path")
                }
                isSafe
            }
            .toList()
    }
}

fun main() {
     val filesToolUtil = FilesToolUtil(SettingsProviderImpl(ConfigStore))
     val tool = ToolFindFolders(ToolRunBashCommand, filesToolUtil)
     println(tool.invoke(ToolFindFolders.Input("Загрузки")))
}