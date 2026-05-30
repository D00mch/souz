package ru.souz.agent.skills.selection

import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.registry.StoredSkill

data class SkillSelectionInput(
    val userMessage: String,
    val availableSkills: List<StoredSkill>,
)

data class SkillSelectionResult(
    val selectedSkillIds: List<SkillId>,
    val rationale: String,
)
