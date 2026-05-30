package ru.souz.backend.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import ru.souz.backend.common.BackendRequestException

internal data class ErrorResponse(
    val error: String,
)

data class BackendV1ErrorEnvelope(
    val error: BackendV1Error,
)

data class BackendV1Error(
    val code: String,
    val message: String,
)

class BackendV1Exception(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
) : RuntimeException(message)

fun invalidV1Request(message: String): BackendV1Exception =
    BackendV1Exception(
        status = HttpStatusCode.BadRequest,
        code = "invalid_request",
        message = message,
    )

fun badRequestV1(message: String): BackendV1Exception =
    BackendV1Exception(
        status = HttpStatusCode.BadRequest,
        code = "bad_request",
        message = message,
    )

fun featureDisabledV1(message: String): BackendV1Exception =
    BackendV1Exception(
        status = HttpStatusCode.NotFound,
        code = "feature_disabled",
        message = message,
    )

internal suspend fun ApplicationCall.respondBackend(
    logger: Logger,
    block: suspend ApplicationCall.() -> Any,
) {
    try {
        respond(block())
    } catch (e: BackendRequestException) {
        respond(HttpStatusCode.fromValue(e.statusCode), ErrorResponse(e.message))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error("Unhandled backend request failure", e)
        respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error."))
    }
}
