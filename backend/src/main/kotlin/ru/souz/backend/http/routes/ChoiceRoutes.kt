package ru.souz.backend.http.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import ru.souz.backend.http.BackendHttpDependencies
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.http.BackendOpenApiSchemas
import ru.souz.backend.http.BackendOpenApiTags
import ru.souz.backend.http.BackendV1AnswerOptionRequest
import ru.souz.backend.http.BackendV1AnswerOptionResponse
import ru.souz.backend.http.describeV1
import ru.souz.backend.http.jsonBody
import ru.souz.backend.http.jsonResponse
import ru.souz.backend.http.receiveOrV1BadRequest
import ru.souz.backend.http.requireJsonContentV1
import ru.souz.backend.http.requireOptionId
import ru.souz.backend.http.requireUserIdFromTrustedProxy
import ru.souz.backend.http.requireV1Service
import ru.souz.backend.http.toResponse
import ru.souz.backend.http.uuidPathParameter
import ru.souz.backend.http.v1ErrorResponses

internal fun Route.choiceRoutes(deps: BackendHttpDependencies) {
    post(BackendHttpRoutes.OPTION_ANSWER_PATTERN) {
        val service = requireV1Service(deps.optionService, "Option")
        call.requireJsonContentV1()
        val request = call.receiveOrV1BadRequest<BackendV1AnswerOptionRequest>()
        call.respond(
            service.answer(
                userId = call.requireUserIdFromTrustedProxy(),
                optionId = call.requireOptionId(),
                selectedOptionIds = request.selectedOptionIds,
                freeText = request.freeText,
                metadata = request.metadata,
            ).toResponse()
        )
    }.describeV1(
        operationId = "answerOption",
        tag = BackendOpenApiTags.OPTIONS,
        summary = "Answer a pending option",
        description = "Records an answer and resumes the associated execution. Returns 404 feature_disabled when options are disabled.",
    ) {
        parameters { uuidPathParameter("optionId", "Pending option UUID.") }
        requestBody {
            jsonBody<BackendV1AnswerOptionRequest>(
                description = "Selected option identifiers, optional free text, and string metadata.",
                schemaTransform = BackendOpenApiSchemas::answerOption,
            )
        }
        responses {
            jsonResponse<BackendV1AnswerOptionResponse>(HttpStatusCode.OK, "Answered option and resumed execution.")
            v1ErrorResponses(HttpStatusCode.BadRequest, HttpStatusCode.NotFound)
        }
    }
}
