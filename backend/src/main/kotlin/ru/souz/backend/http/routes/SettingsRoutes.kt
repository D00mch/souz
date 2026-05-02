package ru.souz.backend.http.routes

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import ru.souz.backend.http.BackendHttpDependencies
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.http.BackendV1SettingsResponse
import ru.souz.backend.http.BackendV1SettingsPatchRequest
import ru.souz.backend.http.receiveOrV1BadRequest
import ru.souz.backend.http.requireJsonContentV1
import ru.souz.backend.http.requireUserIdFromTrustedProxy
import ru.souz.backend.http.requireV1Service
import ru.souz.backend.http.toDto
import ru.souz.backend.http.toUserSettingsOverrides

internal fun Route.settingsRoutes(deps: BackendHttpDependencies) {
    get(BackendHttpRoutes.SETTINGS) {
        val service = requireV1Service(deps.userSettingsService, "User settings")
        call.respond(
            BackendV1SettingsResponse(
                settings = service.get(call.requireUserIdFromTrustedProxy()).toDto(),
            )
        )
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
    }
}
