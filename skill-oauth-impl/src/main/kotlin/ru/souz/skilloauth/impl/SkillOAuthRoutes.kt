package ru.souz.skilloauth.impl

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Installs the OAuth provider redirect callback. Deliberately takes plain parameters rather than
 * any `:backend`-internal dependency type, since `:skill-oauth-impl` must not depend on `:backend`.
 *
 * This is the only OAuth-related HTTP route: `startAuthorization` on [SkillOAuthApiImpl] is an
 * in-process suspend call the tool invokes directly and returns the authorize URL from, so there is
 * nothing to expose as a "start" endpoint — the only inbound HTTP hit is the provider's own redirect.
 */
fun Route.installSkillOAuthRoutes(api: SkillOAuthApiImpl, callbackPath: String) {
    get(callbackPath) {
        val code = call.request.queryParameters["code"]
        val state = call.request.queryParameters["state"]
        if (code.isNullOrBlank() || state.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, "Missing code or state.")
            return@get
        }
        when (val result = api.handleCallback(code, state)) {
            is CallbackResult.Connected ->
                call.respondText("Connected to ${result.provider}. You can close this tab and return to the conversation.")

            CallbackResult.InvalidOrExpiredState ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    "This authorization link has expired or was already used. Ask the assistant to start again.",
                )

            is CallbackResult.ExchangeFailed ->
                call.respond(HttpStatusCode.BadGateway, result.reason)
        }
    }
}
