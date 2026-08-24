package ru.souz.agent

import ru.souz.llms.LLMToolSetup

internal class AgentCoreTools(
    getSkillByName: LLMToolSetup,
    getSkillsByCategory: LLMToolSetup,
    getSkillsNamesByCategory: LLMToolSetup,
    getKnowledge: LLMToolSetup,
    searchKnowledge: LLMToolSetup,
    searchMemory: LLMToolSetup,
    runtimeCommand: LLMToolSetup,
) {
    val graphAlwaysInlineResultTools: List<LLMToolSetup> = listOf(
        getSkillByName,
        getKnowledge,
        searchKnowledge,
    )
    val graphCoreTools: List<LLMToolSetup> = graphAlwaysInlineResultTools + searchMemory + runtimeCommand

    val skillsAlwaysInlineResultTools: List<LLMToolSetup> = listOf(
        getSkillByName,
        getSkillsByCategory,
        getSkillsNamesByCategory,
        getKnowledge,
        searchKnowledge,
    )
    val skillsCoreTools: List<LLMToolSetup> = skillsAlwaysInlineResultTools + searchMemory + runtimeCommand
}
