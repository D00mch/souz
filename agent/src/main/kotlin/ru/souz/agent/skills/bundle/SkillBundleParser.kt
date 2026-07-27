package ru.souz.agent.skills.bundle

object SkillBundleParser {

    fun parse(markdown: String): ParsedSkillMarkdown {
        val normalized = markdown.replace("\r\n", "\n")
        val secondDelimiterIndex = closingDelimiterIndex(normalized)
        val frontmatter = normalized.substring(4, secondDelimiterIndex).trim()
        val body = normalized.substring(secondDelimiterIndex + "\n---\n".length).trim()
        return ParsedSkillMarkdown(
            manifest = parseManifestFrontmatter(frontmatter),
            body = body,
        )
    }

    fun parseManifest(markdown: String): SkillManifest {
        val normalized = markdown.replace("\r\n", "\n")
        val secondDelimiterIndex = closingDelimiterIndex(normalized)
        val frontmatter = normalized.substring(4, secondDelimiterIndex).trim()
        return parseManifestFrontmatter(frontmatter)
    }

    private fun closingDelimiterIndex(normalized: String): Int {
        if (!normalized.startsWith("---\n")) {
            throw SkillBundleException("SKILL.md must start with YAML frontmatter.")
        }

        val secondDelimiterIndex = normalized.indexOf("\n---\n", startIndex = 4)
        if (secondDelimiterIndex < 0) {
            throw SkillBundleException("SKILL.md is missing a closing YAML frontmatter delimiter.")
        }
        return secondDelimiterIndex
    }

    private fun parseManifestFrontmatter(frontmatter: String): SkillManifest {
        val parsedMap = parseYamlLikeMap(frontmatter)

        val name = parsedMap["name"]?.takeIf { it.isNotBlank() }
            ?: throw SkillBundleException("SKILL.md frontmatter is missing required field: name")
        val description = parsedMap["description"]?.takeIf { it.isNotBlank() }
            ?: throw SkillBundleException("SKILL.md frontmatter is missing required field: description")

        val metadata = parseMetadata(frontmatter)
        return SkillManifest(
            name = name,
            description = description,
            author = parsedMap["author"]?.takeIf { it.isNotBlank() },
            version = parsedMap["version"]?.takeIf { it.isNotBlank() },
            metadata = metadata,
            rawFrontmatter = frontmatter,
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
