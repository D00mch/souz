package ru.souz.tool.files

import ru.souz.db.ConfigStore
import ru.souz.db.SettingsProviderImpl
import ru.souz.tool.*

class ToolListFiles(private val filesToolUtil: FilesToolUtil) : ToolSetup<ToolListFiles.Input> {
    data class Input(
        @InputParamDescription("Relative path to list files from")
        val path: String = "~",
        @InputParamDescription("Max depth to traverse (1 = direct children only; <=0 = unlimited)")
        val depth: Int = Integer.MAX_VALUE
    )
    override val name = "ListFiles"
    override val description = "Runs bash ls command at a given path. Use ~ to start from the home directory"
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "List files in the home directory",
            params = mapOf("path" to "${'$'}HOME", "depth" to 1),
        )
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Array of file paths")
        )
    )

    override fun invoke(input: Input): String {
        val base = filesToolUtil.resolveSafeExistingDirectory(input.path)
        val files = filesToolUtil.listDescendants(base, input.depth, includeHidden = false)
            .map { entry ->
                val relPath = java.nio.file.Path.of(base.path).relativize(java.nio.file.Path.of(entry.path))
                    .toString()
                    .replace('\\', '/')
                if (entry.isDirectory) "${base.path}/$relPath/" else "${base.path}/$relPath"
            }

        val result = files.joinToString(",", prefix = "[", postfix = "]")
        if (result.length > 25000) {
            return "Error: content is too large, it will be difficult to correctly process so much data (limit 25000 chars)."
        }
        return result
    }
}

fun main() {
    val filesToolUtil = FilesToolUtil(SettingsProviderImpl(ConfigStore))
    val result = ToolListFiles(filesToolUtil).invoke(ToolListFiles.Input("${'$'}HOME", 3))
    println(result)
}
