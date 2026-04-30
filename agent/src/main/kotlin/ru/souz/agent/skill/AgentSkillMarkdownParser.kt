package ru.souz.agent.skill

object AgentSkillMarkdownParser {
    fun parseSummary(
        markdown: String,
        fallbackName: String,
        source: AgentSkillSource,
    ): AgentSkillSummary {
        val parsed = extractFrontmatter(markdown)
        val name = parsed.stringValue("name")?.ifBlank { null } ?: fallbackName
        val description = parsed.stringValue("description").orEmpty()
        val whenToUse = parsed.stringValue("when_to_use").orEmpty()
        return AgentSkillSummary(
            name = name,
            description = description,
            whenToUse = whenToUse,
            disableModelInvocation = parsed.booleanValue("disable-model-invocation") ?: false,
            userInvocable = parsed.booleanValue("user-invocable") ?: true,
            allowedTools = parsed.stringList("allowed-tools").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            requiresBins = parsed.stringList("metadata", "openclaw", "requires", "bins"),
            supportedOs = parsed.stringList("metadata", "openclaw", "os"),
            source = source,
            folderName = fallbackName,
        )
    }

    fun parseSkill(
        markdown: String,
        fallbackName: String,
        source: AgentSkillSource,
    ): AgentSkill {
        val extracted = extractFrontmatter(markdown)
        return AgentSkill(
            summary = parseSummary(markdown, fallbackName, source),
            body = extracted.body.trim(),
        )
    }

    private fun extractFrontmatter(markdown: String): ExtractedFrontmatter {
        val normalized = markdown.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) {
            return ExtractedFrontmatter(emptyMap(), normalized)
        }

        val endMarker = normalized.indexOf("\n---\n", startIndex = 4)
            .takeIf { it >= 0 }
            ?: normalized.indexOf("\n...\n", startIndex = 4).takeIf { it >= 0 }
            ?: return ExtractedFrontmatter(emptyMap(), normalized)

        val frontmatter = normalized.substring(4, endMarker)
        val body = normalized.substring(endMarker + 5)
        return ExtractedFrontmatter(
            values = FrontmatterSubsetParser.parse(frontmatter),
            body = body,
        )
    }

    private data class ExtractedFrontmatter(
        val values: Map<List<String>, Any>,
        val body: String,
    ) {
        fun stringValue(vararg path: String): String? = values[path.asList()] as? String

        fun booleanValue(vararg path: String): Boolean? = values[path.asList()] as? Boolean

        @Suppress("UNCHECKED_CAST")
        fun stringList(vararg path: String): List<String> = when (val value = values[path.asList()]) {
            is List<*> -> value.filterIsInstance<String>()
            is String -> listOf(value)
            else -> emptyList()
        }
    }
}

private object FrontmatterSubsetParser {
    fun parse(frontmatter: String): Map<List<String>, Any> {
        val values = linkedMapOf<List<String>, Any>()
        val stack = ArrayDeque<Context>().apply { addLast(Context(indent = -1, path = emptyList())) }

        frontmatter.lineSequence().forEach { rawLine ->
            if (rawLine.isBlank()) return@forEach
            val trimmed = rawLine.trim()
            if (trimmed.startsWith("#")) return@forEach

            val indent = rawLine.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            while (stack.size > 1 && indent <= stack.last().indent) {
                stack.removeLast()
            }

            if (trimmed.startsWith("- ")) {
                val value = parseScalar(trimmed.removePrefix("- ").trim())
                val path = stack.last().path
                if (path.isNotEmpty()) {
                    val current = values[path]
                    val updated = when (current) {
                        is List<*> -> current.filterIsInstance<String>() + (value as? String).orEmpty()
                        is String -> listOf(current, (value as? String).orEmpty())
                        else -> listOfNotNull(value as? String)
                    }
                    values[path] = updated
                }
                return@forEach
            }

            val separatorIndex = trimmed.indexOf(':')
            if (separatorIndex <= 0) return@forEach

            val key = trimmed.substring(0, separatorIndex).trim()
            val valuePart = trimmed.substring(separatorIndex + 1).trim()
            val path = stack.last().path + key
            if (valuePart.isEmpty()) {
                stack.addLast(Context(indent = indent, path = path))
            } else {
                values[path] = parseScalar(valuePart)
            }
        }

        return values
    }

    private fun parseScalar(raw: String): Any {
        if (raw.startsWith("[") && raw.endsWith("]")) {
            return raw.removePrefix("[").removeSuffix("]")
                .split(',')
                .mapNotNull { item -> item.trim().trim('"', '\'').takeIf { it.isNotEmpty() } }
        }
        return when (raw.lowercase()) {
            "true" -> true
            "false" -> false
            else -> raw.trim().trim('"', '\'')
        }
    }

    private data class Context(
        val indent: Int,
        val path: List<String>,
    )
}
