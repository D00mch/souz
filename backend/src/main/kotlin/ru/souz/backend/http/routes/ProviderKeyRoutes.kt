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
import ru.souz.backend.http.BackendV1ProviderKeysResponse
import ru.souz.backend.http.BackendV1PutProviderKeyRequest
import ru.souz.backend.http.BackendV1PutProviderKeyResponse
import ru.souz.backend.http.invalidV1Request
import ru.souz.backend.http.receiveOrV1BadRequest
import ru.souz.backend.http.requireJsonContentV1
import ru.souz.backend.http.requireProvider
import ru.souz.backend.http.requireUserIdFromTrustedProxy
import ru.souz.backend.http.requireV1Service
import ru.souz.backend.http.toDto

internal fun Route.providerKeyRoutes(deps: BackendHttpDependencies) {
    get(BackendHttpRoutes.PROVIDER_KEYS) {
        val service = requireV1Service(deps.providerKeyService, "Provider keys")
        call.respond(
            BackendV1ProviderKeysResponse(
                items = service.list(call.requireUserIdFromTrustedProxy()).map { it.toDto() },
            )
        )
    }

    put(BackendHttpRoutes.PROVIDER_KEY_PATTERN) {
        val service = requireV1Service(deps.providerKeyService, "Provider keys")
        call.requireJsonContentV1()
        val request = call.receiveOrV1BadRequest<BackendV1PutProviderKeyRequest>()
        val apiKey = request.apiKey.trim().takeIf { it.isNotEmpty() }
            ?: throw invalidV1Request("apiKey must not be empty.")
        call.respond(
            BackendV1PutProviderKeyResponse(
                providerKey = service.put(
                    userId = call.requireUserIdFromTrustedProxy(),
                    provider = call.requireProvider(),
                    apiKey = apiKey,
                ).toDto()
            )
        )
    }

    delete(BackendHttpRoutes.PROVIDER_KEY_PATTERN) {
        val service = requireV1Service(deps.providerKeyService, "Provider keys")
        service.delete(
            userId = call.requireUserIdFromTrustedProxy(),
            provider = call.requireProvider(),
        )
        call.respond(HttpStatusCode.NoContent)
    }
}
