package ru.souz.agent

enum class AgentId(val storageValue: String) {
    GRAPH("graph"),
    LUA_GRAPH("lua"),
    ;

    companion object {
        val default: AgentId = LUA_GRAPH

        fun fromStorageValue(raw: String?): AgentId {
            if (raw.isNullOrBlank()) return default
            return entries.firstOrNull {
                it.storageValue.equals(raw, ignoreCase = true) ||
                    it.name.equals(raw, ignoreCase = true)
            } ?: default
        }
    }
}
