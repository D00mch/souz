package ru.souz.agent.skills

import java.time.Instant

@JvmInline
value class SkillId(val value: String) {
    init {
        require(value.isNotBlank()) { "SkillId must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * [frontmatter] is a block of YAML, TOML, or JSON metadata at the top of a file.
 */
data class SkillManifest(
    val name: String,
    val description: String,
    val author: String? = null,
    val version: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val rawFrontmatter: String,
)

data class SkillFile(
    val normalizedPath: String,
    val content: ByteArray,
) {
    fun contentAsText(): String = content.toString(Charsets.UTF_8)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SkillFile
        if (normalizedPath != other.normalizedPath) return false
        if (!content.contentEquals(other.content)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = normalizedPath.hashCode()
        result = 31 * result + content.contentHashCode()
        return result
    }
}

data class SkillBundle(
    val skillId: SkillId,
    val manifest: SkillManifest,
    val files: List<SkillFile>,
    val skillMarkdownBody: String,
) {
    val skillMarkdownFile: SkillFile
        get() = files.first { it.normalizedPath == SKILL_MD_PATH }

    companion object {
        fun fromFiles(
            skillId: SkillId,
            files: List<SkillFile>,
        ): SkillBundle {
            if (files.isEmpty()) throw SkillBundleException("Skill bundle is empty.")

            val normalizedFiles = files.map { file ->
                file.copy(normalizedPath = SkillPathNormalizer.normalize(file.normalizedPath))
            }
            val duplicatePath = normalizedFiles
                .groupingBy { it.normalizedPath }
                .eachCount()
                .entries
                .firstOrNull { it.value > 1 }
                ?.key
            if (duplicatePath != null) {
                throw SkillBundleException("Duplicate normalized path detected: $duplicatePath")
            }

            val skillFile = normalizedFiles.firstOrNull { it.normalizedPath == SKILL_MD_PATH }
                ?: throw SkillBundleException("Skill bundle is missing SKILL.md")

            val parsed = SkillFrontmatterParser.parse(skillFile.contentAsText())
            return SkillBundle(
                skillId = skillId,
                manifest = parsed.manifest,
                files = normalizedFiles.sortedBy { it.normalizedPath },
                skillMarkdownBody = parsed.body,
            )
        }
    }
}

data class StoredSkill(
    val userId: String,
    val skillId: SkillId,
    val manifest: SkillManifest,
    val bundleHash: String,
    val createdAt: Instant,
)

data class ActivatedSkill(
    val skillId: SkillId,
    val manifest: SkillManifest,
    val bundleHash: String,
    val instructionBody: String,
    val supportingFiles: List<String>,
)

class SkillBundleException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

internal const val SKILL_MD_PATH = "SKILL.md"
