package ru.souz.agent.session

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.memory.MemoryPromptFact

class GraphSessionServiceTest {
    @Test
    fun `memory augmentation event stores only safe summary`() = runTest {
        val repository = mockk<GraphSessionRepository>()
        val savedSession = slot<GraphSession>()
        every { repository.save(capture(savedSession)) } just runs
        val service = GraphSessionService(
            repository = repository,
            logObjectMapper = jacksonObjectMapper(),
        )

        service.startTask("hello")
        service.emit(
            AgentRuntimeEvent.MemoryPromptAugmented(
                addedBlock = "Relevant memory:\n- Prefer Kotlin.",
                facts = listOf(
                    MemoryPromptFact(
                        factId = "fact-1",
                        scope = "global:global",
                        score = 0.91f,
                    ),
                    MemoryPromptFact(
                        factId = "fact-2",
                        scope = "chat:chat-1",
                        score = 0.72f,
                    ),
                )
            )
        )
        service.finishTask()

        verify(exactly = 1) { repository.save(any()) }
        val saved = savedSession.captured
        val step = saved.steps.single()

        assertEquals("Relevant memory:\n- Prefer Kotlin.", step.outputSummary)
        assertTrue(step.data.contains("fact-1"))
        assertTrue(step.data.contains("global:global"))
        assertTrue(step.data.contains("0.91"))
        assertTrue(step.data.contains("Relevant memory"))
        assertTrue(step.data.contains("Prefer Kotlin"))
        assertFalse(step.data.contains("secret"))
        assertNotNull(step.data)
    }
}
