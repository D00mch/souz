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
import ru.souz.backend.http.BackendOpenApiSchemas
import ru.souz.backend.http.BackendOpenApiTags
import ru.souz.backend.http.BackendV1ChatsResponse
import ru.souz.backend.http.BackendV1CreateChatRequest
import ru.souz.backend.http.BackendV1CreateChatResponse
import ru.souz.backend.http.BackendV1UpdateChatTitleRequest
import ru.souz.backend.http.DEFAULT_CHAT_LIMIT
import ru.souz.backend.http.MAX_CHAT_LIMIT
import ru.souz.backend.http.describeV1
import ru.souz.backend.http.jsonBody
import ru.souz.backend.http.jsonResponse
import ru.souz.backend.http.positiveIntQueryParameter
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
import ru.souz.backend.http.strictBooleanQueryParameter
import ru.souz.backend.http.uuidPathParameter
import ru.souz.backend.http.v1ErrorResponses

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
    }.describeV1(
        operationId = "listChats",
        tag = BackendOpenApiTags.CHATS,
        summary = "List chats",
        description = "Lists chats owned by the trusted user.",
    ) {
        parameters {
            positiveIntQueryParameter(
                name = "limit",
                defaultValue = DEFAULT_CHAT_LIMIT,
                description = "Requested page size. Values above $MAX_CHAT_LIMIT are accepted and clamped to $MAX_CHAT_LIMIT.",
            )
            strictBooleanQueryParameter(
                name = "includeArchived",
                defaultValue = false,
                description = "Whether archived chats are included; only the literal values true and false are accepted.",
            )
        }
        responses {
            jsonResponse<BackendV1ChatsResponse>(HttpStatusCode.OK, "Owned chats.")
            v1ErrorResponses(HttpStatusCode.BadRequest)
        }
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
    }.describeV1(
        operationId = "createChat",
        tag = BackendOpenApiTags.CHATS,
        summary = "Create a chat",
        description = "Creates a new chat owned by the trusted user.",
    ) {
        requestBody {
            jsonBody<BackendV1CreateChatRequest>(description = "Optional initial chat title.")
        }
        responses {
            jsonResponse<BackendV1CreateChatResponse>(HttpStatusCode.Created, "The newly created chat.")
            v1ErrorResponses(HttpStatusCode.BadRequest)
        }
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
    }.describeV1(
        operationId = "updateChatTitle",
        tag = BackendOpenApiTags.CHATS,
        summary = "Update a chat title",
        description = "Replaces the title of a chat owned by the trusted user.",
    ) {
        parameters { uuidPathParameter("chatId", "Owned chat UUID.") }
        requestBody {
            jsonBody<BackendV1UpdateChatTitleRequest>(
                description = "New non-blank title.",
                schemaTransform = BackendOpenApiSchemas::updateChatTitle,
            )
        }
        responses {
            jsonResponse<ru.souz.backend.http.BackendV1ChatDto>(HttpStatusCode.OK, "The updated chat.")
            v1ErrorResponses(HttpStatusCode.BadRequest, HttpStatusCode.NotFound)
        }
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
    }.describeV1(
        operationId = "archiveChat",
        tag = BackendOpenApiTags.CHATS,
        summary = "Archive a chat",
        description = "Marks an owned chat as archived.",
    ) {
        parameters { uuidPathParameter("chatId", "Owned chat UUID.") }
        responses {
            jsonResponse<ru.souz.backend.http.BackendV1ChatDto>(HttpStatusCode.OK, "The archived chat.")
            v1ErrorResponses(HttpStatusCode.BadRequest, HttpStatusCode.NotFound)
        }
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
    }.describeV1(
        operationId = "unarchiveChat",
        tag = BackendOpenApiTags.CHATS,
        summary = "Unarchive a chat",
        description = "Marks an owned chat as active again.",
    ) {
        parameters { uuidPathParameter("chatId", "Owned chat UUID.") }
        responses {
            jsonResponse<ru.souz.backend.http.BackendV1ChatDto>(HttpStatusCode.OK, "The unarchived chat.")
            v1ErrorResponses(HttpStatusCode.BadRequest, HttpStatusCode.NotFound)
        }
    }

    post(BackendHttpRoutes.CHAT_CANCEL_ACTIVE_PATTERN) {
        val service = requireV1Service(deps.executionService, "Execution")
        call.respond(
            service.cancelActive(
                userId = call.requireUserIdFromTrustedProxy(),
                chatId = call.requireChatId(),
            ).toResponse()
        )
    }.describeV1(
        operationId = "cancelActiveExecution",
        tag = BackendOpenApiTags.EXECUTIONS,
        summary = "Cancel the active execution",
        description = "Requests cancellation of the currently active execution for an owned chat.",
    ) {
        parameters { uuidPathParameter("chatId", "Owned chat UUID.") }
        responses {
            jsonResponse<ru.souz.backend.http.BackendV1CancelExecutionResponse>(
                HttpStatusCode.OK,
                "The cancelling or cancelled execution.",
            )
            v1ErrorResponses(HttpStatusCode.BadRequest, HttpStatusCode.NotFound)
        }
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
    }.describeV1(
        operationId = "cancelExecution",
        tag = BackendOpenApiTags.EXECUTIONS,
        summary = "Cancel an execution",
        description = "Requests cancellation of a specific active execution in an owned chat.",
    ) {
        parameters {
            uuidPathParameter("chatId", "Owned chat UUID.")
            uuidPathParameter("executionId", "Execution UUID belonging to the chat.")
        }
        responses {
            jsonResponse<ru.souz.backend.http.BackendV1CancelExecutionResponse>(
                HttpStatusCode.OK,
                "The cancelling or cancelled execution.",
            )
            v1ErrorResponses(HttpStatusCode.BadRequest, HttpStatusCode.NotFound)
        }
    }
}
