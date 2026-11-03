package ru.souz.backend.agent.runtime

import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val mutex = Mutex()
    private val rounds = HashMap<String?, Round>()

    private data class Round(val phrases: List<String>, val nextIndex: Int = 0)

    init {
        require(generic.isNotEmpty() && categories.values.all { it.description.isNotBlank() && it.phrases.isNotEmpty() }) {
            "Waiting phrases need generic phrases and, for every category, a description and phrases"
        }
        require((generic + categories.values.flatMap { it.phrases }).none(String::isBlank)) { "Waiting phrases must not be blank" }
    }

    /** A phrase of [category], or a generic one when the category is null or unknown. */
    suspend fun pick(category: String?): String = mutex.withLock {
        val known = category?.takeIf { it in categories }
        val previous = rounds[known]
        val round = if (previous == null || previous.nextIndex == previous.phrases.size) {
            val shuffled = (known?.let { categories.getValue(it).phrases } ?: generic).shuffled()
            val phrases = if (shuffled.size > 1 && shuffled.first() == previous?.phrases?.last()) {
                shuffled.drop(1) + shuffled.first()
            } else {
                shuffled
            }
            Round(phrases)
        } else {
            previous
        }
        rounds[known] = round.copy(nextIndex = round.nextIndex + 1)
        round.phrases[round.nextIndex]
    }

    companion object {
        fun load(): WaitingPhrases = restJsonMapper.readValue(
            WaitingPhrases::class.java.getResource("/waiting-phrases.json") ?: error("waiting-phrases.json is missing")
        )
    }
}
