package ru.souz.memory

object MemoryBlockRenderer {
    fun render(facts: List<MemoryFact>): String {
        if (facts.isEmpty()) return ""
        return buildString {
            appendLine("Relevant memory:")
            facts.forEach { fact ->
                append("- [")
                append(fact.kind.name.lowercase())
                append("] ")
                append(fact.title.trim())
                if (fact.body.isNotBlank()) {
                    append(": ")
                    append(fact.body.trim())
                }
                appendLine()
            }
        }.trim()
    }
}
