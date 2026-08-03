package ru.souz.agent.skills.implementations.activation

import ru.souz.agent.skills.validation.SkillValidationFinding
import ru.souz.agent.skills.validation.SkillValidationInput
import ru.souz.agent.skills.validation.SkillValidationLevel
import ru.souz.agent.skills.validation.SkillValidator

class FakeSkillValidator private constructor(
    private val findingsFactory: () -> List<SkillValidationFinding>,
) : SkillValidator {
    var invocationCount: Int = 0
        private set

    override suspend fun validate(input: SkillValidationInput): List<SkillValidationFinding> {
        invocationCount += 1
        return findingsFactory()
    }

    companion object {
        fun approving(): FakeSkillValidator = FakeSkillValidator { emptyList() }

        fun rejecting(reason: String): FakeSkillValidator = FakeSkillValidator {
            listOf(
                SkillValidationFinding(
                    code = "llm.reject",
                    message = reason,
                    level = SkillValidationLevel.ERROR,
                    filePath = "SKILL.md",
                )
            )
        }
    }
}
