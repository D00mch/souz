package ru.souz.agent

import ru.souz.agent.state.AgentSettings
import ru.souz.llms.LLMToolSetup

class AgentCoreTools(
    getSkillByName: LLMToolSetup,
    getSkillsByCategory: LLMToolSetup,
    getSkillsNamesByCategory: LLMToolSetup,
    getKnowledge: LLMToolSetup,
    searchKnowledge: LLMToolSetup,
    searchMemory: LLMToolSetup,
    runtimeCommand: LLMToolSetup,
    private val spawnSubagent: ((AgentSettings) -> LLMToolSetup)? = null,
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

    fun graphTools(settings: AgentSettings): List<LLMToolSetup> =
        graphCoreTools + listOfNotNull(spawnSubagent?.invoke(settings))

    fun skillsTools(settings: AgentSettings): List<LLMToolSetup> =
        skillsCoreTools + listOfNotNull(spawnSubagent?.invoke(settings))
}
