@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ru.souz.ui.main

import androidx.lifecycle.viewModelScope
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.jetbrains.compose.resources.getStringArray
import ru.souz.agent.AgentId
import ru.souz.db.SettingsProvider
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMModel
import ru.souz.service.telegram.SelectionBroker
import ru.souz.tool.ImmediateToolPermissionBroker
import ru.souz.tool.ToolPermissionBroker
import ru.souz.tool.ToolPermissionResult
import ru.souz.tool.SelectionApprovalCandidate
import ru.souz.tool.SelectionApprovalRequest
import ru.souz.tool.SelectionApprovalSource
import ru.souz.ui.main.usecases.PermissionsUseCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class MainSelectionEventsTest {
    @Test
    fun `events from cancelled dialog cannot resolve the promoted selection`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkStatic("org.jetbrains.compose.resources.StringArrayResourcesKt")
        try {
            // Startup tips otherwise query AWT screen density in a headless test JVM.
            coEvery { getStringArray(any()) } returns listOf("tip")
            // Exercise request ID changes within a source and colliding IDs across sources.
            for (nextSourceId in listOf("chats", "contacts")) {
                val chats = SelectionSource("chats")
                val nextSource = if (nextSourceId == "chats") chats else SelectionSource(nextSourceId)
                val viewModel = viewModel(setOf(chats, nextSource))
                try {
                    runCurrent()
                    val first = backgroundScope.async { chats.broker.requestSelection("A", listOf(11L)) }
                    runCurrent()
                    val displayed = requireNotNull(viewModel.uiState.value.selectionDialog)
                    val lateChoice = MainEvent.SelectApprovalCandidate(displayed.sourceId, displayed.requestId, 11)
                    val lateDismiss = MainEvent.CancelSelectionDialog(displayed.sourceId, displayed.requestId)
                    val next = backgroundScope.async { nextSource.broker.requestSelection("B", listOf(11L)) }
                    runCurrent()
                    first.cancel()
                    runCurrent()
                    val promoted = requireNotNull(viewModel.uiState.value.selectionDialog)
                    assertEquals("B", promoted.message)
                    assertEquals(nextSourceId, promoted.sourceId)
                    assertEquals(if (nextSource === chats) 2L else 1L, promoted.requestId)

                    // A can still be rendered when B reaches the ViewModel. Neither old action owns B.
                    for (lateEvent in listOf(lateChoice, lateDismiss)) {
                        viewModel.send(lateEvent)
                        runCurrent()
                        assertFalse(next.isCompleted, "Delayed $lateEvent resolved B")
                        assertEquals(promoted, viewModel.uiState.value.selectionDialog)
                    }

                    viewModel.send(MainEvent.SelectApprovalCandidate(promoted.sourceId, promoted.requestId, 11))
                    runCurrent()
                    assertEquals(11L, next.await())
                    assertNull(viewModel.uiState.value.selectionDialog)

                    val last = backgroundScope.async { nextSource.broker.requestSelection("C", listOf(11L)) }
                    runCurrent()
                    val current = requireNotNull(viewModel.uiState.value.selectionDialog)
                    viewModel.send(MainEvent.CancelSelectionDialog(current.sourceId, current.requestId))
                    runCurrent()
                    assertNull(last.await())
                    assertNull(viewModel.uiState.value.selectionDialog)
                } finally {
                    viewModel.viewModelScope.cancel()
                }
            }
        } finally {
            unmockkStatic("org.jetbrains.compose.resources.StringArrayResourcesKt")
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `permission events from cancelled child cannot approve deny or dismiss promoted request`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkStatic("org.jetbrains.compose.resources.StringArrayResourcesKt")
        try {
            coEvery { getStringArray(any()) } returns listOf("tip")
            val broker = ImmediateToolPermissionBroker(mockk { every { safeModeEnabled } returns true })
            val viewModel = viewModel(emptySet(), broker)
            try {
                runCurrent()
                val first = backgroundScope.async { broker.requestPermission("Read synthetic A", emptyMap()) }
                runCurrent()
                val displayed = requireNotNull(viewModel.uiState.value.toolPermissionDialog)
                val next = backgroundScope.async { broker.requestPermission("Delete synthetic B", emptyMap()) }
                runCurrent()
                first.cancel()
                runCurrent()
                val promoted = requireNotNull(viewModel.uiState.value.toolPermissionDialog)
                assertEquals("Delete synthetic B", promoted.description)
                for (event in listOf(MainEvent.ApproveToolPermission(displayed.requestId),
                    MainEvent.RejectToolPermission(displayed.requestId))) {
                    viewModel.send(event)
                    runCurrent()
                    assertFalse(next.isCompleted, "Stale $event must not resolve B")
                    assertEquals(promoted, viewModel.uiState.value.toolPermissionDialog)
                }
                viewModel.send(MainEvent.RejectToolPermission(promoted.requestId))
                runCurrent()
                assertEquals("User disapproved", (next.await() as ToolPermissionResult.No).msg)
                assertNull(viewModel.uiState.value.toolPermissionDialog)
                val last = backgroundScope.async { broker.requestPermission("Read synthetic C", emptyMap()) }
                runCurrent()
                viewModel.send(MainEvent.ApproveToolPermission(requireNotNull(viewModel.uiState.value.toolPermissionDialog).requestId))
                runCurrent()
                assertEquals(ToolPermissionResult.Ok, last.await())
                assertNull(viewModel.uiState.value.toolPermissionDialog)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        } finally {
            unmockkStatic("org.jetbrains.compose.resources.StringArrayResourcesKt")
            Dispatchers.resetMain()
        }
    }

    private fun TestScope.viewModel(
        sources: Set<SelectionApprovalSource>,
        broker: ToolPermissionBroker = mockk { every { requests } returns emptyFlow() },
    ): MainViewModel {
        val settings = mockk<SettingsProvider>(relaxed = true) {
            every { gigaModel } returns LLMModel.Max
            every { embeddingsModel } returns EmbeddingsModel.GigaEmbeddings
            every { onboardingCompleted } returns true
        }
        val permissions = PermissionsUseCase(
            settingsProvider = settings,
            toolPermissionBroker = broker,
            selectionApprovalSources = sources,
            speechUseCase = mockk(),
            permissionPromptService = mockk(),
        )
        return MainViewModel(
            scopeToken = MainViewModelScope(StandardTestDispatcher(testScheduler)),
            agentFacade = mockk(relaxed = true) {
                every { activeAgentId } returns MutableStateFlow(AgentId.GRAPH)
            },
            settingsProvider = settings,
            llmBuildProfile = mockk {
                every { availableModels } returns emptyList()
                every { availableProviders } returns emptySet()
            },
            chatCommandInputSource = mockk {
                every { incomingMessages } returns emptyFlow()
                every { cleanCommands } returns emptyFlow()
            },
            chatUseCase = mockk(relaxed = true) { every { outputs } returns emptyFlow() },
            toolModifyReviewUseCase = mockk { every { outputs } returns emptyFlow() },
            voiceInputUseCase = mockk(relaxed = true) { every { outputs } returns emptyFlow() },
            speechUseCase = mockk(relaxed = true) { every { outputs } returns emptyFlow() },
            permissionsUseCase = permissions,
            localModelUiHost = mockk(relaxed = true),
            backgroundIndexRefresher = mockk(relaxed = true),
            pathOpener = mockk(),
            permissionPromptService = mockk(relaxed = true),
            appScope = backgroundScope,
            ambientTranscriptionService = mockk(),
            ambientBlockAnalyzer = mockk(),
            ambientSemanticBlockBuilder = mockk(),
            attachmentsUseCase = mockk(),
        )
    }

    private class SelectionSource(override val sourceId: String) : SelectionApprovalSource {
        val broker = SelectionBroker<Long, Long>()
        override val requests = broker.requests.map { request ->
            SelectionApprovalRequest(
                sourceId, request.id, "Choose", request.query, "OK", "Cancel",
                request.candidates.map { SelectionApprovalCandidate(it, "Candidate $it") },
                request.completion,
            )
        }

        override fun resolve(requestId: Long, selectedId: Long?) = broker.resolve(requestId, selectedId)
    }
}
