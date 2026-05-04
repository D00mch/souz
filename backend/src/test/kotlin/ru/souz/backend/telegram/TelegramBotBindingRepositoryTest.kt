package ru.souz.backend.telegram

import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import ru.souz.backend.storage.filesystem.FilesystemTelegramBotBindingRepository
import ru.souz.backend.storage.memory.MemoryTelegramBotBindingRepository

class TelegramBotBindingRepositoryTest {
    @Test
    fun `memory repository keeps one binding per chat and replaces token state`() = runTest {
        assertChatScopedUpsertContract(MemoryTelegramBotBindingRepository())
    }

    @Test
    fun `filesystem repository keeps one binding per chat and replaces token state`() = runTest {
        assertChatScopedUpsertContract(
            FilesystemTelegramBotBindingRepository(Files.createTempDirectory("telegram-bindings-fs-chat"))
        )
    }

    @Test
    fun `memory repository enforces unique token hash`() = runTest {
        assertUniqueTokenHashContract(MemoryTelegramBotBindingRepository())
    }

    @Test
    fun `filesystem repository enforces unique token hash`() = runTest {
        assertUniqueTokenHashContract(
            FilesystemTelegramBotBindingRepository(Files.createTempDirectory("telegram-bindings-fs-token"))
        )
    }

    @Test
    fun `memory repository listEnabled excludes disabled bindings`() = runTest {
        assertEnabledListingContract(MemoryTelegramBotBindingRepository())
    }

    @Test
    fun `filesystem repository listEnabled excludes disabled bindings`() = runTest {
        assertEnabledListingContract(
            FilesystemTelegramBotBindingRepository(Files.createTempDirectory("telegram-bindings-fs-enabled"))
        )
    }

    @Test
    fun `memory repository persists last update id`() = runTest {
        assertLastUpdateContract(MemoryTelegramBotBindingRepository())
    }

    @Test
    fun `filesystem repository persists last update id`() = runTest {
        assertLastUpdateContract(
            FilesystemTelegramBotBindingRepository(Files.createTempDirectory("telegram-bindings-fs-update"))
        )
    }

    @Test
    fun `memory repository stores errors and can disable binding`() = runTest {
        assertMarkErrorContract(MemoryTelegramBotBindingRepository())
    }

    @Test
    fun `filesystem repository stores errors and can disable binding`() = runTest {
        assertMarkErrorContract(
            FilesystemTelegramBotBindingRepository(Files.createTempDirectory("telegram-bindings-fs-error"))
        )
    }

    @Test
    fun `memory repository clearError removes stored error state`() = runTest {
        assertClearErrorContract(MemoryTelegramBotBindingRepository())
    }

    @Test
    fun `filesystem repository clearError removes stored error state`() = runTest {
        assertClearErrorContract(
            FilesystemTelegramBotBindingRepository(Files.createTempDirectory("telegram-bindings-fs-clear"))
        )
    }
}

internal suspend fun assertChatScopedUpsertContract(
    repository: TelegramBotBindingRepository,
) {
    val chatId = UUID.randomUUID()
    val created = repository.upsertForChat(
        userId = "user-a",
        chatId = chatId,
        botToken = "123456:first-token",
        botTokenHash = sha256("123456:first-token"),
        now = Instant.parse("2026-05-04T09:00:00Z"),
    )

    val updated = repository.upsertForChat(
        userId = "user-a",
        chatId = chatId,
        botToken = "123456:second-token",
        botTokenHash = sha256("123456:second-token"),
        now = Instant.parse("2026-05-04T09:05:00Z"),
    )

    assertEquals(created.id, updated.id)
    assertEquals(created.createdAt, updated.createdAt)
    assertEquals("123456:second-token", updated.botToken)
    assertEquals(0L, updated.lastUpdateId)
    assertNull(repository.findByTokenHash(sha256("123456:first-token")))
    assertEquals(updated.id, repository.findByTokenHash(sha256("123456:second-token"))?.id)
    assertEquals(updated.id, repository.getByChat(chatId)?.id)
}

internal suspend fun assertUniqueTokenHashContract(
    repository: TelegramBotBindingRepository,
) {
    repository.upsertForChat(
        userId = "user-a",
        chatId = UUID.randomUUID(),
        botToken = "123456:shared-token",
        botTokenHash = sha256("123456:shared-token"),
        now = Instant.parse("2026-05-04T09:00:00Z"),
    )

    assertFails {
        repository.upsertForChat(
            userId = "user-a",
            chatId = UUID.randomUUID(),
            botToken = "123456:shared-token",
            botTokenHash = sha256("123456:shared-token"),
            now = Instant.parse("2026-05-04T09:05:00Z"),
        )
    }
}

internal suspend fun assertEnabledListingContract(
    repository: TelegramBotBindingRepository,
) {
    val enabled = repository.upsertForChat(
        userId = "user-a",
        chatId = UUID.randomUUID(),
        botToken = "123456:enabled-token",
        botTokenHash = sha256("123456:enabled-token"),
        now = Instant.parse("2026-05-04T09:00:00Z"),
    )
    val disabled = repository.upsertForChat(
        userId = "user-a",
        chatId = UUID.randomUUID(),
        botToken = "123456:disabled-token",
        botTokenHash = sha256("123456:disabled-token"),
        now = Instant.parse("2026-05-04T09:01:00Z"),
    )

    repository.markError(
        id = disabled.id,
        lastError = "telegram_unauthorized",
        lastErrorAt = Instant.parse("2026-05-04T09:02:00Z"),
        disable = true,
    )

    val listed = repository.listEnabled()

    assertEquals(listOf(enabled.id), listed.map { it.id })
}

internal suspend fun assertLastUpdateContract(
    repository: TelegramBotBindingRepository,
) {
    val binding = repository.upsertForChat(
        userId = "user-a",
        chatId = UUID.randomUUID(),
        botToken = "123456:update-token",
        botTokenHash = sha256("123456:update-token"),
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

internal suspend fun assertMarkErrorContract(
    repository: TelegramBotBindingRepository,
) {
    val binding = repository.upsertForChat(
        userId = "user-a",
        chatId = UUID.randomUUID(),
        botToken = "123456:error-token",
        botTokenHash = sha256("123456:error-token"),
        now = Instant.parse("2026-05-04T09:00:00Z"),
    )

    repository.markError(
        id = binding.id,
        lastError = "telegram_unauthorized",
        lastErrorAt = Instant.parse("2026-05-04T09:04:00Z"),
        disable = true,
    )

    val stored = repository.getByChat(binding.chatId)

    assertNotNull(stored)
    assertEquals("telegram_unauthorized", stored.lastError)
    assertEquals(Instant.parse("2026-05-04T09:04:00Z"), stored.lastErrorAt)
    assertEquals(false, stored.enabled)
}

internal suspend fun assertClearErrorContract(
    repository: TelegramBotBindingRepository,
) {
    val binding = repository.upsertForChat(
        userId = "user-a",
        chatId = UUID.randomUUID(),
        botToken = "123456:clear-token",
        botTokenHash = sha256("123456:clear-token"),
        now = Instant.parse("2026-05-04T09:00:00Z"),
    )

    repository.markError(
        id = binding.id,
        lastError = "telegram_rate_limited",
        lastErrorAt = Instant.parse("2026-05-04T09:04:00Z"),
        disable = false,
    )
    repository.clearError(
        id = binding.id,
        updatedAt = Instant.parse("2026-05-04T09:05:00Z"),
    )

    val stored = repository.getByChat(binding.chatId)

    assertNotNull(stored)
    assertNull(stored.lastError)
    assertNull(stored.lastErrorAt)
    assertTrue(stored.enabled)
    assertEquals(Instant.parse("2026-05-04T09:05:00Z"), stored.updatedAt)
}

internal fun sha256(token: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
