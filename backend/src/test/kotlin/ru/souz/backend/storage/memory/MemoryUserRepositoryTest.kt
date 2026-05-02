package ru.souz.backend.storage.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class MemoryUserRepositoryTest {
    @Test
    fun `ensure user is idempotent for repeated user ids`() = runTest {
        val repository = MemoryUserRepository()

        val first = repository.ensureUser("user-a")
        val second = repository.ensureUser("user-a")

        assertEquals("user-a", first.id)
        assertEquals(first.createdAt, second.createdAt)
        assertEquals(first.lastSeenAt, second.lastSeenAt)
        assertEquals(1, repository.count())
        assertTrue(second.lastSeenAt != null)
    }
}
