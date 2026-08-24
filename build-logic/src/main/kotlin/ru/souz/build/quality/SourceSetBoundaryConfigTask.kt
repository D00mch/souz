package ru.souz.build.quality

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class SourceSetBoundaryConfigTask : DefaultTask() {
    @get:Input
    abstract val sourceRoots: ListProperty<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun writeConfig() {
        val mappings = sourceRoots.get().distinct().sorted()
        val entries = mappings.map(::parseSourceRoot)
        val conflictingRoots = entries.flatMapIndexed { index, left ->
            entries.drop(index + 1).mapNotNull { right ->
                (left to right).takeIf {
                    left.owner != right.owner && (left.contains(right) || right.contains(left))
                }
            }
        }
        check(conflictingRoots.isEmpty()) {
            conflictingRoots.joinToString(
                prefix = "Overlapping source roots belong to different Kotlin source sets: ",
                separator = "; ",
            ) { (left, right) ->
                "${left.root} (${left.renderedOwner}) and ${right.root} (${right.renderedOwner})"
            }
        }

        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            buildString {
                appendLine("souz-architecture:")
                appendLine("  SourceSetBoundaries:")
                if (mappings.isEmpty()) {
                    appendLine("    sourceRoots: []")
                } else {
                    appendLine("    sourceRoots:")
                    mappings.forEach { mapping ->
                        append("      - '")
                        append(mapping.replace("'", "''"))
                        appendLine("'")
                    }
                }
            }
        )
    }
}

private data class SourceRoot(
    val root: String,
    val module: String,
    val sourceSet: String,
    val production: Boolean,
    val hostMain: Boolean,
) {
    val owner: Pair<String, String> = module to sourceSet
    val renderedOwner: String = "$module:$sourceSet"

    fun contains(other: SourceRoot): Boolean =
        other.root == root || other.root.startsWith("${root.trimEnd('/')}/")
}

private fun parseSourceRoot(encoded: String): SourceRoot {
    val parts = encoded.split('|')
    require(parts.size == 5 && parts.none(String::isEmpty)) {
        "Invalid source-root mapping '$encoded'. " +
            "Expected absoluteRoot|moduleName|sourceSetName|production|hostMain."
    }
    return SourceRoot(
        root = parts[0],
        module = parts[1],
        sourceSet = parts[2],
        production = parts[3].toBooleanStrict(),
        hostMain = parts[4].toBooleanStrict(),
    )
}
