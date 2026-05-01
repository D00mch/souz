package ru.souz.agent.skills.validation

import ru.souz.agent.skills.activation.SkillId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillValidationReducerTest {
    @Test
    fun `reduce approves when validators are clean and confidence meets policy`() {
        val record = SkillValidationReducer.reduce(
            userId = "user-1",
            skillId = SkillId("skill-1"),
            bundleHash = "hash-1",
            policy = SkillValidationPolicy.default(),
            structural = SkillValidationResult(emptyList()),
            static = SkillValidationResult(emptyList()),
            llm = SkillLlmValidationVerdict(
                decision = SkillLlmValidationDecision.APPROVE,
                confidence = SkillValidationPolicy.default().minApprovalConfidence,
                riskLevel = SkillRiskLevel.LOW,
                reasons = listOf("Looks safe."),
                requestedCapabilities = listOf("summarization"),
                suspiciousFiles = emptyList(),
                findings = emptyList(),
                model = "fake-model",
            ),
            createdAt = Instant.EPOCH,
        )

        assertEquals(SkillValidationStatus.APPROVED, record.status)
        assertEquals(listOf("Looks safe."), record.reasons)
        assertTrue(record.findings.isEmpty())
    }

    @Test
    fun `reduce rejects and aggregates findings when any validator fails`() {
        val structuralFinding = SkillValidationFinding(
            code = "struct.invalid",
            message = "Invalid bundle structure.",
            severity = SkillValidationSeverity.ERROR,
            filePath = "SKILL.md",
        )
        val staticFinding = SkillValidationFinding(
            code = "static.suspicious",
            message = "Suspicious content.",
            severity = SkillValidationSeverity.WARNING,
            filePath = "script.sh",
        )
        val llmFinding = SkillValidationFinding(
            code = "llm.reject",
            message = "Unsafe intent.",
            severity = SkillValidationSeverity.ERROR,
            filePath = "SKILL.md",
        )

        val record = SkillValidationReducer.reduce(
            userId = "user-1",
            skillId = SkillId("skill-1"),
            bundleHash = "hash-1",
            policy = SkillValidationPolicy.default(),
            structural = SkillValidationResult(listOf(structuralFinding)),
            static = SkillValidationResult(listOf(staticFinding)),
            llm = SkillLlmValidationVerdict(
                decision = SkillLlmValidationDecision.REJECT,
                confidence = 0.99,
                riskLevel = SkillRiskLevel.HIGH,
                reasons = listOf("Unsafe."),
                requestedCapabilities = emptyList(),
                suspiciousFiles = listOf("SKILL.md"),
                findings = listOf(llmFinding),
                model = "fake-model",
            ),
            createdAt = Instant.EPOCH,
        )

        assertEquals(SkillValidationStatus.REJECTED, record.status)
        assertEquals(listOf(structuralFinding, staticFinding, llmFinding), record.findings)
        assertFalse(record.findings.isEmpty())
    }
}
