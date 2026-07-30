package ru.souz.backend.http.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import ru.souz.backend.http.BackendHttpDependencies
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.http.BackendOpenApiSchemas
import ru.souz.backend.http.BackendOpenApiTags
import ru.souz.backend.http.BackendV1TelegramBotBindingResponse
import ru.souz.backend.http.BackendV1UpsertTelegramBotBindingRequest
import ru.souz.backend.http.describeV1
import ru.souz.backend.http.jsonBody
import ru.souz.backend.http.jsonResponse
import ru.souz.backend.http.receiveOrBadRequest
import ru.souz.backend.http.requireChatId
import ru.souz.backend.http.requireJsonContentV1
import ru.souz.backend.http.requireUserIdFromTrustedProxy
import ru.souz.backend.http.requireV1Service
import ru.souz.backend.http.toDto
import ru.souz.backend.http.uuidPathParameter
import ru.souz.backend.http.v1ErrorResponses

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
    }.describeV1(
        operationId = "getTelegramBotBinding",
        tag = BackendOpenApiTags.TELEGRAM,
        summary = "Get a Telegram bot binding",
        description = "Returns safe Telegram binding metadata for an owned chat. This route is registered only when Telegram support is enabled.",
    ) {
        parameters { uuidPathParameter("chatId", "Owned chat UUID.") }
        responses {
            jsonResponse<BackendV1TelegramBotBindingResponse>(HttpStatusCode.OK, "Current binding metadata, or null.")
            v1ErrorResponses(HttpStatusCode.BadRequest, HttpStatusCode.NotFound)
        }
    }

    put(BackendHttpRoutes.CHAT_TELEGRAM_BOT_PATTERN) {
        val service = requireV1Service(deps.telegramBotBindingService, "Telegram bot binding")
        call.requireJsonContentV1()
        val request = call.receiveOrBadRequest<BackendV1UpsertTelegramBotBindingRequest>()
        val result = service.upsert(
            userId = call.requireUserIdFromTrustedProxy(),
            chatId = call.requireChatId(),
            token = request.token.orEmpty(),
        )
        call.respond(
            BackendV1TelegramBotBindingResponse(
                telegramBot = result.binding.toDto(),
                pendingLinkCommand = result.pendingLinkCommand,
            )
        )
    }.describeV1(
        operationId = "upsertTelegramBotBinding",
        tag = BackendOpenApiTags.TELEGRAM,
        summary = "Create or replace a Telegram bot binding",
        description = "Validates and stores a Telegram bot token, returning safe binding metadata and a one-time link command.",
    ) {
        parameters { uuidPathParameter("chatId", "Owned chat UUID.") }
        requestBody {
            jsonBody<BackendV1UpsertTelegramBotBindingRequest>(
                description = "Write-only Telegram bot token.",
                schemaTransform = BackendOpenApiSchemas::telegramToken,
            )
        }
        responses {
            jsonResponse<BackendV1TelegramBotBindingResponse>(HttpStatusCode.OK, "Created or replaced binding metadata.")
            v1ErrorResponses(HttpStatusCode.BadRequest, HttpStatusCode.NotFound, HttpStatusCode.Conflict)
        }
    }

    delete(BackendHttpRoutes.CHAT_TELEGRAM_BOT_PATTERN) {
        val service = requireV1Service(deps.telegramBotBindingService, "Telegram bot binding")
        service.delete(
            userId = call.requireUserIdFromTrustedProxy(),
            chatId = call.requireChatId(),
        )
        call.respond(BackendV1TelegramBotBindingResponse(telegramBot = null))
    }.describeV1(
        operationId = "deleteTelegramBotBinding",
        tag = BackendOpenApiTags.TELEGRAM,
        summary = "Delete a Telegram bot binding",
        description = "Removes the Telegram bot binding for an owned chat.",
    ) {
        parameters { uuidPathParameter("chatId", "Owned chat UUID.") }
        responses {
            jsonResponse<BackendV1TelegramBotBindingResponse>(HttpStatusCode.OK, "A null binding confirming deletion.")
            v1ErrorResponses(HttpStatusCode.BadRequest, HttpStatusCode.NotFound)
        }
    }
}
