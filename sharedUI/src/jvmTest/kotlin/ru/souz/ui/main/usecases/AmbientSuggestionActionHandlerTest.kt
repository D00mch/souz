package ru.souz.ui.main.usecases

import kotlinx.coroutines.test.runTest
import ru.souz.ambient.AmbientAddressedness
import ru.souz.ambient.AmbientSuggestionStatus
import ru.souz.ambient.AmbientTaskCandidate
import ru.souz.ambient.AmbientTaskRisk
import ru.souz.ambient.InMemoryAmbientSuggestionStore
import kotlin.test.Test
import kotlin.test.assertEquals

class AmbientSuggestionActionHandlerTest {

    @Test
    fun `accept dispatches candidate once and marks completed`() = runTest {
        val store = InMemoryAmbientSuggestionStore(clock = { 1_000L })
        store.addCandidate(candidate("c1"))
        val dispatched = mutableListOf<String>()
        val handler = AmbientSuggestionActionHandler(
            store = store,
            executor = { dispatched += it.taskText },
        )

        handler.accept("c1")
        handler.accept("c1")

        assertEquals(listOf("Task c1"), dispatched)
        assertEquals(AmbientSuggestionStatus.COMPLETED, store.suggestions.value.single().status)
    }

    @Test
    fun `reject does not dispatch`() = runTest {
        val store = InMemoryAmbientSuggestionStore(clock = { 1_000L })
        store.addCandidate(candidate("c1"))
        var dispatched = false
        val handler = AmbientSuggestionActionHandler(
            store = store,
            executor = { dispatched = true },
        )

        handler.reject("c1")

        assertEquals(false, dispatched)
        assertEquals(AmbientSuggestionStatus.REJECTED, store.suggestions.value.single().status)
    }

    @Test
    fun `dispatch failure marks failed`() = runTest {
        val store = InMemoryAmbientSuggestionStore(clock = { 1_000L })
        store.addCandidate(candidate("c1"))
        val handler = AmbientSuggestionActionHandler(
            store = store,
            executor = { error("dispatch failed") },
        )

        handler.accept("c1")

        val suggestion = store.suggestions.value.single()
        assertEquals(AmbientSuggestionStatus.FAILED, suggestion.status)
        assertEquals("dispatch failed", suggestion.failureReason)
    }

    private fun candidate(id: String): AmbientTaskCandidate = AmbientTaskCandidate(
        id = id,
        title = "Title $id",
        taskText = "Task $id",
        suggestionText = "Похоже, я могу помочь: Task $id",
        confidence = 0.9,
        addressedness = AmbientAddressedness.DIRECT_TO_SOUZ,
        matchedCapabilityIds = listOf("tool:FILES:list_files"),
        missingSlots = emptyList(),
        risk = AmbientTaskRisk.LOW,
        requiresConfirmation = true,
        evidenceEventIds = listOf("e1"),
        reason = "test",
    )
}
