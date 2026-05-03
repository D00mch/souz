package ru.souz.agent.skills.selection

fun interface SkillSelector {
    suspend fun select(input: SkillSelectionInput): SkillSelectionResult
}
