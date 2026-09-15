package ru.souz.backend.vk

import java.time.Instant
import java.util.UUID

interface VkBotBindingRepository {
    suspend fun getByChat(chatId: UUID): VkBotBinding?

    suspend fun getByUserAndChat(
        userId: String,
        chatId: UUID,
    ): VkBotBinding?

    suspend fun listForUser(userId: String): List<VkBotBinding>

    suspend fun findByTokenHash(groupTokenHash: String): VkBotBinding?

    suspend fun listEnabled(): List<VkBotBinding>

    suspend fun upsertForChat(
        userId: String,
        chatId: UUID,
        groupToken: String,
        groupTokenHash: String,
        linkSecretHash: String,
        vkGroupId: Long,
        vkGroupName: String?,
        now: Instant,
    ): VkBotBinding

    suspend fun deleteByChat(chatId: UUID)

    suspend fun claimVkUser(
        id: UUID,
        linkSecretHash: String,
        vkUserId: Long,
        vkPeerId: Long,
        vkFirstName: String?,
        vkLastName: String?,
        linkedAt: Instant,
        updatedAt: Instant = linkedAt,
    ): VkUserClaimResult

    suspend fun tryAcquireLease(
        id: UUID,
        owner: String,
        leaseUntil: Instant,
        now: Instant = Instant.now(),
    ): VkBotBinding?

    suspend fun hasActiveLease(
        id: UUID,
        owner: String,
        now: Instant = Instant.now(),
    ): Boolean

    suspend fun updateLastTs(
        id: UUID,
        lastTs: String,
        updatedAt: Instant = Instant.now(),
        owner: String? = null,
    )

    suspend fun markError(
        id: UUID,
        lastError: String,
        lastErrorAt: Instant = Instant.now(),
        disable: Boolean = false,
    )

    suspend fun clearError(
        id: UUID,
        updatedAt: Instant = Instant.now(),
    )
}

class VkBotTokenHashConflictException : RuntimeException("VK group token hash is already bound.")
