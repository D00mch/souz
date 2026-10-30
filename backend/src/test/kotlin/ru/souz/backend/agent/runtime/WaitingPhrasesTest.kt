package ru.souz.backend.agent.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WaitingPhrasesTest {
    @Test
    fun `bundled catalogue has distinct nonblank phrases and described pools`() {
        val catalogue = WaitingPhrases.load()
        val phrases = catalogue.generic + catalogue.categories.values.flatMap { it.phrases }
        assertTrue(phrases.size in 950..1050)
        assertEquals(phrases.size, phrases.toSet().size)
        assertTrue(phrases.all { it.isNotBlank() })
        assertTrue(catalogue.generic.isNotEmpty())
        assertTrue(catalogue.categories.keys.all { it.isNotBlank() && it != "other" })
        assertTrue(catalogue.categories.values.all { it.description.isNotBlank() && it.phrases.isNotEmpty() })
    }

    @Test
    fun `pools repeat a phrase only after the rest of the pool and unknown categories use generic phrases`() {
        val phrases = WaitingPhrases(listOf("a", "b", "c"), mapOf("travel" to WaitingPhrases.Category("Trips.", listOf("x", "y"))))
        val travel = mutableListOf<String>()
        val generic = mutableListOf<String>()
        repeat(100) {
            val travelRound = List(2) { phrases.pick("travel") }
            val genericRound = listOf(phrases.pick("unknown"), phrases.pick(null), phrases.pick(null))
            assertEquals(setOf("x", "y"), travelRound.toSet())
            assertEquals(setOf("a", "b", "c"), genericRound.toSet())
            travel += travelRound
            generic += genericRound
        }
        assertTrue(travel.zipWithNext().all { (before, after) -> before != after })
        assertTrue(generic.zipWithNext().all { (before, after) -> before != after })
        val single = WaitingPhrases(listOf("only"), emptyMap())
        repeat(3) { assertEquals("only", single.pick(null)) }
    }
}
