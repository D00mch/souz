package ru.souz.agent.skills.bundle

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

object SkillBundleParser {

    private val yamlMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class RawManifest(
        val name: String? = null,
        val description: String? = null,
        val author: String? = null,
        val version: String? = null,
        val oauthProvider: String? = null,
        val oauthScopes: List<String>? = null,
        val metadata: Map<String, String>? = null,
    )

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
        val raw = try {
            yamlMapper.readValue(frontmatter, RawManifest::class.java) ?: RawManifest()
        } catch (e: MismatchedInputException) {
            throw SkillBundleException("SKILL.md frontmatter field '${e.path.lastOrNull()?.fieldName}' has the wrong shape: ${e.originalMessage}", e)
        } catch (e: Exception) {
            throw SkillBundleException("SKILL.md frontmatter is not valid YAML: ${e.message}", e)
        }

        val name = raw.name?.takeIf { it.isNotBlank() }
            ?: throw SkillBundleException("SKILL.md frontmatter is missing required field: name")
        val description = raw.description?.takeIf { it.isNotBlank() }
            ?: throw SkillBundleException("SKILL.md frontmatter is missing required field: description")

        return SkillManifest(
            name = name,
            description = description,
            author = raw.author?.takeIf { it.isNotBlank() },
            version = raw.version?.takeIf { it.isNotBlank() },
            oauthProvider = raw.oauthProvider?.takeIf { it.isNotBlank() },
            oauthScopes = raw.oauthScopes.orEmpty(),
            metadata = raw.metadata.orEmpty(),
            rawFrontmatter = frontmatter,
        )
    }

    data class ParsedSkillMarkdown(
        val manifest: SkillManifest,
        val body: String,
    )
}
