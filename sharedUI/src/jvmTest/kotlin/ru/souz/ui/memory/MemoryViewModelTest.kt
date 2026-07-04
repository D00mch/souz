@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ru.souz.ui.memory

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
import ru.souz.memory.MemoryOwnerId
import ru.souz.memory.MemoryOwnerProvider
import ru.souz.memory.MemoryMaintenanceBlockReason
import ru.souz.memory.MemoryMaintenanceController
import ru.souz.memory.MemoryMaintenanceMode
import ru.souz.memory.MemoryMaintenancePreferences
import ru.souz.memory.MemoryMaintenanceStatus
import ru.souz.memory.MemoryScope
import ru.souz.memory.MemoryService
import ru.souz.memory.MemorySourceEvent
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        coVerify(exactly = 1) { service.listFacts(MemoryFactFilter(ownerId = MemoryOwnerId("desktop-owner"))) }
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
                    ),
                    kinds = setOf(MemoryFactKind.PROJECT_RULE),
                    ownerId = MemoryOwnerId("desktop-owner"),
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
                    canonicalKey = "user.preference.language",
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
                    ownerId = MemoryOwnerId("desktop-owner"),
                    canonicalKey = "user.preference.language",
                    pinned = true,
                )
            )
        }
        coVerify(exactly = 1) { service.listFacts(MemoryFactFilter(ownerId = MemoryOwnerId("desktop-owner"))) }
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
                    canonicalKey = "",
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
                    clearCanonicalKey = true,
                    pinned = existing.pinned,
                )
            )
        }
        coVerify(exactly = 1) { service.listFacts(MemoryFactFilter(ownerId = MemoryOwnerId("desktop-owner"))) }
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
        coVerify(exactly = 1) { service.listFacts(MemoryFactFilter(ownerId = MemoryOwnerId("desktop-owner"))) }
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
            coVerify(exactly = 1) { service.listFacts(MemoryFactFilter(ownerId = MemoryOwnerId("desktop-owner"))) }
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

    @Test
    fun `load facts rethrows cancellation without setting error`() = runTest(dispatcher) {
        val service = mockk<MemoryService>()
        coEvery { service.listFacts(any()) } throws CancellationException("cancelled")

        val viewModel = createViewModel(service)

        assertFailsWith<CancellationException> {
            viewModel.handleEvent(MemoryAction.Load)
        }

        val state = viewModel.uiState.value
        assertNull(state.error)
        assertFalse(state.isLoading)
    }

    @Test
    fun `dreamer run now delegates only when maintenance is runnable`() = runTest(dispatcher) {
        val service = mockk<MemoryService>()
        coEvery { service.listFacts(any()) } returns emptyList()
        val controller = FakeMaintenanceController(
            initialStatus = MemoryMaintenanceStatus(
                preferences = MemoryMaintenancePreferences(mode = MemoryMaintenanceMode.LOCAL_ONLY),
                pendingClusters = 1,
                blockedReason = null,
            )
        )

        val viewModel = createViewModel(service, controller)
        viewModel.onAction(MemoryAction.Load)
        advanceUntilIdle()
        viewModel.onAction(MemoryAction.RunDreamerNow)
        advanceUntilIdle()

        assertEquals(MemoryMaintenanceMode.LOCAL_ONLY, viewModel.uiState.value.maintenance.mode)
        assertEquals(1, controller.runNowCount)
        assertEquals(Instant.parse("2026-05-24T12:00:00Z"), viewModel.uiState.value.maintenance.lastAttemptedAt)
        assertEquals(MemoryMaintenanceBlockReason.NO_DETERMINISTIC_ACTIONS, viewModel.uiState.value.maintenance.blockedReason)
        assertNull(viewModel.uiState.value.maintenance.lastCompletedAt)
    }

    @Test
    fun `dreamer run now saves valid edited limits before running`() = runTest(dispatcher) {
        val service = mockk<MemoryService>()
        coEvery { service.listFacts(any()) } returns emptyList()
        val controller = FakeMaintenanceController(
            initialStatus = MemoryMaintenanceStatus(
                preferences = MemoryMaintenancePreferences(
                    mode = MemoryMaintenanceMode.LOCAL_ONLY,
                    dailyCloudTokenLimit = 100,
                    maxCloudCallsPerDay = 2,
                ),
                pendingClusters = 1,
                blockedReason = null,
            )
        )

        val viewModel = createViewModel(service, controller)
        viewModel.onAction(MemoryAction.Load)
        advanceUntilIdle()
        viewModel.onAction(MemoryAction.SetDailyTokenLimit("250"))
        viewModel.onAction(MemoryAction.SetDailyCallLimit("4"))
        advanceUntilIdle()
        viewModel.onAction(MemoryAction.RunDreamerNow)
        advanceUntilIdle()

        assertEquals(250, controller.savedPreferences?.dailyCloudTokenLimit)
        assertEquals(4, controller.savedPreferences?.maxCloudCallsPerDay)
        assertEquals(1, controller.runNowCount)
    }

    @Test
    fun `dreamer run now rethrows cancellation without setting error`() = runTest(dispatcher) {
        val service = mockk<MemoryService>()
        coEvery { service.listFacts(any()) } returns emptyList()
        val controller = FakeMaintenanceController(
            initialStatus = MemoryMaintenanceStatus(
                preferences = MemoryMaintenancePreferences(mode = MemoryMaintenanceMode.LOCAL_ONLY),
                pendingClusters = 1,
                blockedReason = null,
            ),
            runFailure = CancellationException("cancelled"),
        )

        val viewModel = createViewModel(service, controller)
        viewModel.handleEvent(MemoryAction.Load)
        advanceUntilIdle()

        assertFailsWith<CancellationException> {
            viewModel.handleEvent(MemoryAction.RunDreamerNow)
        }

        assertNull(viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.maintenance.isRunningNow)
    }

    @Test
    fun `dreamer run now is ignored when deterministic work is unavailable`() = runTest(dispatcher) {
        val service = mockk<MemoryService>()
        coEvery { service.listFacts(any()) } returns emptyList()
        val controller = FakeMaintenanceController(
            initialStatus = MemoryMaintenanceStatus(
                preferences = MemoryMaintenancePreferences(mode = MemoryMaintenanceMode.LOCAL_ONLY),
                pendingClusters = 1,
                blockedReason = MemoryMaintenanceBlockReason.NO_DETERMINISTIC_ACTIONS,
            )
        )

        val viewModel = createViewModel(service, controller)
        viewModel.onAction(MemoryAction.Load)
        advanceUntilIdle()
        viewModel.onAction(MemoryAction.RunDreamerNow)
        advanceUntilIdle()

        assertEquals(0, controller.runNowCount)
        assertFalse(viewModel.uiState.value.maintenance.canRunNow)
    }

    @Test
    fun `dreamer settings preserve hidden advanced limits`() = runTest(dispatcher) {
        val service = mockk<MemoryService>()
        coEvery { service.listFacts(any()) } returns emptyList()
        val controller = FakeMaintenanceController(
            initialStatus = MemoryMaintenanceStatus(
                preferences = MemoryMaintenancePreferences(
                    mode = MemoryMaintenanceMode.OFF,
                    maxLlmCallsPerRun = 7,
                    maxFactsPerCluster = 23,
                    maxEvidenceExcerptsPerCluster = 5,
                ),
                blockedReason = MemoryMaintenanceBlockReason.DREAMER_DISABLED,
            )
        )

        val viewModel = createViewModel(service, controller)
        viewModel.onAction(MemoryAction.Load)
        advanceUntilIdle()
        viewModel.onAction(MemoryAction.SetDreamerEnabled(true))
        advanceUntilIdle()

        assertEquals(7, controller.savedPreferences?.maxLlmCallsPerRun)
        assertEquals(23, controller.savedPreferences?.maxFactsPerCluster)
        assertEquals(5, controller.savedPreferences?.maxEvidenceExcerptsPerCluster)
    }

    @Test
    fun `cloud dreamer mode is not saved while worker is deterministic only`() = runTest(dispatcher) {
        val service = mockk<MemoryService>()
        coEvery { service.listFacts(any()) } returns emptyList()
        val controller = FakeMaintenanceController()

        val viewModel = createViewModel(service, controller)
        viewModel.onAction(MemoryAction.Load)
        advanceUntilIdle()
        viewModel.onAction(MemoryAction.SelectDreamerMode(MemoryMaintenanceMode.LOCAL_THEN_CLOUD))
        advanceUntilIdle()

        assertEquals(MemoryMaintenanceMode.LOCAL_ONLY, controller.savedPreferences?.mode)
    }

    @Test
    fun `saved cloud dreamer mode is normalized for deterministic ui`() = runTest(dispatcher) {
        val service = mockk<MemoryService>()
        coEvery { service.listFacts(any()) } returns emptyList()
        val controller = FakeMaintenanceController(
            initialStatus = MemoryMaintenanceStatus(
                preferences = MemoryMaintenancePreferences(
                    mode = MemoryMaintenanceMode.LOCAL_THEN_CLOUD,
                    lastEnabledMode = MemoryMaintenanceMode.LOCAL_THEN_CLOUD,
                ),
                pendingClusters = 1,
                blockedReason = null,
            )
        )

        val viewModel = createViewModel(service, controller)
        viewModel.onAction(MemoryAction.Load)
        advanceUntilIdle()

        assertEquals(MemoryMaintenanceMode.LOCAL_ONLY, viewModel.uiState.value.maintenance.mode)
        assertEquals(MemoryMaintenanceMode.LOCAL_ONLY, viewModel.uiState.value.maintenance.lastEnabledMode)
    }

    @Test
    fun `invalid dreamer limits are rejected before saving preferences`() = runTest(dispatcher) {
        val service = mockk<MemoryService>()
        coEvery { service.listFacts(any()) } returns emptyList()
        val controller = FakeMaintenanceController()

        val viewModel = createViewModel(service, controller)
        viewModel.onAction(MemoryAction.Load)
        advanceUntilIdle()
        viewModel.onAction(MemoryAction.SetMaxTokensPerRun("oops"))
        advanceUntilIdle()
        viewModel.onAction(MemoryAction.SelectDreamerMode(MemoryMaintenanceMode.LOCAL_ONLY))
        advanceUntilIdle()

        assertEquals("Invalid Dreamer limits", viewModel.uiState.value.maintenance.fieldError)
        assertNull(controller.savedPreferences)
    }

    @Test
    fun `invalid canonical key is rejected before save`() = runTest(dispatcher) {
        val service = mockk<MemoryService>(relaxed = true)
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
                    canonicalKey = "plain-key",
                    pinned = false,
                )
            )
        )
        advanceUntilIdle()

        assertEquals("Invalid canonical key", viewModel.uiState.value.error)
        coVerify(exactly = 0) { service.createManualFact(any()) }
    }

    private fun createViewModel(
        service: MemoryService,
        maintenanceController: MemoryMaintenanceController? = null,
        ownerProvider: MemoryOwnerProvider = MemoryOwnerProvider { MemoryOwnerId("desktop-owner") },
    ): MemoryViewModel =
        MemoryViewModel(
            DI {
                bindSingleton<MemoryService> { service }
                bindSingleton<MemoryOwnerProvider> { ownerProvider }
                maintenanceController?.let { controller ->
                    bindSingleton<MemoryMaintenanceController> { controller }
                }
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

    private class FakeMaintenanceController(
        initialStatus: MemoryMaintenanceStatus = MemoryMaintenanceStatus(
            preferences = MemoryMaintenancePreferences(),
            blockedReason = MemoryMaintenanceBlockReason.DREAMER_DISABLED,
        ),
        private val runFailure: Throwable? = null,
    ) : MemoryMaintenanceController {
        var savedPreferences: MemoryMaintenancePreferences? = null
            private set
        var runNowCount: Int = 0
            private set
        private var currentStatus = initialStatus

        override suspend fun status(): MemoryMaintenanceStatus = currentStatus

        override suspend fun savePreferences(preferences: MemoryMaintenancePreferences): MemoryMaintenanceStatus {
            savedPreferences = preferences
            currentStatus = currentStatus.copy(
                preferences = preferences,
                blockedReason = if (preferences.mode == MemoryMaintenanceMode.OFF) {
                    MemoryMaintenanceBlockReason.DREAMER_DISABLED
                } else if (currentStatus.pendingClusters > 0) {
                    null
                } else {
                    MemoryMaintenanceBlockReason.NO_PENDING_CLUSTERS
                },
            )
            return currentStatus
        }

        override suspend fun runNow(): MemoryMaintenanceStatus {
            runFailure?.let { throw it }
            runNowCount += 1
            currentStatus = currentStatus.copy(
                lastAttemptedAt = Instant.parse("2026-05-24T12:00:00Z"),
                lastCompletedAt = null,
                blockedReason = MemoryMaintenanceBlockReason.NO_DETERMINISTIC_ACTIONS,
            )
            return currentStatus
        }
    }
}
