package ru.souz.agent.skills

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.streams.asSequence

class SkillBundleLoader {

    fun loadDirectory(
        skillId: SkillId,
        rootDirectory: Path,
    ): SkillBundle {
        if (!rootDirectory.isDirectory()) {
            throw SkillBundleException("Skill root is not a directory: $rootDirectory")
        }

        val files = Files.walk(rootDirectory).use { stream ->
            stream.asSequence()
                .filter { path -> Files.isRegularFile(path) }
                .map { path ->
                    val relativePath = rootDirectory.relativize(path).toString()
                    SkillFile(
                        normalizedPath = SkillPathNormalizer.normalize(relativePath),
                        content = Files.readAllBytes(path),
                    )
                }
                .toList()
        }

        return SkillBundle.fromFiles(skillId = skillId, files = files)
    }
}

internal object SkillPathNormalizer {
    fun normalize(rawPath: String): String {
        val trimmed = rawPath.trim()
        if (trimmed.isEmpty()) throw SkillBundleException("Skill path must not be blank.")
        if (trimmed.startsWith("/") || trimmed.startsWith("\\") || Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(trimmed)) {
            throw SkillBundleException("Absolute skill paths are not allowed: $rawPath")
        }
        if (trimmed.contains('\\')) {
            throw SkillBundleException("Backslash separators are not allowed in skill paths: $rawPath")
        }

        val segments = trimmed.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) throw SkillBundleException("Skill path must not be empty: $rawPath")
        if (segments.any { it == "." || it == ".." }) {
            throw SkillBundleException("Path traversal is not allowed: $rawPath")
        }
        if (segments.any { it.contains('\u0000') }) {
            throw SkillBundleException("Null bytes are not allowed in skill paths: $rawPath")
        }

        return segments.joinToString("/")
    }
}

internal object SkillFrontmatterParser {

    fun parse(markdown: String): ParsedSkillMarkdown {
        val normalized = markdown.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) {
            throw SkillBundleException("SKILL.md must start with YAML frontmatter.")
        }

        val secondDelimiterIndex = normalized.indexOf("\n---\n", startIndex = 4)
        if (secondDelimiterIndex < 0) {
            throw SkillBundleException("SKILL.md is missing a closing YAML frontmatter delimiter.")
        }

        val frontmatter = normalized.substring(4, secondDelimiterIndex).trim()
        val body = normalized.substring(secondDelimiterIndex + "\n---\n".length).trim()
        val parsedMap = parseYamlLikeMap(frontmatter)

        val name = parsedMap["name"]?.takeIf { it.isNotBlank() }
            ?: throw SkillBundleException("SKILL.md frontmatter is missing required field: name")
        val description = parsedMap["description"]?.takeIf { it.isNotBlank() }
            ?: throw SkillBundleException("SKILL.md frontmatter is missing required field: description")

        val metadata = parseMetadata(frontmatter)
        return ParsedSkillMarkdown(
            manifest = SkillManifest(
                name = name,
                description = description,
                author = parsedMap["author"]?.takeIf { it.isNotBlank() },
                version = parsedMap["version"]?.takeIf { it.isNotBlank() },
                metadata = metadata,
                rawFrontmatter = frontmatter,
            ),
            body = body,
        )
    }

    private fun parseYamlLikeMap(frontmatter: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        frontmatter.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            if (line.startsWith(" ") || line.startsWith("\t")) return@forEach
            val separatorIndex = trimmed.indexOf(':')
            if (separatorIndex <= 0) return@forEach
            val key = trimmed.substring(0, separatorIndex).trim()
            val value = trimmed.substring(separatorIndex + 1).trim().trim('"', '\'')
            result[key] = value
        }
        return result
    }

    private fun parseMetadata(frontmatter: String): Map<String, String> {
        val lines = frontmatter.lines()
        val startIndex = lines.indexOfFirst { it.trim() == "metadata:" }
        if (startIndex < 0) return emptyMap()

        val metadata = linkedMapOf<String, String>()
        for (lineIndex in startIndex + 1 until lines.size) {
            val line = lines[lineIndex]
            if (!line.startsWith("  ")) break
            val trimmed = line.trim()
            val separatorIndex = trimmed.indexOf(':')
            if (separatorIndex <= 0) continue
            val key = trimmed.substring(0, separatorIndex).trim()
            val value = trimmed.substring(separatorIndex + 1).trim().trim('"', '\'')
            metadata[key] = value
        }
        return metadata
    }

    data class ParsedSkillMarkdown(
        val manifest: SkillManifest,
        val body: String,
    )
}
