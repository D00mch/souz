package ru.souz.backend.agent.runtime

import com.fasterxml.jackson.module.kotlin.readValue
import ru.souz.llms.restJsonMapper

/**
 * Prepared initial waiting phrases from `waiting-phrases.json`: a generic pool and category pools whose
 * descriptions the routing classifier chooses from. Each pool hands out its phrases in shuffled rounds,
 * so a phrase repeats only after the rest of its pool was used, without consecutive repeats between rounds.
 */
internal class WaitingPhrases(
    val generic: List<String>,
    val categories: Map<String, Category>,
) {
    data class Category(val description: String, val phrases: List<String>)

    private val rounds = HashMap<String?, ArrayDeque<String>>()
    private val previousPhrases = HashMap<String?, String>()

    init {
        require(generic.isNotEmpty() && categories.values.all { it.description.isNotBlank() && it.phrases.isNotEmpty() }) {
            "Waiting phrases need generic phrases and, for every category, a description and phrases"
        }
        require((generic + categories.values.flatMap { it.phrases }).none(String::isBlank)) { "Waiting phrases must not be blank" }
    }

    /** A phrase of [category], or a generic one when the category is null or unknown. */
    @Synchronized // Concurrent executions pick phrases; the lock never spans a suspension.
    fun pick(category: String?): String {
        val known = category?.takeIf { it in categories }
        val round = rounds.getOrPut(known) { ArrayDeque() }
        if (round.isEmpty()) {
            round.addAll((known?.let { categories.getValue(it).phrases } ?: generic).shuffled())
            if (round.size > 1 && round.first() == previousPhrases[known]) round.addLast(round.removeFirst())
        }
        return round.removeFirst().also { previousPhrases[known] = it }
    }

    companion object {
        fun load(): WaitingPhrases = restJsonMapper.readValue(
            WaitingPhrases::class.java.getResource("/waiting-phrases.json") ?: error("waiting-phrases.json is missing")
        )
    }
}
