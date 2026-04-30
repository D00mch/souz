package ru.souz.agent.skill

enum class AgentSkillSource {
    WORKSPACE,
    MANAGED,
}

data class AgentSkillSummary(
    val name: String,
    val description: String,
    val whenToUse: String,
    val disableModelInvocation: Boolean = false,
    val userInvocable: Boolean = true,
    val allowedTools: Set<String> = emptySet(),
    val requiresBins: List<String> = emptyList(),
    val supportedOs: List<String> = emptyList(),
    val source: AgentSkillSource,
    val folderName: String,
)

data class AgentSkill(
    val summary: AgentSkillSummary,
    val body: String,
)

data class AgentSkillMatch(
    val summary: AgentSkillSummary,
    val score: Float,
)

enum class AgentSkillActivationKind {
    EXPLICIT_SLASH,
    HIGH_CONFIDENCE_RETRIEVAL,
}

data class AgentActivatedSkill(
    val skill: AgentSkill,
    val activationKind: AgentSkillActivationKind,
    val requestedName: String,
    val remainingInput: String,
)

data class AgentTurnState(
    val originalInput: String? = null,
    val relevantSkills: List<AgentSkillMatch> = emptyList(),
    val activeSkill: AgentActivatedSkill? = null,
)

fun AgentSkillSummary.normalizedName(): String = name.trim().lowercase()

fun AgentSkillSummary.matchesName(candidate: String): Boolean {
    val normalizedCandidate = candidate.trim().removePrefix("/").lowercase()
    if (normalizedCandidate.isBlank()) return false
    return normalizedCandidate == normalizedName() || normalizedCandidate == folderName.trim().lowercase()
}

fun AgentSkillSummary.requiresRunBashCommand(): Boolean {
    if (requiresBins.isNotEmpty()) return true
    return allowedTools.any { toolName ->
        toolName.equals("bash", ignoreCase = true) || toolName.equals("RunBashCommand", ignoreCase = true)
    }
}

fun AgentActivatedSkill.requiredToolNames(): Set<String> =
    buildSet {
        if (skill.summary.requiresRunBashCommand()) {
            add("RunBashCommand")
        }
    }
