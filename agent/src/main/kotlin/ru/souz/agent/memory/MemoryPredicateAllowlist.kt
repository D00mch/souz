package ru.souz.agent.memory

object MemoryPredicateAllowlist {
    val predicates: Set<String> = setOf(
        "prefers_language",
        "prefers_timezone",
        "requires",
        "works_on",
        "active_initiative",
        "status",
        "depends_on",
        "located_in_file",
        "uses_module",
        "prohibits",
        "approved_decision",
    )

    private val conflictBearingPredicates: Set<String> = setOf(
        "prefers_language",
        "prefers_timezone",
        "active_initiative",
        "status",
        "approved_decision",
    )

    fun isSupported(predicate: String): Boolean = normalizeKey(predicate) in predicates

    fun requiresSlotKey(predicate: String): Boolean = normalizeKey(predicate) in conflictBearingPredicates
}
