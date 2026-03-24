package ru.souz.tool.files

import ru.souz.db.ConfigStore
import ru.souz.db.SettingsProviderImpl
import ru.souz.tool.*
import java.io.File
import kotlin.io.relativeTo

class ToolListFiles(private val filesToolUtil: FilesToolUtil) : ToolSetup<ToolListFiles.Input> {
    data class Input(
        @InputParamDescription("Relative path to list files from")
        val path: String = FilesToolUtil.homeDirectory.absolutePath,
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

    override fun describeAction(input: Input): ToolActionDescriptor? = ToolActionDescriptor(
        kind = ToolActionKind.LIST_FOLDER,
        primary = ToolActionValueFormatter.folderName(input.path),
    )

    override fun invoke(input: Input): String {
        val fixedPath = filesToolUtil.applyDefaultEnvs(input.path)
        val base = File(fixedPath)
        if (!filesToolUtil.isPathSafe(base)) {
            throw ForbiddenFolder(fixedPath)
        }
        if (!base.exists() || !base.isDirectory) {
            throw BadInputException("Invalid directory path: $fixedPath")
        }

        val files = base.walkTopDown()
            .onEnter { file ->
                val excludedPaths: List<String> = filesToolUtil.forbiddenDirectories().map { it.canonicalPath }
                val prohibit = excludedPaths.contains(file.path)
                        || file.name.startsWith('.')
                !prohibit
            }
            .maxDepth(input.depth)
            .filter { it != base }
            .map { file ->
                val relPath = file.relativeTo(base).path
                if (file.isDirectory) "$fixedPath/$relPath/" else "$fixedPath/$relPath"
            } // no sort or .toList() required, not for codex

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
