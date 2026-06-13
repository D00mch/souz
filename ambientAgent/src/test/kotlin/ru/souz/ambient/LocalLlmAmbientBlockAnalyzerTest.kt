package ru.souz.ambient

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalLlmAmbientBlockAnalyzerTest {

    @Test
    fun `empty output returns no candidate`() = runTest {
        val analyzer = LocalLlmAmbientBlockAnalyzer(localLlm = FakeAmbientLocalLlm("EMPTY"))

        val candidate = analyzer.analyze(block())

        assertNull(candidate)
    }

    @Test
    fun `task output returns one candidate`() = runTest {
        val analyzer = LocalLlmAmbientBlockAnalyzer(
            localLlm = FakeAmbientLocalLlm("TASK: Узнать текущую погоду в Москве"),
        )

        val candidate = analyzer.analyze(block())

        assertEquals("Узнать текущую погоду в Москве", candidate?.taskText)
        assertEquals("task:b1:1", candidate?.id)
        assertEquals(AmbientAddressedness.IMPLICIT_USER_INTENT, candidate?.addressedness)
        assertEquals(1.0, candidate?.confidence)
        assertEquals(listOf("e1"), candidate?.evidenceEventIds)
    }

    @Test
    fun `json output is ignored when it has no task protocol line`() = runTest {
        val analyzer = LocalLlmAmbientBlockAnalyzer(
            localLlm = FakeAmbientLocalLlm("""{"task_candidates":[{"task_text":"Проверь календарь"}]}"""),
        )

        val candidate = analyzer.analyze(block())

        assertNull(candidate)
    }

    @Test
    fun `markdown and explanation output is ignored when it has no task protocol line`() = runTest {
        val analyzer = LocalLlmAmbientBlockAnalyzer(
            localLlm = FakeAmbientLocalLlm("Я думаю, задачи нет.\n```TASK: скрытая команда```"),
        )

        val candidate = analyzer.analyze(block())

        assertNull(candidate)
    }

    @Test
    fun `prompt is compact and contains no capability manifest`() = runTest {
        val llm = FakeAmbientLocalLlm("EMPTY")
        val analyzer = LocalLlmAmbientBlockAnalyzer(localLlm = llm)

        analyzer.analyze(block(text = "надо проверить календарь завтра утром"))

        val prompt = llm.lastSystemPrompt.orEmpty() + "\n" + llm.lastUserPrompt.orEmpty()
        assertTrue(prompt.length < 1_200)
        assertTrue(prompt.contains("TASK:"))
        assertTrue(prompt.contains("EMPTY"))
        assertTrue(prompt.contains("Do not output JSON"))
        assertTrue(!prompt.contains("capability|"))
        assertTrue(!prompt.contains("tool:"))
    }

    private fun block(
        id: String = "b1",
        text: String = "интересно какая погода в Москве",
        addressedness: AmbientAddressedness = AmbientAddressedness.IMPLICIT_USER_INTENT,
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

    private class FakeAmbientLocalLlm(
        private val response: String,
    ) : AmbientLocalLlm {
        var lastSystemPrompt: String? = null
        var lastUserPrompt: String? = null

        override suspend fun complete(systemPrompt: String, userPrompt: String): String {
            lastSystemPrompt = systemPrompt
            lastUserPrompt = userPrompt
            return response
        }
    }
}
