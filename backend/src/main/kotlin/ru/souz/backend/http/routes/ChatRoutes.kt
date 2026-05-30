package ru.souz.backend.http.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import ru.souz.backend.http.BackendHttpDependencies
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.http.BackendV1ChatsResponse
import ru.souz.backend.http.BackendV1CreateChatRequest
import ru.souz.backend.http.BackendV1CreateChatResponse
import ru.souz.backend.http.BackendV1UpdateChatTitleRequest
import ru.souz.backend.http.DEFAULT_CHAT_LIMIT
import ru.souz.backend.http.MAX_CHAT_LIMIT
import ru.souz.backend.http.queryBoolean
import ru.souz.backend.http.queryPositiveInt
import ru.souz.backend.http.receiveOrV1BadRequest
import ru.souz.backend.http.requireChatId
import ru.souz.backend.http.requireExecutionId
import ru.souz.backend.http.requireJsonContentV1
import ru.souz.backend.http.requireUserIdFromTrustedProxy
import ru.souz.backend.http.requireV1Service
import ru.souz.backend.http.toDto
import ru.souz.backend.http.toResponse

internal fun Route.chatRoutes(deps: BackendHttpDependencies) {
    get(BackendHttpRoutes.CHATS) {
        val service = requireV1Service(deps.chatService, "Chat")
        val limit = call.queryPositiveInt("limit", DEFAULT_CHAT_LIMIT, MAX_CHAT_LIMIT)
        val includeArchived = call.queryBoolean("includeArchived", defaultValue = false)
        call.respond(
            service.list(
                userId = call.requireUserIdFromTrustedProxy(),
                limit = limit,
                includeArchived = includeArchived,
            ).let { page ->
                BackendV1ChatsResponse(
                    items = page.items.map { it.toDto() },
                    nextCursor = page.nextCursor,
                )
            }
        )
    }

    post(BackendHttpRoutes.CHATS) {
        val service = requireV1Service(deps.chatService, "Chat")
        call.requireJsonContentV1()
        val request = call.receiveOrV1BadRequest<BackendV1CreateChatRequest>()
        call.respond(
            HttpStatusCode.Created,
            BackendV1CreateChatResponse(
                chat = service.create(
                    userId = call.requireUserIdFromTrustedProxy(),
                    title = request.title,
                ).toDto(),
            ),
        )
    }

    patch(BackendHttpRoutes.CHAT_TITLE_PATTERN) {
        val service = requireV1Service(deps.chatService, "Chat")
        call.requireJsonContentV1()
        val request = call.receiveOrV1BadRequest<BackendV1UpdateChatTitleRequest>()
        call.respond(
            service.updateTitle(
                userId = call.requireUserIdFromTrustedProxy(),
                chatId = call.requireChatId(),
                title = request.title,
            ).toDto(),
        )
    }

    post(BackendHttpRoutes.CHAT_ARCHIVE_PATTERN) {
        val service = requireV1Service(deps.chatService, "Chat")
        call.respond(
            service.setArchived(
                userId = call.requireUserIdFromTrustedProxy(),
                chatId = call.requireChatId(),
                archived = true,
            ).toDto(),
        )
    }

    post(BackendHttpRoutes.CHAT_UNARCHIVE_PATTERN) {
        val service = requireV1Service(deps.chatService, "Chat")
        call.respond(
            service.setArchived(
                userId = call.requireUserIdFromTrustedProxy(),
                chatId = call.requireChatId(),
                archived = false,
            ).toDto(),
        )
    }

    post(BackendHttpRoutes.CHAT_CANCEL_ACTIVE_PATTERN) {
        val service = requireV1Service(deps.executionService, "Execution")
        call.respond(
            service.cancelActive(
                userId = call.requireUserIdFromTrustedProxy(),
                chatId = call.requireChatId(),
            ).toResponse()
        )
    }

    post(BackendHttpRoutes.CHAT_EXECUTION_CANCEL_PATTERN) {
        val service = requireV1Service(deps.executionService, "Execution")
        call.respond(
            service.cancelExecution(
                userId = call.requireUserIdFromTrustedProxy(),
                chatId = call.requireChatId(),
                executionId = call.requireExecutionId(),
            ).toResponse()
        )
    }
}
