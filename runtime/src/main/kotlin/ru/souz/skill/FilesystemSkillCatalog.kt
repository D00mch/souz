package ru.souz.skill

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.streams.asSequence
import ru.souz.agent.skill.AgentSkill
import ru.souz.agent.skill.AgentSkillMarkdownParser
import ru.souz.agent.skill.AgentSkillSource
import ru.souz.agent.skill.AgentSkillSummary
import ru.souz.agent.skill.matchesName

class FilesystemSkillCatalog(
    private val directories: SkillDirectories = SkillDirectories.default(),
) {
    fun listSkillSummaries(): List<AgentSkillSummary> =
        discoveredSkills()
            .map { it.summary }
            .sortedBy { it.name.lowercase() }

    fun loadSkill(name: String): AgentSkill? {
        val discovered = discoveredSkills().firstOrNull { it.summary.matchesName(name) } ?: return null
        return AgentSkillMarkdownParser.parseSkill(
            markdown = Files.readString(discovered.skillFile),
            fallbackName = discovered.folderName,
            source = discovered.summary.source,
        )
    }

    internal fun listSkillEntries(): List<SkillCatalogEntry> = discoveredSkills()

    private fun discoveredSkills(): List<SkillCatalogEntry> {
        val merged = linkedMapOf<String, SkillCatalogEntry>()
        scanRoot(directories.managedSkillsDir, AgentSkillSource.MANAGED).forEach { merged[it.summary.name.lowercase()] = it }
        scanRoot(directories.workspaceSkillsDir, AgentSkillSource.WORKSPACE).forEach { merged[it.summary.name.lowercase()] = it }
        return merged.values.toList()
    }

    private fun scanRoot(root: Path, source: AgentSkillSource): List<SkillCatalogEntry> {
        if (!Files.exists(root) || !root.isDirectory()) return emptyList()
        Files.list(root).use { children ->
            return children.asSequence()
                .filter { it.isDirectory() }
                .mapNotNull { directory ->
                    val skillFile = directory.resolve("SKILL.md")
                    if (!Files.isRegularFile(skillFile)) return@mapNotNull null
                    val folderName = directory.fileName?.toString().orEmpty().ifBlank { directory.name }
                    val summary = AgentSkillMarkdownParser.parseSummary(
                        markdown = readSummaryText(skillFile),
                        fallbackName = folderName,
                        source = source,
                    )
                    SkillCatalogEntry(
                        summary = summary,
                        folderName = folderName,
                        skillFile = skillFile,
                    )
                }
                .toList()
        }
    }

    private fun readSummaryText(path: Path): String {
        val markdown = Files.readString(path)
        val normalized = markdown.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return markdown
        val endMarker = normalized.indexOf("\n---\n", startIndex = 4)
            .takeIf { it >= 0 }
            ?: normalized.indexOf("\n...\n", startIndex = 4).takeIf { it >= 0 }
            ?: return markdown
        return normalized.substring(0, endMarker + 5)
    }
}

internal data class SkillCatalogEntry(
    val summary: AgentSkillSummary,
    val folderName: String,
    val skillFile: Path,
) {
    val embeddingText: String =
        listOf(summary.name, summary.description, summary.whenToUse)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(separator = "\n")

    val fingerprint: String =
        listOf(
            summary.name,
            summary.description,
            summary.whenToUse,
            summary.disableModelInvocation.toString(),
            summary.userInvocable.toString(),
            summary.allowedTools.sorted().joinToString(","),
            summary.requiresBins.joinToString(","),
            summary.supportedOs.joinToString(","),
            summary.source.name,
            summary.folderName,
        ).joinToString(separator = "\u001F")
}
