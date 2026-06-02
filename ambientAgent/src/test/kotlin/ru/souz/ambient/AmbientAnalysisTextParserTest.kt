package ru.souz.ambient

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AmbientAnalysisTextParserTest {
    @Test
    fun `parses compact task protocol into candidate`() {
        val parser = AmbientAnalysisTextParser()

        val result = parser.parse(
            blockId = "b1",
            raw = """
                TASK: Узнать текущую погоду в Москве
                SUGGEST: Посмотреть погоду в Москве?
            """.trimIndent(),
            blockAddressedness = AmbientAddressedness.IMPLICIT_USER_INTENT,
            allowedCapabilityIds = setOf("tool:WEB_SEARCH:InternetSearch"),
            evidenceEventIds = listOf("e1"),
        )

        val candidate = result.taskCandidates.single()
        assertEquals("Узнать текущую погоду в Москве", candidate.taskText)
        assertEquals("Посмотреть погоду в Москве?", candidate.suggestionText)
        assertEquals(emptyList(), candidate.matchedCapabilityIds)
        assertEquals(1.0, candidate.confidence)
        assertEquals(AmbientTaskRisk.UNKNOWN, candidate.risk)
        assertEquals(listOf("e1"), candidate.evidenceEventIds)
    }

    @Test
    fun `empty protocol returns no candidates`() {
        val parser = AmbientAnalysisTextParser()

        val result = parser.parse(
            blockId = "b1",
            raw = "EMPTY",
            blockAddressedness = AmbientAddressedness.IMPLICIT_USER_INTENT,
            allowedCapabilityIds = setOf("tool:WEB_SEARCH:InternetSearch"),
            evidenceEventIds = listOf("e1"),
        )

        assertTrue(result.taskCandidates.isEmpty())
    }

    @Test
    fun `ignores capability ids from text protocol`() {
        val parser = AmbientAnalysisTextParser()

        val result = parser.parse(
            blockId = "b1",
            raw = """
                TASK: Проверить календарь завтра
                IDS: tool:CALENDAR:CalendarListEvents, tool:WEB_SEARCH:Fake
            """.trimIndent(),
            blockAddressedness = AmbientAddressedness.IMPLICIT_USER_INTENT,
            allowedCapabilityIds = setOf("tool:CALENDAR:CalendarListEvents"),
            evidenceEventIds = listOf("e1"),
        )

        assertEquals(emptyList(), result.taskCandidates.single().matchedCapabilityIds)
    }

    @Test
    fun `tool call shaped task uses suggestion as natural command`() {
        val parser = AmbientAnalysisTextParser()

        val result = parser.parse(
            blockId = "b1",
            raw = """
                TASK: CalendarListEvents(date="tomorrow")
                SUGGEST: Проверь календарь на завтра?
            """.trimIndent(),
            blockAddressedness = AmbientAddressedness.IMPLICIT_USER_INTENT,
            allowedCapabilityIds = setOf("tool:CALENDAR:CalendarListEvents"),
            evidenceEventIds = listOf("e1"),
        )

        val candidate = result.taskCandidates.single()
        assertEquals("Проверь календарь на завтра", candidate.taskText)
        assertEquals("Проверь календарь на завтра?", candidate.suggestionText)
    }
}
