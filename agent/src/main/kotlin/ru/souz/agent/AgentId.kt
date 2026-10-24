package ru.souz.agent

enum class AgentId(val storageValue: String) {
    // Retained as an unsupported persisted identifier, normalized to SKILLS_GRAPH by the kernel.
    GRAPH("graph"),
    SKILLS_GRAPH("skills"),
    ;

    companion object {
        val default: AgentId = SKILLS_GRAPH

        fun fromStorageValue(raw: String?): AgentId {
            if (raw.isNullOrBlank()) return default
            return entries.firstOrNull {
                it.storageValue.equals(raw, ignoreCase = true) ||
                    it.name.equals(raw, ignoreCase = true)
            } ?: default
        }
    }
}
