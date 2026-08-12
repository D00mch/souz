package ru.souz.agent.skills.validation

import java.time.Instant
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SKILL_MD_PATH
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillManifest

/**
 * Severity assigned to a validation finding.
 */
enum class SkillValidationLevel {
    /** Informational note that does not affect approval. */
    INFO,

    /** Suspicious or non-ideal condition that should be surfaced to the user. */
    WARNING,

    /** Hard failure that should reject the bundle. */
    ERROR,
}

/**
 * Individual issue or note produced by structural, static, or LLM validation.
 */
data class SkillValidationFinding(
    val code: String,
    val message: String,
    val level: SkillValidationLevel,
    val filePath: String? = null,
)

/**
 * Limits and thresholds that define the active validation contract.
 *
 * Changing validation rules requires a new [policyVersion] so cached approvals are not reused.
 */
data class SkillValidationPolicy(
    val policyVersion: String,
    val minApprovalConfidence: Double,
    val maxFileBytes: Int,
    val maxBundleBytes: Int,
    val excerptCharsPerFile: Int,
) {
    companion object {
        fun default(): SkillValidationPolicy = SkillValidationPolicy(
            policyVersion = "skills-policy/v1",
            minApprovalConfidence = 0.66,
            maxFileBytes = 128 * 1024,
            maxBundleBytes = 512 * 1024,
            excerptCharsPerFile = 2_000,
        )
    }
}

/**
 * Persisted validation snapshot for a specific user-visible skill bundle hash.
 */
data class SkillValidationRecord(
    val userId: String,
    val skillId: SkillId,
    val bundleHash: String,
    val policyVersion: String,
    val approved: Boolean,
    val findings: List<SkillValidationFinding> = emptyList(),
    val createdAt: Instant,
)

/**
 * Input shared by all Skill validators.
 */
data class SkillValidationInput(
    val userId: String,
    val skillId: SkillId,
    val bundleHash: String,
    val policy: SkillValidationPolicy,
    val bundle: SkillBundle,
    val previousFindings: List<SkillValidationFinding> = emptyList(),
) {
    val manifest: SkillManifest get() = bundle.manifest
    val filePaths: List<String> get() = bundle.files.map { it.normalizedPath }
    val skillMarkdown: String get() = bundle.skillMarkdownFile.contentAsText()
    val supportingFileExcerpts: Map<String, String>
        get() = bundle.files
            .filterNot { it.normalizedPath == SKILL_MD_PATH }
            .associate { file ->
                file.normalizedPath to file.contentAsText().take(policy.excerptCharsPerFile)
            }
}

/**
 * Replaceable Skill validator. Returning an ERROR finding rejects the bundle.
 */
fun interface SkillValidator {
    suspend fun validate(input: SkillValidationInput): List<SkillValidationFinding>
}
