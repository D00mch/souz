package ru.souz.backend.telegram

import io.ktor.http.HttpStatusCode
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.http.BackendV1Exception
import ru.souz.backend.http.badRequestV1

class TelegramBotBindingService(
    private val chatRepository: ChatRepository,
    private val bindingRepository: TelegramBotBindingRepository,
    private val telegramBotApi: TelegramBotApi,
    private val tokenCrypto: TelegramBotTokenCrypto,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun get(
        userId: String,
        chatId: UUID,
    ): TelegramBotBinding? {
        requireOwnedChat(userId, chatId)
        return bindingRepository.getByUserAndChat(userId, chatId)
    }

    suspend fun upsert(
        userId: String,
        chatId: UUID,
        token: String,
    ): TelegramBotBinding {
        requireOwnedChat(userId, chatId)
        val normalizedToken = token.trim()
        validateToken(normalizedToken)

        val getMe = try {
            telegramBotApi.getMe(normalizedToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TelegramBotApiTransportException) {
            throw bindingFailed()
        } catch (e: Exception) {
            throw bindingFailed()
        }
        if (!getMe.ok) {
            throw invalidTelegramToken()
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
                code = "telegram_bot_already_bound",
                message = "Telegram bot is already bound to another chat.",
            )
        }

        return try {
            bindingRepository.upsertForChat(
                userId = userId,
                chatId = chatId,
                botToken = tokenCrypto.encrypt(normalizedToken),
                botTokenHash = tokenHash,
                botUsername = getMe.result?.username,
                botFirstName = getMe.result?.firstName,
                now = clock.instant(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: TelegramBotTokenHashConflictException) {
            throw BackendV1Exception(
                status = HttpStatusCode.Conflict,
                code = "telegram_bot_already_bound",
                message = "Telegram bot is already bound to another chat.",
            )
        } catch (e: Exception) {
            throw bindingFailed()
        }
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
                code = "telegram_bot_delete_failed",
                message = "Failed to delete Telegram bot binding.",
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

    private fun invalidTelegramToken(): BackendV1Exception =
        BackendV1Exception(
            status = HttpStatusCode.BadRequest,
            code = "invalid_telegram_bot_token",
            message = "Telegram bot token is invalid.",
        )

    private fun bindingFailed(): BackendV1Exception =
        BackendV1Exception(
            status = HttpStatusCode.InternalServerError,
            code = "telegram_bot_bind_failed",
            message = "Failed to bind Telegram bot.",
        )

    private companion object {
        const val MAX_TOKEN_LENGTH: Int = 4096
    }
}
