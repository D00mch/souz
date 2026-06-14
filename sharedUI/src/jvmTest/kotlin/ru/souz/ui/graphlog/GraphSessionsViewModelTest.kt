@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ru.souz.ui.graphlog

import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import ru.souz.agent.session.GraphSession
import ru.souz.agent.session.GraphSessionRepository
import ru.souz.agent.session.GraphStepRecord
import ru.souz.paths.DefaultSouzPaths

class GraphSessionsViewModelTest {

    private lateinit var mainDispatcher: TestDispatcher
    private val createdPaths = mutableListOf<Path>()

    @BeforeTest
    fun setUp() {
        mainDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        createdPaths.asReversed().forEach { path ->
            runCatching { path.toFile().deleteRecursively() }
        }
        createdPaths.clear()
        unmockkAll()
    }

    @Test
    fun `empty load exposes empty state`() = runTest(mainDispatcher) {
        val viewModel = createViewModel(repository = tempRepository(), dispatcher = mainDispatcher)

        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertTrue(viewModel.uiState.value.sessions.isEmpty())
        assertEquals(null, viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `loaded sessions are sorted newest first`() = runTest(mainDispatcher) {
        val repository = tempRepository()
        repository.save(sampleSession(id = "old", startTime = 100L))
        repository.save(sampleSession(id = "new", startTime = 300L))

        val viewModel = createViewModel(repository = repository, dispatcher = mainDispatcher)

        advanceUntilIdle()

        assertEquals(listOf("new", "old"), viewModel.uiState.value.sessions.map { it.id })
        assertEquals(1, viewModel.uiState.value.sessions.first().stepsCount)
        assertEquals("Start", viewModel.uiState.value.sessions.first().nodePathPreview)
    }

    @Test
    fun `open session and back to list update selection`() = runTest(mainDispatcher) {
        val repository = tempRepository()
        repository.save(sampleSession(id = "target", startTime = 100L))
        val viewModel = createViewModel(repository = repository, dispatcher = mainDispatcher)
        advanceUntilIdle()

        viewModel.send(GraphSessionsEvent.OpenSession("target"))
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.selectedSession)
        assertEquals("target", viewModel.uiState.value.selectedSession?.id)

        viewModel.send(GraphSessionsEvent.BackToList)
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.selectedSession)
    }

    @Test
    fun `repository failure produces non crashing error state`() = runTest(mainDispatcher) {
        val repository = mockk<GraphSessionRepository>()
        every { repository.loadAll() } throws IllegalStateException("boom")
        val viewModel = createViewModel(repository = repository, dispatcher = mainDispatcher)

        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals("boom", viewModel.uiState.value.errorMessage)
    }

    private fun createViewModel(
        repository: GraphSessionRepository,
        dispatcher: TestDispatcher,
    ): GraphSessionsViewModel {
        val di = DI {
            bindSingleton<GraphSessionRepository> { repository }
        }
        return GraphSessionsViewModel(di = di, ioDispatcher = dispatcher)
    }

    private fun tempRepository(): GraphSessionRepository {
        val stateRoot = Files.createTempDirectory("graph-sessions-view-model-").also(createdPaths::add)
        return GraphSessionRepository(DefaultSouzPaths(stateRoot = stateRoot))
    }

    private fun sampleSession(id: String, startTime: Long): GraphSession =
        GraphSession(
            id = id,
            startTime = startTime,
            endTime = startTime + 2500L,
            initialInput = "hello",
            steps = listOf(
                GraphStepRecord(
                    stepIndex = 0,
                    nodeName = "Node Start",
                    timestamp = startTime,
                    inputSummary = "input",
                    outputSummary = "output",
                    data = "{}",
                )
            ),
        )
}
