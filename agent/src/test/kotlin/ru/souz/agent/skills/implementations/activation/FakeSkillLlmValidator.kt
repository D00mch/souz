package ru.souz.agent.skills.implementations.activation

import ru.souz.agent.skills.validation.SkillLlmValidationDecision
import ru.souz.agent.skills.validation.SkillLlmValidationInput
import ru.souz.agent.skills.validation.SkillLlmValidationVerdict
import ru.souz.agent.skills.validation.SkillLlmValidator
import ru.souz.agent.skills.validation.SkillRiskLevel
import ru.souz.agent.skills.validation.SkillValidationFinding
import ru.souz.agent.skills.validation.SkillValidationSeverity

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