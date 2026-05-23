@file:OptIn(ExperimentalCoroutinesApi::class)

package ru.souz.ui.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import ru.souz.agent.memory.MemoryEvidenceRecord
import ru.souz.agent.memory.MemoryEvidenceType
import ru.souz.agent.memory.MemoryFactRecord
import ru.souz.agent.memory.MemoryGraphSnapshot
import ru.souz.agent.memory.MemoryObjectKind
import ru.souz.agent.memory.MemoryScope
import ru.souz.agent.memory.MemoryScopeType
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MemoryInspectorViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `manual forget action triggers use case`() = runTest(dispatcher) {
        val service = FakeMemoryInspectorService()
        val viewModel = MemoryInspectorViewModel(
            di = DI {
                bindSingleton<MemoryInspectorService> { service }
            }
        )
        advanceUntilIdle()

        viewModel.send(MemoryInspectorEvent.SelectFact("fact-1"))
        advanceUntilIdle()
        viewModel.send(MemoryInspectorEvent.ForgetSelectedFact)
        advanceUntilIdle()

        assertEquals(listOf("fact-1"), service.forgottenFactIds)
    }

    @Test
    fun `view model starts from service default scope`() = runTest(dispatcher) {
        val service = FakeMemoryInspectorService(
            scope = MemoryScope(MemoryScopeType.THREAD, "thread-42"),
        )
        val viewModel = MemoryInspectorViewModel(
            di = DI {
                bindSingleton<MemoryInspectorService> { service }
            }
        )

        advanceUntilIdle()

        assertEquals(MemoryScope(MemoryScopeType.THREAD, "thread-42"), viewModel.uiState.value.scope)
    }
}

private class FakeMemoryInspectorService(
    private val scope: MemoryScope = MemoryScope(MemoryScopeType.USER, "local-user"),
) : MemoryInspectorService {
    val forgottenFactIds = mutableListOf<String>()

    override suspend fun defaultScope(): MemoryScope = scope

    override fun capabilities(): MemoryInspectorCapabilities = MemoryInspectorCapabilities()

    override suspend fun loadOverview(scope: MemoryScope): MemoryInspectorOverview =
        MemoryInspectorOverview(activeFactCount = 1)

    override suspend fun loadGraphSnapshot(scope: MemoryScope): MemoryGraphSnapshot =
        MemoryGraphSnapshot(
            facts = listOf(
                MemoryFactRecord(
                    id = "fact-1",
                    scope = scope,
                    subjectEntityId = "subject-1",
                    predicate = "prefers_language",
                    objectKind = MemoryObjectKind.TEXT,
                    objectValueText = "ru",
                    slotKey = "user.profile.language",
                    reasonToStore = "test",
                )
            ),
            attributes = listOf(
                MemoryGraphSnapshot.Attribute(
                    factId = "fact-1",
                    subjectEntityId = "subject-1",
                    predicate = "prefers_language",
                    value = "ru",
                )
            ),
        )

    override suspend fun loadTimeline(scope: MemoryScope): List<MemoryGraphSnapshot.TimelineEvent> = emptyList()

    override suspend fun loadEvidence(
        scope: MemoryScope,
        factId: String,
    ): List<MemoryEvidenceRecord> = listOf(
        MemoryEvidenceRecord(
            id = "evidence-1",
            scope = scope,
            evidenceType = MemoryEvidenceType.USER_MESSAGE,
            sourceRef = "turn:1:user",
            contentExcerpt = "Write in Russian",
        )
    )

    override suspend fun loadDiagnostics(scope: MemoryScope): MemoryInspectorDiagnostics =
        MemoryInspectorDiagnostics()

    override suspend fun loadRejectedWrites(
        scope: MemoryScope,
        limit: Int,
    ): List<MemoryInspectorWriteAttempt> = emptyList()

    override suspend fun loadRecentInjections(
        scope: MemoryScope,
        limit: Int,
    ): List<MemoryInspectorInjectionLog> = emptyList()

    override suspend fun forgetFact(factId: String): Boolean {
        forgottenFactIds += factId
        return true
    }

    override suspend fun invalidateFact(factId: String): Boolean = true

    override suspend fun rebuildEmbeddings() = Unit

    override suspend fun runConsolidation(): Boolean = false

    override suspend fun toggleAutoWrite(enabled: Boolean): Boolean = false

    override fun canOpenSourceRef(sourceRef: String?): Boolean = false

    override suspend fun openSourceRef(sourceRef: String?): Boolean = false
}
