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
import ru.souz.backend.http.BackendV1UpsertVkBotBindingRequest
import ru.souz.backend.http.BackendV1VkBotBindingResponse
import ru.souz.backend.http.describeV1
import ru.souz.backend.http.jsonBody
import ru.souz.backend.http.jsonResponse
import ru.souz.backend.http.receiveOrBadRequest
import ru.souz.backend.http.requireChatId
import ru.souz.backend.http.requireJsonContentV1
import ru.souz.backend.http.requireUserIdFromTrustedProxy
import ru.souz.backend.http.toDto
import ru.souz.backend.http.uuidPathParameter
import ru.souz.backend.http.v1ErrorResponses

import ru.souz.backend.vk.VkBotBindingService

internal fun Route.vkRoutes(
    deps: BackendHttpDependencies,
    service: VkBotBindingService,
) {
    get(BackendHttpRoutes.CHAT_VK_BOT_PATTERN) {
        call.respond(
            BackendV1VkBotBindingResponse(
                vkBot = service.get(
                    userId = call.requireUserIdFromTrustedProxy(),
                    chatId = call.requireChatId(),
                )?.toDto()
            )
        )
    }.describeV1(
        operationId = "getVkBotBinding",
        tag = BackendOpenApiTags.VK,
        summary = "Get a VK bot binding",
        description = "Returns safe VK binding metadata for an owned chat. This route is registered only when VK support is enabled.",
    ) {
        parameters { uuidPathParameter("chatId", "Owned chat UUID.") }
        responses {
            jsonResponse<BackendV1VkBotBindingResponse>(HttpStatusCode.OK, "Current binding metadata, or null.")
            v1ErrorResponses(HttpStatusCode.BadRequest, HttpStatusCode.NotFound)
        }
    }

    put(BackendHttpRoutes.CHAT_VK_BOT_PATTERN) {
        call.requireJsonContentV1()
        val request = call.receiveOrBadRequest<BackendV1UpsertVkBotBindingRequest>()
        val result = service.upsert(
            userId = call.requireUserIdFromTrustedProxy(),
            chatId = call.requireChatId(),
            token = request.token.orEmpty(),
        )
        call.respond(
            BackendV1VkBotBindingResponse(
                vkBot = result.binding.toDto(),
                pendingLinkCommand = result.pendingLinkCommand,
            )
        )
    }.describeV1(
        operationId = "upsertVkBotBinding",
        tag = BackendOpenApiTags.VK,
        summary = "Create or replace a VK bot binding",
        description = "Validates and stores a VK group access token, returning safe binding metadata and a one-time link secret.",
    ) {
        parameters { uuidPathParameter("chatId", "Owned chat UUID.") }
        requestBody {
            jsonBody<BackendV1UpsertVkBotBindingRequest>(
                description = "Write-only VK group access token.",
                schemaTransform = BackendOpenApiSchemas::vkToken,
            )
        }
        responses {
            jsonResponse<BackendV1VkBotBindingResponse>(HttpStatusCode.OK, "Created or replaced binding metadata.")
            v1ErrorResponses(HttpStatusCode.BadRequest, HttpStatusCode.NotFound, HttpStatusCode.Conflict)
        }
    }

    delete(BackendHttpRoutes.CHAT_VK_BOT_PATTERN) {
        service.delete(
            userId = call.requireUserIdFromTrustedProxy(),
            chatId = call.requireChatId(),
        )
        call.respond(BackendV1VkBotBindingResponse(vkBot = null))
    }.describeV1(
        operationId = "deleteVkBotBinding",
        tag = BackendOpenApiTags.VK,
        summary = "Delete a VK bot binding",
        description = "Removes the VK bot binding for an owned chat.",
    ) {
        parameters { uuidPathParameter("chatId", "Owned chat UUID.") }
        responses {
            jsonResponse<BackendV1VkBotBindingResponse>(HttpStatusCode.OK, "A null binding confirming deletion.")
            v1ErrorResponses(HttpStatusCode.BadRequest, HttpStatusCode.NotFound)
        }
    }
}
