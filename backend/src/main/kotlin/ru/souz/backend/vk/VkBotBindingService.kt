package ru.souz.backend.vk

import io.ktor.http.HttpStatusCode
import java.security.SecureRandom
import java.util.Base64
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.crypto.sha256Hex
import ru.souz.backend.http.BackendV1Exception
import ru.souz.backend.http.badRequestV1

data class VkBotBindingUpsertResult(
    val binding: VkBotBinding,
    val pendingLinkCommand: String,
)

class VkBotBindingService(
    private val chatRepository: ChatRepository,
    private val bindingRepository: VkBotBindingRepository,
    private val vkBotApi: VkBotApi,
    private val tokenCrypto: VkBotTokenCrypto,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun get(
        userId: String,
        chatId: UUID,
    ): VkBotBinding? {
        requireOwnedChat(userId, chatId)
        return bindingRepository.getByUserAndChat(userId, chatId)
    }

    suspend fun upsert(
        userId: String,
        chatId: UUID,
        token: String,
    ): VkBotBindingUpsertResult {
        requireOwnedChat(userId, chatId)
        val normalizedToken = token.trim()
        validateToken(normalizedToken)

        val groupInfo = try {
            vkBotApi.getGroupInfo(normalizedToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw bindingFailed()
        }
        val group = groupInfo.response?.firstOrNull()
        if (groupInfo.error != null || group == null) {
            throw invalidVkToken()
        }

        val tokenHash = sha256Hex(normalizedToken)
        val existingByToken = try {
            bindingRepository.findByTokenHash(tokenHash)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw bindingFailed()
        }
        if (existingByToken != null && existingByToken.chatId != chatId) {
            throw BackendV1Exception(
                status = HttpStatusCode.Conflict,
                code = "vk_bot_already_bound",
                message = "VK bot is already bound to another chat.",
            )
        }

        val linkSecret = generateLinkSecret()
        val binding = try {
            bindingRepository.upsertForChat(
                userId = userId,
                chatId = chatId,
                groupToken = tokenCrypto.encrypt(normalizedToken),
                groupTokenHash = tokenHash,
                linkSecretHash = sha256Hex(linkSecret),
                vkGroupId = group.id,
                vkGroupName = group.name,
                now = clock.instant(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: VkBotTokenHashConflictException) {
            throw BackendV1Exception(
                status = HttpStatusCode.Conflict,
                code = "vk_bot_already_bound",
                message = "VK bot is already bound to another chat.",
            )
        } catch (e: Exception) {
            throw bindingFailed()
        }

        return VkBotBindingUpsertResult(
            binding = binding,
            pendingLinkCommand = linkSecret,
        )
    }

    suspend fun delete(
        userId: String,
        chatId: UUID,
    ) {
        requireOwnedChat(userId, chatId)
        try {
            bindingRepository.deleteByChat(chatId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw BackendV1Exception(
                status = HttpStatusCode.InternalServerError,
                code = "vk_bot_delete_failed",
                message = "Failed to delete VK bot binding.",
            )
        }
    }

    private suspend fun requireOwnedChat(
        userId: String,
        chatId: UUID,
    ) {
        chatRepository.get(userId, chatId)
            ?: throw BackendV1Exception(
                status = HttpStatusCode.NotFound,
                code = "chat_not_found",
                message = "Chat not found.",
            )
    }

    private fun validateToken(token: String) {
        if (token.isBlank()) {
            throw badRequestV1("token must not be blank.")
        }
        if (token.length > MAX_TOKEN_LENGTH) {
            throw badRequestV1("token must be at most $MAX_TOKEN_LENGTH characters.")
        }
    }

    private fun invalidVkToken(): BackendV1Exception =
        BackendV1Exception(
            status = HttpStatusCode.BadRequest,
            code = "invalid_vk_bot_token",
            message = "VK group access token is invalid.",
        )

    private fun bindingFailed(): BackendV1Exception =
        BackendV1Exception(
            status = HttpStatusCode.InternalServerError,
            code = "vk_bot_bind_failed",
            message = "Failed to bind VK bot.",
        )

    private companion object {
        const val MAX_TOKEN_LENGTH: Int = 4096
        const val LINK_SECRET_BYTES: Int = 18
        val secureRandom: SecureRandom = SecureRandom()
        val linkSecretEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    }

    private fun generateLinkSecret(): String =
        ByteArray(LINK_SECRET_BYTES)
            .also(secureRandom::nextBytes)
            .let(linkSecretEncoder::encodeToString)
}
