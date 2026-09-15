package ru.souz.backend.vk

import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal suspend fun assertChatScopedUpsertContract(repository: VkBotBindingRepository) {
    val chatId = UUID.randomUUID()
    val created = repository.upsertForChat(
        userId = "user-a",
        chatId = chatId,
        groupToken = "vk1:first-token",
        groupTokenHash = sha256("vk1:first-token"),
        linkSecretHash = sha256("first-link-secret"),
        vkGroupId = 111L,
        vkGroupName = "First Group",
        now = Instant.parse("2026-05-04T09:00:00Z"),
    )
    val updated = repository.upsertForChat(
        userId = "user-a",
        chatId = chatId,
        groupToken = "vk1:second-token",
        groupTokenHash = sha256("vk1:second-token"),
        linkSecretHash = sha256("second-link-secret"),
        vkGroupId = 222L,
        vkGroupName = "Second Group",
        now = Instant.parse("2026-05-04T09:05:00Z"),
    )

    assertEquals(created.id, updated.id)
    assertEquals(created.createdAt, updated.createdAt)
    assertEquals("vk1:second-token", updated.groupTokenEncrypted)
    assertEquals(sha256("second-link-secret"), updated.linkSecretHash)
    assertEquals(222L, updated.vkGroupId)
    assertNull(updated.lastTs)
    assertNull(repository.findByTokenHash(sha256("vk1:first-token")))
    assertEquals(updated.id, repository.findByTokenHash(sha256("vk1:second-token"))?.id)
    assertEquals(updated.id, repository.getByChat(chatId)?.id)
}

internal suspend fun assertUniqueTokenHashContract(repository: VkBotBindingRepository) {
    repository.upsertForChat(
        userId = "user-a",
        chatId = UUID.randomUUID(),
        groupToken = "vk1:shared-token",
        groupTokenHash = sha256("vk1:shared-token"),
        linkSecretHash = sha256("shared-link-secret"),
        vkGroupId = 333L,
        vkGroupName = "Shared Group",
        now = Instant.parse("2026-05-04T09:00:00Z"),
    )

    assertFails {
        repository.upsertForChat(
            userId = "user-a",
            chatId = UUID.randomUUID(),
            groupToken = "vk1:shared-token",
            groupTokenHash = sha256("vk1:shared-token"),
            linkSecretHash = sha256("shared-link-secret-2"),
            vkGroupId = 334L,
            vkGroupName = "Shared Group 2",
            now = Instant.parse("2026-05-04T09:05:00Z"),
        )
    }
}

internal suspend fun assertEnabledListingContract(repository: VkBotBindingRepository) {
    val enabled = repository.upsertForChat(
        userId = "user-a",
        chatId = UUID.randomUUID(),
        groupToken = "vk1:enabled-token",
        groupTokenHash = sha256("vk1:enabled-token"),
        linkSecretHash = sha256("enabled-link-secret"),
        vkGroupId = 401L,
        vkGroupName = "Enabled Group",
        now = Instant.parse("2026-05-04T09:00:00Z"),
    )
    val disabled = repository.upsertForChat(
        userId = "user-a",
        chatId = UUID.randomUUID(),
        groupToken = "vk1:disabled-token",
        groupTokenHash = sha256("vk1:disabled-token"),
        linkSecretHash = sha256("disabled-link-secret"),
        vkGroupId = 402L,
        vkGroupName = "Disabled Group",
        now = Instant.parse("2026-05-04T09:01:00Z"),
    )

    repository.markError(
        id = disabled.id,
        lastError = "vk_unauthorized",
        lastErrorAt = Instant.parse("2026-05-04T09:02:00Z"),
        disable = true,
    )

    // Checked by membership, not exact list equality — contract functions may run against a
    // schema shared with other contract checks that also leave enabled rows behind.
    val listedIds = repository.listEnabled().map { it.id }
    assertTrue(enabled.id in listedIds)
    assertTrue(disabled.id !in listedIds)
}

internal suspend fun assertLastTsContract(repository: VkBotBindingRepository) {
    val binding = repository.upsertForChat(
        userId = "user-a",
        chatId = UUID.randomUUID(),
        groupToken = "vk1:update-token",
        groupTokenHash = sha256("vk1:update-token"),
        linkSecretHash = sha256("update-link-secret"),
        vkGroupId = 501L,
        vkGroupName = "Update Group",
        now = Instant.parse("2026-05-04T09:00:00Z"),
    )

    repository.updateLastTs(
        id = binding.id,
        lastTs = "77",
        updatedAt = Instant.parse("2026-05-04T09:03:00Z"),
    )

    val stored = repository.getByChat(binding.chatId)
    assertNotNull(stored)
    assertEquals("77", stored.lastTs)
    assertEquals(Instant.parse("2026-05-04T09:03:00Z"), stored.updatedAt)
}

/**
 * Unlike Telegram's numeric `last_update_id` (kept monotonic via `greatest()`), VK's `last_ts` is
 * an opaque string batch cursor with no ordering guarantee, so `updateLastTs` always applies the
 * given value — including a value that would look "smaller" — as long as the owner matches; a
 * mismatched owner is still rejected, same as Telegram.
 */
internal suspend fun assertLeaseScopedLastTsContract(repository: VkBotBindingRepository) {
    val binding = repository.upsertForChat(
        userId = "user-a",
        chatId = UUID.randomUUID(),
        groupToken = "vk1:update-owner-token",
        groupTokenHash = sha256("vk1:update-owner-token"),
        linkSecretHash = sha256("update-owner-link-secret"),
        vkGroupId = 502L,
        vkGroupName = "Update Owner Group",
        now = Instant.parse("2026-05-04T09:00:00Z"),
    )
    repository.tryAcquireLease(binding.id, "instance-a", Instant.parse("2026-05-04T09:00:45Z"), Instant.parse("2026-05-04T09:00:00Z"))

    repository.updateLastTs(binding.id, "77", Instant.parse("2026-05-04T09:03:00Z"), "instance-a")
    // A numerically "smaller" value from the current owner still overwrites — no greatest() guard.
    repository.updateLastTs(binding.id, "55", Instant.parse("2026-05-04T09:04:00Z"), "instance-a")
    // A different, non-owning instance's write is rejected outright.
    repository.updateLastTs(binding.id, "99", Instant.parse("2026-05-04T09:05:00Z"), "instance-b")

    val storedBeforeHandoff = repository.getByChat(binding.chatId)
    assertNotNull(storedBeforeHandoff)
    assertEquals("55", storedBeforeHandoff.lastTs)

    // The original lease has since expired; instance-b now legitimately takes over.
    repository.tryAcquireLease(binding.id, "instance-b", Instant.parse("2026-05-04T09:01:45Z"), Instant.parse("2026-05-04T09:01:06Z"))
    repository.updateLastTs(binding.id, "10", Instant.parse("2026-05-04T09:06:00Z"), "instance-b")

    val storedAfterHandoff = repository.getByChat(binding.chatId)
    assertNotNull(storedAfterHandoff)
    assertEquals("10", storedAfterHandoff.lastTs)
    assertEquals(Instant.parse("2026-05-04T09:06:00Z"), storedAfterHandoff.updatedAt)
}

internal suspend fun assertMarkErrorContract(repository: VkBotBindingRepository) {
    val binding = repository.upsertForChat(
        userId = "user-a",
        chatId = UUID.randomUUID(),
        groupToken = "vk1:error-token",
        groupTokenHash = sha256("vk1:error-token"),
        linkSecretHash = sha256("error-link-secret"),
        vkGroupId = 601L,
        vkGroupName = "Error Group",
        now = Instant.parse("2026-05-04T09:00:00Z"),
    )

    repository.markError(binding.id, "vk_unauthorized", Instant.parse("2026-05-04T09:04:00Z"), disable = true)

    val stored = repository.getByChat(binding.chatId)
    assertNotNull(stored)
    assertEquals("vk_unauthorized", stored.lastError)
    assertEquals(Instant.parse("2026-05-04T09:04:00Z"), stored.lastErrorAt)
    assertEquals(false, stored.enabled)
}

internal suspend fun assertClearErrorContract(repository: VkBotBindingRepository) {
    val binding = repository.upsertForChat(
        userId = "user-a",
        chatId = UUID.randomUUID(),
        groupToken = "vk1:clear-token",
        groupTokenHash = sha256("vk1:clear-token"),
        linkSecretHash = sha256("clear-link-secret"),
        vkGroupId = 701L,
        vkGroupName = "Clear Group",
        now = Instant.parse("2026-05-04T09:00:00Z"),
    )

    repository.markError(binding.id, "vk_rate_limited", Instant.parse("2026-05-04T09:04:00Z"), disable = false)
    repository.clearError(binding.id, Instant.parse("2026-05-04T09:05:00Z"))

    val stored = repository.getByChat(binding.chatId)
    assertNotNull(stored)
    assertNull(stored.lastError)
    assertNull(stored.lastErrorAt)
    assertTrue(stored.enabled)
    assertEquals(Instant.parse("2026-05-04T09:05:00Z"), stored.updatedAt)
}

internal suspend fun assertClaimVkUserContract(repository: VkBotBindingRepository) {
    val linkSecret = "claim-link-secret"
    val binding = repository.upsertForChat(
        userId = "user-a",
        chatId = UUID.randomUUID(),
        groupToken = "vk1:link-token",
        groupTokenHash = sha256("vk1:link-token"),
        linkSecretHash = sha256(linkSecret),
        vkGroupId = 801L,
        vkGroupName = "Link Group",
        now = Instant.parse("2026-05-04T09:00:00Z"),
    )

    val invalidSecret = repository.claimVkUser(
        id = binding.id,
        linkSecretHash = sha256("wrong-secret"),
        vkUserId = 77L,
        vkPeerId = 77L,
        vkFirstName = "Alice",
        vkLastName = "Doe",
        linkedAt = Instant.parse("2026-05-04T09:05:00Z"),
    )
    val linked = repository.claimVkUser(
        id = binding.id,
        linkSecretHash = sha256(linkSecret),
        vkUserId = 77L,
        vkPeerId = 77L,
        vkFirstName = "Alice",
        vkLastName = "Doe",
        linkedAt = Instant.parse("2026-05-04T09:06:00Z"),
    )
    val alreadyLinked = repository.claimVkUser(
        id = binding.id,
        linkSecretHash = sha256(linkSecret),
        vkUserId = 99L,
        vkPeerId = 99L,
        vkFirstName = "Mallory",
        vkLastName = "Evil",
        linkedAt = Instant.parse("2026-05-04T09:07:00Z"),
    )

    val stored = repository.getByChat(binding.chatId)
    assertIs<VkUserClaimResult.InvalidSecret>(invalidSecret)
    assertIs<VkUserClaimResult.Claimed>(linked)
    assertIs<VkUserClaimResult.AlreadyLinked>(alreadyLinked)
    assertNotNull(stored)
    assertEquals(77L, stored.vkUserId)
    assertEquals(77L, stored.vkPeerId)
    assertEquals("Alice", stored.vkFirstName)
    assertEquals("Doe", stored.vkLastName)
    assertEquals(Instant.parse("2026-05-04T09:06:00Z"), stored.linkedAt)
    assertNull(stored.linkSecretHash)
    assertEquals(true, stored.linked)
}

internal suspend fun assertLeaseContract(repository: VkBotBindingRepository) {
    val binding = repository.upsertForChat(
        userId = "user-a",
        chatId = UUID.randomUUID(),
        groupToken = "vk1:lease-token",
        groupTokenHash = sha256("vk1:lease-token"),
        linkSecretHash = sha256("lease-link-secret"),
        vkGroupId = 901L,
        vkGroupName = "Lease Group",
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

internal fun sha256(token: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
