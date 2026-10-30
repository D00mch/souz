package ru.souz.backend.http.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import ru.souz.backend.http.BackendHttpDependencies
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.http.BackendOpenApiTags
import ru.souz.backend.http.BackendV1CancelSubagentResponse
import ru.souz.backend.http.BackendV1SubagentsResponse
import ru.souz.backend.http.describeV1
import ru.souz.backend.http.jsonResponse
import ru.souz.backend.http.requireChatId
import ru.souz.backend.http.requireExecutionId
import ru.souz.backend.http.requireSubagentId
import ru.souz.backend.http.requireUserIdFromTrustedProxy
import ru.souz.backend.http.uuidPathParameter
import ru.souz.backend.http.v1ErrorResponses

internal fun Route.subagentRoutes(deps: BackendHttpDependencies) {
    get(BackendHttpRoutes.CHAT_SUBAGENTS_PATTERN) {
        call.respond(BackendV1SubagentsResponse(deps.executionService.activeSubagentIds(
            call.requireUserIdFromTrustedProxy(), call.requireChatId(), call.requireExecutionId(),
        )))
    }.describeV1(
        operationId = "listActiveSubagents",
        tag = BackendOpenApiTags.EXECUTIONS,
        summary = "List active subagents",
        description = "Lists live child invocation IDs for an owned execution, including cancellation cleanup. " +
            "Route to the backend process running the parent. Completed children are not retained.",
    ) {
        parameters {
            uuidPathParameter("chatId", "Owned chat UUID.")
            uuidPathParameter("executionId", "Execution UUID belonging to the chat.")
        }
        responses {
            jsonResponse<BackendV1SubagentsResponse>(HttpStatusCode.OK, "Active child IDs; empty after cleanup.")
            v1ErrorResponses(HttpStatusCode.BadRequest, HttpStatusCode.NotFound)
        }
    }

    post(BackendHttpRoutes.CHAT_SUBAGENT_CANCEL_PATTERN) {
        val subagentId = call.requireSubagentId()
        val requested = deps.executionService.cancelSubagent(
            call.requireUserIdFromTrustedProxy(), call.requireChatId(), call.requireExecutionId(), subagentId,
        )
        call.respond(BackendV1CancelSubagentResponse(subagentId.toString(), requested))
    }.describeV1(
        operationId = "cancelSubagent",
        tag = BackendOpenApiTags.EXECUTIONS,
        summary = "Cancel one subagent",
        description = "Cooperatively cancels only the selected child; the waiting parent receives subagent_cancelled. " +
            "Does not roll back tool side effects. Route to the backend process running the parent. " +
            "Repeated requests are safe; cancellationRequested is false if the child is absent or already finished.",
    ) {
        parameters {
            uuidPathParameter("chatId", "Owned chat UUID.")
            uuidPathParameter("executionId", "Execution UUID belonging to the chat.")
            uuidPathParameter("subagentId", "Child invocation UUID from listActiveSubagents.")
        }
        responses {
            jsonResponse<BackendV1CancelSubagentResponse>(HttpStatusCode.OK, "Cancellation request outcome; cleanup may still be running.")
            v1ErrorResponses(HttpStatusCode.BadRequest, HttpStatusCode.NotFound)
        }
    }
}
