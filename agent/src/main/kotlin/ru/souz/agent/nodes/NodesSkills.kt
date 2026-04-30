package ru.souz.agent.nodes

import ru.souz.agent.graph.Node
import ru.souz.agent.skill.AgentActivatedSkill
import ru.souz.agent.skill.AgentSkillActivationKind
import ru.souz.agent.skill.AgentTurnState
import ru.souz.agent.spi.AgentDesktopInfoRepository
import ru.souz.agent.state.AgentContext

internal class NodesSkills(
    private val desktopInfoRepository: AgentDesktopInfoRepository,
) {
    private companion object {
        val SLASH_SKILL_REGEX = Regex("""^/([\p{L}\p{N}_-]+)(?:\s+(.*))?$""")
        const val HIGH_CONFIDENCE_ACTIVATION_SCORE = 0.8f
    }

    fun resolve(name: String = "Resolve skills"): Node<String, String> = Node(name) { ctx ->
        val originalInput = ctx.turnState.originalInput ?: ctx.input.trim()
        if (originalInput.isBlank()) {
            return@Node ctx.map(turnState = AgentTurnState(originalInput = originalInput)) { originalInput }
        }

        val slashMatch = SLASH_SKILL_REGEX.matchEntire(originalInput)
        if (slashMatch != null) {
            val requestedName = slashMatch.groupValues[1]
            val remainingInput = slashMatch.groupValues.getOrNull(2)?.trim().orEmpty()
            val resolvedSkill = desktopInfoRepository.loadSkill(requestedName)
                ?.takeIf { it.summary.userInvocable }
            if (resolvedSkill != null) {
                val activatedSkill = AgentActivatedSkill(
                    skill = resolvedSkill,
                    activationKind = AgentSkillActivationKind.EXPLICIT_SLASH,
                    requestedName = requestedName,
                    remainingInput = remainingInput,
                )
                return@Node ctx.map(
                    turnState = AgentTurnState(
                        originalInput = originalInput,
                        relevantSkills = emptyList(),
                        activeSkill = activatedSkill,
                    )
                ) { remainingInput }
            }
        }

        val relevantSkills = desktopInfoRepository.searchSkills(originalInput)
        val activatedSkill = relevantSkills.firstOrNull()
            ?.takeIf { it.score >= HIGH_CONFIDENCE_ACTIVATION_SCORE && !it.summary.disableModelInvocation }
            ?.let { match ->
                desktopInfoRepository.loadSkill(match.summary.name)?.let { skill ->
                    AgentActivatedSkill(
                        skill = skill,
                        activationKind = AgentSkillActivationKind.HIGH_CONFIDENCE_RETRIEVAL,
                        requestedName = match.summary.name,
                        remainingInput = originalInput,
                    )
                }
            }

        ctx.map(
            turnState = AgentTurnState(
                originalInput = originalInput,
                relevantSkills = relevantSkills,
                activeSkill = activatedSkill,
            )
        ) { originalInput }
    }
}
