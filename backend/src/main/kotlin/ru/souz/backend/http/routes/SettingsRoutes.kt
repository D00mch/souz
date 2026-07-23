package ru.souz.backend.http.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import ru.souz.backend.http.BackendHttpDependencies
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.http.BackendOpenApiSchemas
import ru.souz.backend.http.BackendOpenApiTags
import ru.souz.backend.http.BackendV1SettingsResponse
import ru.souz.backend.http.BackendV1SettingsPatchRequest
import ru.souz.backend.http.describeV1
import ru.souz.backend.http.jsonBody
import ru.souz.backend.http.jsonResponse
import ru.souz.backend.http.receiveOrV1BadRequest
import ru.souz.backend.http.requireJsonContentV1
import ru.souz.backend.http.requireUserIdFromTrustedProxy
import ru.souz.backend.http.requireV1Service
import ru.souz.backend.http.toDto
import ru.souz.backend.http.toUserSettingsOverrides
import ru.souz.backend.http.v1ErrorResponses

internal fun Route.settingsRoutes(deps: BackendHttpDependencies) {
    get(BackendHttpRoutes.SETTINGS) {
        val service = requireV1Service(deps.userSettingsService, "User settings")
        call.respond(
            BackendV1SettingsResponse(
                settings = service.get(call.requireUserIdFromTrustedProxy()).toDto(),
            )
        )
    }.describeV1(
        operationId = "getUserSettings",
        tag = BackendOpenApiTags.SETTINGS,
        summary = "Get user settings",
        description = "Returns the effective public settings for the trusted user.",
    ) {
        responses {
            jsonResponse<BackendV1SettingsResponse>(HttpStatusCode.OK, "Effective user settings.")
            v1ErrorResponses()
        }
    }

    patch(BackendHttpRoutes.SETTINGS) {
        val service = requireV1Service(deps.userSettingsService, "User settings")
        call.requireJsonContentV1()
        val request = call.receiveOrV1BadRequest<BackendV1SettingsPatchRequest>()
        call.respond(
            BackendV1SettingsResponse(
                settings = service.patch(
                    userId = call.requireUserIdFromTrustedProxy(),
                    overrides = request.toUserSettingsOverrides(),
                ).toDto(),
            )
        )
    }.describeV1(
        operationId = "patchUserSettings",
        tag = BackendOpenApiTags.SETTINGS,
        summary = "Update user settings",
        description = "Applies the supplied public setting overrides and returns the resulting effective settings.",
    ) {
        requestBody {
            jsonBody<BackendV1SettingsPatchRequest>(
                description = "Settings fields to update; omitted fields are left unchanged.",
                schemaTransform = BackendOpenApiSchemas::settingsPatch,
            )
        }
        responses {
            jsonResponse<BackendV1SettingsResponse>(HttpStatusCode.OK, "Updated effective user settings.")
            v1ErrorResponses(HttpStatusCode.BadRequest)
        }
    }
}
