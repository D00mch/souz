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
    private val subagentTools: ((AgentSettings) -> List<LLMToolSetup>)? = null,
    forgetMemory: List<LLMToolSetup> = emptyList(),
) {
    val graphAlwaysInlineResultTools: List<LLMToolSetup> = listOf(
        getSkillByName,
        getKnowledge,
        searchKnowledge,
    )
    val graphCoreTools: List<LLMToolSetup> = graphAlwaysInlineResultTools + searchMemory + forgetMemory + runtimeCommand

    val skillsAlwaysInlineResultTools: List<LLMToolSetup> = listOf(
        getSkillByName,
        getSkillsByCategory,
        getSkillsNamesByCategory,
        getKnowledge,
        searchKnowledge,
    )
    val skillsCoreTools: List<LLMToolSetup> = skillsAlwaysInlineResultTools + searchMemory + forgetMemory + runtimeCommand

    fun graphTools(settings: AgentSettings): List<LLMToolSetup> =
        graphCoreTools + subagentTools?.invoke(settings).orEmpty()

    fun skillsTools(settings: AgentSettings): List<LLMToolSetup> =
        skillsCoreTools + subagentTools?.invoke(settings).orEmpty()
}
