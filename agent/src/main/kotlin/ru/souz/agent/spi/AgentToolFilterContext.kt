package ru.souz.agent.spi

import ru.souz.agent.skill.AgentActivatedSkill

data class AgentToolFilterContext(
    val activeSkill: AgentActivatedSkill? = null,
)
