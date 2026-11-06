package ru.souz.ui.main.usecases

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.souz.db.SettingsProvider
import ru.souz.service.telegram.SelectionBroker
import ru.souz.tool.SelectionApprovalCandidate
import ru.souz.tool.SelectionApprovalRequest
import ru.souz.tool.SelectionApprovalSource
import ru.souz.tool.ToolPermissionBroker
import ru.souz.ui.main.MainState
import ru.souz.ui.main.SelectionDialogData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class PermissionsUseCaseTest {
    @Test
    fun `different brokers queue selections with independent colliding request ids`() = runTest {
        val fixture = fixture()
        val first = async { fixture.chats.broker.requestSelection("chat", listOf(11L)) }
        runCurrent()
        val second = async { fixture.contacts.broker.requestSelection("contact", listOf(22L)) }
        runCurrent()

        assertEquals("chats", fixture.state.selectionDialog?.sourceId)
        assertFalse(second.isCompleted)
        fixture.useCase.resolveSelectionDialog("chats", 1, 11)
        runCurrent()
        assertEquals(11L, first.await())
        assertEquals("contacts", fixture.state.selectionDialog?.sourceId)
        assertEquals(1L, fixture.state.selectionDialog?.requestId)

        // A delayed duplicate resolution must not clear another source's prompt.
        fixture.useCase.resolveSelectionDialog("chats", 1, 11)
        runCurrent()
        assertEquals("contacts", fixture.state.selectionDialog?.sourceId)
        fixture.useCase.resolveSelectionDialog("contacts", 1, null)
        runCurrent()
        assertNull(second.await())
        assertNull(fixture.state.selectionDialog)
        assertEquals(listOf("chats", null, "contacts", null), fixture.dialogs.map { it?.sourceId })
    }

    @Test
    fun `cancelling visible and queued children skips stale prompts and admits later work`() = runTest {
        val fixture = fixture()
        val first = async { fixture.chats.broker.requestSelection("chat", listOf(11L)) }
        runCurrent()
        val queued = async { fixture.contacts.broker.requestSelection("cancelled", listOf(22L)) }
        runCurrent()
        queued.cancel()
        runCurrent()
        val next = async { fixture.contacts.broker.requestSelection("next", listOf(33L)) }
        runCurrent()
        first.cancel()
        runCurrent()

        assertEquals("contacts", fixture.state.selectionDialog?.sourceId)
        assertEquals("next", fixture.state.selectionDialog?.message)
        assertEquals(2L, fixture.state.selectionDialog?.requestId)
        fixture.useCase.resolveSelectionDialog("contacts", 2, 33)
        runCurrent()
        assertEquals(33L, next.await())
        assertNull(fixture.state.selectionDialog)
        assertEquals(listOf("chat", "next"), fixture.dialogs.filterNotNull().map { it.message })
    }

    private fun TestScope.fixture(): Fixture {
        val chats = Source("chats")
        val contacts = Source("contacts")
        val useCase = PermissionsUseCase(
            settingsProvider = mockk<SettingsProvider> { every { notificationSoundEnabled } returns false },
            toolPermissionBroker = mockk<ToolPermissionBroker> { every { requests } returns emptyFlow() },
            selectionApprovalSources = setOf(chats, contacts),
            speechUseCase = mockk(),
            permissionPromptService = mockk(),
        )
        val fixture = Fixture(useCase, chats, contacts)
        backgroundScope.launch {
            useCase.outputs.collect { output ->
                if (output is MainUseCaseOutput.State) {
                    fixture.state = output.reduce(fixture.state)
                    fixture.dialogs += fixture.state.selectionDialog
                }
            }
        }
        useCase.start(backgroundScope)
        return fixture
    }

    private class Fixture(val useCase: PermissionsUseCase, val chats: Source, val contacts: Source) {
        var state = MainState()
        val dialogs = mutableListOf<SelectionDialogData?>()
    }

    private class Source(override val sourceId: String) : SelectionApprovalSource {
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
