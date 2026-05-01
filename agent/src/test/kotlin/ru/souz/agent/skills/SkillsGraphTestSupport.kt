package ru.souz.agent.skills

import ru.souz.agent.skills.validation.*

class FakeSkillSelector(
    private val selectedSkillIds: List<SkillId>,
) : SkillSelector {
    override suspend fun select(input: SkillSelectionInput): SkillSelectionResult = SkillSelectionResult(
        selectedSkillIds = selectedSkillIds,
        rationale = if (selectedSkillIds.isEmpty()) "No skill needed." else "Selected by test.",
    )
}

class FakeSkillLlmValidator private constructor(
    private val verdictFactory: () -> SkillLlmValidationVerdict,
) : SkillLlmValidator {
    var invocationCount: Int = 0
        private set

    override suspend fun validate(input: SkillLlmValidationInput): SkillLlmValidationVerdict {
        invocationCount += 1
        return verdictFactory()
    }

    companion object {
        fun approving(): FakeSkillLlmValidator = FakeSkillLlmValidator {
            SkillLlmValidationVerdict(
                decision = SkillLlmValidationDecision.APPROVE,
                confidence = 0.96,
                riskLevel = SkillRiskLevel.LOW,
                reasons = listOf("Benign test fixture"),
                requestedCapabilities = listOf("paper summarization"),
                suspiciousFiles = emptyList(),
                findings = emptyList(),
                model = "fake",
            )
        }

        fun rejecting(reason: String): FakeSkillLlmValidator = FakeSkillLlmValidator {
            SkillLlmValidationVerdict(
                decision = SkillLlmValidationDecision.REJECT,
                confidence = 0.99,
                riskLevel = SkillRiskLevel.HIGH,
                reasons = listOf(reason),
                requestedCapabilities = listOf("unknown"),
                suspiciousFiles = listOf("SKILL.md"),
                findings = listOf(
                    SkillValidationFinding(
                        code = "llm.reject",
                        message = reason,
                        severity = SkillValidationSeverity.ERROR,
                        filePath = "SKILL.md",
                    )
                ),
                model = "fake",
            )
        }
    }
}
