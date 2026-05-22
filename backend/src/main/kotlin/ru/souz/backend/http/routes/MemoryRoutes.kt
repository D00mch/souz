package ru.souz.backend.http.routes

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import ru.souz.agent.memory.MemoryFactRecord
import ru.souz.agent.memory.MemoryGraphSnapshot
import ru.souz.agent.memory.MemoryScope
import ru.souz.agent.memory.MemoryScopeType
import ru.souz.backend.http.BackendHttpDependencies
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.http.BackendV1Exception
import ru.souz.backend.http.invalidV1Request
import ru.souz.backend.http.requireUserIdFromTrustedProxy
import ru.souz.backend.http.requireV1Service
import io.ktor.http.HttpStatusCode

data class BackendV1MemoryOverviewResponse(
    val activeFactCount: Int,
    val activeProfileDocCount: Int,
    val activeFactDocCount: Int,
    val activeEpisodeDocCount: Int,
)

data class BackendV1MemoryFactsResponse(
    val items: List<MemoryFactRecord>,
)

data class BackendV1MemoryGraphResponse(
    val snapshot: MemoryGraphSnapshot,
)

internal fun Route.memoryRoutes(deps: BackendHttpDependencies) {
    get(BackendHttpRoutes.MEMORY_OVERVIEW) {
        val service = requireV1Service(deps.memoryService, "Memory")
        val userId = call.requireUserIdFromTrustedProxy()
        val scope = call.memoryScopeOrNull()
        call.respond(
            service.overview(userId, scope).let {
                BackendV1MemoryOverviewResponse(
                    activeFactCount = it.activeFactCount,
                    activeProfileDocCount = it.activeProfileDocCount,
                    activeFactDocCount = it.activeFactDocCount,
                    activeEpisodeDocCount = it.activeEpisodeDocCount,
                )
            }
        )
    }

    get(BackendHttpRoutes.MEMORY_FACTS) {
        val service = requireV1Service(deps.memoryService, "Memory")
        val userId = call.requireUserIdFromTrustedProxy()
        call.respond(BackendV1MemoryFactsResponse(service.facts(userId, call.memoryScopeOrNull())))
    }

    get(BackendHttpRoutes.MEMORY_GRAPH) {
        val service = requireV1Service(deps.memoryService, "Memory")
        val userId = call.requireUserIdFromTrustedProxy()
        val scope = call.memoryScopeOrNull() ?: service.defaultScopeFor(userId)
        call.respond(BackendV1MemoryGraphResponse(service.graph(userId, scope)))
    }

    post(BackendHttpRoutes.MEMORY_FORGET_PATTERN) {
        val service = requireV1Service(deps.memoryService, "Memory")
        val factId = call.parameters["factId"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw invalidV1Request("factId is required.")
        val updated = service.forget(call.requireUserIdFromTrustedProxy(), factId)
        if (!updated) {
            throw BackendV1Exception(
                status = HttpStatusCode.NotFound,
                code = "memory_fact_not_found",
                message = "Memory fact not found.",
            )
        }
        call.respond(mapOf("ok" to true))
    }

    post(BackendHttpRoutes.MEMORY_INVALIDATE_PATTERN) {
        val service = requireV1Service(deps.memoryService, "Memory")
        val factId = call.parameters["factId"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw invalidV1Request("factId is required.")
        val updated = service.invalidate(call.requireUserIdFromTrustedProxy(), factId)
        if (!updated) {
            throw BackendV1Exception(
                status = HttpStatusCode.NotFound,
                code = "memory_fact_not_found",
                message = "Memory fact not found.",
            )
        }
        call.respond(mapOf("ok" to true))
    }
}

private fun io.ktor.server.application.ApplicationCall.memoryScopeOrNull(): MemoryScope? {
    val type = request.queryParameters["scopeType"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val id = request.queryParameters["scopeId"]?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw invalidV1Request("scopeId is required when scopeType is provided.")
    val scopeType = runCatching { MemoryScopeType.valueOf(type.uppercase()) }.getOrElse {
        throw invalidV1Request("Unsupported scopeType '$type'.")
    }
    return MemoryScope(scopeType, id)
}
