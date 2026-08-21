package ru.souz.build.quality

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.w3c.dom.Element
import java.io.File

@DisableCachingByDefault(because = "This explicit maintenance task rewrites the tracked Detekt baseline.")
abstract class UpdateSouzCoroutineBaselineTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val partialBaselines: ConfigurableFileCollection

    @get:OutputFile
    abstract val baseline: RegularFileProperty

    @TaskAction
    fun update() {
        val output = baseline.get().asFile
        output.parentFile.mkdirs()
        output.writeText(DetektBaselines.merge(partialBaselines.files, ADVISORY_DETEKT_RULES))
        logger.lifecycle("Updated Souz coroutine baseline: {}", output)
    }
}

internal object DetektBaselines {
    fun merge(baselines: Set<File>, excludedRuleIds: Set<String> = emptySet()): String {
        val issueIds = baselines
            .asSequence()
            .sortedBy(File::getPath)
            .flatMap(::currentIssueIds)
            .filterNot { issueId -> excludedRuleIds.any { ruleId -> issueId.startsWith("$ruleId:") } }
            .toSortedSet()

        return buildString {
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
