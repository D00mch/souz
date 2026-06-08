package ru.souz.ambient

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalLlmAmbientBlockAnalyzerTest {

    @Test
    fun `calendar lookup candidate comes from local model json`() = runTest {
        val llm = CapturingAmbientLocalLlm(
            response = """
                {
                  "type":"ambient_analysis",
                  "block_summary":"calendar lookup",
                  "task_candidates":[
                    {
                      "title":"Проверить календарь",
                      "task_text":"Проверь календарь на завтра",
                      "suggestion_text":"Проверить календарь на завтра?",
                      "confidence":0.88,
                      "addressedness":"IMPLICIT_USER_INTENT",
                      "matched_capability_ids":["tool:CALENDAR:CalendarListEvents"],
                      "missing_slots":[],
                      "risk":"LOW",
                      "requires_confirmation":true,
                      "evidence_event_ids":["e1"],
                      "reason":"user wondered about tomorrow calendar"
                    }
                  ]
                }
            """.trimIndent(),
        )
        val analyzer = LocalLlmAmbientBlockAnalyzer(localLlm = llm, clock = { 1_000L })

        val result = analyzer.analyze(
            block = block(
                text = "интересно, надо проверить календарь на завтра",
                addressedness = AmbientAddressedness.IMPLICIT_USER_INTENT,
            ),
            recentContext = emptyList(),
            capabilities = listOf(calendarListEventsCapability()),
        )

        val candidate = result.taskCandidates.single()
        assertEquals("Проверь календарь на завтра", candidate.taskText)
        assertEquals(listOf("tool:CALENDAR:CalendarListEvents"), candidate.matchedCapabilityIds)
        assertEquals(listOf("e1"), candidate.evidenceEventIds)
    }

    @Test
    fun `task candidate can come from compact text protocol`() = runTest {
        val llm = CapturingAmbientLocalLlm(
            response = """
                TASK: Узнать текущую погоду в Москве
            """.trimIndent(),
        )
        val analyzer = LocalLlmAmbientBlockAnalyzer(localLlm = llm, clock = { 1_000L })

        val result = analyzer.analyze(
            block = block(
                text = "интересно какая погода в Москве",
                addressedness = AmbientAddressedness.IMPLICIT_USER_INTENT,
            ),
            recentContext = emptyList(),
            capabilities = listOf(webSearchCapability()),
        )

        val candidate = result.taskCandidates.single()
        assertEquals("Узнать текущую погоду в Москве", candidate.taskText)
        assertEquals(emptyList(), candidate.matchedCapabilityIds)
        assertEquals(1.0, candidate.confidence)
    }

    @Test
    fun `local analyzer prompt stays compact for small ambient models`() = runTest {
        val llm = CapturingAmbientLocalLlm(
            response = """{"type":"ambient_analysis","task_candidates":[]}""",
        )
        val analyzer = LocalLlmAmbientBlockAnalyzer(localLlm = llm, clock = { 1_000L })
        val capabilities = List(40) { index ->
            AmbientCapability(
                id = "tool:FILES:VeryLongToolName$index",
                kind = AmbientCapabilityKind.TOOL,
                category = "FILES",
                name = "VeryLongToolName$index",
                description = "description ".repeat(80),
                examples = listOf("example ".repeat(80)),
            )
        }

        analyzer.analyze(
            block = block(
                text = "надо не забыть проверить календарь на завтра и посмотреть, что там с рабочими встречами",
                addressedness = AmbientAddressedness.IMPLICIT_USER_INTENT,
            ),
            recentContext = listOf(
                block(id = "old", text = "старый контекст, который не должен попадать в prompt"),
                block(id = "recent", text = "последняя фраза для короткого контекста"),
            ),
            capabilities = capabilities,
        )

        val promptChars = llm.lastSystemPrompt.orEmpty().length + llm.lastUserPrompt.orEmpty().length
        val userPrompt = llm.lastUserPrompt.orEmpty()
        assertTrue(promptChars <= 1_900, "Prompt length was $promptChars")
        assertFalse(userPrompt.contains("description description description"))
        assertFalse(userPrompt.contains("example example example"))
        assertFalse(userPrompt.contains("старый контекст"))
        assertTrue(userPrompt.contains("последняя фраза"))
    }

    @Test
    fun `local analyzer prompt asks model to extract complete sentences from speech window`() = runTest {
        val llm = CapturingAmbientLocalLlm(
            response = """{"type":"ambient_analysis","task_candidates":[]}""",
        )
        val analyzer = LocalLlmAmbientBlockAnalyzer(localLlm = llm, clock = { 1_000L })

        analyzer.analyze(
            block = block(
                text = "мы тут говорили про календарь и потом я сказал что надо проверить встречи завтра",
                addressedness = AmbientAddressedness.IMPLICIT_USER_INTENT,
            ),
            recentContext = emptyList(),
            capabilities = listOf(calendarListEventsCapability()),
        )

        val systemPrompt = llm.lastSystemPrompt.orEmpty()
        val userPrompt = llm.lastUserPrompt.orEmpty()
        assertTrue(systemPrompt.contains("3"))
        assertTrue(systemPrompt.contains("цельн"))
        assertTrue(systemPrompt.contains("предлож"))
        assertTrue(userPrompt.contains("capability|CALENDAR|heard="))
        assertTrue(systemPrompt.contains("\"type\":\"final\""))
        assertTrue(systemPrompt.contains("\"content\""))
        assertTrue(systemPrompt.contains("естественная команда"))
        assertTrue(systemPrompt.contains("TASK:"))
        assertTrue(systemPrompt.contains("EMPTY"))
        assertFalse(systemPrompt.contains("IDS:"))
        assertFalse(systemPrompt.contains("SUGGEST"))
        assertFalse(systemPrompt.contains("CONFIDENCE"))
        assertFalse(systemPrompt.contains("RISK"))
    }

    @Test
    fun `diagnostics receive full prompt and raw model output`() = runTest {
        val rawOutput = """{"type":"ambient_analysis","task_candidates":[]}"""
        val llm = CapturingAmbientLocalLlm(response = rawOutput)
        val diagnosticEvents = mutableListOf<AmbientLocalAnalysisDiagnosticEvent>()
        val analyzer = LocalLlmAmbientBlockAnalyzer(
            localLlm = llm,
            diagnostics = { event -> diagnosticEvents += event },
        )

        analyzer.analyze(
            block = block(
                text = "надо проверить календарь завтра утром",
                addressedness = AmbientAddressedness.IMPLICIT_USER_INTENT,
            ),
            recentContext = emptyList(),
            capabilities = listOf(calendarListEventsCapability()),
        )

        assertTrue(
            diagnosticEvents
                .filterIsInstance<AmbientLocalAnalysisDiagnosticEvent.Prompt>()
                .single()
                .userPrompt
                .contains("надо проверить календарь завтра утром")
        )
        assertEquals(
            rawOutput,
            diagnosticEvents
                .filterIsInstance<AmbientLocalAnalysisDiagnosticEvent.RawOutput>()
                .single()
                .raw,
        )
    }

    private fun block(
        id: String = "b1",
        text: String,
        addressedness: AmbientAddressedness = AmbientAddressedness.UNKNOWN,
    ): AmbientSemanticBlock = AmbientSemanticBlock(
        id = id,
        text = text,
        eventIds = listOf("e1"),
        startedAtMs = 100,
        endedAtMs = 200,
        closedAtMs = 300,
        closeReason = AmbientBlockCloseReason.PAUSE,
        speakerRole = AmbientSpeakerRole.PROBABLY_USER,
        addressedness = addressedness,
    )

    private fun calendarListEventsCapability(): AmbientCapability = AmbientCapability(
        id = "tool:CALENDAR:CalendarListEvents",
        kind = AmbientCapabilityKind.TOOL,
        category = "CALENDAR",
        name = "CalendarListEvents",
        description = "List events from a calendar for a date.",
        risk = AmbientCapabilityRisk.LOW,
    )

    private fun webSearchCapability(): AmbientCapability = AmbientCapability(
        id = "tool:WEB_SEARCH:InternetSearch",
        kind = AmbientCapabilityKind.TOOL,
        category = "WEB_SEARCH",
        name = "InternetSearch",
        description = "Short factual internet lookup.",
        risk = AmbientCapabilityRisk.LOW,
    )

    private class CapturingAmbientLocalLlm(
        private val response: String,
    ) : AmbientLocalLlm {
        var lastSystemPrompt: String? = null
        var lastUserPrompt: String? = null

        override suspend fun completeJson(systemPrompt: String, userPrompt: String): String {
            lastSystemPrompt = systemPrompt
            lastUserPrompt = userPrompt
            return response
        }
    }
}
