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
            oauthProvider = parsedMap["oauthProvider"]?.takeIf { it.isNotBlank() },
            oauthScopes = parseScopesList(frontmatter, "oauthScopes"),
            metadata = metadata,
            rawFrontmatter = frontmatter,
        )
    }

    /**
     * Accepts both YAML list shapes: inline (`oauthScopes: [a, b]`) and block (`oauthScopes:`
     * followed by indented `- item` lines, with blank lines/comments tolerated before the first
     * item). Unlike a plain "doesn't match, so empty" fallback, a key present but in neither shape
     * is a manifest authoring mistake and must fail loudly — a skill silently ending up with zero
     * declared scopes would fail closed on OAuth calls, hiding the real problem instead of
     * surfacing it.
     */
    private fun parseScopesList(frontmatter: String, key: String): List<String> {
        val lines = frontmatter.lines()
        val keyLineIndex = lines.indexOfFirst { line ->
            !line.startsWith(" ") && !line.startsWith("\t") && line.trim().startsWith("$key:")
        }
        if (keyLineIndex < 0) return emptyList()

        val inlineValue = stripTrailingComment(lines[keyLineIndex]).trim().removePrefix("$key:").trim()
        if (inlineValue.isNotEmpty()) {
            return parseInlineScopeList(key, inlineValue)
        }

        val values = mutableListOf<String>()
        var lineIndex = keyLineIndex + 1
        while (lineIndex < lines.size) {
            val line = lines[lineIndex]
            val trimmed = stripTrailingComment(line).trim()
            when {
                // A line whose only content (once any trailing comment is stripped) is blank —
                // including a comment-only line — carries nothing to nest under the key.
                trimmed.isEmpty() -> lineIndex++
                // Any positive indentation nests under the key — YAML doesn't mandate a specific
                // width, and hardcoding one here (as this used to) meant a validly-indented single-
                // space list like 'oauthScopes:\n - a' silently parsed as zero items instead of
                // either accepting or rejecting it.
                !line.startsWith(" ") && !line.startsWith("\t") -> return values
                trimmed.startsWith("- ") -> {
                    values += trimmed.removePrefix("-").trim().trim('"', '\'')
                    lineIndex++
                }
                else -> throw SkillBundleException(
                    "SKILL.md frontmatter field '$key' has a malformed list item: '$trimmed'"
                )
            }
        }
        return values
    }

    private fun parseInlineScopeList(key: String, inlineValue: String): List<String> {
        // A bare or explicit-null value (`key:` with nothing after it, or `key: null`/`key: ~`) is
        // valid YAML for "no value" — treated the same as declaring no scopes at all, not an error.
        if (inlineValue == "null" || inlineValue == "~") return emptyList()
        if (!inlineValue.startsWith("[") || !inlineValue.endsWith("]")) {
            throw SkillBundleException(
                "SKILL.md frontmatter field '$key' must be a YAML list — either '$key: [a, b]' or a " +
                    "'- item' block — got: '$inlineValue'"
            )
        }
        val inner = inlineValue.removeSurrounding("[", "]").trim()
        if (inner.isEmpty()) return emptyList()
        return inner.split(",").map { it.trim().trim('"', '\'') }
    }

    /**
     * Cuts a line at its first unquoted `#` that starts a YAML comment (preceded by whitespace, or
     * at the very start of the line) — mirroring the YAML rule that `#` only begins a comment
     * outside of quotes and after whitespace, not mid-token (e.g. `a#b` stays a single value). Without
     * this, a trailing `# ...` comment on an `oauthScopes` line or list item silently became part of
     * the scope string itself, which the OAuth provider client then joins into its `scope`
     * parameter, commonly producing an `invalid_scope` error.
     */
    private fun stripTrailingComment(line: String): String {
        var inSingleQuote = false
        var inDoubleQuote = false
        for (index in line.indices) {
            val c = line[index]
            when {
                c == '\'' && !inDoubleQuote -> inSingleQuote = !inSingleQuote
                c == '"' && !inSingleQuote -> inDoubleQuote = !inDoubleQuote
                c == '#' && !inSingleQuote && !inDoubleQuote &&
                    (index == 0 || line[index - 1].isWhitespace()) -> return line.substring(0, index)
            }
        }
        return line
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
