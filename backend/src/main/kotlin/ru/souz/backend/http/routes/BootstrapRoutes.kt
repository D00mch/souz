package ru.souz.backend.http.routes

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import ru.souz.backend.http.BackendHttpDependencies
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.security.requestIdentity

internal fun Route.v1Routes(deps: BackendHttpDependencies) {
    bootstrapRoutes(deps)
    onboardingRoutes(deps)
    settingsRoutes(deps)
    providerKeyRoutes(deps)
    chatRoutes(deps)
    telegramRoutes(deps)
    messageRoutes(deps)
    eventRoutes(deps)
    choiceRoutes(deps)
}

internal fun Route.bootstrapRoutes(deps: BackendHttpDependencies) {
    get(BackendHttpRoutes.BOOTSTRAP) {
        call.respond(deps.bootstrapService.response(call.requestIdentity()))
    }
}
