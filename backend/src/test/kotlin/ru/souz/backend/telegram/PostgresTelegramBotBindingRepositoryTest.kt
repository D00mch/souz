package ru.souz.backend.telegram

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import ru.souz.backend.crypto.sha256Hex
import ru.souz.backend.storage.postgres.PostgresDataSourceFactory
import ru.souz.backend.storage.postgres.PostgresTelegramBotBindingRepository
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.backend.storage.postgres.postgresAppConfig

class PostgresTelegramBotBindingRepositoryTest {
    @Test
    fun `postgres repository keeps one binding per chat and replaces token state`() = runTest {
        val schema = newPostgresSchema("postgres_tg_binding_chat")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres)

        dataSource.use {
            val repository = PostgresTelegramBotBindingRepository(it)
            val chatId = UUID.randomUUID()
            val created = repository.upsertForChat(
                userId = "user-a",
                chatId = chatId,
                botToken = "123456:first-token",
                botTokenHash = "123456:first-token".sha256Hex(),
                linkSecretHash = "first-link-secret".sha256Hex(),
                now = Instant.parse("2026-05-04T09:00:00Z"),
            )
            val updated = repository.upsertForChat(
                userId = "user-a",
                chatId = chatId,
                botToken = "123456:second-token",
                botTokenHash = "123456:second-token".sha256Hex(),
                linkSecretHash = "second-link-secret".sha256Hex(),
                now = Instant.parse("2026-05-04T09:05:00Z"),
            )

            assertEquals(created.id, updated.id)
            assertEquals(created.createdAt, updated.createdAt)
            assertEquals("123456:second-token", updated.botTokenEncrypted)
            assertEquals("second-link-secret".sha256Hex(), updated.linkSecretHash)
            assertEquals(0L, updated.lastUpdateId)
            assertNull(repository.findByTokenHash("123456:first-token".sha256Hex()))
            assertEquals(updated.id, repository.findByTokenHash("123456:second-token".sha256Hex())?.id)
            assertEquals(updated.id, repository.getByChat(chatId)?.id)
        }
    }

    @Test
    fun `postgres repository enforces unique token hash`() = runTest {
        val schema = newPostgresSchema("postgres_tg_binding_token")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres)

        dataSource.use {
            val repository = PostgresTelegramBotBindingRepository(it)
            repository.upsertForChat(
                userId = "user-a",
                chatId = UUID.randomUUID(),
                botToken = "123456:shared-token",
                botTokenHash = "123456:shared-token".sha256Hex(),
                linkSecretHash = "shared-link-secret".sha256Hex(),
                now = Instant.parse("2026-05-04T09:00:00Z"),
            )

            assertFails {
                repository.upsertForChat(
                    userId = "user-a",
                    chatId = UUID.randomUUID(),
                    botToken = "123456:shared-token",
                    botTokenHash = "123456:shared-token".sha256Hex(),
                    linkSecretHash = "shared-link-secret-2".sha256Hex(),
                    now = Instant.parse("2026-05-04T09:05:00Z"),
                )
            }
        }
    }

    @Test
    fun `postgres repository listEnabled excludes disabled bindings`() = runTest {
        val schema = newPostgresSchema("postgres_tg_binding_enabled")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres)

        dataSource.use {
            val repository = PostgresTelegramBotBindingRepository(it)
            val enabled = repository.upsertForChat(
                userId = "user-a",
                chatId = UUID.randomUUID(),
                botToken = "123456:enabled-token",
                botTokenHash = "123456:enabled-token".sha256Hex(),
                linkSecretHash = "enabled-link-secret".sha256Hex(),
                now = Instant.parse("2026-05-04T09:00:00Z"),
            )
            val disabled = repository.upsertForChat(
                userId = "user-a",
                chatId = UUID.randomUUID(),
                botToken = "123456:disabled-token",
                botTokenHash = "123456:disabled-token".sha256Hex(),
                linkSecretHash = "disabled-link-secret".sha256Hex(),
                now = Instant.parse("2026-05-04T09:01:00Z"),
            )

            repository.markError(
                id = disabled.id,
                lastError = "telegram_unauthorized",
                lastErrorAt = Instant.parse("2026-05-04T09:02:00Z"),
                disable = true,
            )

            assertEquals(listOf(enabled.id), repository.listEnabled().map { it.id })
        }
    }

    @Test
    fun `postgres repository persists last update id`() = runTest {
        val schema = newPostgresSchema("postgres_tg_binding_update")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres)

        dataSource.use {
            val repository = PostgresTelegramBotBindingRepository(it)
            val binding = repository.upsertForChat(
                userId = "user-a",
                chatId = UUID.randomUUID(),
                botToken = "123456:update-token",
                botTokenHash = "123456:update-token".sha256Hex(),
                linkSecretHash = "update-link-secret".sha256Hex(),
                now = Instant.parse("2026-05-04T09:00:00Z"),
            )

            repository.updateLastUpdateId(
                id = binding.id,
                lastUpdateId = 77L,
                updatedAt = Instant.parse("2026-05-04T09:03:00Z"),
            )

            val stored = repository.getByChat(binding.chatId)
            assertNotNull(stored)
            assertEquals(77L, stored.lastUpdateId)
            assertEquals(Instant.parse("2026-05-04T09:03:00Z"), stored.updatedAt)
        }
    }

    @Test
    fun `postgres repository keeps last update id monotonic for current lease owner`() = runTest {
        val schema = newPostgresSchema("postgres_tg_binding_update_owner")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres)

        dataSource.use {
            val repository = PostgresTelegramBotBindingRepository(it)
            val binding = repository.upsertForChat(
                userId = "user-a",
                chatId = UUID.randomUUID(),
                botToken = "123456:update-owner-token",
                botTokenHash = "123456:update-owner-token".sha256Hex(),
                linkSecretHash = "update-owner-link-secret".sha256Hex(),
                now = Instant.parse("2026-05-04T09:00:00Z"),
            )
            repository.tryAcquireLease(binding.id, "instance-a", Instant.parse("2026-05-04T09:00:45Z"), Instant.parse("2026-05-04T09:00:00Z"))

            repository.updateLastUpdateId(binding.id, 77L, Instant.parse("2026-05-04T09:03:00Z"), "instance-a")
            repository.updateLastUpdateId(binding.id, 55L, Instant.parse("2026-05-04T09:04:00Z"), "instance-a")
            repository.updateLastUpdateId(binding.id, 99L, Instant.parse("2026-05-04T09:05:00Z"), "instance-b")
            repository.tryAcquireLease(binding.id, "instance-b", Instant.parse("2026-05-04T09:01:45Z"), Instant.parse("2026-05-04T09:01:06Z"))
            repository.updateLastUpdateId(binding.id, 120L, Instant.parse("2026-05-04T09:06:00Z"), "instance-b")

            val stored = repository.getByChat(binding.chatId)
            assertNotNull(stored)
            assertEquals(120L, stored.lastUpdateId)
            assertEquals(Instant.parse("2026-05-04T09:06:00Z"), stored.updatedAt)
        }
    }

    @Test
    fun `postgres repository stores errors and can disable binding`() = runTest {
        val schema = newPostgresSchema("postgres_tg_binding_error")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres)

        dataSource.use {
            val repository = PostgresTelegramBotBindingRepository(it)
            val binding = repository.upsertForChat(
                userId = "user-a",
                chatId = UUID.randomUUID(),
                botToken = "123456:error-token",
                botTokenHash = "123456:error-token".sha256Hex(),
                linkSecretHash = "error-link-secret".sha256Hex(),
                now = Instant.parse("2026-05-04T09:00:00Z"),
            )

            repository.markError(binding.id, "telegram_unauthorized", Instant.parse("2026-05-04T09:04:00Z"), disable = true)

            val stored = repository.getByChat(binding.chatId)
            assertNotNull(stored)
            assertEquals("telegram_unauthorized", stored.lastError)
            assertEquals(Instant.parse("2026-05-04T09:04:00Z"), stored.lastErrorAt)
            assertEquals(false, stored.enabled)
        }
    }

    @Test
    fun `postgres repository clearError removes stored error state`() = runTest {
        val schema = newPostgresSchema("postgres_tg_binding_clear")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres)

        dataSource.use {
            val repository = PostgresTelegramBotBindingRepository(it)
            val binding = repository.upsertForChat(
                userId = "user-a",
                chatId = UUID.randomUUID(),
                botToken = "123456:clear-token",
                botTokenHash = "123456:clear-token".sha256Hex(),
                linkSecretHash = "clear-link-secret".sha256Hex(),
                now = Instant.parse("2026-05-04T09:00:00Z"),
            )

            repository.markError(binding.id, "telegram_rate_limited", Instant.parse("2026-05-04T09:04:00Z"), disable = false)
            repository.clearError(binding.id, Instant.parse("2026-05-04T09:05:00Z"))

            val stored = repository.getByChat(binding.chatId)
            assertNotNull(stored)
            assertNull(stored.lastError)
            assertNull(stored.lastErrorAt)
            assertTrue(stored.enabled)
            assertEquals(Instant.parse("2026-05-04T09:05:00Z"), stored.updatedAt)
        }
    }

    @Test
    fun `postgres repository persists telegram link metadata`() = runTest {
        val schema = newPostgresSchema("postgres_tg_binding_link")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres)

        dataSource.use {
            val repository = PostgresTelegramBotBindingRepository(it)
            val linkSecret = "claim-link-secret"
            val binding = repository.upsertForChat(
                userId = "user-a",
                chatId = UUID.randomUUID(),
                botToken = "123456:link-token",
                botTokenHash = "123456:link-token".sha256Hex(),
                linkSecretHash = linkSecret.sha256Hex(),
                botUsername = "souz_bot",
                botFirstName = "Souz",
                now = Instant.parse("2026-05-04T09:00:00Z"),
            )

            val invalidSecret = repository.claimTelegramUser(
                id = binding.id,
                linkSecretHash = "wrong-secret".sha256Hex(),
                telegramUserId = 77L,
                telegramChatId = 88L,
                telegramUsername = "alice",
                telegramFirstName = "Alice",
                telegramLastName = "Doe",
                linkedAt = Instant.parse("2026-05-04T09:05:00Z"),
            )
            val linked = repository.claimTelegramUser(
                id = binding.id,
                linkSecretHash = linkSecret.sha256Hex(),
                telegramUserId = 77L,
                telegramChatId = 88L,
                telegramUsername = "alice",
                telegramFirstName = "Alice",
                telegramLastName = "Doe",
                linkedAt = Instant.parse("2026-05-04T09:06:00Z"),
            )
            val alreadyLinked = repository.claimTelegramUser(
                id = binding.id,
                linkSecretHash = linkSecret.sha256Hex(),
                telegramUserId = 99L,
                telegramChatId = 100L,
                telegramUsername = "mallory",
                telegramFirstName = "Mallory",
                telegramLastName = "Evil",
                linkedAt = Instant.parse("2026-05-04T09:07:00Z"),
            )

            val stored = repository.getByChat(binding.chatId)
            assertIs<TelegramUserClaimResult.InvalidSecret>(invalidSecret)
            assertIs<TelegramUserClaimResult.Claimed>(linked)
            assertIs<TelegramUserClaimResult.AlreadyLinked>(alreadyLinked)
            assertNotNull(stored)
            assertEquals(77L, stored.telegramUserId)
            assertEquals(88L, stored.telegramChatId)
            assertEquals("alice", stored.telegramUsername)
            assertEquals("Alice", stored.telegramFirstName)
            assertEquals("Doe", stored.telegramLastName)
            assertEquals(Instant.parse("2026-05-04T09:06:00Z"), stored.linkedAt)
            assertNull(stored.linkSecretHash)
            assertEquals(true, stored.linked)
        }
    }

    @Test
    fun `postgres repository lease allows one owner at a time`() = runTest {
        val schema = newPostgresSchema("postgres_tg_binding_lease")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres)

        dataSource.use {
            val repository = PostgresTelegramBotBindingRepository(it)
            val binding = repository.upsertForChat(
                userId = "user-a",
                chatId = UUID.randomUUID(),
                botToken = "123456:lease-token",
                botTokenHash = "123456:lease-token".sha256Hex(),
                linkSecretHash = "lease-link-secret".sha256Hex(),
                botUsername = "souz_bot",
                botFirstName = "Souz",
                now = Instant.parse("2026-05-04T09:00:00Z"),
            )

            val firstLease = repository.tryAcquireLease(binding.id, "instance-a", Instant.parse("2026-05-04T09:00:45Z"), Instant.parse("2026-05-04T09:00:00Z"))
            val competingLease = repository.tryAcquireLease(binding.id, "instance-b", Instant.parse("2026-05-04T09:00:50Z"), Instant.parse("2026-05-04T09:00:05Z"))
            val renewedBySameOwner = repository.tryAcquireLease(binding.id, "instance-a", Instant.parse("2026-05-04T09:01:05Z"), Instant.parse("2026-05-04T09:00:20Z"))
            val acquiredAfterExpiry = repository.tryAcquireLease(binding.id, "instance-b", Instant.parse("2026-05-04T09:01:45Z"), Instant.parse("2026-05-04T09:01:06Z"))

            assertNotNull(firstLease)
            assertEquals("instance-a", firstLease.pollerOwner)
            assertEquals(null, competingLease)
            assertNotNull(renewedBySameOwner)
            assertEquals("instance-a", renewedBySameOwner.pollerOwner)
            assertNotNull(acquiredAfterExpiry)
            assertEquals("instance-b", acquiredAfterExpiry.pollerOwner)
        }
    }
}
