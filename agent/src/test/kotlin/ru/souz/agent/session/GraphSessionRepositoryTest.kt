package ru.souz.agent.session

import ru.souz.paths.DefaultSouzPaths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphSessionRepositoryTest {
    private val createdPaths = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        createdPaths.asReversed().forEach { path ->
            runCatching { path.toFile().deleteRecursively() }
        }
        createdPaths.clear()
    }

    @Test
    fun `saves sessions under sessions dir`() {
        val stateRoot = createTempDirectory("graph-session-save-")
        val paths = DefaultSouzPaths(stateRoot = stateRoot)
        val repository = GraphSessionRepository(paths = paths)
        val session = sampleSession(id = "saved-session")

        repository.save(session)

        assertTrue(paths.sessionsDir.resolve("saved-session.json").exists())
        assertEquals(1, repository.count())
    }

    private fun createTempDirectory(prefix: String): Path =
        Files.createTempDirectory(prefix).also(createdPaths::add)

    private fun sampleSession(id: String): GraphSession = GraphSession(
        id = id,
        startTime = 123L,
        endTime = 456L,
        initialInput = "hello",
        steps = listOf(
            GraphStepRecord(
                stepIndex = 0,
                nodeName = "start",
                timestamp = 123L,
                inputSummary = "in",
                outputSummary = "out",
                data = "{}",
            )
        ),
    )
}
