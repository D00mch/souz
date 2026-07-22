package ru.souz.backend.http.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import ru.souz.backend.http.BackendHttpDependencies
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.http.BackendOpenApiSchemas
import ru.souz.backend.http.BackendOpenApiTags
import ru.souz.backend.http.BackendV1CreateMessageRequest
import ru.souz.backend.http.BackendV1CreateMessageResponse
import ru.souz.backend.http.BackendV1MessagesResponse
import ru.souz.backend.http.DEFAULT_MESSAGE_LIMIT
import ru.souz.backend.http.MAX_MESSAGE_LIMIT
import ru.souz.backend.http.invalidV1Request
import ru.souz.backend.http.describeV1
import ru.souz.backend.http.jsonBody
import ru.souz.backend.http.jsonResponse
import ru.souz.backend.http.positiveIntQueryParameter
import ru.souz.backend.http.positiveLongQueryParameter
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
import ru.souz.backend.http.uuidPathParameter
import ru.souz.backend.http.v1ErrorResponses

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
    }.describeV1(
        operationId = "listChatMessages",
        tag = BackendOpenApiTags.MESSAGES,
        summary = "List chat messages",
        description = "Lists visible product messages for an owned chat.",
    ) {
        parameters {
            uuidPathParameter("chatId", "Owned chat UUID.")
            positiveLongQueryParameter("beforeSeq", "Return messages before this positive sequence number.")
            positiveLongQueryParameter("afterSeq", "Return messages after this positive sequence number.")
            positiveIntQueryParameter(
                name = "limit",
                defaultValue = DEFAULT_MESSAGE_LIMIT,
                description = "Requested page size. Values above $MAX_MESSAGE_LIMIT are accepted and clamped to $MAX_MESSAGE_LIMIT.",
            )
        }
        responses {
            jsonResponse<BackendV1MessagesResponse>(HttpStatusCode.OK, "Visible messages in sequence order.")
            v1ErrorResponses(HttpStatusCode.BadRequest, HttpStatusCode.NotFound)
        }
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
    }.describeV1(
        operationId = "createChatMessage",
        tag = BackendOpenApiTags.MESSAGES,
        summary = "Create a chat message",
        description = "Persists a user message and starts its agent execution. The response may already contain a completed assistant message or a running execution.",
    ) {
        parameters { uuidPathParameter("chatId", "Owned chat UUID.") }
        requestBody {
            jsonBody<BackendV1CreateMessageRequest>(
                description = "User message and optional request-scoped model settings.",
                schemaTransform = BackendOpenApiSchemas::createMessage,
            )
        }
        responses {
            jsonResponse<BackendV1CreateMessageResponse>(HttpStatusCode.OK, "Created message and execution state.")
            v1ErrorResponses(HttpStatusCode.BadRequest, HttpStatusCode.NotFound, HttpStatusCode.Conflict)
        }
    }
}
