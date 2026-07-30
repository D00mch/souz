package ru.souz.agent

import ru.souz.llms.LLMToolSetup

data class AgentCoreTools(
    val getSkillByNameTool: LLMToolSetup,
    val getSkillsByCategoryTool: LLMToolSetup,
    val getSkillsNamesByCategoryTool: LLMToolSetup,
    val getKnowledgeTool: LLMToolSetup,
    val searchKnowledgeTool: LLMToolSetup,
    val searchMemoryTool: LLMToolSetup,
    val runtimeCommandTool: LLMToolSetup,
) {
    val classicGraphTools: List<LLMToolSetup> = listOf(
        getSkillByNameTool,
        getKnowledgeTool,
        searchKnowledgeTool,
        searchMemoryTool,
        runtimeCommandTool,
    )
    val skillsGraphTools: List<LLMToolSetup> = listOf(
        getSkillByNameTool,
        getSkillsByCategoryTool,
        getSkillsNamesByCategoryTool,
        getKnowledgeTool,
        searchKnowledgeTool,
        searchMemoryTool,
        runtimeCommandTool,
    )
    val alwaysInlineResultToolNames: Set<String> = setOf(
        getSkillByNameTool,
        getSkillsByCategoryTool,
        getSkillsNamesByCategoryTool,
        getKnowledgeTool,
        searchKnowledgeTool,
    ).mapTo(linkedSetOf()) { it.fn.name }
}
