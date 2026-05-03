package ru.souz.backend.storage.filesystem

import java.nio.file.Files
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class FilesystemUserRepositoryTest {
    @Test
    fun `filesystem user repository stores encoded path segment and stable user json`() = runTest {
        val dataDir = Files.createTempDirectory("filesystem-users")
        val userId = "opaque/user:42@example.com"
        val repository = FilesystemUserRepository(dataDir)

        val first = repository.ensureUser(userId)
        val second = repository.ensureUser(userId)
        val userDirectories = Files.list(dataDir.resolve("users")).use { stream -> stream.toList() }
        val storedDirectory = userDirectories.single()
        val storedJson = Files.readString(storedDirectory.resolve("user.json"))

        assertEquals(first.createdAt, second.createdAt)
        assertEquals(1, userDirectories.size)
        assertNotEquals(userId, storedDirectory.name)
        assertTrue(Files.exists(dataDir.resolve("users").resolve(userId)).not())
        assertTrue(storedJson.contains(""""id":"$userId""""))
        assertTrue(storedJson.contains(""""createdAt":""""))
        assertTrue(storedJson.contains(""""lastSeenAt":""""))
    }
}
