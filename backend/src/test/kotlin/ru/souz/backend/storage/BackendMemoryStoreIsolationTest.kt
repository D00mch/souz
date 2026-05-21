package ru.souz.backend.storage

import com.zaxxer.hikari.HikariDataSource
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import ru.souz.agent.memory.MemoryEntityRecord
import ru.souz.agent.memory.MemoryEvidenceRecord
import ru.souz.agent.memory.MemoryEvidenceType
import ru.souz.agent.memory.MemoryFactRecord
import ru.souz.agent.memory.MemoryFactStatus
import ru.souz.agent.memory.MemoryObjectKind
import ru.souz.agent.memory.MemoryScope
import ru.souz.agent.memory.MemoryScopeType
import ru.souz.backend.memory.BackendMemoryStore
import ru.souz.backend.storage.filesystem.FilesystemBackendMemoryStore
import ru.souz.backend.storage.memory.InMemoryBackendMemoryStore
import ru.souz.backend.storage.postgres.PostgresBackendMemoryStore
import ru.souz.backend.storage.postgres.PostgresDataSourceFactory
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.backend.storage.postgres.postgresAppConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackendMemoryStoreIsolationTest {
    @Test
    fun `memory store preserves user isolation`() = runTest {
        assertUserIsolation(InMemoryBackendMemoryStore())
    }

    @Test
    fun `filesystem store preserves user isolation`() = runTest {
        val dataDir = Files.createTempDirectory("backend-memory-filesystem")
        val store = FilesystemBackendMemoryStore(dataDir)

        assertUserIsolation(store)

        val usersRoot = dataDir.resolve("users")
        assertTrue(Files.list(usersRoot).use { it.count() } >= 2)
        assertFalse(Files.exists(usersRoot.resolve("opaque/user:a@example.com")))
    }

    @Test
    fun `postgres store preserves user isolation`() = runTest {
        val dataSource = PostgresDataSourceFactory.create(
            postgresAppConfig(schema = newPostgresSchema("backend_memory_isolation")).postgres!!
        )
        dataSource.use {
            assertUserIsolation(PostgresBackendMemoryStore(it))
        }
    }

    private suspend fun assertUserIsolation(store: BackendMemoryStore) {
        val scope = MemoryScope(MemoryScopeType.USER, "local-user")
        writeLanguageFact(store, userId = "opaque/user:a@example.com", scope = scope, language = "ru")
        writeLanguageFact(store, userId = "opaque/user:b@example.com", scope = scope, language = "en")

        val userAFacts = store.listActiveFacts("opaque/user:a@example.com", scope)
        val userBFacts = store.listActiveFacts("opaque/user:b@example.com", scope)

        assertEquals(1, userAFacts.size)
        assertEquals(1, userBFacts.size)
        assertEquals("ru", userAFacts.single().objectValueText)
        assertEquals("en", userBFacts.single().objectValueText)
    }

    private suspend fun writeLanguageFact(
        store: BackendMemoryStore,
        userId: String,
        scope: MemoryScope,
        language: String,
    ) {
        val subject = store.resolveOrUpsertEntity(
            userId = userId,
            entity = MemoryEntityRecord(
                scope = scope,
                entityType = "USER",
                canonicalName = "current_user",
                displayName = "current_user",
                normalizedKey = "user/current_user",
            ),
        )
        val evidence = store.insertEvidence(
            userId = userId,
            evidence = MemoryEvidenceRecord(
                scope = scope,
                evidenceType = MemoryEvidenceType.USER_MESSAGE,
                sourceRef = "turn:${language}",
                sourceHash = null,
                contentExcerpt = "Language is $language",
                contentJson = null,
            ),
        )
        store.insertFact(
            userId = userId,
            fact = MemoryFactRecord(
                scope = scope,
                subjectEntityId = subject.id!!,
                predicate = "prefers_language",
                objectKind = MemoryObjectKind.TEXT,
                objectEntityId = null,
                objectValueText = language,
                objectValueJson = null,
                slotKey = "user.profile.language",
                confidence = 0.95,
                status = MemoryFactStatus.ACTIVE,
                reasonToStore = "test",
            ),
            evidenceIds = listOf(evidence.id!!),
        )
    }
}
