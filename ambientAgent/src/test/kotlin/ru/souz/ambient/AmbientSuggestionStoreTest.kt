package ru.souz.ambient

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AmbientSuggestionStoreTest {

    @Test
    fun `add candidate creates pending suggestion`() {
        val store = store(clock = { 1_000L })

        store.addCandidate(candidate(id = "c1", taskText = "Создай задачу"))

        val suggestion = store.suggestions.value.single()
        assertEquals("c1", suggestion.id)
        assertEquals(AmbientSuggestionStatus.PENDING, suggestion.status)
        assertEquals(1_000L, suggestion.createdAtMs)
        assertEquals(11_000L, suggestion.expiresAtMs)
    }

    @Test
    fun `duplicate candidate is ignored within cooldown`() {
        var now = 1_000L
        val store = store(clock = { now })

        store.addCandidate(candidate(id = "c1", taskText = "Создай задачу", evidence = listOf("e1")))
        now = 2_000L
        store.addCandidate(candidate(id = "c2", taskText = "  создай   задачу ", evidence = listOf("e1")))

        assertEquals(listOf("c1"), store.suggestions.value.map { it.id })
    }

    @Test
    fun `completed same task is ignored within cooldown even when evidence differs`() {
        var now = 1_000L
        val store = store(clock = { now })

        store.addCandidate(candidate(id = "c1", taskText = "Создай задачу", evidence = listOf("e1")))
        assertEquals("c1", store.accept("c1")?.id)
        store.markExecuting("c1")
        store.markCompleted("c1")

        now = 2_000L
        store.addCandidate(candidate(id = "c2", taskText = "  создай   задачу ", evidence = listOf("e2")))

        assertEquals(listOf("c1"), store.suggestions.value.map { it.id })
        assertEquals(AmbientSuggestionStatus.COMPLETED, store.suggestions.value.single().status)
    }

    @Test
    fun `completed similar task is ignored within cooldown`() {
        var now = 1_000L
        val store = store(clock = { now })

        store.addCandidate(candidate(id = "c1", taskText = "Проверь мой календарь на сегодня", evidence = listOf("e1")))
        assertEquals("c1", store.accept("c1")?.id)
        store.markExecuting("c1")
        store.markCompleted("c1")

        now = 2_000L
        store.addCandidate(candidate(id = "c2", taskText = "Проверь мой календарь на 3 июня 2026", evidence = listOf("e2")))

        assertEquals(listOf("c1"), store.suggestions.value.map { it.id })
    }

    @Test
    fun `same task after cooldown can be added`() {
        var now = 1_000L
        val store = store(clock = { now }, dedupeCooldownMs = 1_000L)

        store.addCandidate(candidate(id = "c1", taskText = "Создай задачу", evidence = listOf("e1")))
        now = 2_500L
        store.addCandidate(candidate(id = "c2", taskText = "создай задачу", evidence = listOf("e1")))

        assertEquals(listOf("c1", "c2"), store.suggestions.value.map { it.id })
    }

    @Test
    fun `max pending suggestions enforced`() {
        var now = 1_000L
        val store = store(clock = { now }, maxPendingSuggestions = 2)

        store.addCandidate(candidate(id = "c1", taskText = "one"))
        now += 1
        store.addCandidate(candidate(id = "c2", taskText = "two"))
        now += 1
        store.addCandidate(candidate(id = "c3", taskText = "three"))

        assertEquals(listOf("c2", "c3"), store.suggestions.value.map { it.id })
    }

    @Test
    fun `expiration marks old pending suggestions expired`() {
        var now = 1_000L
        val store = store(clock = { now })

        store.addCandidate(candidate(id = "c1"))
        now = 10_999L
        store.expireOld()

        assertEquals(AmbientSuggestionStatus.PENDING, store.suggestions.value.single().status)

        now = 11_001L
        store.expireOld()

        assertEquals(AmbientSuggestionStatus.EXPIRED, store.suggestions.value.single().status)
    }

    @Test
    fun `accept reject and execution transitions`() {
        val store = store(clock = { 1_000L })
        store.addCandidate(candidate(id = "c1"))
        store.addCandidate(candidate(id = "c2", taskText = "second"))

        assertEquals("c1", store.accept("c1")?.id)
        store.markExecuting("c1")
        store.markCompleted("c1")
        store.reject("c2")

        assertEquals(AmbientSuggestionStatus.COMPLETED, store.suggestions.value.first { it.id == "c1" }.status)
        assertEquals(AmbientSuggestionStatus.REJECTED, store.suggestions.value.first { it.id == "c2" }.status)
    }

    @Test
    fun `double accept returns null after first accept`() {
        val store = store(clock = { 1_000L })
        store.addCandidate(candidate(id = "c1"))

        val first = store.accept("c1")
        val second = store.accept("c1")

        assertEquals("c1", first?.id)
        assertNull(second)
    }

    @Test
    fun `clear removes suggestions`() {
        val store = store(clock = { 1_000L })
        store.addCandidate(candidate(id = "c1"))

        store.clear()

        assertEquals(emptyList(), store.suggestions.value)
    }

    private fun store(
        clock: () -> Long,
        maxPendingSuggestions: Int = 3,
        ttlMs: Long = AmbientSuggestionStoreConfig().ttlMs,
        dedupeCooldownMs: Long = 2 * 60 * 1_000L,
    ): InMemoryAmbientSuggestionStore = InMemoryAmbientSuggestionStore(
        clock = clock,
        config = AmbientSuggestionStoreConfig(
            maxPendingSuggestions = maxPendingSuggestions,
            ttlMs = ttlMs,
            dedupeCooldownMs = dedupeCooldownMs,
        ),
    )

    private fun candidate(
        id: String = "c1",
        taskText: String = "Напомнить купить хлеб",
        evidence: List<String> = listOf("e1"),
    ): AmbientTaskCandidate = AmbientTaskCandidate(
        id = id,
        title = "Title",
        taskText = taskText,
        suggestionText = "Похоже, я могу помочь: $taskText",
        confidence = 0.9,
        addressedness = AmbientAddressedness.DIRECT_TO_SOUZ,
        matchedCapabilityIds = listOf("tool:CALENDAR:create_event"),
        missingSlots = emptyList(),
        risk = AmbientTaskRisk.LOW,
        requiresConfirmation = true,
        evidenceEventIds = evidence,
        reason = "test",
    )
}
