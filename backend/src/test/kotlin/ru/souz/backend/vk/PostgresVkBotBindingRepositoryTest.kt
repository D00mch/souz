package ru.souz.backend.vk

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import ru.souz.backend.storage.postgres.PostgresDataSourceFactory
import ru.souz.backend.storage.postgres.PostgresVkBotBindingRepository
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.backend.storage.postgres.postgresAppConfig

class PostgresVkBotBindingRepositoryTest {
    @Test
    fun `lease renewal takeover and rebinding fence claims checkpoints and errors`() = runTest {
        PostgresDataSourceFactory.create(postgresAppConfig(newPostgresSchema("vk_lease")).postgres).use { db ->
            val repo = PostgresVkBotBindingRepository(db)
            val chat = UUID.randomUUID()
            val now = Instant.parse("2026-01-01T00:00:00Z")
            val binding = repo.upsertForChat("user", chat, "cipher", "token-hash", "secret", 123, "group", now)
            val id = binding.id
            assertNotNull(repo.tryAcquireLease(id, "a", now.plusSeconds(45), now))
            assertNull(repo.tryAcquireLease(id, "b", now.plusSeconds(45), now))
            assertTrue(repo.renewLease(id, "a", now.plusSeconds(90), now.plusSeconds(30)))
            assertNull(repo.tryAcquireLease(id, "b", now.plusSeconds(90), now.plusSeconds(46)))
            assertNull(repo.claimVkUser(id, "b", "secret", 1, 7, 7, null, null, now))
            assertNull(repo.claimVkUser(id, "a", "wrong", 1, 7, 7, null, null, now))
            assertNotNull(repo.claimVkUser(id, "a", "secret", 1, 7, 7, null, null, now))
            assertNull(repo.claimVkUser(id, "a", "secret", 2, 8, 8, null, null, now))
            repo.updateLastTs(id, "a", "opaque-cursor", now)
            val expired = now.plusSeconds(91)
            repo.updateLastTs(id, "a", "stale", expired)
            assertEquals("opaque-cursor", repo.getByChat(chat)?.lastTs)
            assertFalse(repo.renewLease(id, "a", expired.plusSeconds(45), expired))
            assertNotNull(repo.tryAcquireLease(id, "b", expired.plusSeconds(45), expired))
            repo.markError(id, "a", "stale error", expired, disable = true)
            repo.updateLastTs(id, "b", "1", expired)
            assertTrue(repo.getByChat(chat)?.active == true)
            assertEquals("1", repo.getByChat(chat)?.lastTs)
            val replacement = repo.upsertForChat("user", chat, "cipher2", "token-hash", "new-secret", 123, "group", expired)
            assertTrue(replacement.id != id)
            assertFalse(repo.hasActiveLease(id, "b", expired))
            assertNull(repo.tryAcquireLease(id, "b", expired.plusSeconds(45), expired))
            repo.markError(id, "b", "stale error", expired, disable = true)
            assertTrue(repo.getByChat(chat)?.enabled == true)
            assertFalse(repo.getByChat(chat)?.linked == true)
        }
    }
}
