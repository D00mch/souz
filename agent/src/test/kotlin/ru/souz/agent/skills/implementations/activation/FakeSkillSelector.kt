package ru.souz.agent.skills.implementations.activation

import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.selection.SkillSelectionInput
import ru.souz.agent.skills.selection.SkillSelectionResult
import ru.souz.agent.skills.selection.SkillSelector

class FakeSkillSelector(
    private val selectedSkillIds: List<SkillId>,
) : SkillSelector {
    override suspend fun select(input: SkillSelectionInput): SkillSelectionResult = SkillSelectionResult(
        selectedSkillIds = selectedSkillIds,
        rationale = if (selectedSkillIds.isEmpty()) "No skill needed." else "Selected by test.",
    )
}

