package ru.souz.agent

enum class AgentId(
    val storageValue: String,
    val supportsActiveRunInput: Boolean,
) {
    GRAPH("graph", supportsActiveRunInput = false),
    SKILLS_GRAPH("skills", supportsActiveRunInput = true),
    ;

    companion object {
        val default: AgentId = GRAPH

        fun fromStorageValue(raw: String?): AgentId {
            if (raw.isNullOrBlank()) return default
            return entries.firstOrNull {
                it.storageValue.equals(raw, ignoreCase = true) ||
                    it.name.equals(raw, ignoreCase = true)
            } ?: default
        }
    }
}
