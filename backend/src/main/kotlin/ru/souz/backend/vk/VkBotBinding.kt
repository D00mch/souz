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
    val pollerOwner: String?,
    val pollerLeaseUntil: Instant?,
    val lastError: String?,
    val lastErrorAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val linked: Boolean
        get() = vkUserId != null && vkPeerId != null

    /**
     * Enabled and fully linked — the single "is this a live, usable VK channel" check, shared by
     * [VkChannelProvider][ru.souz.backend.channels.VkChannelProvider]'s own listing/sending and the
     * ownership-claim check in `BackendDiModule` so the two can't silently disagree about which
     * chats belong to VK.
     */
    val active: Boolean
        get() = enabled && linked
}

sealed interface VkUserClaimResult {
    data class Claimed(
        val binding: VkBotBinding,
    ) : VkUserClaimResult

    data class AlreadyLinked(
        val binding: VkBotBinding,
    ) : VkUserClaimResult

    data class InvalidSecret(
        val binding: VkBotBinding,
    ) : VkUserClaimResult

    data object NotFound : VkUserClaimResult
}
