package ru.souz.backend.http.routes

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.slf4j.LoggerFactory
import ru.souz.backend.agent.model.AgentRequest
import ru.souz.backend.http.BackendHttpDependencies
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.http.receiveOrBadRequest
import ru.souz.backend.http.requireAgentAuthorization
import ru.souz.backend.http.requireJsonContent
import ru.souz.backend.http.requireRequestId
import ru.souz.backend.http.respondBackend

private val legacyRoutesLogger = LoggerFactory.getLogger("SouzBackendRoutes.LegacyAgent")

internal fun Route.legacyAgentRoutes(deps: BackendHttpDependencies) {
    post(BackendHttpRoutes.LEGACY_AGENT) {
        call.respondBackend(legacyRoutesLogger) {
            requireAgentAuthorization(deps.internalAgentToken())
            requireJsonContent()
            val request = receiveOrBadRequest<AgentRequest>()
            requireRequestId(request.requestId)
            deps.agentService.sendAgentRequest(request)
        }
    }
}
