package ru.souz.backend.http.routes

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import ru.souz.backend.http.BackendHttpDependencies
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.http.BackendV1OnboardingCompleteRequest
import ru.souz.backend.http.receiveOrV1BadRequest
import ru.souz.backend.http.requireJsonContentV1
import ru.souz.backend.http.requireV1Service
import ru.souz.backend.http.toUserSettingsOverrides
import ru.souz.backend.security.requestIdentity

internal fun Route.onboardingRoutes(deps: BackendHttpDependencies) {
    get(BackendHttpRoutes.ONBOARDING_STATE) {
        val service = requireV1Service(deps.onboardingService, "Onboarding")
        call.respond(service.state(call.requestIdentity()))
    }

    post(BackendHttpRoutes.ONBOARDING_COMPLETE) {
        val service = requireV1Service(deps.onboardingService, "Onboarding")
        call.requireJsonContentV1()
        val request = call.receiveOrV1BadRequest<BackendV1OnboardingCompleteRequest>()
        call.respond(
            service.complete(
                identity = call.requestIdentity(),
                overrides = request.toUserSettingsOverrides(),
            )
        )
    }
}
