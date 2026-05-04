package ru.souz.backend.http.routes

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import ru.souz.backend.http.BackendHttpDependencies
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.http.BackendV1TelegramBotBindingResponse
import ru.souz.backend.http.BackendV1UpsertTelegramBotBindingRequest
import ru.souz.backend.http.receiveOrBadRequest
import ru.souz.backend.http.requireChatId
import ru.souz.backend.http.requireJsonContentV1
import ru.souz.backend.http.requireUserIdFromTrustedProxy
import ru.souz.backend.http.requireV1Service
import ru.souz.backend.http.toDto

internal fun Route.telegramRoutes(deps: BackendHttpDependencies) {
    get(BackendHttpRoutes.CHAT_TELEGRAM_BOT_PATTERN) {
        val service = requireV1Service(deps.telegramBotBindingService, "Telegram bot binding")
        call.respond(
            BackendV1TelegramBotBindingResponse(
                telegramBot = service.get(
                    userId = call.requireUserIdFromTrustedProxy(),
                    chatId = call.requireChatId(),
                )?.toDto()
            )
        )
    }

    put(BackendHttpRoutes.CHAT_TELEGRAM_BOT_PATTERN) {
        val service = requireV1Service(deps.telegramBotBindingService, "Telegram bot binding")
        call.requireJsonContentV1()
        val request = call.receiveOrBadRequest<BackendV1UpsertTelegramBotBindingRequest>()
        call.respond(
            BackendV1TelegramBotBindingResponse(
                telegramBot = service.upsert(
                    userId = call.requireUserIdFromTrustedProxy(),
                    chatId = call.requireChatId(),
                    token = request.token.orEmpty(),
                ).toDto()
            )
        )
    }

    delete(BackendHttpRoutes.CHAT_TELEGRAM_BOT_PATTERN) {
        val service = requireV1Service(deps.telegramBotBindingService, "Telegram bot binding")
        service.delete(
            userId = call.requireUserIdFromTrustedProxy(),
            chatId = call.requireChatId(),
        )
        call.respond(BackendV1TelegramBotBindingResponse(telegramBot = null))
    }
}
