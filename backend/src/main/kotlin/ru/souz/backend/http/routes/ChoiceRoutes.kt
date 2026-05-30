package ru.souz.backend.http.routes

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import ru.souz.backend.http.BackendHttpDependencies
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.http.BackendV1AnswerOptionRequest
import ru.souz.backend.http.receiveOrV1BadRequest
import ru.souz.backend.http.requireJsonContentV1
import ru.souz.backend.http.requireOptionId
import ru.souz.backend.http.requireUserIdFromTrustedProxy
import ru.souz.backend.http.requireV1Service
import ru.souz.backend.http.toResponse

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
    }
}
