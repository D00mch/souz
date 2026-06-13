package ru.souz.ambient

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AmbientSuggestionStoreTest {

    @Test
    fun `add candidate creates pending suggestion`() {
        val store = store(clock = { 1_000L })

        store.add(candidate(id = "c1", taskText = "Создай задачу"))

        val suggestion = store.pending.value.single()
        assertEquals("c1", suggestion.id)
        assertEquals(1_000L, suggestion.createdAtMs)
        assertEquals(11_000L, suggestion.expiresAtMs)
    }

    @Test
    fun `max pending suggestions is enforced`() {
        var now = 1_000L
        val store = store(clock = { now }, maxPendingSuggestions = 2)

        store.add(candidate(id = "c1", taskText = "one"))
        now += 1
        store.add(candidate(id = "c2", taskText = "two"))
        now += 1
        store.add(candidate(id = "c3", taskText = "three"))

        assertEquals(listOf("c2", "c3"), store.pending.value.map { it.id })
    }

    @Test
    fun `ttl expiry removes pending suggestions`() {
        var now = 1_000L
        val store = store(clock = { now })

        store.add(candidate(id = "c1"))
        now = 10_999L
        store.expireOld()
        assertEquals(listOf("c1"), store.pending.value.map { it.id })

        now = 11_001L
        store.expireOld()
        assertEquals(emptyList(), store.pending.value)
    }

    @Test
    fun `dismiss removes pending suggestion`() {
        val store = store(clock = { 1_000L })
        store.add(candidate(id = "c1"))

        store.dismiss("c1")

        assertEquals(emptyList(), store.pending.value)
    }

    @Test
    fun `consume removes and returns suggestion`() {
        val store = store(clock = { 1_000L })
        store.add(candidate(id = "c1"))

        val consumed = store.consume("c1")
        val second = store.consume("c1")

        assertEquals("c1", consumed?.id)
        assertNull(second)
        assertEquals(emptyList(), store.pending.value)
    }

    @Test
    fun `consume returns null for expired suggestion and removes it`() {
        var now = 1_000L
        val store = store(clock = { now })
        store.add(candidate(id = "c1"))

        now = 11_001L

        assertNull(store.consume("c1"))
        assertEquals(emptyList(), store.pending.value)
    }

    @Test
    fun `dedupe uses normalized strict equality`() {
        var now = 1_000L
        val store = store(clock = { now })

        store.add(candidate(id = "c1", taskText = "Создай, задачу!"))
        now = 2_000L
        store.add(candidate(id = "c2", taskText = "  создай   задачу "))
        store.add(candidate(id = "c3", taskText = "создай задачу завтра"))

        assertEquals(listOf("c1", "c3"), store.pending.value.map { it.id })
    }

    @Test
    fun `rejected consumed and expired suggestions are not retained`() {
        var now = 1_000L
        val store = store(clock = { now })

        store.add(candidate(id = "c1", taskText = "first"))
        store.add(candidate(id = "c2", taskText = "second"))
        store.consume("c1")
        store.dismiss("c2")
        store.add(candidate(id = "c3", taskText = "third"))
        now = 11_001L
        store.expireOld()

        assertEquals(emptyList(), store.pending.value)
    }

    private fun store(
        clock: () -> Long,
        maxPendingSuggestions: Int = 3,
        ttlMs: Long = AmbientSuggestionStoreConfig().ttlMs,
        dedupeCooldownMs: Long = AmbientSuggestionStoreConfig().dedupeCooldownMs,
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
    ): AmbientTaskCandidate = AmbientTaskCandidate(
        id = id,
        taskText = taskText,
        confidence = 0.9,
        addressedness = AmbientAddressedness.DIRECT_TO_SOUZ,
        evidenceEventIds = listOf("e1"),
    )
}
