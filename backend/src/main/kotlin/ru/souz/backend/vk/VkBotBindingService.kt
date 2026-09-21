package ru.souz.backend.vk

import io.ktor.http.HttpStatusCode
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.crypto.sha256Hex
import ru.souz.backend.http.BackendV1Exception
import ru.souz.backend.http.badRequestV1
import ru.souz.backend.storage.postgres.PostgresVkBotBindingRepository
import ru.souz.backend.storage.postgres.VkBotTokenHashConflictException

data class VkBotBindingUpsertResult(val binding: VkBotBinding, val pendingLinkCommand: String)

class VkBotBindingService(
    private val chatRepository: ChatRepository,
    private val bindingRepository: PostgresVkBotBindingRepository,
    private val vkBotApi: VkBotApi,
    private val tokenCrypto: VkBotTokenCrypto,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun get(userId: String, chatId: UUID): VkBotBinding? {
        requireOwnedChat(userId, chatId)
        return bindingRepository.getByChat(chatId)
    }

    suspend fun upsert(userId: String, chatId: UUID, token: String): VkBotBindingUpsertResult {
        requireOwnedChat(userId, chatId)
        val normalizedToken = token.trim()
        if (normalizedToken.isEmpty()) throw badRequestV1("token must not be blank.")
        if (normalizedToken.length > 4096) throw badRequestV1("token must be at most 4096 characters.")
        return bindingOperation("bind") {
            val group = try {
                vkBotApi.getGroupInfo(normalizedToken)
            } catch (_: VkBotApiException) {
                throw BackendV1Exception(
                    HttpStatusCode.BadRequest, "invalid_vk_bot_token", "VK group access token is invalid.",
                )
            }
            val secret = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(ByteArray(18).also(SecureRandom()::nextBytes))
            VkBotBindingUpsertResult(
                bindingRepository.upsertForChat(
                    userId, chatId, tokenCrypto.encrypt(normalizedToken), normalizedToken.sha256Hex(),
                    secret.sha256Hex(), group.id, group.name, clock.instant(),
                ),
                secret,
            )
        }
    }

    suspend fun delete(userId: String, chatId: UUID) {
        requireOwnedChat(userId, chatId)
        bindingOperation("delete") { bindingRepository.deleteByChat(chatId) }
    }

    private suspend fun requireOwnedChat(userId: String, chatId: UUID) {
        if (chatRepository.get(userId, chatId) == null) {
            throw BackendV1Exception(HttpStatusCode.NotFound, "chat_not_found", "Chat not found.")
        }
    }

    private suspend fun <T> bindingOperation(operation: String, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: BackendV1Exception) {
        throw e
    } catch (_: VkBotTokenHashConflictException) {
        throw BackendV1Exception(HttpStatusCode.Conflict, "vk_bot_already_bound", "VK bot is already bound to another chat.")
    } catch (_: Exception) {
        throw BackendV1Exception(
            HttpStatusCode.InternalServerError, "vk_bot_${operation}_failed", "Failed to $operation VK bot binding.",
        )
    }
}
