package ru.abledo.tool.files

import ru.abledo.tool.*
import java.io.File
import kotlin.io.relativeTo

object ToolListFiles : ToolSetup<ToolListFiles.Input> {
    data class Input(
        @InputParamDescription("Relative path to list files from")
        val path: String = ".",
        @InputParamDescription("Max depth to traverse (1 = direct children only; <=0 = unlimited)")
        val depth: Int = Integer.MAX_VALUE
    )
    override val name = "ListFiles"
    override val description = "Runs bash ls command at a given path. Dot (.) means current directory"
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "List files in current folder",
            params = mapOf("path" to ".")
        )
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Array of file paths")
        )
    )

    override fun invoke(input: Input): String {
        val fixedPath = FilesToolUtil.applyDefaultEnvs(input.path)
        val base = File(fixedPath)
        if (!base.exists() || !base.isDirectory) {
            throw BadInputException("Invalid directory path: $fixedPath")
        }

        val files = base.walkTopDown()
            .onEnter { file ->
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

        return files.joinToString(",", prefix = "[", postfix = "]")
    }
}

private val excludedPaths: List<String> = run {
    val home = System.getenv("HOME")
    listOf(
        "$home/Library",
        "$home/Sync",
        "$home/Yandex.Disk.localized",
        "$home/go",
        "$home/dotfiles",
        "$home/Applications",
    )
}

fun main() {
    val result = ToolListFiles(
        ToolListFiles.Input("${'$'}HOME", 3)
    )
    println(result)
}
