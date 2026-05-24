@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ru.souz.ui.memory

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import ru.souz.memory.CreateMemoryFactInput
import ru.souz.memory.MemoryEvidence
import ru.souz.memory.MemoryEvidenceDetail
import ru.souz.memory.MemoryFact
import ru.souz.memory.MemoryFactDetails
import ru.souz.memory.MemoryFactFilter
import ru.souz.memory.MemoryFactKind
import ru.souz.memory.MemoryFactPatch
import ru.souz.memory.MemoryFactStatus
import ru.souz.memory.MemoryScope
import ru.souz.memory.MemoryService
import ru.souz.memory.MemorySourceEvent
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryViewModelTest {
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
    fun `load facts populates state`() = runTest(dispatcher) {
        val service = mockk<MemoryService>()
        val loadedFacts = listOf(
            memoryFact(id = "fact-1", pinned = true),
            memoryFact(id = "fact-2"),
        )
        coEvery { service.listFacts(any()) } returns loadedFacts

        val viewModel = createViewModel(service)
        viewModel.onAction(MemoryAction.Load)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("fact-1", "fact-2"), state.facts.map { it.id })
        assertFalse(state.isLoading)
        assertNull(state.error)
        coVerify(exactly = 1) { service.listFacts(MemoryFactFilter()) }
    }

    @Test
    fun `changing filters reloads facts with mapped domain filter`() = runTest(dispatcher) {
        val service = mockk<MemoryService>()
        coEvery { service.listFacts(any()) } returns emptyList()
        val filters = MemoryFiltersUi(
            status = MemoryStatusFilter.ALL,
            kind = MemoryFactKind.PROJECT_RULE,
            scopeType = "chat",
            scopeId = "chat-42",
            query = "kotlin",
        )

        val viewModel = createViewModel(service)
        viewModel.onAction(MemoryAction.ChangeFilters(filters))
        advanceUntilIdle()

        assertEquals(filters, viewModel.uiState.value.filters)
        coVerify(exactly = 1) {
            service.listFacts(
                MemoryFactFilter(
                    statuses = setOf(
                        MemoryFactStatus.ACTIVE,
                        MemoryFactStatus.RETIRED,
                        MemoryFactStatus.DELETED,
                    ),
                    kinds = setOf(MemoryFactKind.PROJECT_RULE),
                    scope = MemoryScope("chat", "chat-42"),
                    query = "kotlin",
                )
            )
        }
    }

    @Test
    fun `save new fact creates manual fact and refreshes list`() = runTest(dispatcher) {
        val service = mockk<MemoryService>()
        val createdFact = memoryFact(id = "fact-created")
        coEvery { service.createManualFact(any()) } returns createdFact
        coEvery { service.listFacts(any()) } returns listOf(createdFact)

        val viewModel = createViewModel(service)
        viewModel.onAction(
            MemoryAction.SaveFact(
                MemoryEditorInput(
                    factId = null,
                    title = "Remember this",
                    body = "Manual fact body",
                    kind = MemoryFactKind.PREFERENCE,
                    scopeType = "global",
                    scopeId = "global",
                    slotKey = "pref",
                    pinned = true,
                )
            )
        )
        advanceUntilIdle()

        coVerify(exactly = 1) {
            service.createManualFact(
                CreateMemoryFactInput(
                    scope = MemoryScope("global", "global"),
                    kind = MemoryFactKind.PREFERENCE,
                    title = "Remember this",
                    body = "Manual fact body",
                    slotKey = "pref",
                    pinned = true,
                )
            )
        }
        coVerify(exactly = 1) { service.listFacts(MemoryFactFilter()) }
        assertNull(viewModel.uiState.value.editor)
        assertFalse(viewModel.uiState.value.isSaving)
        assertEquals(listOf("fact-created"), viewModel.uiState.value.facts.map { it.id })
    }

    @Test
    fun `save existing fact updates fact and refreshes list`() = runTest(dispatcher) {
        val service = mockk<MemoryService>()
        val existing = memoryFact(id = "fact-1", scope = MemoryScope("project", "souz"))
        val updated = existing.copy(title = "Updated title", scope = MemoryScope("chat", "chat-1"))
        coEvery { service.updateFact(any(), any()) } returns updated
        coEvery { service.listFacts(any()) } returns listOf(updated)

        val viewModel = createViewModel(service)
        viewModel.onAction(
            MemoryAction.SaveFact(
                MemoryEditorInput(
                    factId = existing.id,
                    title = "Updated title",
                    body = existing.body,
                    kind = existing.kind,
                    scopeType = "chat",
                    scopeId = "chat-1",
                    slotKey = "",
                    pinned = existing.pinned,
                )
            )
        )
        advanceUntilIdle()

        coVerify(exactly = 1) {
            service.updateFact(
                factId = existing.id,
                patch = MemoryFactPatch(
                    scope = MemoryScope("chat", "chat-1"),
                    kind = existing.kind,
                    title = "Updated title",
                    body = existing.body,
                    clearSlotKey = true,
                    pinned = existing.pinned,
                )
            )
        }
        coVerify(exactly = 1) { service.listFacts(MemoryFactFilter()) }
        assertEquals("Updated title", viewModel.uiState.value.facts.single().title)
    }

    @Test
    fun `set pinned updates fact and refreshes list`() = runTest(dispatcher) {
        val service = mockk<MemoryService>()
        val updated = memoryFact(id = "fact-1", pinned = true)
        coEvery { service.updateFact(any(), any()) } returns updated
        coEvery { service.listFacts(any()) } returns listOf(updated)

        val viewModel = createViewModel(service)
        viewModel.onAction(MemoryAction.SetPinned("fact-1", true))
        advanceUntilIdle()

        coVerify(exactly = 1) { service.updateFact("fact-1", MemoryFactPatch(pinned = true)) }
        coVerify(exactly = 1) { service.listFacts(MemoryFactFilter()) }
    }

    @Test
    fun `retire and delete confirm actions call service and refresh list`() = runTest(dispatcher) {
        val scenarios = listOf(
            PendingMemoryConfirm.Kind.Retire,
            PendingMemoryConfirm.Kind.Delete,
        )

        scenarios.forEach { kind ->
            val service = mockk<MemoryService>()
            when (kind) {
                PendingMemoryConfirm.Kind.Retire -> coEvery { service.retireFact("fact-1") } returns Unit
                PendingMemoryConfirm.Kind.Delete -> coEvery { service.deleteFact("fact-1") } returns Unit
            }
            coEvery { service.listFacts(any()) } returns emptyList()
            val viewModel = createViewModel(service)

            when (kind) {
                PendingMemoryConfirm.Kind.Retire -> viewModel.onAction(MemoryAction.AskRetire("fact-1"))
                PendingMemoryConfirm.Kind.Delete -> viewModel.onAction(MemoryAction.AskDelete("fact-1"))
            }
            advanceUntilIdle()
            assertEquals(PendingMemoryConfirm(kind, "fact-1"), viewModel.uiState.value.confirm)

            viewModel.onAction(MemoryAction.ConfirmAction)
            advanceUntilIdle()

            when (kind) {
                PendingMemoryConfirm.Kind.Retire -> coVerify(exactly = 1) { service.retireFact("fact-1") }
                PendingMemoryConfirm.Kind.Delete -> coVerify(exactly = 1) { service.deleteFact("fact-1") }
            }
            coVerify(exactly = 1) { service.listFacts(MemoryFactFilter()) }
            assertNull(viewModel.uiState.value.confirm)
        }
    }

    @Test
    fun `open details loads fact details and evidence`() = runTest(dispatcher) {
        val service = mockk<MemoryService>()
        val details = MemoryFactDetails(
            fact = memoryFact(id = "fact-1", createdBy = "writer"),
            evidence = listOf(
                MemoryEvidenceDetail(
                    evidence = MemoryEvidence(
                        factId = "fact-1",
                        sourceEventId = "source-1",
                        evidenceText = "Write tests first.",
                    ),
                    sourceEvent = MemorySourceEvent(
                        id = "source-1",
                        scope = MemoryScope("project", "souz"),
                        sourceType = "turn",
                        sourceRef = "assistant-1",
                        text = "User: write tests first",
                        metadataJson = "{}",
                        createdAt = Instant.parse("2026-05-24T10:15:30Z"),
                    ),
                )
            ),
        )
        coEvery { service.getFactDetails("fact-1") } returns details

        val viewModel = createViewModel(service)
        viewModel.onAction(MemoryAction.OpenDetails("fact-1"))
        advanceUntilIdle()

        val selectedFact = viewModel.uiState.value.selectedFact
        assertEquals("fact-1", selectedFact?.fact?.id)
        assertEquals(details.evidence, selectedFact?.evidence)
    }

    @Test
    fun `service failure is exposed through state error`() = runTest(dispatcher) {
        val service = mockk<MemoryService>()
        coEvery { service.listFacts(any()) } throws IllegalStateException("boom")

        val viewModel = createViewModel(service)
        viewModel.onAction(MemoryAction.Load)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("boom", state.error)
        assertFalse(state.isLoading)
        assertTrue(state.facts.isEmpty())
    }

    private fun createViewModel(service: MemoryService): MemoryViewModel =
        MemoryViewModel(
            DI {
                bindSingleton<MemoryService> { service }
            }
        )

    private fun memoryFact(
        id: String,
        scope: MemoryScope = MemoryScope("global", "global"),
        createdBy: String = "user",
        pinned: Boolean = false,
        kind: MemoryFactKind = MemoryFactKind.SEMANTIC,
    ): MemoryFact = MemoryFact(
        id = id,
        scope = scope,
        kind = kind,
        title = "Title $id",
        body = "Body $id",
        slotKey = null,
        status = MemoryFactStatus.ACTIVE,
        confidence = 0.75f,
        pinned = pinned,
        createdBy = createdBy,
        createdAt = Instant.parse("2026-05-24T10:15:30Z"),
        updatedAt = Instant.parse("2026-05-24T11:15:30Z"),
        supersedesFactId = null,
    )
}
