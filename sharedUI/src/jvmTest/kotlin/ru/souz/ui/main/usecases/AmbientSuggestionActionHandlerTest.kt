package ru.souz.ui.main.usecases

import kotlinx.coroutines.test.runTest
import ru.souz.ambient.AmbientAddressedness
import ru.souz.ambient.AmbientTaskCandidate
import ru.souz.ambient.InMemoryAmbientSuggestionStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AmbientSuggestionActionHandlerTest {

    @Test
    fun `accept dispatches candidate once and removes it`() = runTest {
        val store = InMemoryAmbientSuggestionStore(clock = { 1_000L })
        store.add(candidate("c1"))
        val dispatched = mutableListOf<String>()
        val handler = AmbientSuggestionActionHandler(
            store = store,
            executor = { dispatched += it.taskText },
        )

        assertTrue(handler.accept("c1"))
        assertFalse(handler.accept("c1"))

        assertEquals(listOf("Task c1"), dispatched)
        assertEquals(emptyList(), store.pending.value)
    }

    @Test
    fun `reject removes pending suggestion and does not dispatch`() = runTest {
        val store = InMemoryAmbientSuggestionStore(clock = { 1_000L })
        store.add(candidate("c1"))
        var dispatched = false
        val handler = AmbientSuggestionActionHandler(
            store = store,
            executor = { dispatched = true },
        )

        handler.reject("c1")

        assertFalse(dispatched)
        assertEquals(emptyList(), store.pending.value)
    }

    @Test
    fun `dispatch failure does not retain failed suggestion`() = runTest {
        val store = InMemoryAmbientSuggestionStore(clock = { 1_000L })
        store.add(candidate("c1"))
        val handler = AmbientSuggestionActionHandler(
            store = store,
            executor = { error("dispatch failed") },
        )

        assertFalse(handler.accept("c1"))

        assertEquals(emptyList(), store.pending.value)
    }

    private fun candidate(id: String): AmbientTaskCandidate = AmbientTaskCandidate(
        id = id,
        taskText = "Task $id",
        confidence = 0.9,
        addressedness = AmbientAddressedness.DIRECT_TO_SOUZ,
        evidenceEventIds = listOf("e1"),
    )
}
