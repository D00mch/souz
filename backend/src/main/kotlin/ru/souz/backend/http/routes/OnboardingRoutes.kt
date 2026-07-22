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
import ru.souz.backend.http.BackendV1OnboardingCompleteRequest
import ru.souz.backend.http.describeV1
import ru.souz.backend.http.jsonBody
import ru.souz.backend.http.jsonResponse
import ru.souz.backend.http.receiveOrV1BadRequest
import ru.souz.backend.http.requireJsonContentV1
import ru.souz.backend.http.requireV1Service
import ru.souz.backend.http.toUserSettingsOverrides
import ru.souz.backend.http.v1ErrorResponses
import ru.souz.backend.onboarding.OnboardingCompleteResponse
import ru.souz.backend.onboarding.OnboardingStateResponse
import ru.souz.backend.security.requestIdentity

internal fun Route.onboardingRoutes(deps: BackendHttpDependencies) {
    get(BackendHttpRoutes.ONBOARDING_STATE) {
        val service = requireV1Service(deps.onboardingService, "Onboarding")
        call.respond(service.state(call.requestIdentity()))
    }.describeV1(
        operationId = "getOnboardingState",
        tag = BackendOpenApiTags.ONBOARDING,
        summary = "Get onboarding state",
        description = "Returns onboarding requirements, model-access hints, and current effective settings for the trusted user.",
    ) {
        responses {
            jsonResponse<OnboardingStateResponse>(HttpStatusCode.OK, "Current onboarding state.")
            v1ErrorResponses()
        }
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
    }.describeV1(
        operationId = "completeOnboarding",
        tag = BackendOpenApiTags.ONBOARDING,
        summary = "Complete onboarding",
        description = "Persists optional onboarding preferences and marks onboarding complete when model access is available.",
    ) {
        requestBody {
            jsonBody<BackendV1OnboardingCompleteRequest>(
                description = "Optional first-run preferences.",
                schemaTransform = BackendOpenApiSchemas::onboardingComplete,
            )
        }
        responses {
            jsonResponse<OnboardingCompleteResponse>(HttpStatusCode.OK, "Onboarding was completed.")
            v1ErrorResponses(HttpStatusCode.BadRequest, HttpStatusCode.Conflict)
        }
    }
}
