package ru.gigadesk.tool.desktop

import org.slf4j.LoggerFactory
import ru.gigadesk.tool.FewShotExample
import ru.gigadesk.tool.InputParamDescription
import ru.gigadesk.tool.ReturnParameters
import ru.gigadesk.tool.ReturnProperty
import ru.gigadesk.tool.ToolRunBashCommand
import ru.gigadesk.tool.ToolSetup

class ToolFindFolders(
    private val bash: ToolRunBashCommand
) : ToolSetup<ToolFindFolders.Input> {
    private val l = LoggerFactory.getLogger(ToolFindFolders::class.java)

    override val name: String = "FindFolders"
    override val description: String = "Searches for folders in the macOS file system using Spotlight. " +
            "Returns a JSON list of absolute paths found."

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
        val sanitizedName = input.name.replace("\"", "\\\"")

        val exactQuery = "kMDItemDisplayName == \"$sanitizedName\"c && kMDItemContentType == \"public.folder\""
        var output = bash.sh("mdfind '$exactQuery' | head -n 20")

        if (output.isBlank()) {
            l.info("Exact match not found, trying partial search...")
            val partialQuery = "kMDItemDisplayName == \"*$sanitizedName*\"c && kMDItemContentType == \"public.folder\""
            output = bash.sh("mdfind '$partialQuery' | head -n 20")
        }

        if (output.isBlank()) {
            return "[]"
        }

        val paths = output.lines()
            .filter { it.isNotBlank() }
            .joinToString(separator = ",") { path ->
                "\"${path.replace("\"", "\\\"")}\""
            }

        return "[$paths]"
    }
}

fun main() {
    // Тест тула
    val tool = ToolFindFolders(ToolRunBashCommand)

    println(tool.invoke(ToolFindFolders.Input("Downloads")))
}