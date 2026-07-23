package ru.souz.backend.http.routes

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.http.HttpStatusCode
import ru.souz.backend.bootstrap.BootstrapResponse
import ru.souz.backend.http.BackendHttpDependencies
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.http.BackendOpenApiTags
import ru.souz.backend.http.describeV1
import ru.souz.backend.http.jsonResponse
import ru.souz.backend.http.v1ErrorResponses
import ru.souz.backend.security.requestIdentity

internal fun Route.v1Routes(deps: BackendHttpDependencies) {
    bootstrapRoutes(deps)
    onboardingRoutes(deps)
    settingsRoutes(deps)
    providerKeyRoutes(deps)
    chatRoutes(deps)
    if (deps.featureFlags.telegramBot) {
        telegramRoutes(deps)
    }
    messageRoutes(deps)
    eventRoutes(deps)
    choiceRoutes(deps)
    permissionRoutes(deps)
}

internal fun Route.bootstrapRoutes(deps: BackendHttpDependencies) {
    get(BackendHttpRoutes.BOOTSTRAP) {
        call.respond(deps.bootstrapService.response(call.requestIdentity()))
    }.describeV1(
        operationId = "getBootstrap",
        tag = BackendOpenApiTags.BOOTSTRAP,
        summary = "Get backend bootstrap data",
        description = "Returns the trusted user's identity, enabled features, server-visible capabilities, and effective settings.",
    ) {
        responses {
            jsonResponse<BootstrapResponse>(HttpStatusCode.OK, "Bootstrap data for the trusted user.")
            v1ErrorResponses()
        }
    }
}
