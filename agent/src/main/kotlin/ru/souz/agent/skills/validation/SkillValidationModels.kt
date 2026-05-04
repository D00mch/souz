package ru.souz.agent.skills.validation

import ru.souz.agent.skills.bundle.SkillManifest
import ru.souz.agent.skills.activation.SkillId
import java.time.Instant

/**
 * Final lifecycle state stored for a skill validation run.
 */
enum class SkillValidationStatus {
    /** The bundle passed validation under the active policy and can be used. */
    APPROVED,

    /** The bundle failed validation and must not be selected or executed. */
    REJECTED,

    /** A previously stored approval no longer matches the current bundle or policy. */
    STALE,
}

/**
 * Severity assigned to a validation finding.
 */
enum class SkillValidationSeverity {
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
    val severity: SkillValidationSeverity,
    val filePath: String? = null,
)

/**
 * Limits and thresholds that define the active validation contract.
 *
 * Together with [validatorVersion], [policyVersion] forms the cache key for persisted validation
 * records so approvals can be invalidated when the rules change.
 */
data class SkillValidationPolicy(
    val policyVersion: String,
    val validatorVersion: String,
    val minApprovalConfidence: Double,
    val maxFileBytes: Int,
    val maxBundleBytes: Int,
    val excerptCharsPerFile: Int,
) {
    companion object {
        fun default(): SkillValidationPolicy = SkillValidationPolicy(
            policyVersion = "skills-policy/v1",
            validatorVersion = "skills-validator/v1",
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
    val status: SkillValidationStatus,
    val policyVersion: String,
    val validatorVersion: String,
    val model: String? = null,
    val reasons: List<String> = emptyList(),
    val findings: List<SkillValidationFinding> = emptyList(),
    val createdAt: Instant,
)

/**
 * Combined non-LLM validation output aggregated before the final approval decision.
 */
data class SkillValidationResult(
    val findings: List<SkillValidationFinding>,
) {
    val hasHardReject: Boolean = findings.any { it.severity == SkillValidationSeverity.ERROR }
}

/**
 * Binary LLM verdict produced after structural and static checks.
 */
enum class SkillLlmValidationDecision {
    APPROVE,
    REJECT,
}

/**
 * Coarse risk bucket used to communicate the model's overall concern level.
 */
enum class SkillRiskLevel {
    LOW,
    MEDIUM,
    HIGH,
}

/**
 * Sanitized bundle context passed to the LLM validator.
 *
 * The input contains the manifest, selected markdown, bounded supporting excerpts, and findings
 * from deterministic validators so the model reasons over a constrained view of the bundle.
 */
data class SkillLlmValidationInput(
    val userId: String,
    val skillId: SkillId,
    val bundleHash: String,
    val policy: SkillValidationPolicy,
    val manifest: SkillManifest,
    val filePaths: List<String>,
    val skillMarkdown: String,
    val supportingFileExcerpts: Map<String, String>,
    val structuralFindings: List<SkillValidationFinding>,
    val staticFindings: List<SkillValidationFinding>,
)

/**
 * Structured LLM response used to persist and explain the approval decision.
 */
data class SkillLlmValidationVerdict(
    val decision: SkillLlmValidationDecision,
    val confidence: Double,
    val riskLevel: SkillRiskLevel,
    val reasons: List<String>,
    val requestedCapabilities: List<String>,
    val suspiciousFiles: List<String>,
    val findings: List<SkillValidationFinding>,
    val model: String? = null,
)

/**
 * Replaceable LLM-backed validator implementation.
 */
fun interface SkillLlmValidator {
    suspend fun validate(input: SkillLlmValidationInput): SkillLlmValidationVerdict
}
