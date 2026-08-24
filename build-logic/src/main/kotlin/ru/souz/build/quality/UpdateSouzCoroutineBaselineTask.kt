package ru.souz.build.quality

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.w3c.dom.Element
import java.io.File

@DisableCachingByDefault(because = "This explicit maintenance task rewrites the tracked Detekt baseline.")
abstract class UpdateSouzCoroutineBaselineTask : DefaultTask() {
    @get:Internal
    abstract val repositoryDirectory: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val partialBaselines: ConfigurableFileCollection

    @get:Internal
    abstract val baselines: DirectoryProperty

    @TaskAction
    fun update() {
        val repository = repositoryDirectory.get().asFile
        val outputDirectory = baselines.get().asFile
        val rendered = DetektBaselines.renderByAnalysis(repository, partialBaselines.files)
        val expectedFiles = rendered.keys.mapTo(mutableSetOf()) { outputDirectory.resolve(it).normalize() }

        outputDirectory.walkTopDown()
            .filter { it.isFile && it.extension == "xml" && it.normalize() !in expectedFiles }
            .forEach(File::delete)
        rendered.forEach { (path, content) ->
            outputDirectory.resolve(path).apply {
                parentFile.mkdirs()
                writeText(content)
            }
        }
        outputDirectory.walkBottomUp()
            .filter { it.isDirectory && it != outputDirectory }
            .forEach(File::delete)
        logger.lifecycle("Updated Souz coroutine baselines: {}", outputDirectory.relativeInvariantPath(repository))
    }
}

internal object DetektBaselines {
    fun renderByAnalysis(repository: File, baselines: Set<File>): Map<String, String> =
        baselines.asSequence()
            .filter(File::isFile)
            .groupBy { it.analysisBaselinePath(repository) }
            .toSortedMap()
            .mapValues { (_, files) ->
                files.asSequence()
                    .sortedBy(File::getPath)
                    .flatMap(::currentIssueIds)
                    .toSortedSet()
            }
            .filterValues(Set<String>::isNotEmpty)
            .mapValues { (_, issueIds) -> render(issueIds) }

    private fun File.analysisBaselinePath(repository: File): String {
        val path = relativeInvariantPath(repository)
        val marker = "/build/reports/detekt/baselines/"
        require(marker in path) { "Unexpected Detekt partial baseline path: $path" }
        val analysis = nameWithoutExtension.removePrefix("detektBaseline")
        require(analysis.isNotEmpty()) { "Unscoped Detekt baseline is not supported: $path" }
        return "${path.substringBefore(marker)}/${analysis.replaceFirstChar(Char::lowercase)}.xml"
    }

    private fun render(issueIds: Set<String>): String = buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        appendLine("<SmellBaseline>")
        appendLine("  <ManuallySuppressedIssues/>")
        appendLine("  <CurrentIssues>")
        issueIds.forEach { issueId -> appendLine("    <ID>${issueId.escapeXml()}</ID>") }
        appendLine("  </CurrentIssues>")
        appendLine("</SmellBaseline>")
    }

    private fun currentIssueIds(baseline: File): Sequence<String> {
        val currentIssues = parseSecureXml(baseline).getElementsByTagName("CurrentIssues")
        require(currentIssues.length == 1) { "Invalid Detekt baseline: $baseline" }
        val ids = (currentIssues.item(0) as Element).getElementsByTagName("ID")
        return (0 until ids.length).asSequence().map { index -> ids.item(index).textContent }
    }

    private fun String.escapeXml(): String = buildString(length) {
        this@escapeXml.forEach { character ->
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> character
                }
            )
        }
    }
}
