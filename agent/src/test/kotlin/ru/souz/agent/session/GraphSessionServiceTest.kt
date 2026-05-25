@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package ru.souz.agent.session

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.memory.MemoryPromptAugmentation
import ru.souz.paths.DefaultSouzPaths

class GraphSessionServiceTest {
    private val createdPaths = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        createdPaths.asReversed().forEach { path ->
            runCatching { path.deleteRecursively() }
        }
        createdPaths.clear()
    }

    @Test
    fun `memory augmentation event stores only safe summary`() = runTest {
        val stateRoot = Files.createTempDirectory("graph-session-memory-").also(createdPaths::add)
        val repository = GraphSessionRepository(DefaultSouzPaths(stateRoot = stateRoot))
        val service = GraphSessionService(
            repository = repository,
            logObjectMapper = jacksonObjectMapper(),
        )

        service.startTask("hello")
        service.emit(
            AgentRuntimeEvent.MemoryPromptAugmented(
                addedBlock = "Relevant memory:\n- Prefer Kotlin.",
                facts = listOf(
                    MemoryPromptAugmentation.Fact(
                        factId = "fact-1",
                        scope = "global:global",
                        score = 0.91f,
                    ),
                    MemoryPromptAugmentation.Fact(
                        factId = "fact-2",
                        scope = "chat:chat-1",
                        score = 0.72f,
                    ),
                )
            )
        )
        service.finishTask()

        val saved = repository.loadAll().single()
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
