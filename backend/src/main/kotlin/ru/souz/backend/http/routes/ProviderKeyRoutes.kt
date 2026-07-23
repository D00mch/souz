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
import ru.souz.backend.http.BackendOpenApiSchemas
import ru.souz.backend.http.BackendOpenApiTags
import ru.souz.backend.http.BackendV1ProviderKeysResponse
import ru.souz.backend.http.BackendV1PutProviderKeyRequest
import ru.souz.backend.http.BackendV1PutProviderKeyResponse
import ru.souz.backend.http.describeV1
import ru.souz.backend.http.emptyResponse
import ru.souz.backend.http.invalidV1Request
import ru.souz.backend.http.receiveOrV1BadRequest
import ru.souz.backend.http.jsonBody
import ru.souz.backend.http.jsonResponse
import ru.souz.backend.http.providerPathParameter
import ru.souz.backend.http.requireJsonContentV1
import ru.souz.backend.http.requireProvider
import ru.souz.backend.http.requireUserIdFromTrustedProxy
import ru.souz.backend.http.requireV1Service
import ru.souz.backend.http.toDto
import ru.souz.backend.http.v1ErrorResponses

internal fun Route.providerKeyRoutes(deps: BackendHttpDependencies) {
    get(BackendHttpRoutes.PROVIDER_KEYS) {
        val service = requireV1Service(deps.providerKeyService, "Provider keys")
        call.respond(
            BackendV1ProviderKeysResponse(
                items = service.list(call.requireUserIdFromTrustedProxy()).map { it.toDto() },
            )
        )
    }.describeV1(
        operationId = "listProviderKeys",
        tag = BackendOpenApiTags.PROVIDER_KEYS,
        summary = "List configured provider keys",
        description = "Returns safe metadata for provider keys configured by the trusted user; raw keys are never returned.",
    ) {
        responses {
            jsonResponse<BackendV1ProviderKeysResponse>(HttpStatusCode.OK, "Configured provider-key metadata.")
            v1ErrorResponses()
        }
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
    }.describeV1(
        operationId = "putProviderKey",
        tag = BackendOpenApiTags.PROVIDER_KEYS,
        summary = "Store a provider key",
        description = "Encrypts and stores a non-blank provider API key for the trusted user.",
    ) {
        parameters { providerPathParameter() }
        requestBody {
            jsonBody<BackendV1PutProviderKeyRequest>(
                description = "Provider credential. The secret is write-only and is never echoed.",
                schemaTransform = BackendOpenApiSchemas::providerKey,
            )
        }
        responses {
            jsonResponse<BackendV1PutProviderKeyResponse>(HttpStatusCode.OK, "Safe metadata for the stored key.")
            v1ErrorResponses(HttpStatusCode.BadRequest)
        }
    }

    delete(BackendHttpRoutes.PROVIDER_KEY_PATTERN) {
        val service = requireV1Service(deps.providerKeyService, "Provider keys")
        service.delete(
            userId = call.requireUserIdFromTrustedProxy(),
            provider = call.requireProvider(),
        )
        call.respond(HttpStatusCode.NoContent)
    }.describeV1(
        operationId = "deleteProviderKey",
        tag = BackendOpenApiTags.PROVIDER_KEYS,
        summary = "Delete a provider key",
        description = "Removes the trusted user's stored key for the selected provider.",
    ) {
        parameters { providerPathParameter() }
        responses {
            emptyResponse(HttpStatusCode.NoContent, "The provider key was removed or was already absent.")
            v1ErrorResponses(HttpStatusCode.BadRequest)
        }
    }
}
