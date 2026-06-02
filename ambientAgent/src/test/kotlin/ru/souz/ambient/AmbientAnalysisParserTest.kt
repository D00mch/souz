package ru.souz.ambient

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AmbientAnalysisParserTest {

    @Test
    fun `parses valid json with statements and task candidates`() {
        val result = parser().parse(
            blockId = "b1",
            raw = """
                {
                  "type": "ambient_analysis",
                  "block_summary": "summary",
                  "statements": [
                    {"text":"надо купить хлеб","kind":"NOTE","confidence":0.8,"evidence_event_ids":["e1"]}
                  ],
                  "task_candidates": [
                    {
                      "title":"Купить хлеб",
                      "task_text":"Напомнить купить хлеб",
                      "suggestion_text":"Похоже, я могу помочь: напомнить купить хлеб.",
                      "confidence":0.9,
                      "addressedness":"IMPLICIT_USER_INTENT",
                      "matched_capability_ids":["tool:CALENDAR:CreateEvent"],
                      "missing_slots":[],
                      "risk":"LOW",
                      "requires_confirmation":false,
                      "evidence_event_ids":["e1"],
                      "reason":"clear reminder"
                    }
                  ]
                }
            """.trimIndent(),
            blockAddressedness = AmbientAddressedness.IMPLICIT_USER_INTENT,
            allowedCapabilityIds = setOf("tool:CALENDAR:CreateEvent"),
            evidenceEventIds = listOf("e1"),
        )

        assertEquals("b1", result.blockId)
        assertEquals("summary", result.blockSummary)
        assertEquals(AmbientStatementKind.NOTE, result.extractedStatements.single().kind)
        assertEquals("statement:b1:1", result.extractedStatements.single().id)
        assertEquals("task:b1:1", result.taskCandidates.single().id)
        assertTrue(result.taskCandidates.single().requiresConfirmation)
    }

    @Test
    fun `extracts json from code fence and prose wrapper`() {
        val result = parser().parse(
            blockId = "b1",
            raw = "ok\n```json\n{\"type\":\"ambient_analysis\",\"statements\":[],\"task_candidates\":[]}\n```\nend",
            blockAddressedness = AmbientAddressedness.UNKNOWN,
            allowedCapabilityIds = emptySet(),
            evidenceEventIds = emptyList(),
        )

        assertEquals("b1", result.blockId)
        assertEquals(emptyList(), result.taskCandidates)
    }

    @Test
    fun `invalid json returns empty result without crash`() {
        val result = parser().parse(
            blockId = "b1",
            raw = "not json",
            blockAddressedness = AmbientAddressedness.UNKNOWN,
            allowedCapabilityIds = emptySet(),
            evidenceEventIds = listOf("e1"),
        )

        assertEquals(emptyList(), result.extractedStatements)
        assertEquals(emptyList(), result.taskCandidates)
        assertEquals("not json", result.rawModelOutputPreview)
    }

    @Test
    fun `confidence is clamped enums are defaulted and unknown capabilities filtered`() {
        val result = parser().parse(
            blockId = "b1",
            raw = """
                {
                  "statements": [
                    {"text":"x","kind":"WEIRD","confidence":2.5,"evidence_event_ids":["bad"]}
                  ],
                  "task_candidates": [
                    {
                      "title":"T",
                      "task_text":"Task",
                      "suggestion_text":"Suggest",
                      "confidence":-1,
                      "addressedness":"NOPE",
                      "matched_capability_ids":["known","unknown"],
                      "missing_slots":["date"],
                      "risk":"NOPE",
                      "requires_confirmation":false,
                      "evidence_event_ids":["bad"],
                      "reason":"r"
                    }
                  ]
                }
            """.trimIndent(),
            blockAddressedness = AmbientAddressedness.UNKNOWN,
            allowedCapabilityIds = setOf("known"),
            evidenceEventIds = listOf("e1"),
        )

        assertEquals(AmbientStatementKind.OTHER, result.extractedStatements.single().kind)
        assertEquals(1.0, result.extractedStatements.single().confidence)
        assertEquals(listOf("e1"), result.extractedStatements.single().evidenceEventIds)
        assertEquals(0.0, result.taskCandidates.single().confidence)
        assertEquals(AmbientAddressedness.UNKNOWN, result.taskCandidates.single().addressedness)
        assertEquals(listOf("known"), result.taskCandidates.single().matchedCapabilityIds)
        assertEquals(AmbientTaskRisk.UNKNOWN, result.taskCandidates.single().risk)
        assertTrue(result.taskCandidates.single().requiresConfirmation)
    }

    @Test
    fun `background speech task candidates are rejected`() {
        val result = parser().parse(
            blockId = "b1",
            raw = """
                {
                  "statements": [],
                  "task_candidates": [
                    {
                      "title":"Do",
                      "task_text":"Do it",
                      "suggestion_text":"Suggest",
                      "confidence":0.99,
                      "addressedness":"BACKGROUND_OR_QUOTED",
                      "matched_capability_ids":[],
                      "missing_slots":[],
                      "risk":"LOW",
                      "requires_confirmation":true,
                      "evidence_event_ids":["e1"],
                      "reason":"quoted"
                    }
                  ]
                }
            """.trimIndent(),
            blockAddressedness = AmbientAddressedness.BACKGROUND_OR_QUOTED,
            allowedCapabilityIds = emptySet(),
            evidenceEventIds = listOf("e1"),
        )

        assertTrue(result.taskCandidates.isEmpty())
        assertFalse(result.extractedStatements.any { it.text.isBlank() })
    }

    @Test
    fun `empty title and suggestion text are preserved for controller fallback`() {
        val result = parser().parse(
            blockId = "b1",
            raw = """
                {
                  "statements": [],
                  "task_candidates": [
                    {
                      "title":"",
                      "task_text":"Create calendar event",
                      "suggestion_text":"",
                      "confidence":0.9,
                      "addressedness":"DIRECT_TO_SOUZ",
                      "matched_capability_ids":["tool:CALENDAR:create_event"],
                      "missing_slots":[],
                      "risk":"MEDIUM",
                      "requires_confirmation":true,
                      "evidence_event_ids":["e1"],
                      "reason":"direct request"
                    }
                  ]
                }
            """.trimIndent(),
            blockAddressedness = AmbientAddressedness.DIRECT_TO_SOUZ,
            allowedCapabilityIds = setOf("tool:CALENDAR:create_event"),
            evidenceEventIds = listOf("e1"),
        )

        val candidate = result.taskCandidates.single()
        assertEquals("", candidate.title)
        assertEquals("Create calendar event", candidate.taskText)
        assertEquals("", candidate.suggestionText)
    }

    @Test
    fun `tool call shaped json task text is normalized before dispatch`() {
        val result = parser().parse(
            blockId = "b1",
            raw = """
                {
                  "statements": [],
                  "task_candidates": [
                    {
                      "title":"Погода",
                      "task_text":"InternetSearch{query:\"Погода в Москве\"}",
                      "suggestion_text":"Посмотреть погоду в Москве?",
                      "confidence":0.9,
                      "addressedness":"IMPLICIT_USER_INTENT",
                      "matched_capability_ids":["tool:WEB_SEARCH:InternetSearch"],
                      "missing_slots":[],
                      "risk":"LOW",
                      "requires_confirmation":true,
                      "evidence_event_ids":["e1"],
                      "reason":"weather lookup"
                    }
                  ]
                }
            """.trimIndent(),
            blockAddressedness = AmbientAddressedness.IMPLICIT_USER_INTENT,
            allowedCapabilityIds = setOf("tool:WEB_SEARCH:InternetSearch"),
            evidenceEventIds = listOf("e1"),
        )

        assertEquals("Погода в Москве", result.taskCandidates.single().taskText)
    }

    private fun parser(): AmbientAnalysisJsonParser = AmbientAnalysisJsonParser()
}
