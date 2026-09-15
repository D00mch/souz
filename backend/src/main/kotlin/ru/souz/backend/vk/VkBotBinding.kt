package ru.souz.backend.vk

import java.time.Instant
import java.util.UUID

data class VkBotBinding(
    val id: UUID,
    val userId: String,
    val chatId: UUID,
    val groupTokenEncrypted: String,
    val groupTokenHash: String,
    val linkSecretHash: String?,
    val vkGroupId: Long,
    val vkGroupName: String?,
    val lastTs: String?,
    val enabled: Boolean,
    val vkUserId: Long?,
    val vkPeerId: Long?,
    val vkFirstName: String?,
    val vkLastName: String?,
    val linkedAt: Instant?,
    val linkedMessageId: Long?,
    val pollerOwner: String?,
    val pollerLeaseUntil: Instant?,
    val lastError: String?,
    val lastErrorAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val linked: Boolean
        get() = vkUserId != null && vkPeerId != null

    val active: Boolean
        get() = enabled && linked
}
