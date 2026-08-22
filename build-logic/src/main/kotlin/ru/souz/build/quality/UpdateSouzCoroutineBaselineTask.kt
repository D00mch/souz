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
        val rendered = DetektBaselines.renderScoped(
            repository = repository,
            baselines = partialBaselines.files,
            excludedRuleIds = ADVISORY_DETEKT_RULES,
        )
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
            .forEach { directory -> directory.delete() }
        logger.lifecycle("Updated Souz coroutine baselines: {}", outputDirectory)
    }
}

internal object DetektBaselines {
    fun renderScoped(
        repository: File,
        baselines: Set<File>,
        excludedRuleIds: Set<String> = emptySet(),
    ): Map<String, String> = buildMap {
        currentIssuesByScope(repository, baselines, excludedRuleIds).forEach { (path, issueIds) ->
            put(path, render(issueIds))
        }
    }

    fun staleDiagnostics(
        repository: File,
        trackedBaselines: Set<File>,
        currentBaselines: Set<File>,
        excludedRuleIds: Set<String> = emptySet(),
    ): List<QualityDiagnostic> {
        val current = currentIssuesByScope(repository, currentBaselines, excludedRuleIds)
        return trackedBaselines.sortedBy { it.relativeInvariantPath(repository) }.flatMap { baseline ->
            val scope = trackedScope(repository, baseline)
            val trackedIssues = filteredIssueIds(baseline, excludedRuleIds)
            val currentIssues = current[scope]
                ?: return@flatMap listOf(
                    QualityDiagnostic(
                        path = baseline.relativeInvariantPath(repository),
                        line = null,
                        message = "Remove the baseline for an unavailable Detekt analysis task.",
                    )
                )

            (trackedIssues - currentIssues).map { issueId ->
                QualityDiagnostic(
                    path = baseline.relativeInvariantPath(repository),
                    line = baselineLine(baseline, issueId),
                    message = "Remove stale ${issueId.ruleId()} baseline entry for ${issueId.fileName()}.",
                )
            }
        }
    }

    private fun currentIssuesByScope(
        repository: File,
        baselines: Set<File>,
        excludedRuleIds: Set<String>,
    ): Map<String, Set<String>> = buildMap {
        baselines.sortedBy { it.relativeInvariantPath(repository) }.forEach { baseline ->
            val path = scopedPath(repository, baseline)
            check(path !in this) { "Duplicate Detekt baseline scope: $path" }
            put(path, filteredIssueIds(baseline, excludedRuleIds))
        }
    }

    private fun scopedPath(repository: File, baseline: File): String {
        val path = baseline.relativeInvariantPath(repository)
        val marker = "/build/reports/detekt/baselines/"
        require(marker in path) { "Unexpected Detekt partial baseline path: $path" }
        return path.substringBefore(marker) + "/" + path.substringAfter(marker)
    }

    private fun trackedScope(repository: File, baseline: File): String {
        val path = baseline.relativeInvariantPath(repository)
        val marker = "quality/detekt-baselines/"
        require(path.startsWith(marker)) { "Unexpected tracked Detekt baseline path: $path" }
        return path.removePrefix(marker)
    }

    private fun filteredIssueIds(baseline: File, excludedRuleIds: Set<String>): Set<String> =
        currentIssueIds(baseline)
            .filterNot { issueId -> excludedRuleIds.any { ruleId -> issueId.startsWith("$ruleId:") } }
            .toSortedSet()

    private fun baselineLine(baseline: File, issueId: String): Int? {
        val entry = "<ID>${issueId.escapeXml()}</ID>"
        val index = baseline.readLines().indexOfFirst { entry in it }
        return index.takeIf { it >= 0 }?.plus(1)
    }

    private fun String.ruleId(): String = substringBefore(':')

    private fun String.fileName(): String = substringAfter(':').substringBefore(':')

    private fun render(issueIds: Set<String>): String = buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        appendLine("<SmellBaseline>")
        appendLine("  <ManuallySuppressedIssues/>")
        if (issueIds.isEmpty()) {
            appendLine("  <CurrentIssues/>")
        } else {
            appendLine("  <CurrentIssues>")
            issueIds.forEach { issueId ->
                appendLine("    <ID>${issueId.escapeXml()}</ID>")
            }
            appendLine("  </CurrentIssues>")
        }
        appendLine("</SmellBaseline>")
    }

    private fun currentIssueIds(baseline: File): Sequence<String> {
        if (!baseline.isFile) return emptySequence()
        val document = parseSecureXml(baseline)
        val currentIssues = document.getElementsByTagName("CurrentIssues")
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
