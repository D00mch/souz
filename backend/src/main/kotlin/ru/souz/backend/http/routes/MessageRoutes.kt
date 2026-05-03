package ru.souz.backend.http.routes

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import ru.souz.backend.http.BackendHttpDependencies
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.http.BackendV1CreateMessageRequest
import ru.souz.backend.http.BackendV1MessagesResponse
import ru.souz.backend.http.DEFAULT_MESSAGE_LIMIT
import ru.souz.backend.http.MAX_MESSAGE_LIMIT
import ru.souz.backend.http.invalidV1Request
import ru.souz.backend.http.queryPositiveInt
import ru.souz.backend.http.queryPositiveLong
import ru.souz.backend.http.receiveOrV1BadRequest
import ru.souz.backend.http.requireChatId
import ru.souz.backend.http.requireJsonContentV1
import ru.souz.backend.http.requireUserIdFromTrustedProxy
import ru.souz.backend.http.requireV1Service
import ru.souz.backend.http.toDto
import ru.souz.backend.http.toResponse
import ru.souz.backend.http.toUserSettingsOverrides

internal fun Route.messageRoutes(deps: BackendHttpDependencies) {
    get(BackendHttpRoutes.CHAT_MESSAGES_PATTERN) {
        val service = requireV1Service(deps.messageService, "Message")
        val chatId = call.requireChatId()
        val limit = call.queryPositiveInt("limit", DEFAULT_MESSAGE_LIMIT, MAX_MESSAGE_LIMIT)
        call.respond(
            service.list(
                userId = call.requireUserIdFromTrustedProxy(),
                chatId = chatId,
                beforeSeq = call.queryPositiveLong("beforeSeq"),
                afterSeq = call.queryPositiveLong("afterSeq"),
                limit = limit,
            ).let { page ->
                BackendV1MessagesResponse(
                    items = page.items.map { it.toDto() },
                    nextBeforeSeq = page.nextBeforeSeq,
                )
            }
        )
    }

    post(BackendHttpRoutes.CHAT_MESSAGES_PATTERN) {
        val service = requireV1Service(deps.messageService, "Message")
        val chatId = call.requireChatId()
        call.requireJsonContentV1()
        val request = call.receiveOrV1BadRequest<BackendV1CreateMessageRequest>()
        val content = request.content.trim().takeIf { it.isNotEmpty() }
            ?: throw invalidV1Request("content must not be empty.")
        call.respond(
            service.send(
                userId = call.requireUserIdFromTrustedProxy(),
                chatId = chatId,
                content = content,
                clientMessageId = request.clientMessageId,
                requestOverrides = request.options.toUserSettingsOverrides(),
            ).toResponse()
        )
    }
}
