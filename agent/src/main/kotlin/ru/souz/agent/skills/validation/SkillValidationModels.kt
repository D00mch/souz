package ru.souz.agent.skills.validation

import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.SkillManifest
import java.time.Instant

enum class SkillValidationStatus {
    APPROVED,
    REJECTED,
    STALE,
}

enum class SkillValidationSeverity {
    INFO,
    WARNING,
    ERROR,
}

data class SkillValidationFinding(
    val code: String,
    val message: String,
    val severity: SkillValidationSeverity,
    val filePath: String? = null,
)

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
            minApprovalConfidence = 0.80,
            maxFileBytes = 128 * 1024,
            maxBundleBytes = 512 * 1024,
            excerptCharsPerFile = 2_000,
        )
    }
}

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

data class SkillValidationResult(
    val findings: List<SkillValidationFinding>,
) {
    val hasHardReject: Boolean = findings.any { it.severity == SkillValidationSeverity.ERROR }
}

enum class SkillLlmValidationDecision {
    APPROVE,
    REJECT,
}

enum class SkillRiskLevel {
    LOW,
    MEDIUM,
    HIGH,
}

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

fun interface SkillLlmValidator {
    suspend fun validate(input: SkillLlmValidationInput): SkillLlmValidationVerdict
}
