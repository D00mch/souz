package ru.souz.agent.skills.selection

import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.registry.StoredSkill

data class SkillSelectionInput(
    val userMessage: String,
    val recentConversation: List<SkillSelectionMessage> = emptyList(),
    val availableSkills: List<StoredSkill>,
)

data class SkillSelectionResult(
    val selectedSkillIds: List<SkillId>,
    val rationale: String,
)

data class SkillSelectionMessage(
    val role: String,
    val content: String,
)
